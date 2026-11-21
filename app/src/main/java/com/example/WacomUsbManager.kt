package com.example

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

enum class WacomState {
    STOPPED,
    WAITING_FOR_DEVICE,
    REQUESTING_PERMISSION,
    ACTIVE
}

class WacomUsbManager(
    private val context: Context,
    private val penSender: HidPenSender,
    private val keySender: HidKeySender,
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(
        context: Context,
        penSender: HidPenSender,
        keySender: HidKeySender,
        log: (String, LogLevel) -> Unit
    ) : this(context, penSender, keySender, log, null)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.action.WACOM_USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _state = MutableStateFlow(WacomState.STOPPED)
    val state: StateFlow<WacomState> = _state.asStateFlow()

    private val _detectedModel = MutableStateFlow<WacomModelSpec?>(null)
    val detectedModel: StateFlow<WacomModelSpec?> = _detectedModel.asStateFlow()

    private val _liveSample = MutableStateFlow<WacomPenSample?>(null)
    val liveSample: StateFlow<WacomPenSample?> = _liveSample.asStateFlow()

    private val _orientation = MutableStateFlow(WacomOrientation.LANDSCAPE_0)
    val orientation: StateFlow<WacomOrientation> = _orientation.asStateFlow()

    private val _pressureCurve = MutableStateFlow(WacomPressureCurve.GAMMA_1_2)
    val pressureCurve: StateFlow<WacomPressureCurve> = _pressureCurve.asStateFlow()

    private val _buttonMode = MutableStateFlow(WacomButtonMode.STYLUS_ONLY)
    val buttonMode: StateFlow<WacomButtonMode> = _buttonMode.asStateFlow()

    @Volatile private var desiredRunning = false
    @Volatile private var activeDeviceId: Int = -1
    @Volatile private var readerThread: Thread? = null
    private val readerStop = AtomicBoolean(false)
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    private var activeUsbRequests: List<UsbRequest> = emptyList()
    private val connectionLock = Any()
    private val seenUnknownReportIds = HashSet<Int>()

    private fun emitLog(key: LogKey, level: LogLevel, vararg args: Any) {
        val lk = logKey
        if (lk != null) {
            lk(key, level, args)
        } else {
            log(LogLocalizer.format(AppLanguage.EN, key, *args), level)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null && device.vendorId == WacomProtocol.WACOM_VENDOR_ID) {
                        emitLog(LogKey.WACOM_USB_PERMISSION_GRANTED, LogLevel.SUCCESS, device.productName ?: "Wacom")
                        if (desiredRunning) {
                            openAndStartReader(device)
                        }
                    } else {
                        emitLog(LogKey.WACOM_USB_PERMISSION_DENIED, LogLevel.WARN)
                        if (desiredRunning) {
                            _state.value = WacomState.WAITING_FOR_DEVICE
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && device.vendorId == WacomProtocol.WACOM_VENDOR_ID) {
                        val spec = WacomProtocol.resolveModel(device.vendorId, device.productId, device.productName)
                        _detectedModel.value = spec
                        emitLog(
                            LogKey.WACOM_DEVICE_DETECTED,
                            LogLevel.INFO,
                            spec?.modelName ?: "Wacom",
                            "0x%04X:0x%04X".format(device.vendorId, device.productId)
                        )
                        if (desiredRunning && _state.value != WacomState.ACTIVE) {
                            connectOrRequestPermission(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.vendorId == WacomProtocol.WACOM_VENDOR_ID) {
                        if (device.deviceId == activeDeviceId) {
                            emitLog(LogKey.WACOM_DEVICE_DETACHED, LogLevel.WARN)
                            stopReaderOnly()
                            _detectedModel.value = findAttachedWacomDevice()?.let {
                                WacomProtocol.resolveModel(it.vendorId, it.productId, it.productName)
                            }
                            if (desiredRunning) {
                                _state.value = WacomState.WAITING_FOR_DEVICE
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        try {
            val permFilter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(context, usbReceiver, permFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}

        try {
            val usbFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, usbReceiver, usbFilter, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Exception) {}

        refreshAttachedDevice()
    }

    fun setOrientation(orientation: WacomOrientation) {
        _orientation.value = orientation
        emitLog(LogKey.WACOM_ORIENTATION_CHANGED, LogLevel.INFO, orientation.name)
    }

    fun setPressureCurve(curve: WacomPressureCurve) {
        _pressureCurve.value = curve
        emitLog(LogKey.WACOM_PRESSURE_CURVE_CHANGED, LogLevel.INFO, curve.name)
    }

    fun setButtonMode(mode: WacomButtonMode) {
        _buttonMode.value = mode
        emitLog(LogKey.WACOM_BUTTON_MODE_CHANGED, LogLevel.INFO, mode.name)
    }

    fun refreshAttachedDevice(): WacomModelSpec? {
        val dev = findAttachedWacomDevice()
        val spec = dev?.let { WacomProtocol.resolveModel(it.vendorId, it.productId, it.productName) }
        _detectedModel.value = spec
        return spec
    }

    private fun findAttachedWacomDevice(): UsbDevice? {
        val mgr = usbManager ?: return null
        return mgr.deviceList.values.firstOrNull { it.vendorId == WacomProtocol.WACOM_VENDOR_ID }
    }

    fun start() {
        desiredRunning = true
        HidRelayForegroundService.setWacomRunning(context, true)
        emitLog(LogKey.WACOM_STARTING, LogLevel.INFO)

        val dev = findAttachedWacomDevice()
        if (dev == null) {
            _detectedModel.value = null
            _state.value = WacomState.WAITING_FOR_DEVICE
            emitLog(LogKey.WACOM_NO_DEVICE_WAITING, LogLevel.WARN)
            return
        }

        val spec = WacomProtocol.resolveModel(dev.vendorId, dev.productId, dev.productName)
        _detectedModel.value = spec
        emitLog(
            LogKey.WACOM_DEVICE_DETECTED,
            LogLevel.INFO,
            spec?.modelName ?: "Wacom",
            "0x%04X:0x%04X".format(dev.vendorId, dev.productId)
        )
        connectOrRequestPermission(dev)
    }

    fun stop(silent: Boolean = false) {
        desiredRunning = false
        HidRelayForegroundService.setWacomRunning(context, false)
        stopReaderOnly()
        _state.value = WacomState.STOPPED
        if (!silent) {
            emitLog(LogKey.WACOM_STOPPED, LogLevel.INFO)
        }
    }

    fun destroy() {
        stop(silent = true)
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    private fun connectOrRequestPermission(device: UsbDevice) {
        val mgr = usbManager ?: return
        if (mgr.hasPermission(device)) {
            openAndStartReader(device)
        } else {
            _state.value = WacomState.REQUESTING_PERMISSION
            emitLog(LogKey.WACOM_REQUESTING_USB_PERMISSION, LogLevel.INFO)
            val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, 0, permIntent, flags)
            mgr.requestPermission(device, pending)
        }
    }

    private fun findPenInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        // Prefer HID-class interfaces first (Interface 0 is pen on Wacom pen+touch models)
        for (pass in 0..1) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (pass == 0 && intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    ) {
                        return Pair(intf, ep)
                    }
                }
            }
        }
        return null
    }

    private fun trySysfsUnbindWacomViaRoot() {
        try {
            val usbDevicesDir = File("/sys/bus/usb/devices")
            if (!usbDevicesDir.exists()) return
            val script = """
                for d in /sys/bus/usb/devices/*; do
                  [ -f "${'$'}d/idVendor" ] || continue
                  v=${'$'}(cat "${'$'}d/idVendor" 2>/dev/null)
                  if [ "${'$'}v" = "056a" ]; then
                    base=${'$'}(basename "${'$'}d")
                    for intf in "${'$'}d/${'$'}base":*; do
                      if [ -L "${'$'}intf/driver" ]; then
                        drv=${'$'}(basename "${'$'}(readlink "${'$'}intf/driver")")
                        if [ "${'$'}drv" = "usbhid" ]; then
                          echo "${'$'}(basename "${'$'}intf")" > /sys/bus/usb/drivers/usbhid/unbind 2>/dev/null
                        fi
                      fi
                    done
                  fi
                done
            """.trimIndent()
            Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor()
        } catch (_: Exception) {}
    }

    private fun openAndStartReader(device: UsbDevice) {
        val mgr = usbManager ?: return
        stopReaderOnly()

        val spec = WacomProtocol.resolveModel(device.vendorId, device.productId, device.productName)
            ?: return
        _detectedModel.value = spec

        val targetPair = findPenInterfaceAndEndpoint(device)
        if (targetPair == null) {
            emitLog(LogKey.WACOM_NO_ENDPOINT, LogLevel.ERROR, spec.modelName)
            if (desiredRunning) _state.value = WacomState.WAITING_FOR_DEVICE
            return
        }

        val (intf, epIn) = targetPair
        val conn = mgr.openDevice(device)
        if (conn == null) {
            emitLog(LogKey.WACOM_OPEN_FAILED, LogLevel.ERROR, spec.modelName)
            if (desiredRunning) _state.value = WacomState.WAITING_FOR_DEVICE
            return
        }

        var claimed = conn.claimInterface(intf, true)
        if (!claimed) {
            trySysfsUnbindWacomViaRoot()
            claimed = conn.claimInterface(intf, true)
        }
        if (!claimed) {
            conn.close()
            emitLog(LogKey.WACOM_CLAIM_FAILED, LogLevel.ERROR, intf.id)
            if (desiredRunning) _state.value = WacomState.WAITING_FOR_DEVICE
            return
        }

        // Send CTL-471 / Wacom Report ID 0x02 SET_REPORT Feature unlock (absolute pressure mode)
        val initBuf = WacomProtocol.CTL471_FEATURE_INIT_REPORT.copyOf()
        var ctrlRes = conn.controlTransfer(
            0x21,
            0x09,
            0x0302,
            intf.id,
            initBuf,
            initBuf.size,
            1000
        )
        if (ctrlRes < 0) {
            val shortInit = WacomProtocol.WACOM_SHORT_INIT_REPORT.copyOf()
            ctrlRes = conn.controlTransfer(
                0x21,
                0x09,
                0x0302,
                intf.id,
                shortInit,
                shortInit.size,
                1000
            )
        }
        emitLog(LogKey.WACOM_MODE_UNLOCKED, LogLevel.SUCCESS, spec.modelName, ctrlRes)

        // Use exact endpoint wMaxPacketSize so the USB Host finishes every 10-byte CTL-471 packet immediately
        val packetSize = epIn.maxPacketSize.coerceAtLeast(8)
        val pipelineDepth = 4
        val pooledRequests = ArrayList<Pair<UsbRequest, ByteBuffer>>(pipelineDepth)
        var pipelineReady = true
        for (i in 0 until pipelineDepth) {
            val req = UsbRequest()
            if (!req.initialize(conn, epIn)) {
                pipelineReady = false
                req.close()
                break
            }
            val directBuf = ByteBuffer.allocateDirect(packetSize)
            req.clientData = directBuf
            pooledRequests.add(Pair(req, directBuf))
        }
        if (!pipelineReady) {
            for ((req, _) in pooledRequests) {
                try { req.close() } catch (_: Exception) {}
            }
            pooledRequests.clear()
        }

        synchronized(connectionLock) {
            activeConnection = conn
            activeInterface = intf
            activeDeviceId = device.deviceId
            activeUsbRequests = pooledRequests.map { it.first }
            seenUnknownReportIds.clear()
        }

        readerStop.set(false)
        _state.value = WacomState.ACTIVE

        val t = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}

            val pktBuf = ByteArray(packetSize)
            var prevBtn1 = false
            var prevBtn2 = false
            var lastUiUpdateMs = 0L
            var consecutiveTimeouts = 0

            fun handlePacket(n: Int) {
                if (n < 4) return
                val reportId = pktBuf[0].toInt() and 0xFF
                if (reportId == 0x02 && n >= 8) {
                    val sample = WacomProtocol.parseReport02(
                        data = pktBuf,
                        length = n,
                        spec = spec,
                        orientation = _orientation.value,
                        pressureCurve = _pressureCurve.value
                    ) ?: return

                    val report7 = WacomProtocol.buildPenHidReport(
                        inRange = sample.inRange,
                        tipSwitch = sample.tipSwitch,
                        button1 = sample.button1,
                        button2 = sample.button2,
                        hidX = sample.hidX,
                        hidY = sample.hidY,
                        hidPressure = sample.hidPressure
                    )
                    penSender.sendPenReport(report7)

                    if (_buttonMode.value == WacomButtonMode.STYLUS_PLUS_SHORTCUTS) {
                        if (sample.button1 && !prevBtn1) {
                            scope.launch { keySender.sendStroke(KeySource.USB, 0, 0x28.toByte(), 15L) } // Enter
                        }
                        if (sample.button2 && !prevBtn2) {
                            scope.launch { keySender.sendStroke(KeySource.USB, 0, 0x08.toByte(), 15L) } // E
                        }
                    }
                    prevBtn1 = sample.button1
                    prevBtn2 = sample.button2

                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs >= 66L || !sample.inRange) {
                        lastUiUpdateMs = now
                        _liveSample.value = sample
                    }
                } else if (reportId != 0x01) {
                    val isNew = synchronized(seenUnknownReportIds) { seenUnknownReportIds.add(reportId) }
                    if (isNew) {
                        val hex = pktBuf.take(n.coerceAtMost(12)).joinToString(" ") { "%02X".format(it) }
                        emitLog(LogKey.WACOM_UNHANDLED_REPORT, LogLevel.INFO, "0x%02X".format(reportId), hex)
                    }
                }
            }

            if (pooledRequests.isNotEmpty()) {
                for ((req, directBuf) in pooledRequests) {
                    directBuf.clear()
                    req.queue(directBuf)
                }

                while (!readerStop.get() && desiredRunning && !Thread.currentThread().isInterrupted) {
                    val completed: UsbRequest? = try {
                        conn.requestWait(250L)
                    } catch (_: TimeoutException) {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts > 8) {
                            if (findAttachedWacomDevice()?.deviceId != device.deviceId) {
                                break
                            }
                            consecutiveTimeouts = 0
                        }
                        continue
                    } catch (_: Exception) {
                        break
                    }

                    if (completed == null) {
                        if (readerStop.get() || !desiredRunning) break
                        if (findAttachedWacomDevice()?.deviceId != device.deviceId) break
                        continue
                    }

                    consecutiveTimeouts = 0
                    val directBuf = completed.clientData as? ByteBuffer ?: continue
                    val n = directBuf.position().coerceAtMost(packetSize)
                    if (n > 0) {
                        directBuf.rewind()
                        directBuf.get(pktBuf, 0, n)
                    }
                    // Immediately re-queue URB before parsing so the kernel USB pipeline never starves
                    if (!readerStop.get() && desiredRunning) {
                        directBuf.clear()
                        if (!completed.queue(directBuf)) {
                            break
                        }
                    }
                    if (n > 0) {
                        handlePacket(n)
                    }
                }
            } else {
                // Synchronous bulkTransfer fallback using exact endpoint packetSize
                while (!readerStop.get() && desiredRunning && !Thread.currentThread().isInterrupted) {
                    val n = try {
                        conn.bulkTransfer(epIn, pktBuf, packetSize, 250)
                    } catch (_: Exception) {
                        -1
                    }

                    if (n < 0) {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts > 8) {
                            if (findAttachedWacomDevice()?.deviceId != device.deviceId) {
                                break
                            }
                            consecutiveTimeouts = 0
                        }
                        continue
                    }
                    consecutiveTimeouts = 0
                    handlePacket(n)
                }
            }

            penSender.clearPen()
            _liveSample.value = null
            if (desiredRunning && !readerStop.get()) {
                emitLog(LogKey.WACOM_DEVICE_DETACHED, LogLevel.WARN)
                _state.value = WacomState.WAITING_FOR_DEVICE
            }
        }.apply {
            name = "WacomUsbReaderThread"
            isDaemon = true
            start()
        }
        readerThread = t
    }

    private fun stopReaderOnly() {
        readerStop.set(true)
        synchronized(connectionLock) {
            for (req in activeUsbRequests) {
                try { req.cancel() } catch (_: Exception) {}
                try { req.close() } catch (_: Exception) {}
            }
            activeUsbRequests = emptyList()
            readerThread?.interrupt()
            readerThread = null
            try {
                activeInterface?.let { activeConnection?.releaseInterface(it) }
            } catch (_: Exception) {}
            try {
                activeConnection?.close()
            } catch (_: Exception) {}
            activeInterface = null
            activeConnection = null
            activeDeviceId = -1
        }
        penSender.clearPen()
        _liveSample.value = null
    }
}
