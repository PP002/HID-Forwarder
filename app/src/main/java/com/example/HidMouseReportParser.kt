package com.example

import kotlin.math.min

/**
 * Describes how a USB HID Mouse interface encodes its input report bits.
 */
data class HidMouseReportLayout(
    val reportId: Int?, // null if report has no Report ID prefix byte
    val buttonsOffsetBits: Int,
    val buttonsCount: Int,
    val xOffsetBits: Int,
    val xBits: Int,
    val yOffsetBits: Int,
    val yBits: Int,
    val wheelOffsetBits: Int?,
    val wheelBits: Int
)

data class ParsedMouseInput(
    val buttonsMask: Int,
    val dx: Int,
    val dy: Int,
    val wheel: Int
)

object HidMouseReportParser {

    fun bootMouseLayout(): HidMouseReportLayout = HidMouseReportLayout(
        reportId = null,
        buttonsOffsetBits = 0,
        buttonsCount = 5,
        xOffsetBits = 8,
        xBits = 8,
        yOffsetBits = 16,
        yBits = 8,
        wheelOffsetBits = 24,
        wheelBits = 8
    )

    /**
     * Parses raw USB HID mouse interrupt packets using either a discovered layout or standard Boot Mouse fallback.
     */
    fun parsePacket(data: ByteArray, length: Int, layout: HidMouseReportLayout?): ParsedMouseInput? {
        if (length <= 0 || data.isEmpty()) return null
        val effectiveLayout = layout ?: bootMouseLayout()

        var bitPayloadOffset = 0
        if (effectiveLayout.reportId != null) {
            val rid = data[0].toInt() and 0xFF
            if (rid != effectiveLayout.reportId) return null
            bitPayloadOffset = 8
        }

        val totalBitsAvailable = length * 8
        if (totalBitsAvailable < bitPayloadOffset + 24) return null

        val btnCount = effectiveLayout.buttonsCount.coerceIn(1, 5)
        val rawButtons = extractUnsignedBits(
            data,
            bitPayloadOffset + effectiveLayout.buttonsOffsetBits,
            btnCount
        )

        val dx = extractSignedBits(
            data,
            bitPayloadOffset + effectiveLayout.xOffsetBits,
            effectiveLayout.xBits
        )
        val dy = extractSignedBits(
            data,
            bitPayloadOffset + effectiveLayout.yOffsetBits,
            effectiveLayout.yBits
        )

        val wOff = effectiveLayout.wheelOffsetBits
        val wheel = if (wOff != null && effectiveLayout.wheelBits > 0 &&
            totalBitsAvailable >= bitPayloadOffset + wOff + effectiveLayout.wheelBits
        ) {
            extractSignedBits(data, bitPayloadOffset + wOff, effectiveLayout.wheelBits)
        } else {
            0
        }

        return ParsedMouseInput(
            buttonsMask = rawButtons and 0x1F,
            dx = dx,
            dy = dy,
            wheel = wheel
        )
    }

    /**
     * Parses a USB HID Report Descriptor and extracts the Mouse Input report bit layout if present.
     */
    fun parseDescriptorForMouse(desc: ByteArray): HidMouseReportLayout? {
        if (desc.isEmpty()) return null

        var i = 0
        var usagePage = 0
        var reportSize = 0
        var reportCount = 0
        var reportId: Int? = null
        var usageMin = -1
        var usageMax = -1
        val localUsages = ArrayList<Int>()
        val collectionStack = ArrayList<Int>() // full usage ((usagePage << 16) | usage)

        var inMouseAppCollection = false
        val bitCursorByReportId = HashMap<Int, Int>() // reportId (-1 if none) -> bit offset

        var foundReportId: Int? = null
        var buttonsOffset = -1
        var buttonsCount = 0
        var xOffset = -1
        var xBits = 0
        var yOffset = -1
        var yBits = 0
        var wheelOffset: Int? = null
        var wheelBits = 0

        fun readItemValueUnsigned(offset: Int, size: Int): Int {
            var v = 0
            for (b in 0 until size) {
                v = v or ((desc[offset + b].toInt() and 0xFF) shl (8 * b))
            }
            return v
        }

        while (i < desc.size) {
            val prefix = desc[i].toInt() and 0xFF
            if (prefix == 0xFE) {
                // Long item
                if (i + 2 >= desc.size) break
                val dataSize = desc[i + 1].toInt() and 0xFF
                i += 3 + dataSize
                continue
            }

            val sizeCode = prefix and 0x03
            val itemSize = if (sizeCode == 3) 4 else sizeCode
            val itemType = (prefix shr 2) and 0x03
            val itemTag = (prefix shr 4) and 0x0F

            if (i + 1 + itemSize > desc.size) break
            val value = readItemValueUnsigned(i + 1, itemSize)
            i += 1 + itemSize

            when (itemType) {
                1 -> { // Global item
                    when (itemTag) {
                        0x0 -> usagePage = value
                        0x7 -> reportSize = value
                        0x8 -> {
                            reportId = value and 0xFF
                            if (inMouseAppCollection && foundReportId == null &&
                                buttonsOffset < 0 && xOffset < 0 && yOffset < 0
                            ) {
                                foundReportId = reportId
                            }
                        }
                        0x9 -> reportCount = value
                    }
                }
                2 -> { // Local item
                    when (itemTag) {
                        0x0 -> localUsages.add(
                            if (itemSize == 4) value else ((usagePage shl 16) or value)
                        )
                        0x1 -> usageMin = value
                        0x2 -> usageMax = value
                    }
                }
                0 -> { // Main item
                    when (itemTag) {
                        0xA -> { // Collection
                            val u = localUsages.lastOrNull() ?: 0
                            collectionStack.add(u)
                            val isAppCollection = (value and 0xFF) == 0x01
                            if (isAppCollection && u == 0x00010002) {
                                // Generic Desktop (0x01) -> Mouse (0x02)
                                inMouseAppCollection = true
                                foundReportId = reportId
                            }
                            localUsages.clear()
                            usageMin = -1
                            usageMax = -1
                        }
                        0xC -> { // End Collection
                            if (collectionStack.isNotEmpty()) {
                                val popped = collectionStack.removeAt(collectionStack.size - 1)
                                if (popped == 0x00010002) {
                                    inMouseAppCollection = false
                                }
                            }
                            localUsages.clear()
                            usageMin = -1
                            usageMax = -1
                        }
                        0x8 -> { // Input
                            val ridKey = reportId ?: -1
                            val cursor = bitCursorByReportId[ridKey] ?: 0
                            val isConst = (value and 0x01) != 0
                            val isRelative = (value and 0x04) != 0
                            val totalFieldBits = reportSize * reportCount

                            if (inMouseAppCollection && !isConst && reportSize > 0 && reportCount > 0 &&
                                (foundReportId == reportId)
                            ) {
                                // Button field (Usage Page 0x09)
                                if (usagePage == 0x09 && buttonsOffset < 0 && reportSize == 1) {
                                    buttonsOffset = cursor
                                    buttonsCount = min(5, reportCount)
                                } else {
                                    // Variable axes
                                    for (idx in 0 until reportCount) {
                                        val u = when {
                                            idx < localUsages.size -> localUsages[idx]
                                            usageMin >= 0 && usageMax >= usageMin -> {
                                                val offsetUsage = (usageMin + idx).coerceAtMost(usageMax)
                                                (usagePage shl 16) or offsetUsage
                                            }
                                            localUsages.isNotEmpty() -> localUsages.last()
                                            else -> 0
                                        }
                                        val fieldBitPos = cursor + idx * reportSize
                                        when (u) {
                                            0x00010030 -> if (isRelative && xOffset < 0) {
                                                xOffset = fieldBitPos
                                                xBits = reportSize
                                            }
                                            0x00010031 -> if (isRelative && yOffset < 0) {
                                                yOffset = fieldBitPos
                                                yBits = reportSize
                                            }
                                            0x00010038 -> if (isRelative && wheelOffset == null) {
                                                wheelOffset = fieldBitPos
                                                wheelBits = reportSize
                                            }
                                        }
                                    }
                                }
                            }

                            bitCursorByReportId[ridKey] = cursor + totalFieldBits
                            localUsages.clear()
                            usageMin = -1
                            usageMax = -1
                        }
                        else -> { // Output / Feature
                            localUsages.clear()
                            usageMin = -1
                            usageMax = -1
                        }
                    }
                }
            }
        }

        if (xOffset < 0 || yOffset < 0 || xBits <= 0 || yBits <= 0) return null
        if (buttonsOffset < 0) {
            buttonsOffset = 0
            buttonsCount = 3
        }

        return HidMouseReportLayout(
            reportId = foundReportId,
            buttonsOffsetBits = buttonsOffset,
            buttonsCount = buttonsCount,
            xOffsetBits = xOffset,
            xBits = xBits,
            yOffsetBits = yOffset,
            yBits = yBits,
            wheelOffsetBits = wheelOffset,
            wheelBits = wheelBits
        )
    }

    fun extractUnsignedBits(data: ByteArray, bitOffset: Int, bitCount: Int): Int {
        if (bitCount <= 0) return 0
        var result = 0
        for (k in 0 until bitCount) {
            val bitPos = bitOffset + k
            val byteIdx = bitPos ushr 3
            if (byteIdx >= data.size) break
            val bitIdx = bitPos and 7
            val bit = (data[byteIdx].toInt() ushr bitIdx) and 1
            result = result or (bit shl k)
        }
        return result
    }

    fun extractSignedBits(data: ByteArray, bitOffset: Int, bitCount: Int): Int {
        if (bitCount <= 0 || bitCount > 31) return 0
        val unsigned = extractUnsignedBits(data, bitOffset, bitCount)
        val signBit = 1 shl (bitCount - 1)
        return if ((unsigned and signBit) != 0) {
            unsigned or (signBit - 1).inv()
        } else {
            unsigned
        }
    }

    fun buildMouseHidReport(buttonsMask: Int, dx: Int, dy: Int, wheel: Int): ByteArray {
        return byteArrayOf(
            (buttonsMask and 0x1F).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
    }
}
