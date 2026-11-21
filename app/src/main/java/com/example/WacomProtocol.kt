package com.example

import kotlin.math.pow
import kotlin.math.roundToInt

data class WacomModelSpec(
    val vendorId: Int,
    val productId: Int,
    val modelName: String,
    val maxX: Int,
    val maxY: Int,
    val maxPressure: Int
)

enum class WacomOrientation {
    LANDSCAPE_0,
    PORTRAIT_270_USB_TOP,
    PORTRAIT_90,
    LANDSCAPE_180
}

enum class WacomPressureCurve {
    GAMMA_1_2,
    LINEAR_1_0
}

enum class WacomButtonMode {
    STYLUS_ONLY,
    STYLUS_PLUS_SHORTCUTS
}

data class WacomPenSample(
    val inRange: Boolean,
    val tipSwitch: Boolean,
    val button1: Boolean,
    val button2: Boolean,
    val rawX: Int,
    val rawY: Int,
    val rawPressure: Int,
    val hidX: Int,
    val hidY: Int,
    val hidPressure: Int
)

object WacomProtocol {
    const val WACOM_VENDOR_ID = 0x056A
    const val CTL_471_PRODUCT_ID = 0x0300

    const val HID_MAX_COORD = 32767
    const val HID_MAX_PRESSURE = 2047

    // Report ID 0x02 initialization feature packet from wacom-on-android (CTL-471)
    val CTL471_FEATURE_INIT_REPORT = byteArrayOf(0x02, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00)
    // Short 2-byte Wacom mode-switch fallback used by newer Bamboo/Intuos tablets
    val WACOM_SHORT_INIT_REPORT = byteArrayOf(0x02, 0x02)

    val KNOWN_MODELS: List<WacomModelSpec> = listOf(
        WacomModelSpec(WACOM_VENDOR_ID, 0x0300, "Wacom CTL-471 (One / Bamboo)", 15200, 9500, 1023),
        WacomModelSpec(WACOM_VENDOR_ID, 0x0301, "Wacom CTL-671 (One M)", 21648, 13530, 1023),
        WacomModelSpec(WACOM_VENDOR_ID, 0x037A, "Wacom CTL-472 (One S)", 15200, 9500, 2047),
        WacomModelSpec(WACOM_VENDOR_ID, 0x037B, "Wacom CTL-672 (One M)", 21600, 13500, 2047),
        WacomModelSpec(WACOM_VENDOR_ID, 0x030E, "Wacom CTL-480 (Intuos S)", 15200, 9500, 1023),
        WacomModelSpec(WACOM_VENDOR_ID, 0x030F, "Wacom CTH-480 (Intuos Pen & Touch)", 15200, 9500, 1023),
        WacomModelSpec(WACOM_VENDOR_ID, 0x033B, "Wacom CTL-490 (Intuos Draw)", 15200, 9500, 1023),
        WacomModelSpec(WACOM_VENDOR_ID, 0x0374, "Wacom CTL-4100 (Intuos S)", 15200, 9500, 4095)
    )

    fun resolveModel(vendorId: Int, productId: Int, productName: String? = null): WacomModelSpec? {
        if (vendorId != WACOM_VENDOR_ID) return null
        KNOWN_MODELS.firstOrNull { it.productId == productId }?.let { return it }
        val fallbackName = productName?.takeIf { it.isNotBlank() }
            ?: "Wacom Tablet (0x${productId.toString(16).uppercase()})"
        return WacomModelSpec(
            vendorId = vendorId,
            productId = productId,
            modelName = fallbackName,
            maxX = 15200,
            maxY = 9500,
            maxPressure = 1023
        )
    }

    /**
     * Parses a Wacom Report ID 0x02 pen packet ([0x02, status, xLo, xHi, yLo, yHi, pLo, pHi, ...]).
     * Returns null for non-0x02 reports or malformed frames.
     */
    fun parseReport02(
        data: ByteArray,
        length: Int,
        spec: WacomModelSpec,
        orientation: WacomOrientation,
        pressureCurve: WacomPressureCurve
    ): WacomPenSample? {
        if (length < 8 || data.size < 8) return null
        val reportId = data[0].toInt() and 0xFF
        if (reportId != 0x02) return null

        val status = data[1].toInt() and 0xFF
        val tipSwitch = (status and 0x01) != 0
        val button1 = (status and 0x02) != 0
        val button2 = (status and 0x04) != 0
        val inRange = ((status and 0x20) != 0) || ((status and 0x80) != 0) || tipSwitch

        val rawX = ((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val rawY = ((data[5].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val rawPressure = ((data[7].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)

        val nx = (rawX.toDouble() / spec.maxX.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val ny = (rawY.toDouble() / spec.maxY.coerceAtLeast(1)).coerceIn(0.0, 1.0)

        val (rotX, rotY) = when (orientation) {
            WacomOrientation.LANDSCAPE_0 -> Pair(nx, ny)
            WacomOrientation.PORTRAIT_270_USB_TOP -> Pair(1.0 - ny, nx)
            WacomOrientation.PORTRAIT_90 -> Pair(ny, 1.0 - nx)
            WacomOrientation.LANDSCAPE_180 -> Pair(1.0 - nx, 1.0 - ny)
        }

        val hidX = (rotX * HID_MAX_COORD).roundToInt().coerceIn(0, HID_MAX_COORD)
        val hidY = (rotY * HID_MAX_COORD).roundToInt().coerceIn(0, HID_MAX_COORD)

        val hidPressure = if (rawPressure > 0 && tipSwitch) {
            val normP = (rawPressure.toDouble() / spec.maxPressure.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val curved = when (pressureCurve) {
                WacomPressureCurve.GAMMA_1_2 -> normP.pow(1.2)
                WacomPressureCurve.LINEAR_1_0 -> normP
            }
            (curved * HID_MAX_PRESSURE).roundToInt().coerceIn(1, HID_MAX_PRESSURE)
        } else if (rawPressure > 0 && inRange) {
            // Preserve non-zero raw pressure only when tip is active; hover reports 0 pressure
            0
        } else {
            0
        }

        return WacomPenSample(
            inRange = inRange,
            tipSwitch = tipSwitch,
            button1 = button1,
            button2 = button2,
            rawX = rawX,
            rawY = rawY,
            rawPressure = rawPressure,
            hidX = hidX,
            hidY = hidY,
            hidPressure = hidPressure
        )
    }

    /**
     * Packs a 7-byte Bluetooth HID Pen Input Report (Report ID 2 payload):
     * [flags(1), xLo, xHi, yLo, yHi, pressureLo, pressureHi]
     *
     * Flag bits matching WACOM_PEN_REPORT_DESCRIPTOR:
     * bit 0: Tip Switch (0x42)
     * bit 1: Barrel Switch (0x44)
     * bit 2: Secondary Barrel Switch (0x5A)
     * bit 3: Eraser (0x45)
     * bit 4: Invert (0x3C)
     * bit 5: In Range (0x32)
     */
    fun buildPenHidReport(
        inRange: Boolean,
        tipSwitch: Boolean,
        button1: Boolean,
        button2: Boolean,
        hidX: Int,
        hidY: Int,
        hidPressure: Int,
        eraser: Boolean = false,
        invert: Boolean = false
    ): ByteArray {
        var flags = 0
        if (inRange) {
            if (tipSwitch) flags = flags or 0x01
            if (button1) flags = flags or 0x02
            if (button2) flags = flags or 0x04
            if (eraser) flags = flags or 0x08
            if (invert) flags = flags or 0x10
            flags = flags or 0x20
        }
        val cx = hidX.coerceIn(0, HID_MAX_COORD)
        val cy = hidY.coerceIn(0, HID_MAX_COORD)
        val cp = if (inRange && (tipSwitch || eraser)) hidPressure.coerceIn(0, HID_MAX_PRESSURE) else 0

        return byteArrayOf(
            flags.toByte(),
            (cx and 0xFF).toByte(),
            ((cx shr 8) and 0xFF).toByte(),
            (cy and 0xFF).toByte(),
            ((cy shr 8) and 0xFF).toByte(),
            (cp and 0xFF).toByte(),
            ((cp shr 8) and 0xFF).toByte()
        )
    }
}
