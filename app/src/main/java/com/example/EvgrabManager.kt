package com.example

import android.content.Context
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class EvgrabState {
    STOPPED,
    RUNNING,
    STOPPED_BY_HOTKEY
}

data class DiscoveredKeyboard(
    val name: String,
    val eventNode: String // e.g. "/dev/input/event5"
)

class EvgrabManager(
    private val context: Context,
    private val keySender: HidKeySender,
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(
        context: Context,
        keySender: HidKeySender,
        log: (String, LogLevel) -> Unit
    ) : this(context, keySender, log, null)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var managerJob: Job? = null
    private val activeProcesses = mutableListOf<Process>()
    private val processLock = Any()

    // Foreground fallback state (used when evgrab is stopped or libevgrab.so is missing)
    private var fgModifiers: Byte = 0
    private val fgPressedKeys = LinkedHashSet<Byte>()
    private val fgLock = Any()
    @Volatile private var fgModeLogged = false

    private val _state = MutableStateFlow(EvgrabState.STOPPED)
    val state: StateFlow<EvgrabState> = _state.asStateFlow()

    private val _activeKeyboards = MutableStateFlow<List<DiscoveredKeyboard>>(emptyList())
    val activeKeyboards: StateFlow<List<DiscoveredKeyboard>> = _activeKeyboards.asStateFlow()

    private fun emitLog(key: LogKey, level: LogLevel, vararg args: Any) {
        val lk = logKey
        if (lk != null) {
            lk(key, level, args)
        } else {
            log(LogLocalizer.format(AppLanguage.EN, key, *args), level)
        }
    }

    fun start() {
        stop(silent = true)
        clearForegroundKeys()
        _state.value = EvgrabState.RUNNING
        HidRelayForegroundService.setEvgrabRunning(context, true)
        emitLog(LogKey.EVGRAB_STARTING, LogLevel.INFO)

        managerJob = scope.launch {
            while (isActive && _state.value == EvgrabState.RUNNING) {
                val libPath = resolveEvgrabBinaryPath()
                if (libPath == null) {
                    emitLog(
                        LogKey.EVGRAB_BINARY_NOT_FOUND,
                        LogLevel.ERROR,
                        context.applicationInfo.nativeLibraryDir
                    )
                    stop(silent = true, markState = EvgrabState.STOPPED)
                    break
                }

                val keyboards = discoverKeyboards()
                _activeKeyboards.value = keyboards

                if (keyboards.isEmpty()) {
                    emitLog(LogKey.EVGRAB_NO_KEYBOARD_RETRY, LogLevel.INFO)
                    delay(2000)
                    continue
                }

                emitLog(
                    LogKey.EVGRAB_LOCKED_DEVICES,
                    LogLevel.INFO,
                    keyboards.size,
                    keyboards.joinToString { "${it.name} (${it.eventNode})" }
                )

                val jobs = keyboards.map { kb ->
                    launch {
                        runEvgrabForDevice(libPath, kb)
                    }
                }

                jobs.forEach { it.join() }

                if (_state.value != EvgrabState.RUNNING || !isActive) {
                    break
                }

                emitLog(LogKey.EVGRAB_DISCONNECTED_RETRY, LogLevel.WARN)
                delay(2000)
            }
        }
    }

    private suspend fun runEvgrabForDevice(libPath: String, kb: DiscoveredKeyboard) {
        var process: Process? = null
        try {
            val cmd = arrayOf("su", "-c", "exec \"$libPath\" ${kb.eventNode}")
            process = Runtime.getRuntime().exec(cmd)
            synchronized(processLock) {
                activeProcesses.add(process)
            }

            emitLog(LogKey.EVGRAB_LISTENING, LogLevel.SUCCESS, kb.name, kb.eventNode)

            val input = DataInputStream(process.inputStream)
            val errReader = process.errorStream.bufferedReader()
            val buf = ByteArray(8)

            val errJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val line = errReader.readLine() ?: break
                        if (line.isNotBlank()) {
                            emitLog(LogKey.EVGRAB_STDERR, LogLevel.WARN, line)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // High-priority dedicated thread reading 8-byte HID reports from stdout without IO-pool throttling during screen-off
            val readerStopped = AtomicBoolean(false)
            val readThread = Thread {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (_: Throwable) {}
                try {
                    while (!readerStopped.get() && !Thread.currentThread().isInterrupted) {
                        input.readFully(buf)
                        keySender.sendRawReport(KeySource.USB, buf)
                    }
                } catch (_: Exception) {
                }
            }.apply {
                name = "EvgrabStdoutReader"
                isDaemon = true
                start()
            }

            val exitCode = process.waitFor()
            readerStopped.set(true)
            readThread.interrupt()
            errJob.cancel()

            emitLog(
                LogKey.EVGRAB_PROCESS_EXITED,
                if (exitCode == 0) LogLevel.SUCCESS else LogLevel.WARN,
                kb.eventNode,
                exitCode
            )

            if (exitCode == 0 && _state.value == EvgrabState.RUNNING) {
                // Exit code 0: user pressed Ctrl+Alt+Q or process received SIGTERM/SIGINT.
                // Do not auto-restart; UI switches to "Stopped by Hotkey".
                _state.value = EvgrabState.STOPPED_BY_HOTKEY
                HidRelayForegroundService.setEvgrabRunning(context, false)
                emitLog(LogKey.EVGRAB_STOPPED_BY_HOTKEY, LogLevel.WARN)
                terminateAllProcesses()
                keySender.clearSource(KeySource.USB)
                _activeKeyboards.value = emptyList()
            }
        } catch (e: Exception) {
            emitLog(
                LogKey.EVGRAB_EXEC_EXCEPTION,
                LogLevel.ERROR,
                kb.eventNode,
                e.localizedMessage ?: "Unknown error"
            )
        } finally {
            if (process != null) {
                synchronized(processLock) {
                    activeProcesses.remove(process)
                }
                try { process.destroy() } catch (_: Exception) {}
            }
            keySender.clearSource(KeySource.USB)
        }
    }

    private fun terminateAllProcesses() {
        synchronized(processLock) {
            for (p in activeProcesses) {
                try { p.destroy() } catch (_: Exception) {}
            }
            activeProcesses.clear()
        }
        scope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f libevgrab.so")).waitFor()
            } catch (_: Exception) {}
        }
    }

    fun stop(silent: Boolean = false, markState: EvgrabState = EvgrabState.STOPPED) {
        _state.value = markState
        HidRelayForegroundService.setEvgrabRunning(context, false)
        managerJob?.cancel()
        managerJob = null

        terminateAllProcesses()
        clearForegroundKeys()
        _activeKeyboards.value = emptyList()

        if (!silent) {
            emitLog(LogKey.EVGRAB_STOPPED, LogLevel.INFO)
        }
    }

    fun clearForegroundKeys() {
        synchronized(fgLock) {
            fgModifiers = 0
            fgPressedKeys.clear()
        }
        keySender.clearSource(KeySource.USB)
    }

    /**
     * Foreground OTG physical keyboard fallback when evgrab is STOPPED (no root / libevgrab.so needed).
     * Does not intercept hardware key events while RUNNING or STOPPED_BY_HOTKEY.
     */
    fun onForegroundHardwareKeyEvent(event: KeyEvent): Boolean {
        if (_state.value != EvgrabState.STOPPED) return false

        val dev = event.device
        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || dev == null || dev.isVirtual) {
            return false
        }
        if ((dev.sources and InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD) {
            return false
        }

        // Do not intercept system navigation, volume, or power keys
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> return false
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP
        if (!isDown && !isUp) return false

        // Ignore OS key auto-repeat (target device handles key repeat)
        if (isDown && event.repeatCount > 0) return true

        val scanMod = HidTextInputView.evdevScanCodeToModifierMask(event.scanCode)
        val modMask = if (scanMod != 0) scanMod else HidTextInputView.getModifierMask(event.keyCode)
        if (modMask != 0) {
            synchronized(fgLock) {
                fgModifiers = if (isDown) {
                    (fgModifiers.toInt() or modMask).toByte()
                } else {
                    (fgModifiers.toInt() and modMask.inv()).toByte()
                }
                keySender.updateSourceKeys(KeySource.USB, fgModifiers, fgPressedKeys)
            }
            if (!fgModeLogged) {
                fgModeLogged = true
                emitLog(LogKey.USB_FG_DETECTED, LogLevel.INFO, dev.name)
            }
            return true
        }

        val scanUsage = if (event.scanCode > 0) HidTextInputView.evdevScanCodeToHidUsage(event.scanCode) else 0.toByte()
        val usage = if (scanUsage != 0.toByte()) scanUsage else HidTextInputView.androidKeyCodeToHidUsage(event.keyCode)
        if (usage != 0.toByte()) {
            synchronized(fgLock) {
                if (isDown) {
                    fgPressedKeys.add(usage)
                } else {
                    fgPressedKeys.remove(usage)
                }
                keySender.updateSourceKeys(KeySource.USB, fgModifiers, fgPressedKeys)
            }
            if (!fgModeLogged) {
                fgModeLogged = true
                emitLog(LogKey.USB_FG_DETECTED, LogLevel.INFO, dev.name)
            }
            return true
        }

        return false
    }

    /**
     * Resolves the libevgrab.so executable path, or returns null if unavailable.
     */
    private fun resolveEvgrabBinaryPath(): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val primary = File(nativeDir, "libevgrab.so")
        if (primary.exists()) return primary.absolutePath

        val appPrivateCandidates = listOf(
            "${context.filesDir}/libevgrab.so",
            "${context.filesDir}/evgrab"
        )
        for (c in appPrivateCandidates) {
            val f = File(c)
            if (f.exists()) {
                f.setExecutable(true)
                return c
            }
        }

        val rootCandidates = listOf(
            "/data/local/tmp/libevgrab.so",
            "/data/local/tmp/evgrab",
            "/data/data/com.termux/files/home/evgrab",
            "/data/data/com.termux/files/home/libevgrab.so"
        )
        for (c in rootCandidates) {
            if (File(c).exists()) return c
        }

        // Android SELinux may block direct access to /data/local/tmp or Termux; probe and chmod via su
        return try {
            val checkCmd = rootCandidates.joinToString(" ") { "\"$it\"" }
            val p = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "for f in $checkCmd; do if [ -f \"\$f\" ]; then chmod 755 \"\$f\" 2>/dev/null; echo \"\$f\"; break; fi; done")
            )
            val found = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            found.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scans /proc/bus/input/devices (falling back to su) for keyboards with both EV_KEY (0x2) and EV_REP (0x100000).
     */
    private fun discoverKeyboards(): List<DiscoveredKeyboard> {
        val directContent = try {
            val f = File("/proc/bus/input/devices")
            if (f.canRead()) f.readText().takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        }

        val content = directContent ?: try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/bus/input/devices"))
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text
        } catch (_: Exception) {
            ""
        }

        if (content.isBlank()) {
            return emptyList()
        }

        val result = mutableListOf<DiscoveredKeyboard>()
        val blocks = content.split(Regex("\\r?\\n\\r?\\n+"))
        for (block in blocks) {
            var name = ""
            var handlers = ""
            var evHex = ""

            for (line in block.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("N: Name=") -> name = trimmed.substringAfter("N: Name=").trim('"', ' ')
                    trimmed.startsWith("H: Handlers=") -> handlers = trimmed.substringAfter("H: Handlers=").trim()
                    trimmed.startsWith("B: EV=") -> evHex = trimmed.substringAfter("B: EV=").trim()
                }
            }

            val ev = evHex.toLongOrNull(16) ?: 0L
            val hasKey = (ev and 0x2L) != 0L       // EV_KEY (1 << 1)
            val hasRep = (ev and 0x100000L) != 0L  // EV_REP (1 << 20)
            val eventMatch = Regex("""event(\d+)""").find(handlers)
            val eventNode = eventMatch?.value

            if (eventNode != null && hasKey && hasRep) {
                result.add(DiscoveredKeyboard(name.ifEmpty { "External USB Keyboard" }, "/dev/input/$eventNode"))
            }
        }

        return result
    }
}
