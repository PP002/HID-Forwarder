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

data class DiscoveredMouse(
    val name: String,
    val eventNode: String // e.g. "/dev/input/event7"
)

class EvgrabManager(
    private val context: Context,
    private val keySender: HidKeySender,
    private val mouseSender: HidMouseSender?,
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(
        context: Context,
        keySender: HidKeySender,
        log: (String, LogLevel) -> Unit,
        logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
    ) : this(context, keySender, null, log, logKey)

    constructor(
        context: Context,
        keySender: HidKeySender,
        log: (String, LogLevel) -> Unit
    ) : this(context, keySender, null, log, null)

    private data class RunningGrabProcess(
        val eventNode: String,
        val isMouse: Boolean,
        val job: Job,
        @Volatile var process: Process? = null
    )

    private class FgKeyState {
        var modifiers: Byte = 0
        val pressedKeys = LinkedHashSet<Byte>()
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var managerJob: Job? = null
    private val runningGrabs = java.util.concurrent.ConcurrentHashMap<String, RunningGrabProcess>()
    private val rescanSignal = java.util.concurrent.atomic.AtomicBoolean(false)

    // Foreground fallback state per hardware deviceId (used when evgrab is STOPPED)
    private val fgDeviceStates = HashMap<Int, FgKeyState>()
    private val fgLock = Any()
    private val loggedFgDeviceIds = HashSet<Int>()

    private val _state = MutableStateFlow(EvgrabState.STOPPED)
    val state: StateFlow<EvgrabState> = _state.asStateFlow()

    private val _activeKeyboards = MutableStateFlow<List<DiscoveredKeyboard>>(emptyList())
    val activeKeyboards: StateFlow<List<DiscoveredKeyboard>> = _activeKeyboards.asStateFlow()

    private val _activeEvMice = MutableStateFlow<List<DiscoveredMouse>>(emptyList())
    val activeEvMice: StateFlow<List<DiscoveredMouse>> = _activeEvMice.asStateFlow()

    private fun emitLog(key: LogKey, level: LogLevel, vararg args: Any) {
        val lk = logKey
        if (lk != null) {
            lk(key, level, args)
        } else {
            log(LogLocalizer.format(AppLanguage.EN, key, *args), level)
        }
    }

    fun kickRescan() {
        rescanSignal.set(true)
    }

    fun start() {
        stop(silent = true)
        clearForegroundKeys()
        _state.value = EvgrabState.RUNNING
        HidRelayForegroundService.setEvgrabRunning(context, true)
        emitLog(LogKey.EVGRAB_STARTING, LogLevel.INFO)

        managerJob = scope.launch {
            val libPath = resolveEvgrabBinaryPath()
            if (libPath == null) {
                emitLog(
                    LogKey.EVGRAB_BINARY_NOT_FOUND,
                    LogLevel.ERROR,
                    context.applicationInfo.nativeLibraryDir
                )
                stop(silent = true, markState = EvgrabState.STOPPED)
                return@launch
            }

            var noDeviceLogged = false
            while (isActive && _state.value == EvgrabState.RUNNING) {
                rescanSignal.set(false)
                val (keyboards, mice) = discoverInputDevices()

                _activeKeyboards.value = keyboards
                _activeEvMice.value = if (mouseSender != null) mice else emptyList()

                val newKbs = keyboards.filter { !runningGrabs.containsKey(it.eventNode) }
                if (newKbs.isNotEmpty()) {
                    emitLog(
                        LogKey.EVGRAB_LOCKED_DEVICES,
                        LogLevel.INFO,
                        newKbs.size,
                        newKbs.joinToString { "${it.name} (${it.eventNode})" }
                    )
                    for (kb in newKbs) {
                        launchKeyboardGrab(libPath, kb)
                    }
                }

                if (mouseSender != null) {
                    val newMice = mice.filter { !runningGrabs.containsKey(it.eventNode) }
                    if (newMice.isNotEmpty()) {
                        emitLog(
                            LogKey.EVGRAB_LOCKED_MICE,
                            LogLevel.INFO,
                            newMice.size,
                            newMice.joinToString { "${it.name} (${it.eventNode})" }
                        )
                        for (mouse in newMice) {
                            launchMouseGrab(libPath, mouse)
                        }
                    }
                }

                if (runningGrabs.isEmpty() && keyboards.isEmpty() && (mouseSender == null || mice.isEmpty())) {
                    if (!noDeviceLogged) {
                        noDeviceLogged = true
                        emitLog(LogKey.EVGRAB_NO_KEYBOARD_RETRY, LogLevel.INFO)
                    }
                } else {
                    noDeviceLogged = false
                }

                // Poll every 1.5s (waking early if hotplug rescan triggered) so additional OTG hub devices attach seamlessly
                for (step in 0 until 15) {
                    if (!isActive || _state.value != EvgrabState.RUNNING) break
                    if (rescanSignal.getAndSet(false)) break
                    delay(100)
                }
            }
        }
    }

    private fun CoroutineScope.launchKeyboardGrab(libPath: String, kb: DiscoveredKeyboard) {
        lateinit var entry: RunningGrabProcess
        val job = launch {
            var process: Process? = null
            val sourceId = "usb:${kb.eventNode}"
            try {
                val cmd = arrayOf("su", "-c", "exec \"$libPath\" ${kb.eventNode}")
                process = Runtime.getRuntime().exec(cmd)
                entry.process = process

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
                    } catch (_: Exception) {}
                }

                val readerStopped = AtomicBoolean(false)
                val readThread = Thread {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    } catch (_: Throwable) {}
                    try {
                        while (!readerStopped.get() && !Thread.currentThread().isInterrupted) {
                            input.readFully(buf)
                            keySender.sendRawReport(sourceId, buf)
                        }
                    } catch (_: Exception) {}
                }.apply {
                    name = "EvgrabKb-${kb.eventNode}"
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
                    _state.value = EvgrabState.STOPPED_BY_HOTKEY
                    HidRelayForegroundService.setEvgrabRunning(context, false)
                    emitLog(LogKey.EVGRAB_STOPPED_BY_HOTKEY, LogLevel.WARN)
                    terminateAllProcesses()
                    keySender.clearSourcesWithPrefix("usb:")
                    mouseSender?.clearMouse()
                    _activeKeyboards.value = emptyList()
                    _activeEvMice.value = emptyList()
                }
            } catch (e: Exception) {
                emitLog(
                    LogKey.EVGRAB_EXEC_EXCEPTION,
                    LogLevel.ERROR,
                    kb.eventNode,
                    e.localizedMessage ?: "Unknown error"
                )
            } finally {
                runningGrabs.remove(kb.eventNode, entry)
                try { process?.destroy() } catch (_: Exception) {}
                keySender.removeSource(sourceId)
                _activeKeyboards.value = _activeKeyboards.value.filter { it.eventNode != kb.eventNode }
            }
        }
        entry = RunningGrabProcess(kb.eventNode, isMouse = false, job = job)
        runningGrabs[kb.eventNode] = entry
    }

    private fun CoroutineScope.launchMouseGrab(libPath: String, mouse: DiscoveredMouse) {
        val mSender = mouseSender ?: return
        lateinit var entry: RunningGrabProcess
        val job = launch {
            var process: Process? = null
            val sourceId = "evmouse:${mouse.eventNode}"
            try {
                val cmd = arrayOf("su", "-c", "exec \"$libPath\" --mouse ${mouse.eventNode}")
                process = Runtime.getRuntime().exec(cmd)
                entry.process = process

                emitLog(LogKey.EVGRAB_MOUSE_LISTENING, LogLevel.SUCCESS, mouse.name, mouse.eventNode)

                val input = DataInputStream(process.inputStream)
                val errReader = process.errorStream.bufferedReader()
                val buf = ByteArray(4)

                val errJob = scope.launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val line = errReader.readLine() ?: break
                            if (line.isNotBlank()) {
                                emitLog(LogKey.EVGRAB_STDERR, LogLevel.WARN, line)
                            }
                        }
                    } catch (_: Exception) {}
                }

                val readerStopped = AtomicBoolean(false)
                val readThread = Thread {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    } catch (_: Throwable) {}
                    try {
                        while (!readerStopped.get() && !Thread.currentThread().isInterrupted) {
                            input.readFully(buf)
                            val buttons = buf[0].toInt() and 0x1F
                            val dx = buf[1].toInt()
                            val dy = buf[2].toInt()
                            val wheel = buf[3].toInt()
                            mSender.sendRelativeMove(
                                sourceId = sourceId,
                                buttonsMask = buttons,
                                dx = dx,
                                dy = dy,
                                wheel = wheel
                            )
                        }
                    } catch (_: Exception) {}
                }.apply {
                    name = "EvgrabMouse-${mouse.eventNode}"
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
                    mouse.eventNode,
                    exitCode
                )
            } catch (e: Exception) {
                emitLog(
                    LogKey.EVGRAB_EXEC_EXCEPTION,
                    LogLevel.ERROR,
                    mouse.eventNode,
                    e.localizedMessage ?: "Unknown error"
                )
            } finally {
                runningGrabs.remove(mouse.eventNode, entry)
                try { process?.destroy() } catch (_: Exception) {}
                mSender.removeSource(sourceId)
                _activeEvMice.value = _activeEvMice.value.filter { it.eventNode != mouse.eventNode }
            }
        }
        entry = RunningGrabProcess(mouse.eventNode, isMouse = true, job = job)
        runningGrabs[mouse.eventNode] = entry
    }

    private fun terminateAllProcesses() {
        for (entry in runningGrabs.values) {
            try { entry.job.cancel() } catch (_: Exception) {}
            try { entry.process?.destroy() } catch (_: Exception) {}
        }
        runningGrabs.clear()
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
        keySender.clearSourcesWithPrefix("usb:")
        clearForegroundKeys()
        _activeKeyboards.value = emptyList()
        _activeEvMice.value = emptyList()

        if (!silent) {
            emitLog(LogKey.EVGRAB_STOPPED, LogLevel.INFO)
        }
    }

    fun clearForegroundKeys() {
        synchronized(fgLock) {
            fgDeviceStates.clear()
            loggedFgDeviceIds.clear()
        }
        keySender.clearSourcesWithPrefix("fg_usb:")
    }

    /**
     * Foreground OTG multi-keyboard fallback when evgrab is STOPPED (no root / libevgrab.so needed).
     * Tracks modifier and pressed-key state separately per Android InputDevice ID.
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

        if (isDown && event.repeatCount > 0) return true

        val sourceId = "fg_usb:${event.deviceId}"
        val scanMod = HidTextInputView.evdevScanCodeToModifierMask(event.scanCode)
        val modMask = if (scanMod != 0) scanMod else HidTextInputView.getModifierMask(event.keyCode)
        if (modMask != 0) {
            val firstTimeForDev: Boolean
            synchronized(fgLock) {
                val st = fgDeviceStates.getOrPut(event.deviceId) { FgKeyState() }
                st.modifiers = if (isDown) {
                    (st.modifiers.toInt() or modMask).toByte()
                } else {
                    (st.modifiers.toInt() and modMask.inv()).toByte()
                }
                keySender.updateSourceKeys(sourceId, st.modifiers, st.pressedKeys)
                firstTimeForDev = loggedFgDeviceIds.add(event.deviceId)
            }
            if (firstTimeForDev) {
                emitLog(LogKey.USB_FG_DETECTED, LogLevel.INFO, dev.name)
            }
            return true
        }

        val scanUsage = if (event.scanCode > 0) HidTextInputView.evdevScanCodeToHidUsage(event.scanCode) else 0.toByte()
        val usage = if (scanUsage != 0.toByte()) scanUsage else HidTextInputView.androidKeyCodeToHidUsage(event.keyCode)
        if (usage != 0.toByte()) {
            val firstTimeForDev: Boolean
            synchronized(fgLock) {
                val st = fgDeviceStates.getOrPut(event.deviceId) { FgKeyState() }
                if (isDown) {
                    st.pressedKeys.add(usage)
                } else {
                    st.pressedKeys.remove(usage)
                }
                keySender.updateSourceKeys(sourceId, st.modifiers, st.pressedKeys)
                firstTimeForDev = loggedFgDeviceIds.add(event.deviceId)
            }
            if (firstTimeForDev) {
                emitLog(LogKey.USB_FG_DETECTED, LogLevel.INFO, dev.name)
            }
            return true
        }

        return false
    }

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

    private fun discoverInputDevices(): Pair<List<DiscoveredKeyboard>, List<DiscoveredMouse>> {
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
            return Pair(emptyList(), emptyList())
        }

        val keyboards = mutableListOf<DiscoveredKeyboard>()
        val mice = mutableListOf<DiscoveredMouse>()
        val blocks = content.split(Regex("\\r?\\n\\r?\\n+"))
        val mouseHandlerRegex = Regex("""\bmouse\d+\b""")
        val eventNodeRegex = Regex("""event(\d+)""")

        for (block in blocks) {
            var name = ""
            var vendor = ""
            var handlers = ""
            var evHex = ""

            for (line in block.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("I:") -> {
                        vendor = Regex("""Vendor=([0-9a-fA-F]+)""").find(trimmed)?.groupValues?.getOrNull(1) ?: ""
                    }
                    trimmed.startsWith("N: Name=") -> name = trimmed.substringAfter("N: Name=").trim('"', ' ')
                    trimmed.startsWith("H: Handlers=") -> handlers = trimmed.substringAfter("H: Handlers=").trim()
                    trimmed.startsWith("B: EV=") -> evHex = trimmed.substringAfter("B: EV=").trim()
                }
            }

            // Skip Wacom vendor (056a) so WacomUsbManager handles tablets exclusively
            if (vendor.equals("056a", ignoreCase = true)) continue

            val ev = evHex.toLongOrNull(16) ?: 0L
            val hasKey = (ev and 0x2L) != 0L       // EV_KEY (1 << 1)
            val hasRel = (ev and 0x4L) != 0L       // EV_REL (1 << 2)
            val hasAbs = (ev and 0x8L) != 0L       // EV_ABS (1 << 3)
            val hasRep = (ev and 0x100000L) != 0L  // EV_REP (1 << 20)
            val eventNode = eventNodeRegex.find(handlers)?.value ?: continue

            if (hasKey && hasRep) {
                keyboards.add(DiscoveredKeyboard(name.ifEmpty { "External USB Keyboard" }, "/dev/input/$eventNode"))
            } else if (mouseHandlerRegex.containsMatchIn(handlers) && hasKey && hasRel && !hasAbs) {
                mice.add(DiscoveredMouse(name.ifEmpty { "External USB Mouse" }, "/dev/input/$eventNode"))
            }
        }

        return Pair(keyboards, mice)
    }
}
