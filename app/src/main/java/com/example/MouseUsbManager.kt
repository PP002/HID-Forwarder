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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

enum class MouseRelayState {
    STOPPED,
    WAITING_FOR_DEVICE,
    REQUESTING_PERMISSION,
    ACTIVE
}

data class ConnectedUsbMouse(
    val deviceId: Int,
    val name: String,
    val vidPid: String,
    val interfaceId: Int,
    val isActive: Boolean
)

/**
 * Multi-OTG USB HID Mouse Host reader.
 * Supports multiple simultaneously attached USB mice and wireless mouse receivers (via USB OTG hub),
 * including standard Boot Mice (subclass=1, protocol=2) and custom HID Report Descriptor gaming/multi-button mice.
 */
class MouseUsbManager(
    private val context: Context,
    private val mouseSender: HidMouseSender,
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(
        context: Context,
        mouseSender: HidMouseSender,
        log: (String, LogLevel) -> Unit
    ) : this(context, mouseSender, log, null)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.action.MOUSE_USB_PERMISSION"
        private const val PERMISSION_REQ_BASE = 20000
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _state = MutableStateFlow(MouseRelayState.STOPPED)
    val state: StateFlow<MouseRelayState> = _state.asStateFlow()

    private val _activeMice = MutableStateFlow<List<ConnectedUsbMouse>>(emptyList())
    val activeMice: StateFlow<List<ConnectedUsbMouse>> = _activeMice.asStateFlow()

    private val _sensitivity = MutableStateFlow(1.0f)
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private class ActiveMouseSession(
        val device: UsbDevice,
        val deviceId: Int,
        val name: String,
        val vidPid: String,
        val intf: UsbInterface,
        val conn: UsbDeviceConnection,
        val requests: List<UsbRequest>,
        val stopFlag: AtomicBoolean,
        var thread: Thread? = null
    )

    private val sessions = ConcurrentHashMap<Int, ActiveMouseSession>()
    private val permissionQueue = ArrayDeque<UsbDevice>()
    private var permissionInFlightDeviceId: Int? = null
    private val stateLock = Any()
    @Volatile private var desiredRunning = false

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
                    synchronized(stateLock) {
                        if (device?.deviceId == permissionInFlightDeviceId) {
                            permissionInFlightDeviceId = null
                        }
                    }
                    if (granted && device != null && isPotentialMouseUsbDevice(device)) {
                        emitLog(LogKey.MOUSE_USB_PERMISSION_GRANTED, LogLevel.SUCCESS, displayDeviceName(device))
                        if (desiredRunning) {
                            openAndStartReader(device)
                        }
                    } else if (device != null) {
                        emitLog(LogKey.MOUSE_USB_PERMISSION_DENIED, LogLevel.WARN, displayDeviceName(device))
                    }
                    requestNextQueuedPermission()
                    syncStateAndList()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && isPotentialMouseUsbDevice(device)) {
                        emitLog(
                            LogKey.MOUSE_DEVICE_DETECTED,
                            LogLevel.INFO,
                            displayDeviceName(device),
                            formatVidPid(device)
                        )
                        if (desiredRunning) {
                            connectOrQueuePermission(device)
                        }
                        syncStateAndList()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null) {
                        val existed = closeSession(device.deviceId)
                        synchronized(stateLock) {
                            permissionQueue.removeAll { it.deviceId == device.deviceId }
                            if (permissionInFlightDeviceId == device.deviceId) {
                                permissionInFlightDeviceId = null
                            }
                        }
                        if (existed) {
                            emitLog(LogKey.MOUSE_DEVICE_DETACHED, LogLevel.WARN, displayDeviceName(device))
                        }
                        syncStateAndList()
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

        syncStateAndList()
    }

    fun setSensitivity(scale: Float) {
        _sensitivity.value = scale.coerceIn(0.25f, 4.0f)
        emitLog(LogKey.MOUSE_SENSITIVITY_CHANGED, LogLevel.INFO, "%.2fx".format(_sensitivity.value))
    }

    fun refreshAttachedDevices(): List<ConnectedUsbMouse> {
        if (desiredRunning) {
            scanAndConnectAttachedMice()
        }
        return syncStateAndList()
    }

    fun start() {
        desiredRunning = true
        HidRelayForegroundService.setMouseRunning(context, true)
        emitLog(LogKey.MOUSE_STARTING, LogLevel.INFO)

        val candidates = findCandidateMouseDevices()
        if (candidates.isEmpty()) {
            _state.value = MouseRelayState.WAITING_FOR_DEVICE
            syncStateAndList()
            emitLog(LogKey.MOUSE_NO_DEVICE_WAITING, LogLevel.WARN)
            return
        }

        scanAndConnectAttachedMice()
        syncStateAndList()
    }

    fun stop(silent: Boolean = false) {
        desiredRunning = false
        HidRelayForegroundService.setMouseRunning(context, false)
        synchronized(stateLock) {
            permissionQueue.clear()
            permissionInFlightDeviceId = null
        }
        for (id in sessions.keys.toList()) {
            closeSession(id)
        }
        _state.value = MouseRelayState.STOPPED
        syncStateAndList()
        if (!silent) {
            emitLog(LogKey.MOUSE_STOPPED, LogLevel.INFO)
        }
    }

    fun destroy() {
        stop(silent = true)
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    private fun displayDeviceName(device: UsbDevice): String {
        return device.productName?.takeIf { it.isNotBlank() }
            ?: "USB Mouse (${formatVidPid(device)})"
    }

    private fun formatVidPid(device: UsbDevice): String {
        return "0x%04X:0x%04X".format(device.vendorId, device.productId)
    }

    private fun isPotentialMouseUsbDevice(device: UsbDevice): Boolean {
        if (device.vendorId == WacomProtocol.WACOM_VENDOR_ID) return false
        return findCandidateMouseInterfaces(device).isNotEmpty()
    }

    /**
     * Returns HID Interrupt-IN interfaces ordered by likelihood of being a mouse:
     * 1) Explicit Boot Mouse (class=3, subclass=1, protocol=2)
     * 2) Generic/Report-mode HID interface (class=3, protocol=0, excluding Boot Keyboard subclass=1 protocol=1)
     */
    private fun findCandidateMouseInterfaces(device: UsbDevice): List<Pair<UsbInterface, UsbEndpoint>> {
        val bootMatches = ArrayList<Pair<UsbInterface, UsbEndpoint>>()
        val genericHidMatches = ArrayList<Pair<UsbInterface, UsbEndpoint>>()

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            // Skip explicit Boot Keyboards (subclass=1, protocol=1)
            if (intf.interfaceSubclass == 1 && intf.interfaceProtocol == 1) continue

            var epIn: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                ) {
                    epIn = ep
                    break
                }
            }
            if (epIn == null) continue

            if (intf.interfaceSubclass == 1 && intf.interfaceProtocol == 2) {
                bootMatches.add(Pair(intf, epIn))
            } else {
                genericHidMatches.add(Pair(intf, epIn))
            }
        }
        return bootMatches + genericHidMatches
    }

    private fun findCandidateMouseDevices(): List<UsbDevice> {
        val mgr = usbManager ?: return emptyList()
        return mgr.deviceList.values.filter { isPotentialMouseUsbDevice(it) }
    }

    private fun scanAndConnectAttachedMice() {
        for (dev in findCandidateMouseDevices()) {
            if (!sessions.containsKey(dev.deviceId)) {
                connectOrQueuePermission(dev)
            }
        }
    }

    private fun connectOrQueuePermission(device: UsbDevice) {
        val mgr = usbManager ?: return
        if (sessions.containsKey(device.deviceId)) return

        if (mgr.hasPermission(device)) {
            openAndStartReader(device)
        } else {
            synchronized(stateLock) {
                if (permissionInFlightDeviceId == device.deviceId) return
                if (permissionQueue.none { it.deviceId == device.deviceId }) {
                    permissionQueue.addLast(device)
                }
            }
            requestNextQueuedPermission()
        }
    }

    private fun requestNextQueuedPermission() {
        val mgr = usbManager ?: return
        val nextDev: UsbDevice? = synchronized(stateLock) {
            if (permissionInFlightDeviceId != null) return
            var candidate: UsbDevice? = null
            while (permissionQueue.isNotEmpty()) {
                val d = permissionQueue.removeFirst()
                if (!sessions.containsKey(d.deviceId)) {
                    candidate = d
                    break
                }
            }
            if (candidate != null) {
                if (!mgr.hasPermission(candidate)) {
                    permissionInFlightDeviceId = candidate.deviceId
                }
            }
            candidate
        }

        if (nextDev == null) return

        if (mgr.hasPermission(nextDev)) {
            openAndStartReader(nextDev)
            requestNextQueuedPermission()
            return
        }

        _state.value = MouseRelayState.REQUESTING_PERMISSION
        emitLog(LogKey.MOUSE_REQUESTING_USB_PERMISSION, LogLevel.INFO, displayDeviceName(nextDev))
        val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context,
            PERMISSION_REQ_BASE + (nextDev.deviceId and 0x3FFF),
            permIntent,
            flags
        )
        mgr.requestPermission(nextDev, pending)
    }

    private fun trySysfsUnbindInterfaceViaRoot(vendorId: Int, productId: Int, interfaceNumber: Int) {
        try {
            if (!File("/sys/bus/usb/devices").exists()) return
            val vidHex = "%04x".format(vendorId)
            val pidHex = "%04x".format(productId)
            val intfHex = "%02x".format(interfaceNumber)
            val script = """
                for d in /sys/bus/usb/devices/*; do
                  [ -f "${'$'}d/idVendor" ] || continue
                  v=${'$'}(cat "${'$'}d/idVendor" 2>/dev/null)
                  p=${'$'}(cat "${'$'}d/idProduct" 2>/dev/null)
                  if [ "${'$'}v" = "$vidHex" ] && [ "${'$'}p" = "$pidHex" ]; then
                    base=${'$'}(basename "${'$'}d")
                    for intf in "${'$'}d/${'$'}base":*; do
                      bnum=${'$'}(cat "${'$'}intf/bInterfaceNumber" 2>/dev/null)
                      if [ "${'$'}bnum" = "$intfHex" ] && [ -L "${'$'}intf/driver" ]; then
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

    private fun fetchMouseLayoutFromDescriptor(
        conn: UsbDeviceConnection,
        intf: UsbInterface
    ): HidMouseReportLayout? {
        val descBuf = ByteArray(1024)
        val len = conn.controlTransfer(
            0x81, // USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_INTERFACE
            0x06, // GET_DESCRIPTOR
            0x2200, // HID Report Descriptor
            intf.id,
            descBuf,
            descBuf.size,
            1000
        )
        if (len <= 0) return null
        return HidMouseReportParser.parseDescriptorForMouse(descBuf.copyOf(len))
    }

    private fun openAndStartReader(device: UsbDevice) {
        val mgr = usbManager ?: return
        if (sessions.containsKey(device.deviceId)) return

        val candidates = findCandidateMouseInterfaces(device)
        if (candidates.isEmpty()) return

        val conn = mgr.openDevice(device)
        val devName = displayDeviceName(device)
        if (conn == null) {
            emitLog(LogKey.MOUSE_OPEN_FAILED, LogLevel.ERROR, devName)
            return
        }

        var selectedIntf: UsbInterface? = null
        var selectedEp: UsbEndpoint? = null
        var selectedLayout: HidMouseReportLayout? = null

        for ((intf, ep) in candidates) {
            var claimed = conn.claimInterface(intf, true)
            if (!claimed) {
                trySysfsUnbindInterfaceViaRoot(device.vendorId, device.productId, intf.id)
                claimed = conn.claimInterface(intf, true)
            }
            if (!claimed) continue

            val parsedLayout = fetchMouseLayoutFromDescriptor(conn, intf)
            val isBootMouse = (intf.interfaceSubclass == 1 && intf.interfaceProtocol == 2)
            if (parsedLayout != null || isBootMouse) {
                selectedIntf = intf
                selectedEp = ep
                selectedLayout = parsedLayout ?: HidMouseReportParser.bootMouseLayout()
                break
            }
            try { conn.releaseInterface(intf) } catch (_: Exception) {}
        }

        if (selectedIntf == null || selectedEp == null) {
            conn.close()
            // Do not error-spam if a USB keyboard's extra vendor interface wasn't a mouse
            return
        }

        val intf = selectedIntf
        val epIn = selectedEp
        val layout = selectedLayout
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

        val stopFlag = AtomicBoolean(false)
        val session = ActiveMouseSession(
            device = device,
            deviceId = device.deviceId,
            name = devName,
            vidPid = formatVidPid(device),
            intf = intf,
            conn = conn,
            requests = pooledRequests.map { it.first },
            stopFlag = stopFlag
        )
        sessions[device.deviceId] = session
        syncStateAndList()
        emitLog(LogKey.MOUSE_READER_ACTIVE, LogLevel.SUCCESS, devName, intf.id)

        val sourceKey = "usb_mouse:${device.deviceId}"

        val t = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}

            val pktBuf = ByteArray(packetSize)
            var fracX = 0f
            var fracY = 0f
            var consecutiveTimeouts = 0

            fun handlePacket(n: Int) {
                val parsed = HidMouseReportParser.parsePacket(pktBuf, n, layout) ?: return
                val sens = _sensitivity.value
                val scaledDxFloat = parsed.dx * sens + fracX
                val scaledDyFloat = parsed.dy * sens + fracY
                val outDx = scaledDxFloat.toInt()
                val outDy = scaledDyFloat.toInt()
                fracX = scaledDxFloat - outDx
                fracY = scaledDyFloat - outDy

                mouseSender.sendRelativeMove(
                    sourceId = sourceKey,
                    buttonsMask = parsed.buttonsMask,
                    dx = outDx,
                    dy = outDy,
                    wheel = parsed.wheel
                )
            }

            if (pooledRequests.isNotEmpty()) {
                for ((req, directBuf) in pooledRequests) {
                    directBuf.clear()
                    req.queue(directBuf)
                }
                while (!stopFlag.get() && desiredRunning && !Thread.currentThread().isInterrupted) {
                    val completed: UsbRequest? = try {
                        conn.requestWait(250L)
                    } catch (_: TimeoutException) {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts > 8) {
                            if (usbManager?.deviceList?.containsKey(device.deviceName) != true) break
                            consecutiveTimeouts = 0
                        }
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    if (completed == null) {
                        if (stopFlag.get() || !desiredRunning) break
                        if (usbManager?.deviceList?.containsKey(device.deviceName) != true) break
                        continue
                    }
                    consecutiveTimeouts = 0
                    val directBuf = completed.clientData as? ByteBuffer ?: continue
                    val n = directBuf.position().coerceAtMost(packetSize)
                    if (n > 0) {
                        directBuf.rewind()
                        directBuf.get(pktBuf, 0, n)
                    }
                    if (!stopFlag.get() && desiredRunning) {
                        directBuf.clear()
                        if (!completed.queue(directBuf)) break
                    }
                    if (n > 0) handlePacket(n)
                }
            } else {
                while (!stopFlag.get() && desiredRunning && !Thread.currentThread().isInterrupted) {
                    val n = try {
                        conn.bulkTransfer(epIn, pktBuf, packetSize, 250)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n < 0) {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts > 8) {
                            if (usbManager?.deviceList?.containsKey(device.deviceName) != true) break
                            consecutiveTimeouts = 0
                        }
                        continue
                    }
                    consecutiveTimeouts = 0
                    handlePacket(n)
                }
            }

            mouseSender.removeSource(sourceKey)
            if (sessions.remove(device.deviceId, session)) {
                cleanupSessionResources(session)
                syncStateAndList()
            }
        }.apply {
            name = "MouseUsbReader-${device.deviceId}"
            isDaemon = true
            start()
        }
        session.thread = t
    }

    private fun closeSession(deviceId: Int): Boolean {
        val session = sessions.remove(deviceId) ?: return false
        mouseSender.removeSource("usb_mouse:$deviceId")
        cleanupSessionResources(session)
        return true
    }

    private fun cleanupSessionResources(session: ActiveMouseSession) {
        session.stopFlag.set(true)
        for (req in session.requests) {
            try { req.cancel() } catch (_: Exception) {}
            try { req.close() } catch (_: Exception) {}
        }
        session.thread?.interrupt()
        try {
            session.conn.releaseInterface(session.intf)
        } catch (_: Exception) {}
        try {
            session.conn.close()
        } catch (_: Exception) {}
    }

    private fun syncStateAndList(): List<ConnectedUsbMouse> {
        val attached = findCandidateMouseDevices()
        val activeSessions = sessions.values.toList()
        val combined = ArrayList<ConnectedUsbMouse>()

        for (s in activeSessions) {
            combined.add(
                ConnectedUsbMouse(
                    deviceId = s.deviceId,
                    name = s.name,
                    vidPid = s.vidPid,
                    interfaceId = s.intf.id,
                    isActive = true
                )
            )
        }
        for (dev in attached) {
            if (!sessions.containsKey(dev.deviceId)) {
                // Only advertise explicit Boot Mice or non-HID-keyboard candidates before claiming
                val hasBootMouse = findCandidateMouseInterfaces(dev).any {
                    it.first.interfaceSubclass == 1 && it.first.interfaceProtocol == 2
                }
                if (hasBootMouse) {
                    combined.add(
                        ConnectedUsbMouse(
                            deviceId = dev.deviceId,
                            name = displayDeviceName(dev),
                            vidPid = formatVidPid(dev),
                            interfaceId = 0,
                            isActive = false
                        )
                    )
                }
            }
        }

        _activeMice.value = combined
        _state.value = when {
            !desiredRunning -> MouseRelayState.STOPPED
            activeSessions.isNotEmpty() -> MouseRelayState.ACTIVE
            synchronized(stateLock) { permissionInFlightDeviceId != null } -> MouseRelayState.REQUESTING_PERMISSION
            else -> MouseRelayState.WAITING_FOR_DEVICE
        }
        return combined
    }
}
