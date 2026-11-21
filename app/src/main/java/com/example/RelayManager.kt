package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val key: LogKey? = null,
    val args: List<Any> = emptyList()
) {
    fun resolve(lang: AppLanguage): String {
        return if (key != null) {
            LogLocalizer.format(lang, key, *args.toTypedArray())
        } else {
            message
        }
    }
}

enum class LogLevel {
    INFO, SUCCESS, WARN, ERROR
}

enum class HidState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

enum class HidProfileMode {
    COMBO,
    WACOM_PEN,
    KEYBOARD
}

class RelayManager(private val context: Context) {

    companion object {
        const val DEFAULT_KINDLE_MAC = "48:78:5E:7D:96:C1"
        const val DEFAULT_PORT = 9876
        const val KEYBOARD_REPORT_ID = 1

        // Standard 8-byte USB/Bluetooth Boot Keyboard HID Report Descriptor with Report ID = 1
        // Report layout: [Report ID 1] + [Modifier (1 byte), Reserved (1 byte), Keycode 1..6 (6 bytes)]
        val REPORT_DESCRIPTOR: ByteArray = (
            "05010906A101" + "8501" + "050719E029E7" + "150025017501" + "950881029501" +
            "750881019505" + "750105081901" + "290591029501" + "750391019506" +
            "750815002565" + "050719002965" + "8100C0"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Standard 8-byte USB/Bluetooth Boot Keyboard HID Report Descriptor WITHOUT Report ID (for ID=0 hosts)
        val REPORT_DESCRIPTOR_NO_ID: ByteArray = (
            "05010906A101" + "050719E029E7" + "150025017501" + "950881029501" +
            "750881019505" + "750105081901" + "290591029501" + "750391019506" +
            "750815002565" + "050719002965" + "8100C0"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // 7-byte HID Digitizer Pen Report Descriptor with Report ID = 2 (94 bytes)
        // Payload: [flags(1), xLo, xHi, yLo, yHi, pLo, pHi]
        // Flags: bit0=TipSwitch, bit1=BarrelSwitch, bit2=SecondaryBarrelSwitch, bit3=Eraser, bit4=Invert, bit5=InRange
        val WACOM_PEN_REPORT_DESCRIPTOR: ByteArray = (
            "050D0902A101" +
            "85020920A100" +
            "09420944095A0945093C0932" +
            "15002501750195068102" +
            "95028103" +
            "05010930150026FF7F" +
            "350046F005550E6511" +
            "751095018102" +
            "093146B6038102" +
            "050D0930150026FF07" +
            "3500450055006500" +
            "751095018102" +
            "C0C0"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Combined Keyboard (Report ID 1) + Wacom Pen Digitizer (Report ID 2) HID Descriptor (159 bytes)
        val COMBO_REPORT_DESCRIPTOR: ByteArray = REPORT_DESCRIPTOR + WACOM_PEN_REPORT_DESCRIPTOR
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // Unified serialized HID key report sender
    val keySender = HidKeySender(
        log = { msg, lvl -> log(msg, lvl) },
        logKey = { key, lvl, args -> log(key, lvl, *args) }
    )

    // Serialized high-priority HID pen digitizer sender (Report ID 2)
    val penSender = HidPenSender(
        log = { msg, lvl -> log(msg, lvl) },
        logKey = { key, lvl, args -> log(key, lvl, *args) }
    )

    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var targetHostDevice: BluetoothDevice? = null
    @Volatile private var isProxyBound = false
    @Volatile private var isProxyConnecting = false
    @Volatile private var registeredWithReportId: Boolean = true
    @Volatile private var isRunning = true
    @Volatile private var isDestroyed = false
    @Volatile private var isIntentionalUnregister = false
    @Volatile private var sdpRetryCount = 0
    @Volatile private var lastConnectedAtMs = 0L
    @Volatile private var lastRegisterAttemptMs = 0L
    @Volatile var activeLanguage: AppLanguage = AppLanguage.AUTO
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Observables for UI
    private val _isHidRegistered = MutableStateFlow(false)
    val isHidRegistered: StateFlow<Boolean> = _isHidRegistered.asStateFlow()

    private val _connectionState = MutableStateFlow(HidState.DISCONNECTED)
    val connectionState: StateFlow<HidState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    private val _isSocketListening = MutableStateFlow(false)
    val isSocketListening: StateFlow<Boolean> = _isSocketListening.asStateFlow()

    private val _isClientConnected = MutableStateFlow(false)
    val isClientConnected: StateFlow<Boolean> = _isClientConnected.asStateFlow()

    // Combined transmitted HID report packet count from Keyboard + Wacom Pen senders
    val packetsForwarded: StateFlow<Long> = combine(keySender.packetsSent, penSender.packetsSent) { k, p ->
        k + p
    }.stateIn(scope, SharingStarted.Eagerly, 0L)

    private val _targetAddress = MutableStateFlow(DEFAULT_KINDLE_MAC)
    val targetAddress: StateFlow<String> = _targetAddress.asStateFlow()

    private val _reportIdMode = MutableStateFlow(ReportIdMode.AUTO)
    val reportIdMode: StateFlow<ReportIdMode> = _reportIdMode.asStateFlow()

    private val _hidProfileMode = MutableStateFlow(HidProfileMode.COMBO)
    val hidProfileMode: StateFlow<HidProfileMode> = _hidProfileMode.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            log(LogKey.BT_POWERED_ON, LogLevel.INFO)
                            sdpRetryCount = 0
                            initializeHid()
                        }
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            _isHidRegistered.value = false
                            _connectionState.value = HidState.DISCONNECTED
                            syncForegroundNotification()
                            log(LogKey.BT_POWERED_OFF, LogLevel.WARN)
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val addr = device?.address ?: "Unknown"
                    when (bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            log(LogKey.BT_BONDING, LogLevel.INFO, addr)
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            log(LogKey.BT_BONDED, LogLevel.SUCCESS, addr)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                            if (prev == BluetoothDevice.BOND_BONDED || prev == BluetoothDevice.BOND_BONDING) {
                                log(LogKey.BT_UNBONDED, LogLevel.WARN, addr)
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        log(LogKey.SERVICE_INIT, LogLevel.INFO)
        if (bluetoothAdapter == null) {
            log(LogKey.BT_UNSUPPORTED, LogLevel.ERROR)
        } else if (!bluetoothAdapter.isEnabled) {
            log(LogKey.BT_DISABLED, LogLevel.WARN)
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(context, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Exception) {}
    }

    fun syncForegroundNotification() {
        val strings = StringProvider.get(activeLanguage)
        val devName = _connectedDeviceName.value
        val status = when {
            _connectionState.value == HidState.CONNECTED && !devName.isNullOrBlank() ->
                strings.notifConnectedFmt.replace("%s", devName)
            _isHidRegistered.value -> strings.notifReady
            else -> strings.notifDefault
        }
        HidRelayForegroundService.updateStatus(context, status)
    }

    fun setTargetAddress(address: String) {
        _targetAddress.value = address.trim()
    }

    private fun syncSendersEnabled() {
        when (_hidProfileMode.value) {
            HidProfileMode.COMBO -> {
                keySender.keyEnabled = true
                penSender.penEnabled = true
            }
            HidProfileMode.WACOM_PEN -> {
                keySender.keyEnabled = false
                penSender.penEnabled = true
            }
            HidProfileMode.KEYBOARD -> {
                keySender.keyEnabled = true
                penSender.penEnabled = false
            }
        }
    }

    private fun activeUseReportId(): Boolean {
        return when (_hidProfileMode.value) {
            HidProfileMode.COMBO -> true
            HidProfileMode.WACOM_PEN -> true
            HidProfileMode.KEYBOARD -> _reportIdMode.value != ReportIdMode.FORCE_0
        }
    }

    @SuppressLint("MissingPermission")
    fun setHidProfileMode(mode: HidProfileMode) {
        if (_hidProfileMode.value == mode) return
        _hidProfileMode.value = mode
        syncSendersEnabled()
        log(LogKey.HID_PROFILE_SWITCHED, LogLevel.INFO, mode.name)

        val hid = hidDevice
        if (hid != null) {
            isIntentionalUnregister = true
            try {
                hid.unregisterApp()
            } catch (_: Exception) {}
            isIntentionalUnregister = false
            sdpRetryCount = 0
            registerHidApp(hid, useReportId = activeUseReportId())
            log(LogKey.REPORT_ID_RESYNC_WARN, LogLevel.WARN)
        }
    }

    @SuppressLint("MissingPermission")
    fun setReportIdMode(mode: ReportIdMode) {
        val prevUseId = activeUseReportId()
        _reportIdMode.value = mode
        keySender.reportIdMode = mode
        val nextUseId = activeUseReportId()
        log(LogKey.REPORT_ID_SWITCHED, LogLevel.INFO, mode.name)

        val hid = hidDevice
        if (hid != null && prevUseId != nextUseId) {
            isIntentionalUnregister = true
            try {
                hid.unregisterApp()
            } catch (_: Exception) {}
            isIntentionalUnregister = false
            sdpRetryCount = 0
            registerHidApp(hid, useReportId = nextUseId)
            log(LogKey.REPORT_ID_RESYNC_WARN, LogLevel.WARN)
        }
    }

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = LogEntry(
            timestamp = ts,
            level = level,
            message = message
        )
        _logs.update { current ->
            (current + entry).takeLast(200) // Keep last 200 logs
        }
    }

    fun log(key: LogKey, level: LogLevel = LogLevel.INFO, vararg args: Any) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = LogEntry(
            timestamp = ts,
            level = level,
            message = LogLocalizer.format(AppLanguage.EN, key, *args),
            key = key,
            args = args.toList()
        )
        _logs.update { current ->
            (current + entry).takeLast(200) // Keep last 200 logs
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    fun initializeHid() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            log(LogKey.BT_ADAPTER_UNAVAILABLE, LogLevel.ERROR)
            return
        }

        if ((isProxyBound && hidDevice != null) || isProxyConnecting) {
            log(LogKey.HID_PROXY_ALREADY_BOUND, LogLevel.INFO)
            return
        }

        isProxyConnecting = true
        log(LogKey.HID_PROXY_FETCHING, LogLevel.INFO)
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                isProxyConnecting = false
                val hid = proxy as BluetoothHidDevice
                hidDevice = hid
                keySender.hidDevice = hid
                penSender.hidDevice = hid
                syncSendersEnabled()
                isProxyBound = true
                log(LogKey.HID_PROXY_CONNECTED, LogLevel.INFO)
                registerHidApp(hid, useReportId = activeUseReportId())
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val wasBound = isProxyBound || hidDevice != null || isProxyConnecting
                isProxyConnecting = false
                hidDevice = null
                keySender.hidDevice = null
                penSender.hidDevice = null
                isProxyBound = false
                _isHidRegistered.value = false
                _connectionState.value = HidState.DISCONNECTED
                syncForegroundNotification()
                HidRelayForegroundService.setHidConnected(context, false)
                if (wasBound) {
                    log(LogKey.HID_PROXY_DISCONNECTED, LogLevel.WARN)
                }
                if (!isDestroyed && bluetoothAdapter?.isEnabled == true) {
                    scope.launch {
                        delay(1000L)
                        if (!isDestroyed && !isProxyBound && !isProxyConnecting && bluetoothAdapter?.isEnabled == true) {
                            initializeHid()
                        }
                    }
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun ensureHidReady() {
        if (isDestroyed) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        sdpRetryCount = 0
        val hid = hidDevice
        if (hid == null || !isProxyBound) {
            if (!isProxyConnecting) {
                initializeHid()
            }
        } else if (!_isHidRegistered.value) {
            val now = System.currentTimeMillis()
            if (now - lastRegisterAttemptMs > 600L) {
                log(LogKey.HID_RECOVER_FOREGROUND, LogLevel.INFO)
                registerHidApp(hid, useReportId = activeUseReportId())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp(hid: BluetoothHidDevice, useReportId: Boolean) {
        val profileMode = _hidProfileMode.value
        val effectiveUseReportId = if (profileMode == HidProfileMode.KEYBOARD) useReportId else true
        registeredWithReportId = effectiveUseReportId
        val (descriptor, subclass, sdpName) = when (profileMode) {
            HidProfileMode.COMBO -> Triple(
                COMBO_REPORT_DESCRIPTOR,
                BluetoothHidDevice.SUBCLASS1_COMBO,
                "RelayComboHid"
            )
            HidProfileMode.WACOM_PEN -> Triple(
                WACOM_PEN_REPORT_DESCRIPTOR,
                BluetoothHidDevice.SUBCLASS2_DIGITIZER_TABLET,
                "RelayWacomPen"
            )
            HidProfileMode.KEYBOARD -> Triple(
                if (effectiveUseReportId) REPORT_DESCRIPTOR else REPORT_DESCRIPTOR_NO_ID,
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                "RelayKeyboard"
            )
        }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            sdpName,
            "Android HID Relay",
            "Android",
            subclass,
            descriptor
        )

        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                // Registration state strictly follows the system callback
                _isHidRegistered.value = registered
                if (registered) {
                    sdpRetryCount = 0
                    log(LogKey.HID_SDP_REGISTERED, LogLevel.SUCCESS)
                    syncForegroundNotification()
                    // Sync immediately if a host is already connected at OS level
                    val existingHost = pluggedDevice?.takeIf {
                        try {
                            hid.getConnectionState(it) == BluetoothProfile.STATE_CONNECTED
                        } catch (_: Exception) {
                            false
                        }
                    } ?: try {
                        hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
                            .firstOrNull()
                    } catch (_: Exception) {
                        null
                    }
                    if (existingHost != null) {
                        _connectionState.value = HidState.CONNECTED
                        lastConnectedAtMs = System.currentTimeMillis()
                        targetHostDevice = existingHost
                        keySender.hostDevice = existingHost
                        keySender.protocolMode = BluetoothHidDevice.PROTOCOL_REPORT_MODE
                        penSender.hostDevice = existingHost
                        val devName = try { existingHost.name ?: "Unknown Host" } catch (_: Exception) { "Unknown Host" }
                        _connectedDeviceName.value = devName
                        _connectedDeviceAddress.value = existingHost.address
                        syncForegroundNotification()
                        HidRelayForegroundService.setHidConnected(context, true)
                        log(LogKey.HID_EXISTING_HOST, LogLevel.SUCCESS, devName, existingHost.address)
                    }
                } else {
                    log(LogKey.HID_SDP_UNREGISTERED, LogLevel.WARN)
                    syncForegroundNotification()
                    if (!isDestroyed && !isIntentionalUnregister &&
                        hidDevice === hid && bluetoothAdapter?.isEnabled == true && sdpRetryCount < 5
                    ) {
                        sdpRetryCount++
                        val attempt = sdpRetryCount
                        scope.launch {
                            delay(800L)
                            if (!isDestroyed && !_isHidRegistered.value &&
                                hidDevice === hid && bluetoothAdapter?.isEnabled == true
                            ) {
                                log(LogKey.HID_SDP_RETRY, LogLevel.INFO, attempt)
                                registerHidApp(hid, useReportId = activeUseReportId())
                            }
                        }
                    }
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val quickDrop = lastConnectedAtMs > 0L && (System.currentTimeMillis() - lastConnectedAtMs) < 4500L
                        lastConnectedAtMs = 0L
                        _connectionState.value = HidState.DISCONNECTED
                        if (targetHostDevice?.address == device.address) {
                            targetHostDevice = null
                            keySender.hostDevice = null
                            keySender.clearAll()
                            penSender.hostDevice = null
                            penSender.clearPen()
                            _connectedDeviceName.value = null
                            _connectedDeviceAddress.value = null
                        }
                        syncForegroundNotification()
                        HidRelayForegroundService.setHidConnected(context, false)
                        if (quickDrop) {
                            log(LogKey.HID_QUICK_DROP_HINT, LogLevel.WARN)
                        }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionState.value = HidState.CONNECTING
                    }
                    BluetoothProfile.STATE_CONNECTED -> {
                        lastConnectedAtMs = System.currentTimeMillis()
                        _connectionState.value = HidState.CONNECTED
                        targetHostDevice = device
                        keySender.hostDevice = device
                        keySender.protocolMode = BluetoothHidDevice.PROTOCOL_REPORT_MODE
                        penSender.hostDevice = device
                        val devName = try { device.name ?: "Unknown Host" } catch (_: Exception) { "Unknown Host" }
                        _connectedDeviceName.value = devName
                        _connectedDeviceAddress.value = device.address
                        syncForegroundNotification()
                        HidRelayForegroundService.setHidConnected(context, true)
                    }
                    BluetoothProfile.STATE_DISCONNECTING -> {
                        _connectionState.value = HidState.DISCONNECTING
                    }
                }
                log(
                    LogKey.HID_HOST_STATE_CHANGED,
                    if (state == BluetoothProfile.STATE_CONNECTED) LogLevel.SUCCESS else LogLevel.INFO,
                    device.address,
                    state
                )
            }

            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                log(LogKey.HID_GET_REPORT, LogLevel.INFO, type.toString(), id.toString(), bufferSize)
                try {
                    if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                        val payload = if (id == HidPenSender.PEN_REPORT_ID.toByte() ||
                            _hidProfileMode.value == HidProfileMode.WACOM_PEN
                        ) {
                            penSender.getLastReport()
                        } else {
                            keySender.getLastReport()
                        }
                        hid.replyReport(device, type, id, payload)
                    } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) {
                        hid.replyReport(device, type, id, byteArrayOf(0))
                    } else {
                        hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                    }
                } catch (e: Exception) {
                    log(LogKey.HID_REPLY_REPORT_FAILED, LogLevel.WARN, e.localizedMessage ?: "Unknown error")
                }
            }

            override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                log(LogKey.HID_SET_REPORT, LogLevel.INFO, type.toString(), id.toString(), data.size)
            }

            override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
                keySender.protocolMode = protocol
                val modeName = if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) "BOOT (0)" else "REPORT (1)"
                log(LogKey.HID_SET_PROTOCOL, LogLevel.INFO, modeName, keySender.resolveActiveReportId())
            }

            override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
                // LED status output report from host (e.g., Caps/Num Lock)
            }
        }

        // Only register once per proxy binding; status is updated asynchronously in onAppStatusChanged
        try {
            lastRegisterAttemptMs = System.currentTimeMillis()
            val accepted = hid.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
            if (accepted) {
                log(LogKey.HID_REGISTER_SENT, LogLevel.INFO, effectiveUseReportId)
            } else {
                _isHidRegistered.value = false
                log(LogKey.HID_REGISTER_REJECTED, LogLevel.WARN)
            }
        } catch (e: Exception) {
            log(LogKey.HID_REGISTER_EXCEPTION, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
        }
    }

    @SuppressLint("MissingPermission")
    fun pairOrConnectTarget(targetAddr: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            log(LogKey.BT_ADAPTER_UNAVAILABLE, LogLevel.ERROR)
            return
        }
        val cleanAddr = targetAddr.trim()
        if (!BluetoothAdapter.checkBluetoothAddress(cleanAddr)) {
            log(LogKey.INVALID_MAC, LogLevel.ERROR, cleanAddr)
            return
        }

        if (!_isHidRegistered.value) {
            val hid = hidDevice
            if (hid != null) {
                log(LogKey.HID_SDP_AUTO_REGISTER_WAIT, LogLevel.WARN)
                registerHidApp(hid, useReportId = activeUseReportId())
            } else {
                initializeHid()
                log(LogKey.HID_PROXY_REINIT_WAIT, LogLevel.WARN)
            }
            return
        }

        try {
            val device = adapter.getRemoteDevice(cleanAddr)
            val devName = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
            log(LogKey.TARGET_DEVICE_INFO, LogLevel.INFO, devName, cleanAddr, device.bondState)

            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                log(LogKey.TARGET_BONDED_CONNECTING, LogLevel.INFO)
                val hid = hidDevice
                if (hid == null) {
                    log(LogKey.HID_DEVICE_NOT_READY, LogLevel.ERROR)
                    return
                }
                val res = hid.connect(device)
                log(LogKey.HID_CONNECT_CALL, if (res) LogLevel.INFO else LogLevel.WARN, res)
            } else {
                log(LogKey.TARGET_UNBONDED_CREATING, LogLevel.INFO)
                val res = device.createBond()
                log(LogKey.CREATE_BOND_CALL, LogLevel.WARN, res)
            }
        } catch (e: Exception) {
            log(LogKey.CONNECT_EXCEPTION, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectTarget() {
        val hid = hidDevice
        val host = targetHostDevice
        if (hid != null && host != null) {
            log(LogKey.DISCONNECTING_HOST, LogLevel.INFO, host.address)
            try {
                hid.disconnect(host)
            } catch (e: Exception) {
                log(LogKey.DISCONNECT_FAILED, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
            }
        } else {
            log(LogKey.NO_CONNECTED_TARGET, LogLevel.WARN)
        }
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            log(LogKey.BONDED_LIST_FAILED, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
            emptyList()
        }
    }

    fun startRelayServer(port: Int = DEFAULT_PORT) {
        if (_isSocketListening.value && serverSocket != null && !serverSocket!!.isClosed) {
            log(LogKey.TCP_ALREADY_LISTENING, LogLevel.INFO, port)
            return
        }

        Thread {
            try {
                isRunning = true
                try {
                    serverSocket?.close()
                } catch (_: Exception) {}

                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                }
                _isSocketListening.value = true
                log(LogKey.TCP_SERVER_STARTED, LogLevel.SUCCESS, port)

                while (isRunning) {
                    val socket = try {
                        serverSocket?.accept() ?: break
                    } catch (_: Exception) {
                        break
                    }
                    _isClientConnected.value = true
                    log(LogKey.TCP_CLIENT_CONNECTED, LogLevel.SUCCESS)

                    try {
                        val input = DataInputStream(socket.getInputStream())
                        val buf = ByteArray(8)
                        while (isRunning) {
                            input.readFully(buf)
                            keySender.sendRawReport(KeySource.USB, buf)
                        }
                    } catch (e: Exception) {
                        log(LogKey.TCP_CLIENT_DISCONNECTED, LogLevel.WARN, e.message ?: "Closed")
                    } finally {
                        _isClientConnected.value = false
                        log(LogKey.TCP_CLIENT_RELEASED, LogLevel.INFO)
                        keySender.clearSource(KeySource.USB)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log(LogKey.TCP_SERVER_ERROR, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
                }
            } finally {
                _isSocketListening.value = false
            }
        }.apply {
            name = "HidRelaySocketThread"
            isDaemon = true
            start()
        }
    }

    /**
     * Sends a single test keystroke.
     */
    fun sendTestKeystroke(modifier: Byte, keycode: Byte, keyLabel: String) {
        val hexCode = "0x${Integer.toHexString(keycode.toInt() and 0xFF)}"
        log(LogKey.TEST_KEY_SENT, LogLevel.INFO, keyLabel, hexCode)
        scope.launch {
            keySender.sendStroke(KeySource.TEST, modifier, keycode, 35L)
        }
    }

    /**
     * Sends a test ASCII text string keystroke by keystroke.
     */
    fun sendTestText(text: String) {
        log(LogKey.TEST_TEXT_SENT, LogLevel.INFO, text)
        scope.launch {
            for (ch in text) {
                if (ch.code > 127) {
                    log(LogKey.HID_CANNOT_SEND_COMPOSED_CHAR, LogLevel.WARN, ch.toString())
                    continue
                }
                val (mod, code) = HidTextInputView.charToHidUsage(ch)
                if (code != 0.toByte()) {
                    keySender.sendStroke(KeySource.TEST, mod, code, 25L)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun makeDiscoverable(durationSeconds: Int = 300) {
        if (!_isHidRegistered.value) {
            val hid = hidDevice
            if (hid != null) {
                log(LogKey.HID_SDP_AUTO_REGISTER_WAIT, LogLevel.WARN)
                registerHidApp(hid, useReportId = activeUseReportId())
            } else {
                initializeHid()
            }
        }
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            log(LogKey.DISCOVERABLE_REQUESTED, LogLevel.INFO, durationSeconds)
        } catch (e: Exception) {
            log(LogKey.DISCOVERABLE_FAILED, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
        }
    }

    @SuppressLint("MissingPermission")
    fun destroy() {
        isDestroyed = true
        isRunning = false
        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        keySender.clearAll()
        penSender.clearPen()
        penSender.shutdown()

        isIntentionalUnregister = true
        try {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: Exception) {}

        isProxyBound = false
        _isHidRegistered.value = false
        _connectionState.value = HidState.DISCONNECTED
        log(LogKey.SERVICE_DESTROYED, LogLevel.INFO)
    }

    fun cleanup() = destroy()
}
