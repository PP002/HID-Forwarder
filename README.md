# HID Forwarder (USB OTG → Bluetooth HID Relay)

An Android Jetpack Compose utility that turns your Android phone into a **Bluetooth HID peripheral relay**—forwarding **USB OTG keyboards, USB OTG mice, and Wacom drawing tablets** (plus an on-screen QWERTY soft keyboard and Touchpad) wirelessly to any Bluetooth host such as a PC, Mac, Linux machine, tablet, e-reader, or another phone.

The Android app in `app/` is **100% non-root and Google Play Store compliant** (zero `su` calls, zero native PIE binaries). Optional root-level `EVIOCGRAB` helpers are separated into `/root-helper/` for advanced ADB/root setups over the app's local TCP relay (`127.0.0.1:9876`).

---

## Core Features

### 1. Multi-OTG USB Hub Concurrency
Plug a powered or passive **USB OTG Hub** into your Android phone to connect **multiple USB input devices simultaneously** (e.g., keyboards + mice + a Wacom tablet):
- Hotplug supervisor discovers newly attached or detached devices independently—unplugging one device releases only its own state without interrupting others.
- Per-source modifier, key-rollover, and mouse-button states are OR-merged cleanly before transmission.
- Sibling-safe USB interface handling ensures unified wireless dongles keep keyboard and mouse interfaces working together.

### 2. USB OTG & Screen Keyboard → Bluetooth Keyboard (Report ID `1`)
- **Non-Root Foreground USB Relay (`UsbKeyboardManager`)**: Uses Android `InputManager` hotplug detection and forwards external hardware `KeyEvent`s per `deviceId` whenever the app is in foreground.
- **On-Screen QWERTY Live Relay (`HidTextInputView`)**: Disables phone-side IME composition (`TYPE_NULL`) and forwards raw keycodes over Bluetooth HID so the target host handles its own layout/IME (e.g., CJK, Cangjie, Japanese, dead keys).
- **Local TCP Socket Relay (`127.0.0.1:9876`)**: Accepts raw 8-byte HID keyboard reports (`[modifier, reserved, k1..k6]`) from external local clients such as `/root-helper/evgrab_relay.sh`.

### 3. USB OTG Mouse, Wireless Dongle & Touchpad → Bluetooth Mouse (Report ID `3`)
- **Non-Root Multi-OTG USB Mouse Reader (`MouseUsbManager`)**:
  - Uses Android `UsbManager` HID Interrupt-IN pipelining (`UsbRequest` 4-deep queue + `bulkTransfer` fallback).
  - Parses standard **Boot Mice** (`subclass=1, protocol=2`) and dynamically decodes **custom HID Report Descriptors** (`GET_DESCRIPTOR 0x2200`).
- **Built-in Touchpad & Pointer Capture**:
  - **Touchpad gestures**: 1-finger drag to move cursor, tap for Left Click, 2-finger tap for Right Click, 2-finger vertical drag to scroll, plus Left / Middle / Right / Page Up / Page Down controls.
  - **Pointer Capture (`View.requestPointerCapture()`)**: Non-root mouse capture mode using Android's `SOURCE_MOUSE_RELATIVE` deltas + configurable sensitivity (`0.5x`, `1.0x`, `1.5x`, `2.5x`).

### 4. USB OTG Wacom Drawing Tablet → Bluetooth Digitizer Pen (Report ID `2`)
- Native Android USB Host support for **Wacom CTL-471 (One / Bamboo)** and compatible Wacom OTG tablets (`0x056A`):
  - Automatically unlocks Wacom native pen mode (`Feature Report 0x02`) over USB control transfer.
  - Relays X/Y coordinates, tip/hover/eraser flags, barrel buttons, and 16-bit scaled pen pressure.
  - Supports **4 board orientations** (`0°`, `270° USB Top`, `90°`, `180°`), **pressure curves** (`Gamma 1.2`, `Linear 1.0`), and **barrel button mapping**.

---

## Optional External Root Helper (`/root-helper/`)

For background/screen-off exclusive keyboard/mouse capture via Linux `/dev/input/event*` (`EVIOCGRAB`), build and run the standalone C helper located in `/root-helper/` separately from the Android app:
- `/root-helper/evgrab.c`
- `/root-helper/evgrab_relay.sh`
- See `/root-helper/README.md` for NDK/cross-compile and `127.0.0.1:9876` usage instructions.

---

## Play Store Release Build via GitHub Actions

The workflow at `.github/workflows/build-play-aab.yml` runs unit tests (`gradle :app:testDebugUnitTest`) and builds a signed Android App Bundle (`:app:bundleRelease`) ready for Google Play Console (`com.benkollo.hidfwd`).

### Required GitHub Repository Secrets
Configure these in **Settings → Secrets and variables → Actions**:
1. `UPLOAD_KEYSTORE_BASE64`: Base64-encoded Play upload keystore (with key alias `upload`, e.g. `base64 -w 0 my-upload-key.jks`).
2. `STORE_PASSWORD`: Keystore password.
3. `KEY_PASSWORD`: Key password for alias `upload`.
4. `PLAY_SERVICE_ACCOUNT_JSON` *(optional)*: Google Play Console service account JSON to automatically upload draft bundles to the `internal` track.

---

## Local Build & Unit Tests

- **Requirements**: JDK 21, Android SDK (`minSdk 28`, `targetSdk`/`compileSdk 36`), Gradle `9.3.1` (no NDK/CMake required).
- **Build Debug APK**:
  ```bash
  gradle :app:assembleDebug
  ```
- **Run JVM / Robolectric Unit Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
