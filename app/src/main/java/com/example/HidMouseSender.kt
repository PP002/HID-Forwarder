package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class MouseTelemetrySample(
    val buttonsMask: Int,
    val lastDx: Int,
    val lastDy: Int,
    val lastWheel: Int
)

/**
 * Serialized high-priority sender for Bluetooth HID Mouse reports (Report ID 3, or ID 0 in Mouse-only Boot/No-ID mode).
 *
 * Supports multiple concurrent OTG/foreground mouse sources by OR-merging per-source button bitmasks,
 * preserving every button press/release transition immediately while coalescing & chunking relative motion
 * deltas into [-127, 127] frames at ~125 Hz.
 */
class HidMouseSender(
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(log: (String, LogLevel) -> Unit) : this(log, null)

    companion object {
        const val MOUSE_REPORT_ID = 3
        // ~125 Hz move cadence prevents BT L2CAP queue bloat while keeping cursor tracking silky smooth
        private const val MIN_MOVE_INTERVAL_NS = 8_000_000L
        private const val TELEMETRY_EMIT_INTERVAL_MS = 66L
        private const val COUNTER_EMIT_INTERVAL_MS = 80L
    }

    private fun emitLog(key: LogKey, level: LogLevel, vararg args: Any) {
        val lk = logKey
        if (lk != null) {
            lk(key, level, args)
        } else {
            log(LogLocalizer.format(AppLanguage.EN, key, *args), level)
        }
    }

    @Volatile var hidDevice: BluetoothHidDevice? = null
    @Volatile var hostDevice: BluetoothDevice? = null
    @Volatile var mouseEnabled: Boolean = true
    @Volatile var protocolMode: Byte = BluetoothHidDevice.PROTOCOL_REPORT_MODE
    @Volatile var reportIdMode: ReportIdMode = ReportIdMode.AUTO

    /** When non-null (e.g. COMBO mode = 3), overrides dynamic Boot/Report ID resolution. */
    @Volatile var fixedReportId: Int? = MOUSE_REPORT_ID

    @Volatile private var lastReport: ByteArray = ByteArray(4)
    @Volatile private var lastErrorLogMs: Long = 0L
    @Volatile private var lastSkipLogMs: Long = 0L
    private var internalPacketsSent: Long = 0L
    private var lastCounterEmitMs: Long = 0L
    private var lastTelemetryEmitMs: Long = 0L

    private val queueLock = Object()
    private val sourceButtons = ConcurrentHashMap<String, Int>()
    private var mergedButtons: Int = 0
    private var pendingDx: Int = 0
    private var pendingDy: Int = 0
    private var pendingWheel: Int = 0
    private val transitionQueue = ArrayDeque<ByteArray>()
    @Volatile private var closed = false

    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    private val _liveTelemetry = MutableStateFlow<MouseTelemetrySample?>(null)
    val liveTelemetry: StateFlow<MouseTelemetrySample?> = _liveTelemetry.asStateFlow()

    private val workerThread = Thread {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {}

        var lastMoveSentNs = 0L

        while (!closed) {
            var nextReport: ByteArray? = null
            var isTransition = false

            synchronized(queueLock) {
                while (!closed) {
                    if (transitionQueue.isNotEmpty()) {
                        nextReport = transitionQueue.removeFirst()
                        isTransition = true
                        break
                    }
                    if (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0) {
                        val nowNs = System.nanoTime()
                        val elapsedNs = nowNs - lastMoveSentNs
                        if (elapsedNs >= MIN_MOVE_INTERVAL_NS) {
                            val cx = pendingDx.coerceIn(-127, 127)
                            val cy = pendingDy.coerceIn(-127, 127)
                            val cw = pendingWheel.coerceIn(-127, 127)
                            pendingDx -= cx
                            pendingDy -= cy
                            pendingWheel -= cw
                            nextReport = HidMouseReportParser.buildMouseHidReport(mergedButtons, cx, cy, cw)
                            isTransition = false
                            break
                        } else {
                            val waitMs = ((MIN_MOVE_INTERVAL_NS - elapsedNs) / 1_000_000L).coerceAtLeast(1L)
                            try {
                                queueLock.wait(waitMs)
                            } catch (_: InterruptedException) {
                                if (closed) return@synchronized
                            }
                            continue
                        }
                    }
                    try {
                        queueLock.wait()
                    } catch (_: InterruptedException) {
                        if (closed) return@synchronized
                    }
                }
            }

            val report = nextReport ?: continue
            if (!isTransition) {
                lastMoveSentNs = System.nanoTime()
            }
            dispatchToBluetooth(report, isTransition)
        }
    }.apply {
        name = "HidMouseSenderThread"
        isDaemon = true
        start()
    }

    fun resolveActiveReportId(): Int {
        fixedReportId?.let { return it }
        return when (reportIdMode) {
            ReportIdMode.AUTO -> {
                if (protocolMode == BluetoothHidDevice.PROTOCOL_BOOT_MODE) 0 else MOUSE_REPORT_ID
            }
            ReportIdMode.FORCE_1 -> MOUSE_REPORT_ID
            ReportIdMode.FORCE_0 -> 0
        }
    }

    fun getLastReport(): ByteArray = lastReport.copyOf()

    fun sendRelativeMove(
        sourceId: String,
        buttonsMask: Int? = null,
        dx: Int = 0,
        dy: Int = 0,
        wheel: Int = 0
    ) {
        if (closed) return
        val now = System.currentTimeMillis()
        var emitTelemetry: MouseTelemetrySample? = null

        synchronized(queueLock) {
            var buttonsChanged = false
            if (buttonsMask != null) {
                val masked = buttonsMask and 0x1F
                if (masked == 0) {
                    sourceButtons.remove(sourceId)
                } else {
                    sourceButtons[sourceId] = masked
                }
                val nextMerged = computeMergedButtons()
                if (nextMerged != mergedButtons) {
                    mergedButtons = nextMerged
                    buttonsChanged = true
                }
            }

            pendingDx += dx
            pendingDy += dy
            pendingWheel += wheel

            if (buttonsChanged) {
                val cx = pendingDx.coerceIn(-127, 127)
                val cy = pendingDy.coerceIn(-127, 127)
                val cw = pendingWheel.coerceIn(-127, 127)
                pendingDx -= cx
                pendingDy -= cy
                pendingWheel -= cw
                if (transitionQueue.size < 64) {
                    transitionQueue.addLast(
                        HidMouseReportParser.buildMouseHidReport(mergedButtons, cx, cy, cw)
                    )
                }
            }

            if (buttonsChanged || now - lastTelemetryEmitMs >= TELEMETRY_EMIT_INTERVAL_MS) {
                lastTelemetryEmitMs = now
                emitTelemetry = MouseTelemetrySample(
                    buttonsMask = mergedButtons,
                    lastDx = dx,
                    lastDy = dy,
                    lastWheel = wheel
                )
            }
            queueLock.notifyAll()
        }

        if (emitTelemetry != null) {
            _liveTelemetry.value = emitTelemetry
        }
    }

    fun updateSourceButtons(sourceId: String, buttonsMask: Int) {
        sendRelativeMove(sourceId = sourceId, buttonsMask = buttonsMask, dx = 0, dy = 0, wheel = 0)
    }

    fun sendClick(sourceId: String, buttonMask: Int) {
        val masked = buttonMask and 0x1F
        if (masked == 0) return
        updateSourceButtons(sourceId, masked)
        try {
            Thread.sleep(20L)
        } catch (_: InterruptedException) {}
        updateSourceButtons(sourceId, 0)
    }

    fun removeSource(sourceId: String) {
        if (closed) return
        synchronized(queueLock) {
            if (sourceButtons.remove(sourceId) != null) {
                val nextMerged = computeMergedButtons()
                if (nextMerged != mergedButtons) {
                    mergedButtons = nextMerged
                    if (transitionQueue.size < 64) {
                        transitionQueue.addLast(
                            HidMouseReportParser.buildMouseHidReport(mergedButtons, 0, 0, 0)
                        )
                    }
                    queueLock.notifyAll()
                }
            }
        }
    }

    fun clearMouse() {
        val zero = ByteArray(4)
        synchronized(queueLock) {
            sourceButtons.clear()
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
            transitionQueue.clear()
            if (mergedButtons != 0 || lastReport.any { it != 0.toByte() }) {
                transitionQueue.addLast(zero)
            }
            mergedButtons = 0
            queueLock.notifyAll()
        }
        _liveTelemetry.value = null
    }

    fun shutdown() {
        closed = true
        synchronized(queueLock) {
            sourceButtons.clear()
            transitionQueue.clear()
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
            queueLock.notifyAll()
        }
        workerThread.interrupt()
    }

    private fun computeMergedButtons(): Int {
        var mask = 0
        for (v in sourceButtons.values) {
            mask = mask or v
        }
        return mask and 0x1F
    }

    @SuppressLint("MissingPermission")
    private fun dispatchToBluetooth(report: ByteArray, isTransition: Boolean) {
        lastReport = report
        if (!mouseEnabled) return

        val hid = hidDevice
        val host = hostDevice
        val reportId = resolveActiveReportId()
        if (hid != null && host != null) {
            try {
                val ok = hid.sendReport(host, reportId, report)
                if (ok) {
                    internalPacketsSent++
                    val now = System.currentTimeMillis()
                    if (isTransition || now - lastCounterEmitMs >= COUNTER_EMIT_INTERVAL_MS) {
                        lastCounterEmitMs = now
                        _packetsSent.value = internalPacketsSent
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
                val now = System.currentTimeMillis()
                if (now - lastErrorLogMs > 1500L) {
                    lastErrorLogMs = now
                    emitLog(LogKey.HID_SEND_EXCEPTION, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
                }
            }
        } else if (report.any { it != 0.toByte() }) {
            val now = System.currentTimeMillis()
            if (now - lastSkipLogMs > 2500L) {
                lastSkipLogMs = now
                if (hid == null) {
                    emitLog(LogKey.HID_SKIP_NO_PROXY, LogLevel.WARN)
                } else {
                    emitLog(LogKey.HID_SKIP_NO_HOST, LogLevel.WARN)
                }
            }
        }
    }
}
