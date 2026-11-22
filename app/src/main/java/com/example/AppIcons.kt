package com.example

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

object AppIcons {
    val Bluetooth: ImageVector
        get() {
            if (_bluetooth != null) return _bluetooth!!
            _bluetooth = materialIcon(name = "Filled.Bluetooth") {
                materialPath {
            moveTo(17.71f, 7.71f)
            lineTo(12.0f, 2.0f)
            horizontalLineToRelative(-1.0f)
            verticalLineToRelative(7.59f)
            lineTo(6.41f, 5.0f)
            lineTo(5.0f, 6.41f)
            lineTo(10.59f, 12.0f)
            lineTo(5.0f, 17.59f)
            lineTo(6.41f, 19.0f)
            lineTo(11.0f, 14.41f)
            lineTo(11.0f, 22.0f)
            horizontalLineToRelative(1.0f)
            lineToRelative(5.71f, -5.71f)
            lineToRelative(-4.3f, -4.29f)
            lineToRelative(4.3f, -4.29f)
            close()
            moveTo(13.0f, 5.83f)
            lineToRelative(1.88f, 1.88f)
            lineTo(13.0f, 9.59f)
            lineTo(13.0f, 5.83f)
            close()
            moveTo(14.88f, 16.29f)
            lineTo(13.0f, 18.17f)
            verticalLineToRelative(-3.76f)
            lineToRelative(1.88f, 1.88f)
            close()
                }
            }
            return _bluetooth!!
        }
    private var _bluetooth: ImageVector? = null

    val BluetoothSearching: ImageVector
        get() {
            if (_bluetoothSearching != null) return _bluetoothSearching!!
            _bluetoothSearching = materialIcon(name = "Filled.BluetoothSearching", autoMirror = true) {
                materialPath {
            moveTo(14.24f, 12.01f)
            lineToRelative(2.32f, 2.32f)
            curveToRelative(0.28f, -0.72f, 0.44f, -1.51f, 0.44f, -2.33f)
            curveToRelative(0.0f, -0.82f, -0.16f, -1.59f, -0.43f, -2.31f)
            lineToRelative(-2.33f, 2.32f)
            close()
            moveTo(19.53f, 6.71f)
            lineToRelative(-1.26f, 1.26f)
            curveToRelative(0.63f, 1.21f, 0.98f, 2.57f, 0.98f, 4.02f)
            reflectiveCurveToRelative(-0.36f, 2.82f, -0.98f, 4.02f)
            lineToRelative(1.2f, 1.2f)
            curveToRelative(0.97f, -1.54f, 1.54f, -3.36f, 1.54f, -5.31f)
            curveToRelative(-0.01f, -1.89f, -0.55f, -3.67f, -1.48f, -5.19f)
            close()
            moveTo(15.71f, 7.71f)
            lineTo(10.0f, 2.0f)
            lineTo(9.0f, 2.0f)
            verticalLineToRelative(7.59f)
            lineTo(4.41f, 5.0f)
            lineTo(3.0f, 6.41f)
            lineTo(8.59f, 12.0f)
            lineTo(3.0f, 17.59f)
            lineTo(4.41f, 19.0f)
            lineTo(9.0f, 14.41f)
            lineTo(9.0f, 22.0f)
            horizontalLineToRelative(1.0f)
            lineToRelative(5.71f, -5.71f)
            lineToRelative(-4.3f, -4.29f)
            lineToRelative(4.3f, -4.29f)
            close()
            moveTo(11.0f, 5.83f)
            lineToRelative(1.88f, 1.88f)
            lineTo(11.0f, 9.59f)
            lineTo(11.0f, 5.83f)
            close()
            moveTo(12.88f, 16.29f)
            lineTo(11.0f, 18.17f)
            verticalLineToRelative(-3.76f)
            lineToRelative(1.88f, 1.88f)
            close()
                }
            }
            return _bluetoothSearching!!
        }
    private var _bluetoothSearching: ImageVector? = null

    val Devices: ImageVector
        get() {
            if (_devices != null) return _devices!!
            _devices = materialIcon(name = "Filled.Devices") {
                materialPath {
            moveTo(4.0f, 6.0f)
            horizontalLineToRelative(18.0f)
            lineTo(22.0f, 4.0f)
            lineTo(4.0f, 4.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(11.0f)
            lineTo(0.0f, 17.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(14.0f)
            verticalLineToRelative(-3.0f)
            lineTo(4.0f, 17.0f)
            lineTo(4.0f, 6.0f)
            close()
            moveTo(23.0f, 8.0f)
            horizontalLineToRelative(-6.0f)
            curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
            verticalLineToRelative(10.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(6.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            lineTo(24.0f, 9.0f)
            curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
            close()
            moveTo(22.0f, 17.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(-7.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(7.0f)
            close()
                }
            }
            return _devices!!
        }
    private var _devices: ImageVector? = null

    val LinkOff: ImageVector
        get() {
            if (_linkOff != null) return _linkOff!!
            _linkOff = materialIcon(name = "Filled.LinkOff") {
                materialPath {
            moveTo(17.0f, 7.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(1.9f)
            horizontalLineToRelative(4.0f)
            curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
            curveToRelative(0.0f, 1.43f, -0.98f, 2.63f, -2.31f, 2.98f)
            lineToRelative(1.46f, 1.46f)
            curveTo(20.88f, 15.61f, 22.0f, 13.95f, 22.0f, 12.0f)
            curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
            close()
            moveTo(16.0f, 11.0f)
            horizontalLineToRelative(-2.19f)
            lineToRelative(2.0f, 2.0f)
            lineTo(16.0f, 13.0f)
            close()
            moveTo(2.0f, 4.27f)
            lineToRelative(3.11f, 3.11f)
            curveTo(3.29f, 8.12f, 2.0f, 9.91f, 2.0f, 12.0f)
            curveToRelative(0.0f, 2.76f, 2.24f, 5.0f, 5.0f, 5.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(-1.9f)
            lineTo(7.0f, 15.1f)
            curveToRelative(-1.71f, 0.0f, -3.1f, -1.39f, -3.1f, -3.1f)
            curveToRelative(0.0f, -1.59f, 1.21f, -2.9f, 2.76f, -3.07f)
            lineTo(8.73f, 11.0f)
            lineTo(8.0f, 11.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.73f)
            lineTo(13.0f, 15.27f)
            lineTo(13.0f, 17.0f)
            horizontalLineToRelative(1.73f)
            lineToRelative(4.01f, 4.0f)
            lineTo(20.0f, 19.74f)
            lineTo(3.27f, 3.0f)
            lineTo(2.0f, 4.27f)
            close()
                }
            }
            return _linkOff!!
        }
    private var _linkOff: ImageVector? = null

    val Keyboard: ImageVector
        get() {
            if (_keyboard != null) return _keyboard!!
            _keyboard = materialIcon(name = "Filled.Keyboard") {
                materialPath {
            moveTo(20.0f, 5.0f)
            lineTo(4.0f, 5.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 17.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(22.0f, 7.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(11.0f, 8.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(11.0f, 8.0f)
            close()
            moveTo(11.0f, 11.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            close()
            moveTo(8.0f, 8.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            lineTo(8.0f, 10.0f)
            lineTo(8.0f, 8.0f)
            close()
            moveTo(8.0f, 11.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            lineTo(8.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            close()
            moveTo(7.0f, 13.0f)
            lineTo(5.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(7.0f, 10.0f)
            lineTo(5.0f, 10.0f)
            lineTo(5.0f, 8.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(16.0f, 17.0f)
            lineTo(8.0f, 17.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(16.0f, 13.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(16.0f, 10.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(14.0f, 8.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(19.0f, 13.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(19.0f, 10.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(17.0f, 8.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
                }
            }
            return _keyboard!!
        }
    private var _keyboard: ImageVector? = null

    val KeyboardHide: ImageVector
        get() {
            if (_keyboardHide != null) return _keyboardHide!!
            _keyboardHide = materialIcon(name = "Filled.KeyboardHide") {
                materialPath {
            moveTo(20.0f, 3.0f)
            lineTo(4.0f, 3.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 15.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(22.0f, 5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(11.0f, 6.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(11.0f, 6.0f)
            close()
            moveTo(11.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(11.0f, 9.0f)
            close()
            moveTo(8.0f, 6.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            lineTo(8.0f, 8.0f)
            lineTo(8.0f, 6.0f)
            close()
            moveTo(8.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            lineTo(8.0f, 11.0f)
            lineTo(8.0f, 9.0f)
            close()
            moveTo(7.0f, 11.0f)
            lineTo(5.0f, 11.0f)
            lineTo(5.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(7.0f, 8.0f)
            lineTo(5.0f, 8.0f)
            lineTo(5.0f, 6.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(16.0f, 15.0f)
            lineTo(8.0f, 15.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(16.0f, 11.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(14.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(16.0f, 8.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(14.0f, 6.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(19.0f, 11.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(17.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(19.0f, 8.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(17.0f, 6.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(12.0f, 23.0f)
            lineToRelative(4.0f, -4.0f)
            lineTo(8.0f, 19.0f)
            lineToRelative(4.0f, 4.0f)
            close()
                }
            }
            return _keyboardHide!!
        }
    private var _keyboardHide: ImageVector? = null

    val Terminal: ImageVector
        get() {
            if (_terminal != null) return _terminal!!
            _terminal = materialIcon(name = "Filled.Terminal") {
                materialPath {
            moveTo(20.0f, 4.0f)
            horizontalLineTo(4.0f)
            curveTo(2.89f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(6.0f)
            curveTo(22.0f, 4.9f, 21.11f, 4.0f, 20.0f, 4.0f)
            close()
            moveTo(20.0f, 18.0f)
            horizontalLineTo(4.0f)
            verticalLineTo(8.0f)
            horizontalLineToRelative(16.0f)
            verticalLineTo(18.0f)
            close()
            moveTo(18.0f, 17.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(17.0f)
            close()
            moveTo(7.5f, 17.0f)
            lineToRelative(-1.41f, -1.41f)
            lineTo(8.67f, 13.0f)
            lineToRelative(-2.59f, -2.59f)
            lineTo(7.5f, 9.0f)
            lineToRelative(4.0f, 4.0f)
            lineTo(7.5f, 17.0f)
            close()
                }
            }
            return _terminal!!
        }
    private var _terminal: ImageVector? = null

    val ContentCopy: ImageVector
        get() {
            if (_contentCopy != null) return _contentCopy!!
            _contentCopy = materialIcon(name = "Filled.ContentCopy") {
                materialPath {
            moveTo(16.0f, 1.0f)
            lineTo(4.0f, 1.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            horizontalLineToRelative(2.0f)
            lineTo(4.0f, 3.0f)
            horizontalLineToRelative(12.0f)
            lineTo(16.0f, 1.0f)
            close()
            moveTo(19.0f, 5.0f)
            lineTo(8.0f, 5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(11.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 7.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 21.0f)
            lineTo(8.0f, 21.0f)
            lineTo(8.0f, 7.0f)
            horizontalLineToRelative(11.0f)
            verticalLineToRelative(14.0f)
            close()
                }
            }
            return _contentCopy!!
        }
    private var _contentCopy: ImageVector? = null

    val Draw: ImageVector
        get() {
            if (_draw != null) return _draw!!
            _draw = materialIcon(name = "Filled.Draw") {
                materialPath {
            moveTo(18.85f, 10.39f)
            lineToRelative(1.06f, -1.06f)
            curveToRelative(0.78f, -0.78f, 0.78f, -2.05f, 0.0f, -2.83f)
            lineTo(18.5f, 5.09f)
            curveToRelative(-0.78f, -0.78f, -2.05f, -0.78f, -2.83f, 0.0f)
            lineToRelative(-1.06f, 1.06f)
            lineTo(18.85f, 10.39f)
            close()
            moveTo(13.19f, 7.56f)
            lineTo(4.0f, 16.76f)
            verticalLineTo(21.0f)
            horizontalLineToRelative(4.24f)
            lineToRelative(9.19f, -9.19f)
            lineTo(13.19f, 7.56f)
            close()
            moveTo(19.0f, 17.5f)
            curveToRelative(0.0f, 2.19f, -2.54f, 3.5f, -5.0f, 3.5f)
            curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
            reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
            curveToRelative(1.54f, 0.0f, 3.0f, -0.73f, 3.0f, -1.5f)
            curveToRelative(0.0f, -0.47f, -0.48f, -0.87f, -1.23f, -1.2f)
            lineToRelative(1.48f, -1.48f)
            curveTo(18.32f, 15.45f, 19.0f, 16.29f, 19.0f, 17.5f)
            close()
            moveTo(4.58f, 13.35f)
            curveTo(3.61f, 12.79f, 3.0f, 12.06f, 3.0f, 11.0f)
            curveToRelative(0.0f, -1.8f, 1.89f, -2.63f, 3.56f, -3.36f)
            curveTo(7.59f, 7.18f, 9.0f, 6.56f, 9.0f, 6.0f)
            curveToRelative(0.0f, -0.41f, -0.78f, -1.0f, -2.0f, -1.0f)
            curveTo(5.74f, 5.0f, 5.2f, 5.61f, 5.17f, 5.64f)
            curveTo(4.82f, 6.05f, 4.19f, 6.1f, 3.77f, 5.76f)
            curveTo(3.36f, 5.42f, 3.28f, 4.81f, 3.62f, 4.38f)
            curveTo(3.73f, 4.24f, 4.76f, 3.0f, 7.0f, 3.0f)
            curveToRelative(2.24f, 0.0f, 4.0f, 1.32f, 4.0f, 3.0f)
            curveToRelative(0.0f, 1.87f, -1.93f, 2.72f, -3.64f, 3.47f)
            curveTo(6.42f, 9.88f, 5.0f, 10.5f, 5.0f, 11.0f)
            curveToRelative(0.0f, 0.31f, 0.43f, 0.6f, 1.07f, 0.86f)
            lineTo(4.58f, 13.35f)
            close()
                }
            }
            return _draw!!
        }
    private var _draw: ImageVector? = null

    val Usb: ImageVector
        get() {
            if (_usb != null) return _usb!!
            _usb = materialIcon(name = "Filled.Usb") {
                materialPath {
            moveTo(15.0f, 7.0f)
            verticalLineToRelative(4.0f)
            horizontalLineToRelative(1.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-3.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(2.0f)
            lineToRelative(-3.0f, -4.0f)
            lineToRelative(-3.0f, 4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(8.0f)
            horizontalLineTo(8.0f)
            verticalLineToRelative(-2.07f)
            curveToRelative(0.7f, -0.37f, 1.2f, -1.08f, 1.2f, -1.93f)
            curveToRelative(0.0f, -1.21f, -0.99f, -2.2f, -2.2f, -2.2f)
            curveToRelative(-1.21f, 0.0f, -2.2f, 0.99f, -2.2f, 2.2f)
            curveToRelative(0.0f, 0.85f, 0.5f, 1.56f, 1.2f, 1.93f)
            verticalLineTo(13.0f)
            curveToRelative(0.0f, 1.11f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(3.05f)
            curveToRelative(-0.71f, 0.37f, -1.2f, 1.1f, -1.2f, 1.95f)
            curveToRelative(0.0f, 1.22f, 0.99f, 2.2f, 2.2f, 2.2f)
            curveToRelative(1.21f, 0.0f, 2.2f, -0.98f, 2.2f, -2.2f)
            curveToRelative(0.0f, -0.85f, -0.49f, -1.58f, -1.2f, -1.95f)
            verticalLineTo(15.0f)
            horizontalLineToRelative(3.0f)
            curveToRelative(1.11f, 0.0f, 2.0f, -0.89f, 2.0f, -2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(1.0f)
            verticalLineTo(7.0f)
            horizontalLineToRelative(-4.0f)
            close()
                }
            }
            return _usb!!
        }
    private var _usb: ImageVector? = null

    val Mouse: ImageVector
        get() {
            if (_mouse != null) return _mouse!!
            _mouse = materialIcon(name = "Filled.Mouse") {
                materialPath {
            moveTo(13.0f, 1.07f)
            lineTo(13.0f, 9.0f)
            horizontalLineToRelative(7.0f)
            curveToRelative(0.0f, -4.08f, -3.05f, -7.44f, -7.0f, -7.93f)
            close()
            moveTo(4.0f, 15.0f)
            curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
            reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
            verticalLineToRelative(-4.0f)
            lineTo(4.0f, 11.0f)
            verticalLineToRelative(4.0f)
            close()
            moveTo(11.0f, 1.07f)
            curveTo(7.05f, 1.56f, 4.0f, 4.92f, 4.0f, 9.0f)
            horizontalLineToRelative(7.0f)
            lineTo(11.0f, 1.07f)
            close()
                }
            }
            return _mouse!!
        }
    private var _mouse: ImageVector? = null

    val TouchApp: ImageVector
        get() {
            if (_touchApp != null) return _touchApp!!
            _touchApp = materialIcon(name = "Filled.TouchApp") {
                materialPath {
            moveTo(9.0f, 11.24f)
            verticalLineTo(7.5f)
            curveTo(9.0f, 6.12f, 10.12f, 5.0f, 11.5f, 5.0f)
            reflectiveCurveTo(14.0f, 6.12f, 14.0f, 7.5f)
            verticalLineToRelative(3.74f)
            curveToRelative(1.21f, -0.81f, 2.0f, -2.18f, 2.0f, -3.74f)
            curveTo(16.0f, 5.01f, 13.99f, 3.0f, 11.5f, 3.0f)
            reflectiveCurveTo(7.0f, 5.01f, 7.0f, 7.5f)
            curveTo(7.0f, 9.06f, 7.79f, 10.43f, 9.0f, 11.24f)
            close()
            moveTo(18.84f, 15.87f)
            lineToRelative(-4.54f, -2.26f)
            curveToRelative(-0.17f, -0.07f, -0.35f, -0.11f, -0.54f, -0.11f)
            horizontalLineTo(13.0f)
            verticalLineToRelative(-6.0f)
            curveTo(13.0f, 6.67f, 12.33f, 6.0f, 11.5f, 6.0f)
            reflectiveCurveTo(10.0f, 6.67f, 10.0f, 7.5f)
            verticalLineToRelative(10.74f)
            curveToRelative(-3.6f, -0.76f, -3.54f, -0.75f, -3.67f, -0.75f)
            curveToRelative(-0.31f, 0.0f, -0.59f, 0.13f, -0.79f, 0.33f)
            lineToRelative(-0.79f, 0.8f)
            lineToRelative(4.94f, 4.94f)
            curveTo(9.96f, 23.83f, 10.34f, 24.0f, 10.75f, 24.0f)
            horizontalLineToRelative(6.79f)
            curveToRelative(0.75f, 0.0f, 1.33f, -0.55f, 1.44f, -1.28f)
            lineToRelative(0.75f, -5.27f)
            curveToRelative(0.01f, -0.07f, 0.02f, -0.14f, 0.02f, -0.2f)
            curveTo(19.75f, 16.63f, 19.37f, 16.09f, 18.84f, 15.87f)
            close()
                }
            }
            return _touchApp!!
        }
    private var _touchApp: ImageVector? = null

}
