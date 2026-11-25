package com.example

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class HidTextInputView(
    context: Context,
    private val keySender: HidKeySender,
    private val log: (String, LogLevel) -> Unit,
    private val logKey: ((LogKey, LogLevel, Array<out Any>) -> Unit)?
) : View(context) {
    constructor(
        context: Context,
        keySender: HidKeySender,
        log: (String, LogLevel) -> Unit
    ) : this(context, keySender, log, null)

    private val scope = CoroutineScope(Dispatchers.IO)
    private val strokeQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private var currentModifiers: Byte = 0
    private val currentKeys = LinkedHashSet<Byte>()
    private val lock = Any()
    @Volatile private var lastComposingWarnMs: Long = 0L

    private fun emitLog(key: LogKey, level: LogLevel, vararg args: Any) {
        val lk = logKey
        if (lk != null) {
            lk(key, level, args)
        } else {
            log(LogLocalizer.format(AppLanguage.EN, key, *args), level)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        // Single serialized queue ensures strict stroke ordering without blocking the main thread
        scope.launch {
            for (action in strokeQueue) {
                try {
                    action()
                } catch (e: Exception) {
                    emitLog(LogKey.IME_QUEUE_EXCEPTION, LogLevel.ERROR, e.localizedMessage ?: "Unknown error")
                }
            }
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            synchronized(lock) {
                currentModifiers = 0
                currentKeys.clear()
            }
            keySender.clearSource(KeySource.SOFT_KEYBOARD)
        }
        emitLog(LogKey.IME_FOCUS_CHANGED, LogLevel.INFO, gainFocus)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && isSoftOrImeKeyEvent(event) && handleImeKeyEvent(event, sourceTag = "View.onKeyDown")) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && isSoftOrImeKeyEvent(event) && handleImeKeyEvent(event, sourceTag = "View.onKeyUp")) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent?): Boolean {
        if (event != null && !event.characters.isNullOrEmpty() && isSoftOrImeKeyEvent(event)) {
            emitLog(LogKey.IME_VIEW_KEY_MULTIPLE, LogLevel.INFO, event.characters ?: "")
            enqueueText(event.characters)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun isSoftOrImeKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        return event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || dev == null || dev.isVirtual
    }

    @Suppress("DEPRECATION")
    private fun handleImeKeyEvent(event: KeyEvent, sourceTag: String): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters
            if (!chars.isNullOrEmpty()) {
                emitLog(LogKey.IME_ACTION_MULTIPLE, LogLevel.INFO, sourceTag, chars)
                enqueueText(chars)
                return true
            }
            return false
        }

        val isDown = (event.action == KeyEvent.ACTION_DOWN)
        val isUp = (event.action == KeyEvent.ACTION_UP)
        if (!isDown && !isUp) return false

        val modMask = getModifierMask(event.keyCode)
        if (modMask != 0) {
            synchronized(lock) {
                currentModifiers = if (isDown) {
                    (currentModifiers.toInt() or modMask).toByte()
                } else {
                    (currentModifiers.toInt() and modMask.inv()).toByte()
                }
                keySender.updateSourceKeys(KeySource.SOFT_KEYBOARD, currentModifiers, currentKeys)
            }
            return true
        }

        val usage = androidKeyCodeToHidUsage(event.keyCode)
        if (usage != 0.toByte()) {
            val extraShift = androidKeyCodeImplicitShift(event.keyCode)
            val metaMods = metaStateToHidModifiers(event.metaState) or extraShift
            if (isDown) {
                emitLog(
                    LogKey.IME_KEY_MAPPED,
                    LogLevel.INFO,
                    sourceTag,
                    event.keyCode,
                    "0x${"%02X".format(usage)}"
                )
            }
            synchronized(lock) {
                if (isDown) {
                    currentKeys.add(usage)
                } else {
                    currentKeys.remove(usage)
                }
                val effectiveMods = if (isDown) {
                    (currentModifiers.toInt() or metaMods).toByte()
                } else {
                    currentModifiers
                }
                keySender.updateSourceKeys(KeySource.SOFT_KEYBOARD, effectiveMods, currentKeys)
            }
            return true
        }

        // Fallback to unicodeChar when direct Android keyCode mapping is unavailable (e.g. symbol keys)
        if (isDown && event.unicodeChar != 0) {
            val ch = event.unicodeChar.toChar()
            if (ch.code <= 127) {
                val (mod, code) = charToHidUsage(ch)
                if (code != 0.toByte()) {
                    emitLog(
                        LogKey.IME_UNICODE_MAPPED,
                        LogLevel.INFO,
                        sourceTag,
                        ch.toString(),
                        "0x${"%02X".format(code)}"
                    )
                    strokeQueue.trySend {
                        keySender.sendStroke(KeySource.SOFT_KEYBOARD, mod, code, 15L)
                    }
                    return true
                }
            } else {
                emitLog(LogKey.HID_CANNOT_SEND_COMPOSED_CHAR, LogLevel.WARN, ch.toString())
                return true
            }
        }

        return false
    }

    private fun enqueueText(str: String) {
        strokeQueue.trySend {
            for (ch in str) {
                if (ch.code > 127) {
                    emitLog(LogKey.HID_CANNOT_SEND_COMPOSED_CHAR, LogLevel.WARN, ch.toString())
                    continue
                }
                val (mod, code) = charToHidUsage(ch)
                if (code != 0.toByte()) {
                    keySender.sendStroke(KeySource.SOFT_KEYBOARD, mod, code, 15L)
                }
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL requests raw key events from the IME without preedit composition or candidate bars
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_ACTION_NONE

        emitLog(LogKey.IME_IC_CONNECTED, LogLevel.INFO)

        return object : BaseInputConnection(this@HidTextInputView, false) {

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (handleImeKeyEvent(event, sourceTag = "IC.sendKeyEvent")) {
                    return true
                }
                return super.sendKeyEvent(event)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text.isNullOrEmpty()) return true
                emitLog(LogKey.IME_IC_COMMIT_TEXT, LogLevel.INFO, text.toString())
                enqueueText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Composition is unsupported over HID; return true and drop composing text
                if (!text.isNullOrEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastComposingWarnMs > 2500L) {
                        lastComposingWarnMs = now
                        emitLog(LogKey.IME_COMPOSING_BLOCKED, LogLevel.WARN, text.toString())
                    }
                }
                return true
            }

            override fun finishComposingText(): Boolean {
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                emitLog(LogKey.IME_IC_DELETE_SURROUNDING, LogLevel.INFO, beforeLength, afterLength)
                strokeQueue.trySend {
                    repeat(beforeLength.coerceAtLeast(0)) {
                        keySender.sendStroke(KeySource.SOFT_KEYBOARD, 0, 0x2A.toByte(), 15L) // DEL / Backspace
                    }
                    repeat(afterLength.coerceAtLeast(0)) {
                        keySender.sendStroke(KeySource.SOFT_KEYBOARD, 0, 0x4C.toByte(), 15L) // Forward Delete
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                emitLog(LogKey.IME_IC_EDITOR_ACTION, LogLevel.INFO, actionCode)
                strokeQueue.trySend {
                    keySender.sendStroke(KeySource.SOFT_KEYBOARD, 0, 0x28.toByte(), 15L) // Enter
                }
                return true
            }
        }
    }

    companion object {
        fun metaStateToHidModifiers(metaState: Int): Int {
            var mods = 0
            if ((metaState and KeyEvent.META_CTRL_LEFT_ON) != 0) mods = mods or 0x01
            if ((metaState and KeyEvent.META_CTRL_RIGHT_ON) != 0) mods = mods or 0x10
            if ((metaState and KeyEvent.META_CTRL_ON) != 0 && (mods and 0x11) == 0) mods = mods or 0x01

            if ((metaState and KeyEvent.META_SHIFT_LEFT_ON) != 0) mods = mods or 0x02
            if ((metaState and KeyEvent.META_SHIFT_RIGHT_ON) != 0) mods = mods or 0x20
            if ((metaState and KeyEvent.META_SHIFT_ON) != 0 && (mods and 0x22) == 0) mods = mods or 0x02

            if ((metaState and KeyEvent.META_ALT_LEFT_ON) != 0) mods = mods or 0x04
            if ((metaState and KeyEvent.META_ALT_RIGHT_ON) != 0) mods = mods or 0x40
            if ((metaState and KeyEvent.META_ALT_ON) != 0 && (mods and 0x44) == 0) mods = mods or 0x04

            if ((metaState and KeyEvent.META_META_LEFT_ON) != 0) mods = mods or 0x08
            if ((metaState and KeyEvent.META_META_RIGHT_ON) != 0) mods = mods or 0x80
            if ((metaState and KeyEvent.META_META_ON) != 0 && (mods and 0x88) == 0) mods = mods or 0x08
            return mods
        }

        fun getModifierMask(keyCode: Int): Int {
            return when (keyCode) {
                KeyEvent.KEYCODE_CTRL_LEFT -> 0x01
                KeyEvent.KEYCODE_SHIFT_LEFT -> 0x02
                KeyEvent.KEYCODE_ALT_LEFT -> 0x04
                KeyEvent.KEYCODE_META_LEFT -> 0x08
                KeyEvent.KEYCODE_CTRL_RIGHT -> 0x10
                KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x20
                KeyEvent.KEYCODE_ALT_RIGHT -> 0x40
                KeyEvent.KEYCODE_META_RIGHT -> 0x80
                else -> 0
            }
        }

        fun androidKeyCodeImplicitShift(keyCode: Int): Int {
            return when (keyCode) {
                KeyEvent.KEYCODE_AT,
                KeyEvent.KEYCODE_POUND,
                KeyEvent.KEYCODE_STAR,
                KeyEvent.KEYCODE_PLUS -> 0x02
                else -> 0
            }
        }

        fun androidKeyCodeToHidUsage(keyCode: Int): Byte {
            return when (keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                    (0x04 + (keyCode - KeyEvent.KEYCODE_A)).toByte()

                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 ->
                    (0x1E + (keyCode - KeyEvent.KEYCODE_1)).toByte()

                KeyEvent.KEYCODE_0 -> 0x27.toByte()
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x28.toByte()
                KeyEvent.KEYCODE_ESCAPE -> 0x29.toByte()
                KeyEvent.KEYCODE_DEL -> 0x2A.toByte()
                KeyEvent.KEYCODE_TAB -> 0x2B.toByte()
                KeyEvent.KEYCODE_SPACE -> 0x2C.toByte()
                KeyEvent.KEYCODE_MINUS -> 0x2D.toByte()
                KeyEvent.KEYCODE_EQUALS -> 0x2E.toByte()
                KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F.toByte()
                KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30.toByte()
                KeyEvent.KEYCODE_BACKSLASH -> 0x31.toByte()
                KeyEvent.KEYCODE_SEMICOLON -> 0x33.toByte()
                KeyEvent.KEYCODE_APOSTROPHE -> 0x34.toByte()
                KeyEvent.KEYCODE_GRAVE -> 0x35.toByte()
                KeyEvent.KEYCODE_COMMA -> 0x36.toByte()
                KeyEvent.KEYCODE_PERIOD -> 0x37.toByte()
                KeyEvent.KEYCODE_SLASH -> 0x38.toByte()
                KeyEvent.KEYCODE_CAPS_LOCK -> 0x39.toByte()

                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                    (0x3A + (keyCode - KeyEvent.KEYCODE_F1)).toByte()

                KeyEvent.KEYCODE_SYSRQ -> 0x46.toByte()
                KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47.toByte()
                KeyEvent.KEYCODE_BREAK -> 0x48.toByte()
                KeyEvent.KEYCODE_INSERT -> 0x49.toByte()
                KeyEvent.KEYCODE_MOVE_HOME -> 0x4A.toByte()
                KeyEvent.KEYCODE_PAGE_UP -> 0x4B.toByte()
                KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C.toByte()
                KeyEvent.KEYCODE_MOVE_END -> 0x4D.toByte()
                KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E.toByte()
                KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F.toByte()
                KeyEvent.KEYCODE_DPAD_LEFT -> 0x50.toByte()
                KeyEvent.KEYCODE_DPAD_DOWN -> 0x51.toByte()
                KeyEvent.KEYCODE_DPAD_UP -> 0x52.toByte()
                KeyEvent.KEYCODE_NUM_LOCK -> 0x53.toByte()

                KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54.toByte()
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55.toByte()
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56.toByte()
                KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57.toByte()
                in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 ->
                    (0x59 + (keyCode - KeyEvent.KEYCODE_NUMPAD_1)).toByte()
                KeyEvent.KEYCODE_NUMPAD_0 -> 0x62.toByte()
                KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63.toByte()

                // Shifted symbol keycodes sent by some soft keyboards
                KeyEvent.KEYCODE_AT -> 0x1F.toByte()      // Shift + 2
                KeyEvent.KEYCODE_POUND -> 0x20.toByte()   // Shift + 3
                KeyEvent.KEYCODE_STAR -> 0x25.toByte()    // Shift + 8
                KeyEvent.KEYCODE_PLUS -> 0x2E.toByte()    // Shift + =
                else -> 0.toByte()
            }
        }

        /**
         * Maps a Linux evdev scanCode directly to a HID Boot Keyboard Usage.
         */
        fun evdevScanCodeToHidUsage(scanCode: Int): Byte {
            return when (scanCode) {
                1 -> 0x29.toByte()
                in 2..11 -> (0x1E + (scanCode - 2)).toByte() // 1..0
                12 -> 0x2D.toByte()
                13 -> 0x2E.toByte()
                14 -> 0x2A.toByte()
                15 -> 0x2B.toByte()
                16 -> 0x14.toByte() // q
                17 -> 0x1A.toByte() // w
                18 -> 0x08.toByte() // e
                19 -> 0x15.toByte() // r
                20 -> 0x17.toByte() // t
                21 -> 0x1C.toByte() // y
                22 -> 0x18.toByte() // u
                23 -> 0x0C.toByte() // i
                24 -> 0x12.toByte() // o
                25 -> 0x13.toByte() // p
                26 -> 0x2F.toByte()
                27 -> 0x30.toByte()
                28 -> 0x28.toByte()
                30 -> 0x04.toByte() // a
                31 -> 0x16.toByte() // s
                32 -> 0x07.toByte() // d
                33 -> 0x09.toByte() // f
                34 -> 0x0A.toByte() // g
                35 -> 0x0B.toByte() // h
                36 -> 0x0D.toByte() // j
                37 -> 0x0E.toByte() // k
                38 -> 0x0F.toByte() // l
                39 -> 0x33.toByte()
                40 -> 0x34.toByte()
                41 -> 0x35.toByte()
                43 -> 0x31.toByte()
                44 -> 0x1D.toByte() // z
                45 -> 0x1B.toByte() // x
                46 -> 0x06.toByte() // c
                47 -> 0x19.toByte() // v
                48 -> 0x05.toByte() // b
                49 -> 0x11.toByte() // n
                50 -> 0x10.toByte() // m
                51 -> 0x36.toByte()
                52 -> 0x37.toByte()
                53 -> 0x38.toByte()
                55 -> 0x55.toByte()
                57 -> 0x2C.toByte()
                58 -> 0x39.toByte()
                in 59..68 -> (0x3A + (scanCode - 59)).toByte() // F1..F10
                69 -> 0x53.toByte()
                70 -> 0x47.toByte()
                71 -> 0x5F.toByte()
                72 -> 0x60.toByte()
                73 -> 0x61.toByte()
                74 -> 0x56.toByte()
                75 -> 0x5C.toByte()
                76 -> 0x5D.toByte()
                77 -> 0x5E.toByte()
                78 -> 0x57.toByte()
                79 -> 0x59.toByte()
                80 -> 0x5A.toByte()
                81 -> 0x5B.toByte()
                82 -> 0x62.toByte()
                83 -> 0x63.toByte()
                86 -> 0x64.toByte()
                87 -> 0x44.toByte()
                88 -> 0x45.toByte()
                96 -> 0x58.toByte()
                98 -> 0x54.toByte()
                99 -> 0x46.toByte()
                102 -> 0x4A.toByte()
                103 -> 0x52.toByte()
                104 -> 0x4B.toByte()
                105 -> 0x50.toByte()
                106 -> 0x4F.toByte()
                107 -> 0x4D.toByte()
                108 -> 0x51.toByte()
                109 -> 0x4E.toByte()
                110 -> 0x49.toByte()
                111 -> 0x4C.toByte()
                119 -> 0x48.toByte()
                else -> 0.toByte()
            }
        }

        fun evdevScanCodeToModifierMask(scanCode: Int): Int {
            return when (scanCode) {
                29 -> 0x01
                42 -> 0x02
                56 -> 0x04
                125 -> 0x08
                97 -> 0x10
                54 -> 0x20
                100 -> 0x40
                126 -> 0x80
                else -> 0
            }
        }

        fun charToHidUsage(ch: Char): Pair<Byte, Byte> {
            val shift: Byte = 0x02
            return when (ch) {
                in 'a'..'z' -> Pair(0, (0x04 + (ch - 'a')).toByte())
                in 'A'..'Z' -> Pair(shift, (0x04 + (ch - 'A')).toByte())
                in '1'..'9' -> Pair(0, (0x1E + (ch - '1')).toByte())
                '0' -> Pair(0, 0x27.toByte())
                ' ' -> Pair(0, 0x2C.toByte())
                '\n' -> Pair(0, 0x28.toByte())
                '\t' -> Pair(0, 0x2B.toByte())
                '\b' -> Pair(0, 0x2A.toByte())

                '-' -> Pair(0, 0x2D.toByte())
                '_' -> Pair(shift, 0x2D.toByte())
                '=' -> Pair(0, 0x2E.toByte())
                '+' -> Pair(shift, 0x2E.toByte())
                '[' -> Pair(0, 0x2F.toByte())
                '{' -> Pair(shift, 0x2F.toByte())
                ']' -> Pair(0, 0x30.toByte())
                '}' -> Pair(shift, 0x30.toByte())
                '\\' -> Pair(0, 0x31.toByte())
                '|' -> Pair(shift, 0x31.toByte())
                ';' -> Pair(0, 0x33.toByte())
                ':' -> Pair(shift, 0x33.toByte())
                '\'' -> Pair(0, 0x34.toByte())
                '"' -> Pair(shift, 0x34.toByte())
                '`' -> Pair(0, 0x35.toByte())
                '~' -> Pair(shift, 0x35.toByte())
                ',' -> Pair(0, 0x36.toByte())
                '<' -> Pair(shift, 0x36.toByte())
                '.' -> Pair(0, 0x37.toByte())
                '>' -> Pair(shift, 0x37.toByte())
                '/' -> Pair(0, 0x38.toByte())
                '?' -> Pair(shift, 0x38.toByte())

                '!' -> Pair(shift, 0x1E.toByte())
                '@' -> Pair(shift, 0x1F.toByte())
                '#' -> Pair(shift, 0x20.toByte())
                '$' -> Pair(shift, 0x21.toByte())
                '%' -> Pair(shift, 0x22.toByte())
                '^' -> Pair(shift, 0x23.toByte())
                '&' -> Pair(shift, 0x24.toByte())
                '*' -> Pair(shift, 0x25.toByte())
                '(' -> Pair(shift, 0x26.toByte())
                ')' -> Pair(shift, 0x27.toByte())
                else -> Pair(0, 0)
            }
        }
    }
}
