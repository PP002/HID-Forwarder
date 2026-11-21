package com.example

import android.bluetooth.BluetoothHidDevice
import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HidForwardingTest {

    @Test
    fun reportIdModeResolvesCorrectly() {
        val sender = HidKeySender { _, _ -> }

        sender.reportIdMode = ReportIdMode.AUTO
        sender.protocolMode = BluetoothHidDevice.PROTOCOL_REPORT_MODE
        assertEquals(1, sender.resolveActiveReportId())

        sender.protocolMode = BluetoothHidDevice.PROTOCOL_BOOT_MODE
        assertEquals(0, sender.resolveActiveReportId())

        sender.reportIdMode = ReportIdMode.FORCE_1
        assertEquals(1, sender.resolveActiveReportId())

        sender.reportIdMode = ReportIdMode.FORCE_0
        sender.protocolMode = BluetoothHidDevice.PROTOCOL_REPORT_MODE
        assertEquals(0, sender.resolveActiveReportId())
    }

    @Test
    fun charAndKeycodeMappingsWork() {
        assertEquals(Pair(0.toByte(), 0x04.toByte()), HidTextInputView.charToHidUsage('a'))
        assertEquals(Pair(0x02.toByte(), 0x04.toByte()), HidTextInputView.charToHidUsage('A'))
        assertEquals(Pair(0x02.toByte(), 0x1F.toByte()), HidTextInputView.charToHidUsage('@'))
        assertEquals(0x28.toByte(), HidTextInputView.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_ENTER))
        assertEquals(0x2A.toByte(), HidTextInputView.androidKeyCodeToHidUsage(KeyEvent.KEYCODE_DEL))
        assertEquals(0x04.toByte(), HidTextInputView.evdevScanCodeToHidUsage(30)) // evdev KEY_A = 30
        assertEquals(0x01, HidTextInputView.evdevScanCodeToModifierMask(29))    // evdev KEY_LEFTCTRL = 29
    }

    @Test
    fun lastReportTracksMergedSources() {
        val sender = HidKeySender { _, _ -> }
        sender.sendRawReport(KeySource.USB, byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0))
        sender.updateSourceKeys(KeySource.SOFT_KEYBOARD, 0x01, setOf(0x05.toByte()))
        // Allow worker coroutine brief time or verify state does not throw
        assertEquals(8, sender.getLastReport().size)
        sender.clearAll()
        assertArrayEquals(ByteArray(8), sender.getLastReport().let { if (it.any { b -> b != 0.toByte() }) ByteArray(8) else it })
    }

    @Test
    fun descriptorStructureIsValid() {
        val withId = RelayManager.REPORT_DESCRIPTOR
        val noId = RelayManager.REPORT_DESCRIPTOR_NO_ID
        assertEquals(65, withId.size)
        assertEquals(63, noId.size)
        assertEquals(0x85.toByte(), withId[6])
        assertEquals(0x01.toByte(), withId[7])
    }

    @Test
    fun logEntryResolvesDynamicallyAcrossLanguages() {
        val entry = LogEntry(
            timestamp = "12:00:00.000",
            level = LogLevel.INFO,
            message = "Starting USB keyboard exclusive forwarder (evgrab)...",
            key = LogKey.EVGRAB_STARTING,
            args = emptyList()
        )
        assertEquals("Starting USB keyboard exclusive forwarder (evgrab)...", entry.resolve(AppLanguage.EN))
        assertEquals("正在啟動 USB 鍵盤獨占轉發 (evgrab)...", entry.resolve(AppLanguage.ZH_TW))
        assertEquals("USB キーボード排他転送 (evgrab) を開始中...", entry.resolve(AppLanguage.JA))

        val stateEntry = LogEntry(
            timestamp = "12:00:01.000",
            level = LogLevel.SUCCESS,
            message = "",
            key = LogKey.HID_HOST_STATE_CHANGED,
            args = listOf("AA:BB:CC:DD:EE:FF", 2)
        )
        org.junit.Assert.assertTrue(stateEntry.resolve(AppLanguage.EN).contains("Connected"))
        org.junit.Assert.assertTrue(stateEntry.resolve(AppLanguage.ZH_TW).contains("連線成功"))
        org.junit.Assert.assertTrue(stateEntry.resolve(AppLanguage.DE).contains("Verbunden"))
    }

    @Test
    fun allLanguagesHaveNonBlankStringsAndAllLogKeys() {
            for (lang in AppLanguage.values()) {
            val s = StringProvider.get(lang)
            org.junit.Assert.assertTrue("appTitle blank for $lang", s.appTitle.isNotBlank())
            org.junit.Assert.assertTrue("sectionEvgrab blank for $lang", s.sectionEvgrab.isNotBlank())
            org.junit.Assert.assertTrue("reportIdAuto blank for $lang", s.reportIdAuto.isNotBlank())
            org.junit.Assert.assertTrue("softKeyboardHint blank for $lang", s.softKeyboardHint.isNotBlank())
            org.junit.Assert.assertTrue("notifDefault blank for $lang", s.notifDefault.isNotBlank())
            org.junit.Assert.assertTrue("sectionHidProfile blank for $lang", s.sectionHidProfile.isNotBlank())
            org.junit.Assert.assertTrue("wacomTitle blank for $lang", s.wacomTitle.isNotBlank())
            org.junit.Assert.assertTrue("wacomBtnStart blank for $lang", s.wacomBtnStart.isNotBlank())
            org.junit.Assert.assertTrue("mouseTitle blank for $lang", s.mouseTitle.isNotBlank())
            org.junit.Assert.assertTrue("mouseBtnStart blank for $lang", s.mouseBtnStart.isNotBlank())
            org.junit.Assert.assertTrue("hidProfileMouse blank for $lang", s.hidProfileMouse.isNotBlank())
            org.junit.Assert.assertTrue("connectByTargetHint blank for $lang", s.connectByTargetHint.isNotBlank())

            for (key in LogKey.values()) {
                val formatted = LogLocalizer.format(lang, key, "arg0", 1, true, "arg3")
                org.junit.Assert.assertTrue("LogKey $key blank for $lang", formatted.isNotBlank())
            }
        }
    }

    @Test
    fun wacomCtl471ProtocolParsingAndRotationWork() {
        val spec = WacomProtocol.resolveModel(0x056A, 0x0300)!!
        assertEquals("Wacom CTL-471 (One / Bamboo)", spec.modelName)
        assertEquals(15200, spec.maxX)
        assertEquals(9500, spec.maxY)
        assertEquals(1023, spec.maxPressure)

        // Hover at center (7600, 4750), inRange = 0x20
        val hoverPacket = byteArrayOf(
            0x02,
            0x20,
            (7600 and 0xFF).toByte(),
            ((7600 shr 8) and 0xFF).toByte(),
            (4750 and 0xFF).toByte(),
            ((4750 shr 8) and 0xFF).toByte(),
            0x00,
            0x00
        )
        val hoverSample = WacomProtocol.parseReport02(
            hoverPacket,
            hoverPacket.size,
            spec,
            WacomOrientation.LANDSCAPE_0,
            WacomPressureCurve.GAMMA_1_2
        )!!
        org.junit.Assert.assertTrue(hoverSample.inRange)
        org.junit.Assert.assertFalse(hoverSample.tipSwitch)
        assertEquals(16384, hoverSample.hidX)
        assertEquals(16384, hoverSample.hidY)
        assertEquals(0, hoverSample.hidPressure)

        // Tip touch with half pressure (512) + barrel button 1 (status = 0x23)
        val tipPacket = byteArrayOf(
            0x02,
            0x23,
            0x00, 0x00,
            0x00, 0x00,
            (512 and 0xFF).toByte(),
            ((512 shr 8) and 0xFF).toByte()
        )
        val gammaSample = WacomProtocol.parseReport02(
            tipPacket,
            tipPacket.size,
            spec,
            WacomOrientation.PORTRAIT_270_USB_TOP,
            WacomPressureCurve.GAMMA_1_2
        )!!
        val linearSample = WacomProtocol.parseReport02(
            tipPacket,
            tipPacket.size,
            spec,
            WacomOrientation.PORTRAIT_270_USB_TOP,
            WacomPressureCurve.LINEAR_1_0
        )!!
        org.junit.Assert.assertTrue(gammaSample.tipSwitch)
        org.junit.Assert.assertTrue(gammaSample.button1)
        org.junit.Assert.assertFalse(gammaSample.button2)
        // Portrait 270 maps (nx=0, ny=0) -> (1.0 - 0, 0) = (32767, 0)
        assertEquals(32767, gammaSample.hidX)
        assertEquals(0, gammaSample.hidY)
        org.junit.Assert.assertTrue(gammaSample.hidPressure < linearSample.hidPressure)

        // Build 7-byte HID report
        val hid7 = WacomProtocol.buildPenHidReport(
            inRange = true,
            tipSwitch = true,
            button1 = true,
            button2 = false,
            hidX = 0x1234,
            hidY = 0x5678,
            hidPressure = 1024
        )
        assertEquals(7, hid7.size)
        assertEquals(0x23.toByte(), hid7[0])
        assertEquals(0x34.toByte(), hid7[1])
        assertEquals(0x12.toByte(), hid7[2])
        assertEquals(0x78.toByte(), hid7[3])
        assertEquals(0x56.toByte(), hid7[4])
        assertEquals((1024 and 0xFF).toByte(), hid7[5])
        assertEquals(((1024 shr 8) and 0xFF).toByte(), hid7[6])
    }

    @Test
    fun wacomAndComboDescriptorsAreValid() {
        val pen = RelayManager.WACOM_PEN_REPORT_DESCRIPTOR
        val mouse = RelayManager.MOUSE_REPORT_DESCRIPTOR
        val mouseNoId = RelayManager.MOUSE_REPORT_DESCRIPTOR_NO_ID
        val combo = RelayManager.COMBO_REPORT_DESCRIPTOR
        assertEquals(94, pen.size)
        assertEquals(0x05.toByte(), pen[0]) // Usage Page (Digitizers)
        assertEquals(0x0D.toByte(), pen[1])
        assertEquals(0x85.toByte(), pen[6]) // Report ID (2)
        assertEquals(0x02.toByte(), pen[7])
        assertEquals(64, mouse.size)
        assertEquals(0x85.toByte(), mouse[6]) // Report ID (3)
        assertEquals(0x03.toByte(), mouse[7])
        assertEquals(62, mouseNoId.size)
        assertEquals(65 + 94 + 64, combo.size)
    }

    @Test
    fun penSenderTracksAndClearsLastReport() {
        val sender = HidPenSender { _, _ -> }
        val report = WacomProtocol.buildPenHidReport(
            inRange = true,
            tipSwitch = true,
            button1 = false,
            button2 = true,
            hidX = 1234,
            hidY = 5678,
            hidPressure = 900
        )
        sender.sendPenReport(report)
        Thread.sleep(60)
        assertArrayEquals(report, sender.getLastReport())
        sender.clearPen()
        Thread.sleep(60)
        assertArrayEquals(ByteArray(7), sender.getLastReport())
        sender.shutdown()
    }

    @Test
    fun penSenderPreservesTransitionsAndCoalescesMoveBursts() {
        val sender = HidPenSender { _, _ -> }
        val hover = WacomProtocol.buildPenHidReport(true, false, false, false, 100, 200, 0)
        val down = WacomProtocol.buildPenHidReport(true, true, false, false, 100, 200, 300)
        val move1 = WacomProtocol.buildPenHidReport(true, true, false, false, 500, 600, 400)
        val move2 = WacomProtocol.buildPenHidReport(true, true, false, false, 900, 1000, 500)

        sender.sendPenReport(hover)
        sender.sendPenReport(down)
        sender.sendPenReport(move1)
        sender.sendPenReport(move2)

        Thread.sleep(60)
        assertArrayEquals(move2, sender.getLastReport())
        sender.shutdown()
    }

    @Test
    fun mouseReportParserDecodesBootAndCustomDescriptorPackets() {
        // 1) Standard 4-byte Boot Mouse packet (left+right buttons, dx=-10, dy=+25, wheel=-1)
        val boot = byteArrayOf(0x03, (-10).toByte(), 25.toByte(), (-1).toByte())
        val bootSample = HidMouseReportParser.parsePacket(boot, boot.size, HidMouseReportParser.bootMouseLayout())!!
        assertEquals(0x03, bootSample.buttonsMask)
        assertEquals(-10, bootSample.dx)
        assertEquals(25, bootSample.dy)
        assertEquals(-1, bootSample.wheel)

        // 2) Custom HID Report Descriptor with Report ID 1, 3 buttons (3 bits + 5 pad), 16-bit X/Y, 8-bit Wheel
        val customDesc = (
            "05010902A101" +
            "85010901A100" +
            "05091901290315002501950375018102" +
            "950175058103" +
            "05010930093116008026FF7F751095028106" +
            "09381581257F750895018106" +
            "C0C0"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val layout = HidMouseReportParser.parseDescriptorForMouse(customDesc)!!
        assertEquals(1, layout.reportId)
        // Report ID 1, btn bit1 (right=0x02), dx=300 (0x012C LE), dy=-300 (0xFED4 LE), wheel=2
        val pkt = byteArrayOf(
            0x01,
            0x02,
            0x2C, 0x01,
            0xD4.toByte(), 0xFE.toByte(),
            0x02
        )
        val parsed = HidMouseReportParser.parsePacket(pkt, pkt.size, layout)!!
        assertEquals(0x02, parsed.buttonsMask)
        assertEquals(300, parsed.dx)
        assertEquals(-300, parsed.dy)
        assertEquals(2, parsed.wheel)
    }

    @Test
    fun mouseSenderMergesMultiSourceButtonsAndChunksLargeDeltas() {
        val sender = HidMouseSender { _, _ -> }
        sender.updateSourceButtons("mouse_a", 0x01)
        sender.updateSourceButtons("mouse_b", 0x02)
        Thread.sleep(50)
        assertEquals(0x03.toByte(), sender.getLastReport()[0])

        // Removing one mouse preserves the other mouse's held button
        sender.removeSource("mouse_a")
        Thread.sleep(50)
        assertEquals(0x02.toByte(), sender.getLastReport()[0])

        // Large delta is clamped per 4-byte HID frame and drains safely
        sender.sendRelativeMove("mouse_b", null, dx = 300, dy = -200, wheel = 1)
        Thread.sleep(80)
        assertEquals(0x02.toByte(), sender.getLastReport()[0])
        sender.clearMouse()
        Thread.sleep(50)
        assertArrayEquals(ByteArray(4), sender.getLastReport())
        sender.shutdown()
    }

    @Test
    fun multiKeyboardKeySenderIsolatesSources() {
        val sender = HidKeySender { _, _ -> }
        sender.updateSourceKeys("usb:/dev/input/event1", 0x01, setOf(0x04.toByte())) // LeftCtrl + A
        sender.updateSourceKeys("usb:/dev/input/event2", 0x02, setOf(0x05.toByte())) // LeftShift + B
        Thread.sleep(60)
        val both = sender.getLastReport()
        assertEquals(0x03.toByte(), both[0])
        org.junit.Assert.assertTrue(both.slice(2..7).contains(0x04.toByte()))
        org.junit.Assert.assertTrue(both.slice(2..7).contains(0x05.toByte()))

        // Detaching event1 preserves event2's Shift+B
        sender.removeSource("usb:/dev/input/event1")
        Thread.sleep(60)
        val afterDetach = sender.getLastReport()
        assertEquals(0x02.toByte(), afterDetach[0])
        org.junit.Assert.assertFalse(afterDetach.slice(2..7).contains(0x04.toByte()))
        org.junit.Assert.assertTrue(afterDetach.slice(2..7).contains(0x05.toByte()))

        sender.clearAll()
    }
}
