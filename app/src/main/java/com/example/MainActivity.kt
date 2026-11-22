package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.Amber500
import com.example.ui.theme.Cyan300
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Emerald500
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.OledBlack
import com.example.ui.theme.OledBorder
import com.example.ui.theme.OledSurface
import com.example.ui.theme.OledSurfaceVariant
import com.example.ui.theme.Rose500
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate900
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private var relayManagerInstance: RelayManager? = null
        private var evgrabManagerInstance: EvgrabManager? = null
        private var wacomManagerInstance: WacomUsbManager? = null
        private var mouseManagerInstance: MouseUsbManager? = null
    }

    private lateinit var relayManager: RelayManager
    private lateinit var evgrabManager: EvgrabManager
    private lateinit var wacomManager: WacomUsbManager
    private lateinit var mouseManager: MouseUsbManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on to allow continuous relaying
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Reuse existing managers across configuration changes
        relayManager = relayManagerInstance ?: RelayManager(applicationContext).also {
            relayManagerInstance = it
        }

        evgrabManager = evgrabManagerInstance ?: EvgrabManager(
            applicationContext,
            relayManager.keySender,
            relayManager.mouseSender,
            log = { msg, lvl -> relayManager.log(msg, lvl) },
            logKey = { key, lvl, args -> relayManager.log(key, lvl, *args) }
        ).also {
            evgrabManagerInstance = it
        }

        wacomManager = wacomManagerInstance ?: WacomUsbManager(
            applicationContext,
            relayManager.penSender,
            relayManager.keySender,
            log = { msg, lvl -> relayManager.log(msg, lvl) },
            logKey = { key, lvl, args -> relayManager.log(key, lvl, *args) }
        ).also {
            wacomManagerInstance = it
        }

        mouseManager = mouseManagerInstance ?: MouseUsbManager(
            applicationContext,
            relayManager.mouseSender,
            log = { msg, lvl -> relayManager.log(msg, lvl) },
            logKey = { key, lvl, args -> relayManager.log(key, lvl, *args) }
        ).also {
            mouseManagerInstance = it
        }

        setContent {
            MyApplicationTheme {
                MainScreen(
                    relayManager = relayManager,
                    evgrabManager = evgrabManager,
                    wacomManager = wacomManager,
                    mouseManager = mouseManager
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::relayManager.isInitialized &&
            relayManager.connectionState.value == HidState.CONNECTED &&
            ::evgrabManager.isInitialized &&
            evgrabManager.onForegroundHardwareKeyEvent(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (::wacomManager.isInitialized) {
            wacomManager.refreshAttachedDevice()
        }
        if (::mouseManager.isInitialized) {
            mouseManager.refreshAttachedDevices()
        }
        if (::evgrabManager.isInitialized) {
            evgrabManager.kickRescan()
        }
        if (hasBluetoothPermissions() && ::relayManager.isInitialized) {
            HidRelayForegroundService.start(
                applicationContext,
                StringProvider.get(relayManager.activeLanguage).notifDefault
            )
            relayManager.ensureHidReady()
            relayManager.syncForegroundNotification()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            if (::evgrabManager.isInitialized) {
                evgrabManager.clearForegroundKeys()
            }
            if (::relayManager.isInitialized) {
                relayManager.keySender.removeSource("fg_touchpad_page_up")
                relayManager.keySender.removeSource("fg_touchpad_page_down")
                relayManager.mouseSender.removeSource("fg_touchpad")
                relayManager.mouseSender.removeSource("fg_touchpad_btn")
                relayManager.mouseSender.removeSource("fg_capture")
            }
        }
    }

    override fun onDestroy() {
        // Do NOT destroy connection or processes on configuration change (e.g. rotation or USB plug/unplug)
        if (!isChangingConfigurations && isFinishing) {
            if (::evgrabManager.isInitialized) {
                evgrabManager.stop()
            }
            if (::wacomManager.isInitialized) {
                wacomManager.destroy()
            }
            if (::mouseManager.isInitialized) {
                mouseManager.destroy()
            }
            HidRelayForegroundService.stop(applicationContext)
            if (::relayManager.isInitialized) {
                relayManager.cleanup()
            }
            evgrabManagerInstance = null
            wacomManagerInstance = null
            mouseManagerInstance = null
            relayManagerInstance = null
        }
        super.onDestroy()
    }
}

private fun hasAllBluetoothPermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    relayManager: RelayManager,
    evgrabManager: EvgrabManager,
    wacomManager: WacomUsbManager,
    mouseManager: MouseUsbManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var currentLanguage by remember { mutableStateOf(AppLanguage.AUTO) }
    val strings = StringProvider.get(currentLanguage)

    // Observe RelayManager state
    val isHidRegistered by relayManager.isHidRegistered.collectAsState()
    val connectionState by relayManager.connectionState.collectAsState()
    val connectedAddress by relayManager.connectedDeviceAddress.collectAsState()
    val connectedName by relayManager.connectedDeviceName.collectAsState()
    val isSocketListening by relayManager.isSocketListening.collectAsState()
    val isClientConnected by relayManager.isClientConnected.collectAsState()
    val hidProfileMode by relayManager.hidProfileMode.collectAsState()
    val logs by relayManager.logs.collectAsState()

    // Observe EvgrabManager state
    val evgrabState by evgrabManager.state.collectAsState()
    val activeKeyboards by evgrabManager.activeKeyboards.collectAsState()
    val activeEvMice by evgrabManager.activeEvMice.collectAsState()

    // Observe WacomUsbManager state
    val wacomState by wacomManager.state.collectAsState()
    val wacomModel by wacomManager.detectedModel.collectAsState()
    val wacomOrientation by wacomManager.orientation.collectAsState()
    val wacomPressureCurve by wacomManager.pressureCurve.collectAsState()
    val wacomButtonMode by wacomManager.buttonMode.collectAsState()

    // Observe MouseUsbManager state
    val mouseState by mouseManager.state.collectAsState()
    val activeUsbMice by mouseManager.activeMice.collectAsState()
    val mouseSensitivity by mouseManager.sensitivity.collectAsState()

    val combinedMouseCount = activeUsbMice.size + activeEvMice.size

    var showBondedDialog by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }
    var isLanguageExpanded by remember { mutableStateOf(false) }
    var isStatusExpanded by remember { mutableStateOf(false) }
    var isConsoleExpanded by remember { mutableStateOf(true) }
    var isWacomCardExpanded by remember { mutableStateOf(false) }
    var isMouseCardExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(currentLanguage) {
        relayManager.activeLanguage = currentLanguage
        relayManager.syncForegroundNotification()
    }

    // Bluetooth + notification permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val btOk = hasAllBluetoothPermissions(context)
        permissionsGranted = btOk
        if (btOk) {
            HidRelayForegroundService.start(context.applicationContext, strings.notifDefault)
            relayManager.ensureHidReady()
            relayManager.syncForegroundNotification()
            relayManager.startRelayServer(RelayManager.DEFAULT_PORT)
        } else {
            relayManager.log(LogKey.MISSING_BT_PERMISSIONS, LogLevel.WARN)
        }
    }

    // Auto check permissions on launch
    LaunchedEffect(Unit) {
        val neededPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (hasAllBluetoothPermissions(context)) {
            permissionsGranted = true
            HidRelayForegroundService.start(context.applicationContext, strings.notifDefault)
            relayManager.ensureHidReady()
            relayManager.syncForegroundNotification()
            relayManager.startRelayServer(RelayManager.DEFAULT_PORT)
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = OledSurface,
                drawerContentColor = Slate100,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .testTag("sidebar_drawer")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    // Drawer Header with App Icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_logo_custom),
                            contentDescription = strings.appTitle,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = strings.appTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = strings.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                                fontSize = 11.sp
                            )
                        }
                    }

                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(vertical = 8.dp))

                    // Section 1: USB Keyboard Exclusive Grab (evgrab)
                    Text(
                        text = strings.sectionEvgrab,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = OledSurfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, OledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_key_forwarder_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.evgrabTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Slate100
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = when (evgrabState) {
                                            EvgrabState.RUNNING -> {
                                                val kbText = if (activeKeyboards.isNotEmpty()) {
                                                    activeKeyboards.joinToString { it.name }
                                                } else {
                                                    strings.evgrabSearching
                                                }
                                                strings.evgrabRunningFmt.replace("%s", kbText)
                                            }
                                            EvgrabState.STOPPED_BY_HOTKEY -> strings.backgroundFwdHotkeyStopped
                                            EvgrabState.STOPPED -> strings.backgroundFwdInactive
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (evgrabState) {
                                            EvgrabState.RUNNING -> Color.White
                                            EvgrabState.STOPPED_BY_HOTKEY -> Slate200
                                            EvgrabState.STOPPED -> Slate400
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = (evgrabState == EvgrabState.RUNNING),
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) evgrabManager.start() else evgrabManager.stop()
                                    },
                                    modifier = Modifier.testTag("switch_drawer_key_forwarder")
                                )
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = strings.evgrabDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // Multi-OTG USB Hub Connected Devices Summary
                    Text(
                        text = strings.sectionOtgHub,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = OledSurfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, OledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_otg_hub_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val wacomCount = if (wacomModel != null) 1 else 0
                            Text(
                                text = String.format(
                                    strings.otgSummaryFmt,
                                    activeKeyboards.size,
                                    combinedMouseCount,
                                    wacomCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (activeKeyboards.isNotEmpty() || combinedMouseCount > 0 || wacomCount > 0) Color.White else Slate400
                            )
                            val hubNames = buildList {
                                activeKeyboards.forEach { add("KB: ${it.name}") }
                                activeUsbMice.forEach { add("Mouse: ${it.name}") }
                                activeEvMice.forEach { add("EvMouse: ${it.name}") }
                                wacomModel?.let { add("Pen: ${it.modelName}") }
                            }
                            if (hubNames.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                hubNames.forEach { devLabel ->
                                    Text(
                                        text = "• $devLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate200,
                                        fontSize = 10.5.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // USB OTG Mouse Relay Section
                    Text(
                        text = strings.sectionMouse,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = OledSurfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, OledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_mouse_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.mouseTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Slate100
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = when {
                                            combinedMouseCount > 0 -> strings.mouseActiveChipFmt.replace("%d", combinedMouseCount.toString())
                                            mouseState == MouseRelayState.WAITING_FOR_DEVICE -> strings.mouseWaitingChip
                                            mouseState == MouseRelayState.REQUESTING_PERMISSION -> strings.mousePermChip
                                            else -> strings.mouseNoneConnected
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when {
                                            combinedMouseCount > 0 || mouseState == MouseRelayState.ACTIVE -> Color.White
                                            mouseState == MouseRelayState.WAITING_FOR_DEVICE || mouseState == MouseRelayState.REQUESTING_PERMISSION -> Slate200
                                            else -> Slate400
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = (mouseState != MouseRelayState.STOPPED),
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) mouseManager.start() else mouseManager.stop()
                                    },
                                    modifier = Modifier.testTag("switch_drawer_mouse")
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = strings.mouseDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // Wacom USB OTG Digitizer Section
                    Text(
                        text = strings.sectionWacom,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = OledSurfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, OledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_wacom_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.wacomTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Slate100
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = wacomModel?.modelName ?: strings.wacomModelNone,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (wacomState) {
                                            WacomState.ACTIVE -> Color.White
                                            WacomState.WAITING_FOR_DEVICE, WacomState.REQUESTING_PERMISSION -> Slate200
                                            WacomState.STOPPED -> Slate400
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = (wacomState != WacomState.STOPPED),
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) wacomManager.start() else wacomManager.stop()
                                    },
                                    modifier = Modifier.testTag("switch_drawer_wacom")
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = strings.wacomDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // Bluetooth HID Profile Mode Selector (Combo / Wacom Pen Only / Keyboard Only / Mouse Only)
                    Text(
                        text = strings.sectionHidProfile,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HidProfileMode.values().forEach { mode ->
                            val isSelected = hidProfileMode == mode
                            val label = when (mode) {
                                HidProfileMode.COMBO -> strings.hidProfileCombo
                                HidProfileMode.WACOM_PEN -> strings.hidProfileWacom
                                HidProfileMode.KEYBOARD -> strings.hidProfileKeyboard
                                HidProfileMode.MOUSE -> strings.hidProfileMouse
                            }
                            Surface(
                                onClick = { relayManager.setHidProfileMode(mode) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Cyan400.copy(alpha = 0.15f) else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.dp, Cyan400) else BorderStroke(1.dp, OledBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("hid_profile_mode_${mode.name}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) Color.White else Slate200,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = strings.cdSelected,
                                            tint = Cyan400,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // Section 2: Multi-Language Selector (Foldable)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { isLanguageExpanded = !isLanguageExpanded }
                            .padding(vertical = 6.dp)
                            .testTag("btn_toggle_language_section"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.sectionLanguage,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentLanguage.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = if (isLanguageExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = strings.cdToggleExpand,
                            tint = Color.White
                        )
                    }

                    val detectedLang = remember { AppLanguage.detectSystemLanguage() }

                    AnimatedVisibility(visible = isLanguageExpanded) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            AppLanguage.values().forEach { lang ->
                                val isSelected = currentLanguage == lang
                                Surface(
                                    onClick = { currentLanguage = lang },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Cyan400.copy(alpha = 0.15f) else Color.Transparent,
                                    border = if (isSelected) BorderStroke(1.dp, Cyan400) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("lang_option_${lang.name}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = lang.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isSelected) Color.White else Slate200,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            Text(
                                                text = if (lang == AppLanguage.AUTO) {
                                                    "${strings.autoDetectedLabel}: ${detectedLang.displayName}"
                                                } else {
                                                    lang.subName
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSelected) Slate200 else Slate400,
                                                fontSize = 11.sp
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = strings.cdSelected,
                                                tint = Cyan400,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = OledBlack,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = strings.appTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = strings.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate400,
                                fontSize = 11.sp
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("btn_open_sidebar")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = strings.cdMenu,
                                tint = Cyan400
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = OledBlack,
                        titleContentColor = Color.White,
                        actionIconContentColor = Slate200
                    )
                )
            }
        ) { innerPadding ->
            val anyUpperExpanded = isMouseCardExpanded || isWacomCardExpanded || isStatusExpanded
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OledBlack)
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (anyUpperExpanded) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status Summary Chip Card (Foldable)
                    StatusSummaryCard(
                        strings = strings,
                        isExpanded = isStatusExpanded,
                        onToggleExpand = { isStatusExpanded = !isStatusExpanded },
                        isHidRegistered = isHidRegistered,
                        connectionState = connectionState,
                        connectedHost = connectedName ?: connectedAddress,
                        isSocketListening = isSocketListening,
                        isClientConnected = isClientConnected,
                        packetsFlow = relayManager.packetsForwarded,
                        evgrabState = evgrabState,
                        activeKeyboards = activeKeyboards,
                        wacomState = wacomState,
                        wacomModel = wacomModel,
                        mouseState = mouseState,
                        activeMouseCount = combinedMouseCount,
                        onToggleEvgrab = {
                            if (evgrabState == EvgrabState.RUNNING) evgrabManager.stop() else evgrabManager.start()
                        },
                        onToggleWacom = {
                            if (wacomState == WacomState.STOPPED) wacomManager.start() else wacomManager.stop()
                        },
                        onToggleMouse = {
                            if (mouseState == MouseRelayState.STOPPED) mouseManager.start() else mouseManager.stop()
                        }
                    )

                    // Target Device Bluetooth Connection Card (connect initiated from target device)
                    TargetDeviceCard(
                        strings = strings,
                        connectionState = connectionState,
                        connectedHost = connectedName ?: connectedAddress,
                        onMakeDiscoverable = { relayManager.makeDiscoverable() },
                        onDisconnectTarget = { relayManager.disconnectTarget() },
                        onChooseBonded = { showBondedDialog = true }
                    )

                    // Compact Collapsible USB OTG Wacom -> Bluetooth Pen Card
                    if (hidProfileMode == HidProfileMode.COMBO || hidProfileMode == HidProfileMode.WACOM_PEN) {
                        WacomTabletCard(
                            strings = strings,
                            wacomState = wacomState,
                            detectedModel = wacomModel,
                            liveSampleFlow = wacomManager.liveSample,
                            orientation = wacomOrientation,
                            pressureCurve = wacomPressureCurve,
                            buttonMode = wacomButtonMode,
                            isExpanded = isWacomCardExpanded,
                            onToggleExpand = { isWacomCardExpanded = !isWacomCardExpanded },
                            onToggleStartStop = {
                                if (wacomState == WacomState.STOPPED) wacomManager.start() else wacomManager.stop()
                            },
                            onSelectOrientation = { wacomManager.setOrientation(it) },
                            onSelectPressureCurve = { wacomManager.setPressureCurve(it) },
                            onSelectButtonMode = { wacomManager.setButtonMode(it) }
                        )
                    }

                    // Compact Collapsible USB OTG Mouse & Touchpad Card
                    if (hidProfileMode == HidProfileMode.COMBO || hidProfileMode == HidProfileMode.MOUSE) {
                        MouseTouchpadCard(
                            strings = strings,
                            isConnected = (connectionState == HidState.CONNECTED),
                            mouseState = mouseState,
                            activeUsbMice = activeUsbMice,
                            activeEvMouseCount = activeEvMice.size,
                            sensitivity = mouseSensitivity,
                            mouseSender = relayManager.mouseSender,
                            keySender = relayManager.keySender,
                            isExpanded = isMouseCardExpanded,
                            onToggleExpand = { isMouseCardExpanded = !isMouseCardExpanded },
                            onToggleStartStop = {
                                if (mouseState == MouseRelayState.STOPPED) mouseManager.start() else mouseManager.stop()
                            },
                            onSensitivityChange = { mouseManager.setSensitivity(it) }
                        )
                    }

                    // Soft Keyboard Input Card (visible in Combo or Keyboard-only HID profile)
                    if (hidProfileMode == HidProfileMode.COMBO || hidProfileMode == HidProfileMode.KEYBOARD) {
                        SoftKeyboardInputCard(
                            strings = strings,
                            isConnected = (connectionState == HidState.CONNECTED),
                            relayManager = relayManager
                        )
                    }

                    // Real-time Terminal Log Console Card (Collapsible; expands to fill remaining bottom area)
                    val consoleModifier = when {
                        anyUpperExpanded && isConsoleExpanded -> Modifier.height(220.dp)
                        anyUpperExpanded -> Modifier
                        else -> Modifier.weight(1f, fill = isConsoleExpanded)
                    }
                    TerminalLogsCard(
                        strings = strings,
                        currentLanguage = currentLanguage,
                        logs = logs,
                        listState = listState,
                        isExpanded = isConsoleExpanded,
                        onToggleExpand = { isConsoleExpanded = !isConsoleExpanded },
                        onClearLogs = { relayManager.clearLogs() },
                        onCopyLogs = {
                            val allLogs = logs.joinToString("\n") { "[${it.timestamp}] ${it.resolve(currentLanguage)}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("HID Logs", allLogs))
                            Toast.makeText(context, strings.logCopied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = consoleModifier
                    )
                }
            }
        }
    }

    // Dialog for choosing bonded device
    if (showBondedDialog) {
        val bondedDevices = remember { relayManager.getBondedDevices() }
        AlertDialog(
            onDismissRequest = { showBondedDialog = false },
            containerColor = OledSurface,
            title = {
                Text(
                    text = strings.bondedDialogTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                if (bondedDevices.isEmpty()) {
                    Text(
                        text = strings.noBondedDevices,
                        color = Slate400,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(260.dp)
                    ) {
                        items(bondedDevices) { device ->
                            val devName = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = OledSurfaceVariant,
                                border = BorderStroke(1.dp, OledBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("bonded_device_${device.address}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = devName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Slate100
                                        )
                                        Text(
                                            text = device.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate400,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Icon(
                                        imageVector = AppIcons.Bluetooth,
                                        contentDescription = strings.cdSelectDevice,
                                        tint = Cyan400
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBondedDialog = false }) {
                    Text(strings.btnOk, color = Color.White)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusSummaryCard(
    strings: LocalizedStrings,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    isHidRegistered: Boolean,
    connectionState: HidState,
    connectedHost: String?,
    isSocketListening: Boolean,
    isClientConnected: Boolean,
    packetsFlow: StateFlow<Long>,
    evgrabState: EvgrabState,
    activeKeyboards: List<DiscoveredKeyboard>,
    wacomState: WacomState,
    wacomModel: WacomModelSpec?,
    mouseState: MouseRelayState,
    activeMouseCount: Int,
    onToggleEvgrab: () -> Unit,
    onToggleWacom: () -> Unit,
    onToggleMouse: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_summary_card"),
        colors = CardDefaults.cardColors(containerColor = OledSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, OledBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleExpand)
                    .testTag("status_summary_header"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    connectionState == HidState.CONNECTED -> Emerald500
                                    isHidRegistered -> Cyan400
                                    else -> Amber500
                                }
                            )
                    )
                    Column {
                        Text(
                            text = strings.systemStatusTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (!isExpanded) {
                            val summaryText = when (connectionState) {
                                HidState.CONNECTED -> "${strings.deviceConnected}: ${connectedHost ?: ""}"
                                HidState.CONNECTING -> strings.chipHostConnecting
                                HidState.DISCONNECTING -> strings.chipHostDisconnecting
                                HidState.DISCONNECTED -> if (isHidRegistered) strings.btHidReady else strings.btHidInit
                            }
                            Text(
                                text = summaryText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connectionState == HidState.CONNECTED) Color.White else Slate400,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    PacketCountText(packetsFlow = packetsFlow)
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_expand_status")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = strings.cdToggleExpand,
                            tint = Cyan400
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(bottom = 8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // HID Status Chip
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(if (isHidRegistered) strings.btHidReady else strings.btHidInit, color = Slate100)
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isHidRegistered) Emerald500 else Amber500)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(containerColor = OledSurfaceVariant),
                            modifier = Modifier.testTag("chip_hid_status")
                        )

                        // Host Connection Status Chip
                        AssistChip(
                            onClick = {},
                            label = {
                                val label = when (connectionState) {
                                    HidState.CONNECTED -> "${strings.deviceConnected}: ${connectedHost ?: ""}"
                                    HidState.CONNECTING -> strings.chipHostConnecting
                                    HidState.DISCONNECTING -> strings.chipHostDisconnecting
                                    HidState.DISCONNECTED -> strings.deviceDisconnected
                                }
                                Text(label, color = Slate100)
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (connectionState == HidState.CONNECTED) Emerald500 else Slate400)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(containerColor = OledSurfaceVariant),
                            modifier = Modifier.testTag("chip_connection_status")
                        )

                        // evgrab USB Grab Status Chip (clickable to toggle USB forwarding)
                        AssistChip(
                            onClick = onToggleEvgrab,
                            label = {
                                val text = when (evgrabState) {
                                    EvgrabState.RUNNING -> strings.chipEvgrabRunningFmt.replace("%d", activeKeyboards.size.toString())
                                    EvgrabState.STOPPED_BY_HOTKEY -> strings.chipEvgrabHotkeyStopped
                                    EvgrabState.STOPPED -> strings.chipEvgrabForeground
                                }
                                Text(text, color = Slate100)
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (evgrabState) {
                                                EvgrabState.RUNNING -> Emerald500
                                                EvgrabState.STOPPED_BY_HOTKEY -> Amber500
                                                EvgrabState.STOPPED -> Cyan400
                                            }
                                        )
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(containerColor = OledSurfaceVariant),
                            modifier = Modifier.testTag("chip_fwd_mode")
                        )

                        // USB Mouse Status Chip (clickable to toggle USB OTG Mouse forwarding)
                        AssistChip(
                            onClick = onToggleMouse,
                            label = {
                                val text = when {
                                    activeMouseCount > 0 -> strings.mouseActiveChipFmt.replace("%d", activeMouseCount.toString())
                                    mouseState == MouseRelayState.WAITING_FOR_DEVICE -> strings.mouseWaitingChip
                                    mouseState == MouseRelayState.REQUESTING_PERMISSION -> strings.mousePermChip
                                    mouseState == MouseRelayState.ACTIVE -> strings.mouseActiveChipFmt.replace("%d", "1")
                                    else -> strings.mouseStoppedChip
                                }
                                Text(text, color = Slate100)
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                activeMouseCount > 0 || mouseState == MouseRelayState.ACTIVE -> Emerald500
                                                mouseState == MouseRelayState.WAITING_FOR_DEVICE || mouseState == MouseRelayState.REQUESTING_PERMISSION -> Amber500
                                                else -> Slate400
                                            }
                                        )
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(containerColor = OledSurfaceVariant),
                            modifier = Modifier.testTag("chip_mouse_status")
                        )

                        // Wacom OTG Pen Status Chip (clickable to toggle Wacom forwarding)
                        AssistChip(
                            onClick = onToggleWacom,
                            label = {
                                val shortModel = wacomModel?.modelName?.substringBefore(" (") ?: "Wacom"
                                val text = when (wacomState) {
                                    WacomState.ACTIVE -> strings.wacomActiveChipFmt.replace("%s", shortModel)
                                    WacomState.WAITING_FOR_DEVICE -> strings.wacomWaitingChip
                                    WacomState.REQUESTING_PERMISSION -> strings.wacomPermChip
                                    WacomState.STOPPED -> strings.wacomStoppedChip
                                }
                                Text(text, color = Slate100)
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (wacomState) {
                                                WacomState.ACTIVE -> Emerald500
                                                WacomState.WAITING_FOR_DEVICE, WacomState.REQUESTING_PERMISSION -> Amber500
                                                WacomState.STOPPED -> Slate400
                                            }
                                        )
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(containerColor = OledSurfaceVariant),
                            modifier = Modifier.testTag("chip_wacom_status")
                        )

                        // Socket Relay Status Chip
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (isClientConnected) strings.socketClientConnected
                                    else if (isSocketListening) strings.socketListening
                                    else strings.socketInactive,
                                    color = Slate100
                                )
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isClientConnected) Emerald500 else if (isSocketListening) Cyan400 else Rose500)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(containerColor = OledSurfaceVariant),
                            modifier = Modifier.testTag("chip_socket_status")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PacketCountText(packetsFlow: StateFlow<Long>) {
    val packetsForwarded by packetsFlow.collectAsState()
    Text(
        text = "# $packetsForwarded",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = Slate200
    )
}

@Composable
fun TargetDeviceCard(
    strings: LocalizedStrings,
    connectionState: HidState,
    connectedHost: String?,
    onMakeDiscoverable: () -> Unit,
    onDisconnectTarget: () -> Unit,
    onChooseBonded: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("target_device_card"),
        colors = CardDefaults.cardColors(containerColor = OledSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, OledBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.targetDeviceCardTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Slate100
                )
                if (connectionState == HidState.CONNECTED && !connectedHost.isNullOrBlank()) {
                    Text(
                        text = connectedHost,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            if (connectionState != HidState.CONNECTED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.connectByTargetHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400,
                    fontSize = 11.5.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons (Connection initiated from target device via Discoverable)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onMakeDiscoverable,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Color.Black),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("discoverable_button")
                    ) {
                        Icon(AppIcons.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.btnMakeDiscoverable, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onChooseBonded,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, OledBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("choose_bonded_button")
                    ) {
                        Icon(AppIcons.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.btnBondedList, maxLines = 1)
                    }
                }

                if (connectionState == HidState.CONNECTED) {
                    Button(
                        onClick = onDisconnectTarget,
                        colors = ButtonDefaults.buttonColors(containerColor = Rose500, contentColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("disconnect_button")
                    ) {
                        Icon(AppIcons.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.btnDisconnect)
                    }
                }
            }
        }
    }
}

/**
 * Soft keyboard live relay (QWERTY Termux-style input).
 * Intercepts sendKeyEvent and commitText via HidTextInputView and queues serialized HID reports.
 */
@Composable
fun SoftKeyboardInputCard(
    strings: LocalizedStrings,
    isConnected: Boolean,
    relayManager: RelayManager
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val isSystemImeVisible = WindowInsets.ime.getBottom(density) > 0
    var hidTextInputView by remember { mutableStateOf<HidTextInputView?>(null) }
    var isImeActive by remember { mutableStateOf(false) }

    LaunchedEffect(isSystemImeVisible) {
        if (!isSystemImeVisible && isImeActive) {
            isImeActive = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("soft_keyboard_input_card"),
        colors = CardDefaults.cardColors(containerColor = OledSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, OledBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Keyboard,
                        contentDescription = null,
                        tint = Cyan400,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.softKeyboardCardTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Slate100
                    )
                }
                if (!isConnected) {
                    Text(
                        text = strings.needConnectFirst,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate200
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Embedded HidTextInputView in a fixed-height container to avoid layout recalculations stealing IME focus
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OledBlack)
                    .border(
                        width = 1.dp,
                        color = if (isImeActive) Emerald500.copy(alpha = 0.7f) else OledBorder,
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                AndroidView(
                    factory = { ctx ->
                        HidTextInputView(
                            ctx,
                            relayManager.keySender,
                            log = { msg, lvl -> relayManager.log(msg, lvl) },
                            logKey = { key, lvl, args -> relayManager.log(key, lvl, *args) }
                        ).apply {
                            setOnClickListener { v ->
                                focusManager.clearFocus(force = true)
                                v.requestFocusFromTouch()
                                v.requestFocus()
                                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.restartInput(v)
                                imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                                isImeActive = true
                            }
                        }.also {
                            hidTextInputView = it
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Show / Hide soft keyboard toggle button
            Button(
                onClick = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (isImeActive && isSystemImeVisible) {
                        hidTextInputView?.let { v ->
                            imm?.hideSoftInputFromWindow(v.windowToken, 0)
                        }
                        isImeActive = false
                    } else {
                        focusManager.clearFocus(force = true)
                        hidTextInputView?.let { v ->
                            v.isFocusable = true
                            v.isFocusableInTouchMode = true
                            v.requestFocusFromTouch()
                            v.requestFocus()
                            v.post {
                                v.requestFocusFromTouch()
                                v.requestFocus()
                                imm?.restartInput(v)
                                val shown = imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) ?: false
                                if (!shown) {
                                    imm?.showSoftInput(v, 0)
                                }
                                relayManager.log(
                                    LogKey.IME_TOGGLE_FOCUS_STATUS,
                                    LogLevel.INFO,
                                    v.hasFocus(),
                                    shown || isSystemImeVisible
                                )
                            }
                        }
                        isImeActive = true
                    }
                },
                enabled = isConnected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isImeActive) Emerald500 else Cyan400,
                    contentColor = Color.Black,
                    disabledContainerColor = OledSurfaceVariant,
                    disabledContentColor = Slate400
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("btn_toggle_soft_keyboard")
            ) {
                Icon(
                    imageVector = if (isImeActive) AppIcons.KeyboardHide else AppIcons.Keyboard,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isImeActive) strings.btnToggleImeHide else strings.btnToggleImeShow,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = strings.softKeyboardHint,
                style = MaterialTheme.typography.bodySmall,
                color = Slate400,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun TerminalLogsCard(
    strings: LocalizedStrings,
    currentLanguage: AppLanguage,
    logs: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("terminal_logs_card"),
        colors = CardDefaults.cardColors(containerColor = OledSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, OledBorder)
    ) {
        Column(
            modifier = Modifier
                .then(if (isExpanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("btn_toggle_console")
                ) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = strings.cdToggleExpand,
                            tint = Cyan400
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = AppIcons.Terminal,
                        contentDescription = null,
                        tint = Slate200,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.terminalLogsTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Slate100
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("btn_clear_logs")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = strings.btnClear,
                            tint = Slate400,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onCopyLogs,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("btn_copy_logs")
                    ) {
                        Icon(
                            imageVector = AppIcons.ContentCopy,
                            contentDescription = strings.btnCopy,
                            tint = Cyan400,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                modifier = if (isExpanded) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = OledBlack,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, OledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (logs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = strings.noLogsYet,
                                    color = Slate400,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .testTag("logs_lazy_list")
                            ) {
                                items(logs) { entry ->
                                    val textColor = when (entry.level) {
                                        LogLevel.INFO -> Slate200
                                        LogLevel.SUCCESS -> Color.White
                                        LogLevel.WARN -> Slate200
                                        LogLevel.ERROR -> Color.White
                                    }

                                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                                        Text(
                                            text = "[${entry.timestamp}] ",
                                            color = Slate400,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = entry.resolve(currentLanguage),
                                            color = textColor,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WacomTabletCard(
    strings: LocalizedStrings,
    wacomState: WacomState,
    detectedModel: WacomModelSpec?,
    liveSampleFlow: StateFlow<WacomPenSample?>,
    orientation: WacomOrientation,
    pressureCurve: WacomPressureCurve,
    buttonMode: WacomButtonMode,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleStartStop: () -> Unit,
    onSelectOrientation: (WacomOrientation) -> Unit,
    onSelectPressureCurve: (WacomPressureCurve) -> Unit,
    onSelectButtonMode: (WacomButtonMode) -> Unit
) {
    val isRunning = wacomState != WacomState.STOPPED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wacom_tablet_card"),
        colors = CardDefaults.cardColors(containerColor = OledSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (wacomState == WacomState.ACTIVE) Emerald500.copy(alpha = 0.6f) else OledBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = AppIcons.Draw,
                        contentDescription = null,
                        tint = if (wacomState == WacomState.ACTIVE) Emerald400 else Cyan400,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = strings.wacomTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Slate100
                        )
                        WacomTelemetryText(
                            strings = strings,
                            wacomState = wacomState,
                            detectedModel = detectedModel,
                            liveSampleFlow = liveSampleFlow
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onToggleStartStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Rose500 else Cyan400,
                            contentColor = if (isRunning) Color.White else Color.Black
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("btn_toggle_wacom")
                    ) {
                        Icon(
                            imageVector = AppIcons.Usb,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isRunning) strings.wacomBtnStop else strings.wacomBtnStart,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_expand_wacom")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = strings.cdToggleExpand,
                            tint = Cyan400
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(bottom = 8.dp))

                    // Orientation selector
                    Text(
                        text = strings.wacomOrientationLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WacomOrientation.values().forEach { orient ->
                            val label = when (orient) {
                                WacomOrientation.LANDSCAPE_0 -> strings.orientLandscape0
                                WacomOrientation.PORTRAIT_270_USB_TOP -> strings.orientPortrait270
                                WacomOrientation.PORTRAIT_90 -> strings.orientPortrait90
                                WacomOrientation.LANDSCAPE_180 -> strings.orientLandscape180
                            }
                            FilterChip(
                                selected = orientation == orient,
                                onClick = { onSelectOrientation(orient) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan400.copy(alpha = 0.2f),
                                    selectedLabelColor = Color.White,
                                    labelColor = Slate200
                                ),
                                modifier = Modifier.testTag("wacom_orient_${orient.name}")
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Pressure curve selector
                    Text(
                        text = strings.wacomPressureLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WacomPressureCurve.values().forEach { curve ->
                            val label = when (curve) {
                                WacomPressureCurve.GAMMA_1_2 -> strings.pressureGamma12
                                WacomPressureCurve.LINEAR_1_0 -> strings.pressureLinear
                            }
                            FilterChip(
                                selected = pressureCurve == curve,
                                onClick = { onSelectPressureCurve(curve) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan400.copy(alpha = 0.2f),
                                    selectedLabelColor = Color.White,
                                    labelColor = Slate200
                                ),
                                modifier = Modifier.testTag("wacom_pressure_${curve.name}")
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Barrel button mode selector
                    Text(
                        text = strings.wacomButtonModeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WacomButtonMode.values().forEach { mode ->
                            val label = when (mode) {
                                WacomButtonMode.STYLUS_ONLY -> strings.btnModeStylusOnly
                                WacomButtonMode.STYLUS_PLUS_SHORTCUTS -> strings.btnModeShortcuts
                            }
                            FilterChip(
                                selected = buttonMode == mode,
                                onClick = { onSelectButtonMode(mode) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan400.copy(alpha = 0.2f),
                                    selectedLabelColor = Color.White,
                                    labelColor = Slate200
                                ),
                                modifier = Modifier.testTag("wacom_btn_mode_${mode.name}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WacomTelemetryText(
    strings: LocalizedStrings,
    wacomState: WacomState,
    detectedModel: WacomModelSpec?,
    liveSampleFlow: StateFlow<WacomPenSample?>
) {
    val liveSample by liveSampleFlow.collectAsState()
    val showTelemetry = liveSample != null && wacomState == WacomState.ACTIVE
    val subtitleText = if (showTelemetry) {
        val sample = liveSample!!
        val penState = when {
            sample.tipSwitch -> strings.wacomStateTip
            sample.inRange -> strings.wacomStateHover
            else -> strings.wacomStateIdle
        }
        String.format(
            strings.wacomLiveTelemetryFmt,
            penState,
            sample.hidX,
            sample.hidY,
            sample.hidPressure
        )
    } else {
        detectedModel?.modelName ?: strings.wacomModelNone
    }
    Text(
        text = subtitleText,
        style = MaterialTheme.typography.bodySmall,
        color = if (wacomState == WacomState.ACTIVE) Color.White else Slate400,
        fontFamily = if (showTelemetry) FontFamily.Monospace else FontFamily.Default,
        fontSize = 11.sp,
        maxLines = 1
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MouseTouchpadCard(
    strings: LocalizedStrings,
    isConnected: Boolean,
    mouseState: MouseRelayState,
    activeUsbMice: List<ConnectedUsbMouse>,
    activeEvMouseCount: Int,
    sensitivity: Float,
    mouseSender: HidMouseSender,
    keySender: HidKeySender,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleStartStop: () -> Unit,
    onSensitivityChange: (Float) -> Unit
) {
    val isRunning = mouseState != MouseRelayState.STOPPED
    val totalMice = activeUsbMice.size + activeEvMouseCount
    var isPointerCaptured by remember { mutableStateOf(false) }
    var captureSurfaceView by remember { mutableStateOf<View?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mouse_touchpad_card"),
        colors = CardDefaults.cardColors(containerColor = OledSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (totalMice > 0 || isPointerCaptured) Emerald500.copy(alpha = 0.6f) else OledBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = AppIcons.Mouse,
                        contentDescription = null,
                        tint = if (totalMice > 0 || isPointerCaptured) Emerald400 else Cyan400,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = strings.mouseTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Slate100
                        )
                        MouseTelemetryText(
                            strings = strings,
                            mouseState = mouseState,
                            activeUsbMice = activeUsbMice,
                            activeEvMouseCount = activeEvMouseCount,
                            telemetryFlow = mouseSender.liveTelemetry
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onToggleStartStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Rose500 else Cyan400,
                            contentColor = if (isRunning) Color.White else Color.Black
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("btn_toggle_mouse")
                    ) {
                        Icon(
                            imageVector = AppIcons.Usb,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isRunning) strings.mouseBtnStop else strings.mouseBtnStart,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_expand_mouse")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = strings.cdToggleExpand,
                            tint = Cyan400
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = OledBorder, modifier = Modifier.padding(bottom = 8.dp))

                    // Sensitivity preset selector
                    Text(
                        text = strings.mouseSensitivityLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0.5f, 1.0f, 1.5f, 2.5f).forEach { scale ->
                            val isSelected = kotlin.math.abs(sensitivity - scale) < 0.05f
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSensitivityChange(scale) },
                                label = { Text("${scale}x", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan400.copy(alpha = 0.2f),
                                    selectedLabelColor = Color.White,
                                    labelColor = Slate200
                                ),
                                modifier = Modifier.testTag("mouse_sens_${scale}")
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val v = captureSurfaceView
                                if (v != null) {
                                    if (isPointerCaptured) {
                                        v.releasePointerCapture()
                                        isPointerCaptured = false
                                        mouseSender.removeSource("fg_capture")
                                    } else {
                                        v.isFocusable = true
                                        v.isFocusableInTouchMode = true
                                        v.requestFocusFromTouch()
                                        v.requestFocus()
                                        v.requestPointerCapture()
                                        isPointerCaptured = v.hasPointerCapture()
                                    }
                                }
                            },
                            enabled = isConnected,
                            border = BorderStroke(1.dp, if (isPointerCaptured) Emerald500 else OledBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("btn_capture_pointer")
                        ) {
                            Icon(
                                imageVector = AppIcons.TouchApp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isPointerCaptured) strings.mouseReleaseBtn else strings.mouseCaptureBtn,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Touchpad & Pointer Capture surface (expanded height for larger touch area)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(OledBlack)
                            .border(
                                width = 1.dp,
                                color = if (isPointerCaptured) Emerald500 else OledBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
                                object : View(ctx) {
                                    override fun onPointerCaptureChange(hasCapture: Boolean) {
                                        super.onPointerCaptureChange(hasCapture)
                                        isPointerCaptured = hasCapture
                                        if (!hasCapture) {
                                            mouseSender.removeSource("fg_capture")
                                        }
                                    }
                                }.apply {
                                    isFocusable = true
                                    isFocusableInTouchMode = true

                                    var prevX = 0f
                                    var prevY = 0f
                                    var downX = 0f
                                    var downY = 0f
                                    var prevMidY = 0f
                                    var downTimeMs = 0L
                                    var maxPointers = 0
                                    var maxMoveDist = 0f
                                    var remX = 0f
                                    var remY = 0f
                                    var remScroll = 0f

                                    var capRemX = 0f
                                    var capRemY = 0f
                                    var capRemWheel = 0f

                                    setOnTouchListener { view, event ->
                                        view.parent?.requestDisallowInterceptTouchEvent(true)
                                        when (event.actionMasked) {
                                            MotionEvent.ACTION_DOWN -> {
                                                prevX = event.x
                                                prevY = event.y
                                                downX = event.x
                                                downY = event.y
                                                downTimeMs = event.eventTime
                                                maxPointers = 1
                                                maxMoveDist = 0f
                                                true
                                            }
                                            MotionEvent.ACTION_POINTER_DOWN -> {
                                                maxPointers = max(maxPointers, event.pointerCount)
                                                if (event.pointerCount >= 2) {
                                                    prevMidY = (event.getY(0) + event.getY(1)) * 0.5f
                                                }
                                                true
                                            }
                                            MotionEvent.ACTION_MOVE -> {
                                                maxPointers = max(maxPointers, event.pointerCount)
                                                if (event.pointerCount == 1 && maxPointers == 1) {
                                                    val rawDx = (event.x - prevX) * sensitivity
                                                    val rawDy = (event.y - prevY) * sensitivity
                                                    prevX = event.x
                                                    prevY = event.y
                                                    maxMoveDist = max(maxMoveDist, hypot(event.x - downX, event.y - downY))

                                                    val totX = rawDx + remX
                                                    val totY = rawDy + remY
                                                    val ix = totX.roundToInt()
                                                    val iy = totY.roundToInt()
                                                    remX = totX - ix
                                                    remY = totY - iy
                                                    if (ix != 0 || iy != 0) {
                                                        mouseSender.sendRelativeMove("fg_touchpad", null, ix, iy, 0)
                                                    }
                                                } else if (event.pointerCount >= 2) {
                                                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                                                    val dMidY = midY - prevMidY
                                                    prevMidY = midY
                                                    maxMoveDist = max(maxMoveDist, touchSlop + 4f)
                                                    val rawWheel = (-dMidY / 26f) + remScroll
                                                    val iw = rawWheel.roundToInt()
                                                    remScroll = rawWheel - iw
                                                    if (iw != 0) {
                                                        mouseSender.sendRelativeMove("fg_touchpad", null, 0, 0, iw)
                                                    }
                                                }
                                                true
                                            }
                                            MotionEvent.ACTION_UP -> {
                                                val elapsed = event.eventTime - downTimeMs
                                                if (maxMoveDist < touchSlop && elapsed < 260L) {
                                                    val clickBit = if (maxPointers >= 2) 0x02 else 0x01
                                                    mouseSender.updateSourceButtons("fg_touchpad", clickBit)
                                                    view.postDelayed({
                                                        mouseSender.updateSourceButtons("fg_touchpad", 0)
                                                    }, 35L)
                                                }
                                                true
                                            }
                                            MotionEvent.ACTION_CANCEL -> true
                                            else -> true
                                        }
                                    }

                                    setOnCapturedPointerListener { _, event ->
                                        val btnState = event.buttonState
                                        var mask = 0
                                        if ((btnState and MotionEvent.BUTTON_PRIMARY) != 0) mask = mask or 0x01
                                        if ((btnState and MotionEvent.BUTTON_SECONDARY) != 0) mask = mask or 0x02
                                        if ((btnState and MotionEvent.BUTTON_TERTIARY) != 0) mask = mask or 0x04
                                        if ((btnState and MotionEvent.BUTTON_BACK) != 0) mask = mask or 0x08
                                        if ((btnState and MotionEvent.BUTTON_FORWARD) != 0) mask = mask or 0x10

                                        val totX = event.getAxisValue(MotionEvent.AXIS_X) * sensitivity + capRemX
                                        val totY = event.getAxisValue(MotionEvent.AXIS_Y) * sensitivity + capRemY
                                        val totW = event.getAxisValue(MotionEvent.AXIS_VSCROLL) + capRemWheel
                                        val ix = totX.roundToInt()
                                        val iy = totY.roundToInt()
                                        val iw = totW.roundToInt()
                                        capRemX = totX - ix
                                        capRemY = totY - iy
                                        capRemWheel = totW - iw

                                        mouseSender.sendRelativeMove("fg_capture", mask, ix, iy, iw)
                                        true
                                    }

                                    captureSurfaceView = this
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("touchpad_surface")
                        )

                        Text(
                            text = if (isPointerCaptured) strings.mouseReleaseBtn else strings.mouseTouchpadHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPointerCaptured) Color.White else Slate400,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 16.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Tactile Mouse & Page Up/Down Bar: [Left] [PgUp] [Middle] [PgDn] [Right]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        @Composable
                        fun MouseBarButton(
                            bit: Int,
                            label: String,
                            tag: String,
                            modifier: Modifier = Modifier
                        ) {
                            var isHeld by remember { mutableStateOf(false) }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isHeld) Cyan400.copy(alpha = 0.25f) else OledSurfaceVariant,
                                border = BorderStroke(1.dp, if (isHeld) Cyan400 else OledBorder),
                                modifier = modifier
                                    .height(48.dp)
                                    .testTag(tag)
                                    .pointerInput(bit) {
                                        detectTapGestures(
                                            onPress = {
                                                isHeld = true
                                                mouseSender.updateSourceButtons("fg_touchpad_btn", bit)
                                                tryAwaitRelease()
                                                isHeld = false
                                                mouseSender.updateSourceButtons("fg_touchpad_btn", 0)
                                            }
                                        )
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isHeld) Color.White else Slate100,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        @Composable
                        fun PageNavButton(
                            isUp: Boolean,
                            tag: String,
                            modifier: Modifier = Modifier
                        ) {
                            var isHeld by remember { mutableStateOf(false) }
                            val hidKeyCode = if (isUp) 0x4B.toByte() else 0x4E.toByte() // Page Up / Page Down
                            val sourceId = if (isUp) "fg_touchpad_page_up" else "fg_touchpad_page_down"
                            val desc = if (isUp) "Page Up" else "Page Down"
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isHeld) Cyan400.copy(alpha = 0.25f) else OledSurfaceVariant,
                                border = BorderStroke(1.dp, if (isHeld) Cyan400 else OledBorder),
                                modifier = modifier
                                    .height(48.dp)
                                    .testTag(tag)
                                    .pointerInput(isUp) {
                                        detectTapGestures(
                                            onPress = {
                                                isHeld = true
                                                if (keySender.keyEnabled) {
                                                    keySender.updateSourceKeys(sourceId, 0, setOf(hidKeyCode))
                                                } else {
                                                    mouseSender.sendRelativeMove(
                                                        "fg_touchpad_btn",
                                                        null,
                                                        0,
                                                        0,
                                                        if (isUp) 5 else -5
                                                    )
                                                }
                                                tryAwaitRelease()
                                                isHeld = false
                                                if (keySender.keyEnabled) {
                                                    keySender.updateSourceKeys(sourceId, 0, emptySet())
                                                }
                                            }
                                        )
                                    }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isUp) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = desc,
                                        tint = if (isHeld) Color.White else Slate100,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (isUp) "PgUp" else "PgDn",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isHeld) Color.White else Slate200,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        MouseBarButton(
                            bit = 0x01,
                            label = strings.mouseBtnLeft,
                            tag = "btn_mouse_left",
                            modifier = Modifier.weight(1.1f)
                        )
                        PageNavButton(
                            isUp = true,
                            tag = "btn_mouse_page_up",
                            modifier = Modifier.weight(0.95f)
                        )
                        MouseBarButton(
                            bit = 0x04,
                            label = strings.mouseBtnMiddle,
                            tag = "btn_mouse_middle",
                            modifier = Modifier.weight(1.0f)
                        )
                        PageNavButton(
                            isUp = false,
                            tag = "btn_mouse_page_down",
                            modifier = Modifier.weight(0.95f)
                        )
                        MouseBarButton(
                            bit = 0x02,
                            label = strings.mouseBtnRight,
                            tag = "btn_mouse_right",
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MouseTelemetryText(
    strings: LocalizedStrings,
    mouseState: MouseRelayState,
    activeUsbMice: List<ConnectedUsbMouse>,
    activeEvMouseCount: Int,
    telemetryFlow: StateFlow<MouseTelemetrySample?>
) {
    val sample by telemetryFlow.collectAsState()
    val totalActive = activeUsbMice.size + activeEvMouseCount
    val subtitleText = when {
        sample != null && (totalActive > 0 || mouseState != MouseRelayState.STOPPED) -> {
            val s = sample!!
            "OTG:$totalActive  BTN:0x%02X  dX:%d dY:%d W:%d".format(
                s.buttonsMask,
                s.lastDx,
                s.lastDy,
                s.lastWheel
            )
        }
        activeUsbMice.isNotEmpty() -> activeUsbMice.joinToString { it.name }
        activeEvMouseCount > 0 -> strings.mouseActiveChipFmt.replace("%d", activeEvMouseCount.toString())
        else -> strings.mouseNoneConnected
    }
    Text(
        text = subtitleText,
        style = MaterialTheme.typography.bodySmall,
        color = if (totalActive > 0 || mouseState == MouseRelayState.ACTIVE) Color.White else Slate400,
        fontFamily = if (sample != null) FontFamily.Monospace else FontFamily.Default,
        fontSize = 11.sp,
        maxLines = 1
    )
}
