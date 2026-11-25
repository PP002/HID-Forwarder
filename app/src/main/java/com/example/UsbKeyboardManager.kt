package com.example

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectedUsbKeyboard(
    val deviceId: Int,
    val name: String
)

/**
 * Non-root foreground USB OTG / external hardware keyboard manager.
 *
 * Uses Android's InputManager API to discover attached external keyboards and intercepts
 * foreground hardware KeyEvents per InputDevice ID to forward merged 8-byte HID reports
 * without requiring su/root permissions.
 */
class UsbKeyboardManager(
    context: Context,
    private val keySender: HidKeySender,
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) {
    constructor(
        context: Context,
        keySender: HidKeySender,
        log: (String, LogLevel) -> Unit
    ) : this(context, keySender, log, null)

    private class FgKeyState {
        var modifiers: Byte = 0
        val pressedKeys = LinkedHashSet<Byte>()
    }

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val fgDeviceStates = HashMap<Int, FgKeyState>()
    private val fgLock = Any()
    private val loggedFgDeviceIds = HashSet<Int>()

    private val _activeKeyboards = MutableStateFlow<List<ConnectedUsbKeyboard>>(emptyList())
    val activeKeyboards: StateFlow<List<ConnectedUsbKeyboard>> = _activeKeyboards.asStateFlow()

    private fun emitLog(key: LogKey, level: LogLevel, vararg args: Any) {
        val lk = logKey
        if (lk != null) {
            lk(key, level, args)
        } else {
            log(LogLocalizer.format(AppLanguage.EN, key, *args), level)
        }
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            refreshAttachedKeyboards()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            synchronized(fgLock) {
                fgDeviceStates.remove(deviceId)
                loggedFgDeviceIds.remove(deviceId)
            }
            keySender.removeSource("fg_usb:$deviceId")
            refreshAttachedKeyboards()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            refreshAttachedKeyboards()
        }
    }

    init {
        try {
            inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        } catch (_: Exception) {}
        refreshAttachedKeyboards()
    }

    fun refreshAttachedKeyboards(): List<ConnectedUsbKeyboard> {
        val im = inputManager ?: return emptyList()
        val found = mutableListOf<ConnectedUsbKeyboard>()
        try {
            for (id in im.inputDeviceIds) {
                val dev = im.getInputDevice(id) ?: continue
                if (isExternalHardwareKeyboard(dev)) {
                    found.add(ConnectedUsbKeyboard(dev.id, dev.name.ifBlank { "External Keyboard" }))
                }
            }
        } catch (_: Exception) {}
        _activeKeyboards.value = found
        return found
    }

    private fun isExternalHardwareKeyboard(dev: InputDevice): Boolean {
        if (dev.id == KeyCharacterMap.VIRTUAL_KEYBOARD || dev.isVirtual) return false
        val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dev.isExternal
        } else {
            dev.id > 0
        }
        if (!isExternal) return false
        if (dev.vendorId == WacomProtocol.WACOM_VENDOR_ID) return false
        val hasKbSource = (dev.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        return hasKbSource && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    fun clearForegroundKeys() {
        synchronized(fgLock) {
            fgDeviceStates.clear()
            loggedFgDeviceIds.clear()
        }
        keySender.clearSourcesWithPrefix("fg_usb:")
    }

    fun destroy() {
        try {
            inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        } catch (_: Exception) {}
        clearForegroundKeys()
    }

    /**
     * Foreground OTG multi-keyboard handler (no root / su required).
     * Tracks modifier and pressed-key state separately per Android InputDevice ID.
     */
    fun onForegroundHardwareKeyEvent(event: KeyEvent): Boolean {
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
}
