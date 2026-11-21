package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Serialized high-priority sender for Bluetooth HID Pen/Digitizer reports (Report ID 2).
 *
 * Preserves every pen state transition (hover enter/exit, tip up/down, barrel button changes)
 * while coalescing intermediate coordinate-only move reports so high-rate USB tablets (133Hz+)
 * never build up Bluetooth latency.
 */
class HidPenSender(
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(log: (String, LogLevel) -> Unit) : this(log, null)

    companion object {
        const val PEN_REPORT_ID = 2
        // Minimum interval for coordinate-only move updates (~166 Hz cap prevents BT L2CAP burst backlog)
        private const val MIN_MOVE_INTERVAL_NS = 6_000_000L
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
    @Volatile var penEnabled: Boolean = true

    @Volatile private var lastReport: ByteArray = ByteArray(7)
    private var lastEnqueuedReport: ByteArray = ByteArray(7)
    @Volatile private var lastEnqueuedFlags: Byte = 0
    @Volatile private var lastErrorLogMs: Long = 0L
    @Volatile private var lastSkipLogMs: Long = 0L
    private var internalPacketsSent: Long = 0L
    private var lastCounterEmitMs: Long = 0L

    private val queueLock = Object()
    private val transitionQueue = ArrayDeque<ByteArray>()
    private var pendingMoveReport: ByteArray? = null
    @Volatile private var closed = false

    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

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
                    val move = pendingMoveReport
                    if (move != null) {
                        val nowNs = System.nanoTime()
                        val elapsedNs = nowNs - lastMoveSentNs
                        if (elapsedNs >= MIN_MOVE_INTERVAL_NS) {
                            pendingMoveReport = null
                            nextReport = move
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
        name = "HidPenSenderThread"
        isDaemon = true
        start()
    }

    fun getLastReport(): ByteArray = lastReport.copyOf()

    fun sendPenReport(report7: ByteArray) {
        if (report7.size < 7 || closed) return
        val flags = report7[0]
        synchronized(queueLock) {
            // Drop exact duplicate stationary packets to save Bluetooth bandwidth and L2CAP queue space
            var same = true
            for (i in 0 until 7) {
                if (report7[i] != lastEnqueuedReport[i]) {
                    same = false
                    break
                }
            }
            if (same) return

            val copy = report7.copyOf(7)
            System.arraycopy(copy, 0, lastEnqueuedReport, 0, 7)

            if (flags != lastEnqueuedFlags) {
                // Always preserve flag state edges (in-range, tip switch, barrel buttons) without delay
                pendingMoveReport?.let {
                    if (transitionQueue.size < 32) {
                        transitionQueue.addLast(it)
                    }
                    pendingMoveReport = null
                }
                if (transitionQueue.size < 64) {
                    transitionQueue.addLast(copy)
                }
                lastEnqueuedFlags = flags
            } else if ((flags.toInt() and 0x20) != 0) {
                // Coalesce move-only coordinates while pen is in range
                pendingMoveReport = copy
            }
            queueLock.notifyAll()
        }
    }

    fun clearPen() {
        val zero = ByteArray(7)
        synchronized(queueLock) {
            transitionQueue.clear()
            pendingMoveReport = null
            if (lastEnqueuedFlags != 0.toByte() || lastReport.any { it != 0.toByte() }) {
                transitionQueue.addLast(zero)
            }
            lastEnqueuedFlags = 0
            lastEnqueuedReport.fill(0)
            queueLock.notifyAll()
        }
    }

    fun shutdown() {
        closed = true
        synchronized(queueLock) {
            transitionQueue.clear()
            pendingMoveReport = null
            queueLock.notifyAll()
        }
        workerThread.interrupt()
    }

    @SuppressLint("MissingPermission")
    private fun dispatchToBluetooth(report: ByteArray, isTransition: Boolean) {
        lastReport = report
        if (!penEnabled) return

        val hid = hidDevice
        val host = hostDevice
        if (hid != null && host != null) {
            try {
                val ok = hid.sendReport(host, PEN_REPORT_ID, report)
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
                            PEN_REPORT_ID,
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
        } else if (report[0] != 0.toByte()) {
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
