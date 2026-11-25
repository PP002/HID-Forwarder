package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class KeySource {
    USB,
    SOFT_KEYBOARD,
    TEST
}

enum class ReportIdMode(val label: String) {
    AUTO("Auto (Report=1 / Boot=0)"),
    FORCE_1("Report ID = 1"),
    FORCE_0("Report ID = 0 (No ID)")
}

class HidKeySender(
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(log: (String, LogLevel) -> Unit) : this(log, null)
    companion object {
        const val KEYBOARD_REPORT_ID = 1
    }

    private fun emitLog(key: LogKey, level: LogLevel, vararg args: Any) {
        val lk = logKey
        if (lk != null) {
            lk(key, level, args)
        } else {
            log(LogLocalizer.format(AppLanguage.EN, key, *args), level)
        }
    }

    private val senderDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HidKeySenderThread").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(senderDispatcher)
    private val reportChannel = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile var hidDevice: BluetoothHidDevice? = null
    @Volatile var hostDevice: BluetoothDevice? = null
    @Volatile var protocolMode: Byte = BluetoothHidDevice.PROTOCOL_REPORT_MODE
    @Volatile var reportIdMode: ReportIdMode = ReportIdMode.AUTO
    @Volatile var verboseTxLog: Boolean = true
    @Volatile var keyEnabled: Boolean = true

    @Volatile private var lastReport: ByteArray = ByteArray(8)
    @Volatile private var lastErrorLogMs: Long = 0L

    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    private class SourceState {
        var modifiers: Byte = 0
        val pressedKeys = LinkedHashSet<Byte>()
    }

    private val sourceStates = ConcurrentHashMap<String, SourceState>().apply {
        put(KeySource.USB.name, SourceState())
        put(KeySource.SOFT_KEYBOARD.name, SourceState())
        put(KeySource.TEST.name, SourceState())
    }

    private val stateLock = Any()

    init {
        // Serialized high-priority worker thread processing all outgoing HID reports sequentially
        scope.launch {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
            for (report in reportChannel) {
                dispatchToBluetooth(report)
            }
        }
    }

    fun getLastReport(): ByteArray = lastReport.copyOf()

    fun resolveActiveReportId(): Int {
        return when (reportIdMode) {
            ReportIdMode.AUTO -> {
                if (protocolMode == BluetoothHidDevice.PROTOCOL_BOOT_MODE) 0 else KEYBOARD_REPORT_ID
            }
            ReportIdMode.FORCE_1 -> KEYBOARD_REPORT_ID
            ReportIdMode.FORCE_0 -> 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun dispatchToBluetooth(report: ByteArray) {
        val wasPressed = lastReport.any { it != 0.toByte() }
        lastReport = report.copyOf()
        if (!keyEnabled) return
        val hid = hidDevice
        val host = hostDevice
        if (hid != null && host != null) {
            val reportId = resolveActiveReportId()
            val isNonZero = report.any { it != 0.toByte() }
            try {
                val ok = hid.sendReport(host, reportId, report)
                if (ok) {
                    _packetsSent.update { it + 1 }
                    if (verboseTxLog && (isNonZero || wasPressed)) {
                        val hex = report.joinToString(" ") { "%02X".format(it) }
                        emitLog(LogKey.HID_TX_OK, LogLevel.SUCCESS, reportId, hex)
                    }
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastErrorLogMs > 1500L) {
                        lastErrorLogMs = now
                        val state = try {
                            hid.getConnectionState(host)
                        } catch (_: Exception) {
                            -1
                        }
                        val hex = report.joinToString(" ") { "%02X".format(it) }
                        emitLog(
                            LogKey.HID_SEND_REPORT_FAILED,
                            LogLevel.ERROR,
                            reportId,
                            hex,
                            host.address,
                            state
                        )
                    }
                }
            } catch (e: Exception) {
                emitLog(LogKey.HID_SEND_EXCEPTION, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
            }
        } else {
            val hasKeyPressed = report.any { it != 0.toByte() }
            if (hasKeyPressed) {
                val now = System.currentTimeMillis()
                if (now - lastErrorLogMs > 2500L) {
                    lastErrorLogMs = now
                    if (hid == null) {
                        emitLog(LogKey.HID_SKIP_NO_PROXY, LogLevel.WARN)
                    } else {
                        emitLog(LogKey.HID_SKIP_NO_HOST, LogLevel.WARN)
                    }
                }
            }
        }
    }

    /**
     * Updates key state for the given source enum and enqueues the merged HID report.
     */
    fun updateSourceKeys(source: KeySource, modifiers: Byte, keys: Set<Byte>) {
        updateSourceKeys(source.name, modifiers, keys)
    }

    /**
     * Updates key state for an arbitrary per-device source ID and enqueues the merged HID report.
     */
    fun updateSourceKeys(sourceId: String, modifiers: Byte, keys: Set<Byte>) {
        val mergedReport: ByteArray
        synchronized(stateLock) {
            val state = sourceStates.getOrPut(sourceId) { SourceState() }
            state.modifiers = modifiers
            state.pressedKeys.clear()
            state.pressedKeys.addAll(keys)

            mergedReport = computeMergedReport()
        }
        reportChannel.trySend(mergedReport)
    }

    /**
     * Updates source state directly from an 8-byte boot keyboard report [modifier, 0, key1..key6] (used by local TCP relay client).
     */
    fun sendRawReport(source: KeySource, report8: ByteArray) {
        sendRawReport(source.name, report8)
    }

    /**
     * Updates per-device source state directly from an 8-byte boot keyboard report [modifier, 0, key1..key6].
     */
    fun sendRawReport(sourceId: String, report8: ByteArray) {
        if (report8.size < 8) return
        val mergedReport: ByteArray
        synchronized(stateLock) {
            val state = sourceStates.getOrPut(sourceId) { SourceState() }
            state.modifiers = report8[0]
            state.pressedKeys.clear()
            for (i in 2..7) {
                val k = report8[i]
                if (k != 0.toByte()) {
                    state.pressedKeys.add(k)
                }
            }
            mergedReport = computeMergedReport()
        }
        reportChannel.trySend(mergedReport)
    }

    /**
     * Releases all active keys for the specified source enum.
     */
    fun clearSource(source: KeySource) {
        clearSource(source.name)
    }

    /**
     * Releases all active keys for the specified source ID.
     */
    fun clearSource(sourceId: String) {
        val mergedReport: ByteArray
        synchronized(stateLock) {
            val state = sourceStates[sourceId]
            if (state != null) {
                state.modifiers = 0
                state.pressedKeys.clear()
            }
            mergedReport = computeMergedReport()
        }
        reportChannel.trySend(mergedReport)
    }

    /**
     * Releases all active keys for a dynamic source ID and removes its state entry (e.g. on USB unplug).
     */
    fun removeSource(sourceId: String) {
        val mergedReport: ByteArray
        synchronized(stateLock) {
            sourceStates.remove(sourceId)
            mergedReport = computeMergedReport()
        }
        reportChannel.trySend(mergedReport)
    }

    /**
     * Releases all active keys whose sourceId starts with the given prefix.
     */
    fun clearSourcesWithPrefix(prefix: String) {
        val mergedReport: ByteArray
        synchronized(stateLock) {
            val it = sourceStates.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.key.startsWith(prefix)) {
                    it.remove()
                }
            }
            mergedReport = computeMergedReport()
        }
        reportChannel.trySend(mergedReport)
    }

    /**
     * Releases all keys across all input sources.
     */
    fun clearAll() {
        val emptyReport = ByteArray(8)
        synchronized(stateLock) {
            for (st in sourceStates.values) {
                st.modifiers = 0
                st.pressedKeys.clear()
            }
        }
        reportChannel.trySend(emptyReport)
    }

    /**
     * Sends a press-and-release keystroke sequentially inside the caller's coroutine.
     */
    suspend fun sendStroke(source: KeySource, modifier: Byte, keycode: Byte, delayMs: Long = 15L) {
        sendStroke(source.name, modifier, keycode, delayMs)
    }

    suspend fun sendStroke(sourceId: String, modifier: Byte, keycode: Byte, delayMs: Long = 15L) {
        updateSourceKeys(sourceId, modifier, setOf(keycode))
        delay(delayMs)
        updateSourceKeys(sourceId, 0, emptySet())
        delay(delayMs)
    }

    private fun computeMergedReport(): ByteArray {
        var mergedMods = 0
        val combinedKeys = LinkedHashSet<Byte>()

        for (st in sourceStates.values) {
            mergedMods = mergedMods or (st.modifiers.toInt() and 0xFF)
            combinedKeys.addAll(st.pressedKeys)
        }

        val report = ByteArray(8)
        report[0] = mergedMods.toByte()
        report[1] = 0 // Reserved

        var idx = 2
        for (k in combinedKeys) {
            if (idx >= 8) break
            report[idx++] = k
        }
        return report
    }
}
