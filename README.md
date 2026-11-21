# HID Forwarder (USB OTG → Bluetooth HID Relay)

An Android Jetpack Compose utility that turns your Android phone into a **Bluetooth HID peripheral relay**—forwarding **USB OTG keyboards, USB OTG mice, and Wacom drawing tablets** (plus an on-screen QWERTY soft keyboard and Touchpad) wirelessly to any Bluetooth host such as a PC, Mac, Linux machine, tablet, e-reader (Kindle), or another phone.

---

## Core Features

### 1. Multi-OTG USB Hub Concurrency
Plug a powered or passive **USB OTG Hub** into your Android phone to connect **multiple USB input devices simultaneously** (e.g., multiple keyboards + multiple mice + a Wacom tablet):
- Hotplug supervisor discovers newly attached or detached devices independently—unplugging one keyboard or mouse releases only that device's state without interrupting other connected devices.
- Per-source modifier, key-rollover, and mouse-button states are OR-merged cleanly before transmission so multiple keyboards/mice work seamlessly together.
- Sibling-safe USB interface handling ensures unified wireless dongles (e.g., keyboard + mouse combo receivers) keep both their keyboard interface and mouse interface working without tearing each other down.

### 2. USB OTG & Screen Keyboard → Bluetooth Keyboard (Report ID `1`)
- **Root Exclusive Grab (`libevgrab.so` / `EVIOCGRAB`)**: Captures all external USB keyboards (`EV_KEY + EV_REP`) exclusively at the Linux `/dev/input/event*` layer so keystrokes never trigger input on the phone itself—even in the background or with the screen off.
  - Press **`Ctrl + Alt + Q`** on any grabbed keyboard to immediately release the keyboard grab.
- **Non-Root Foreground USB Relay**: Intercepts external hardware `KeyEvent`s per `deviceId` whenever the app is open on non-rooted devices.
- **On-Screen QWERTY Live Relay (`HidTextInputView`)**: Disables phone-side IME composition (`TYPE_NULL`) and forwards raw keycodes over Bluetooth HID so the target host handles its own layout/IME (e.g., CJK, Cangjie, Japanese, dead keys).
- **Local TCP Socket Relay (`127.0.0.1:9876`)**: Accepts raw 8-byte HID keyboard packets from local helper clients (`[modifier, reserved, k1..k6]`).

### 3. USB OTG Mouse, Wireless Dongle & Touchpad → Bluetooth Mouse (Report ID `3`)
- **Non-Root Multi-OTG USB Mouse Reader (`MouseUsbManager`)**:
  - Uses Android `UsbManager` HID Interrupt-IN pipelining (`UsbRequest` 4-deep queue + `bulkTransfer` fallback).
  - Parses standard **Boot Mice** (`subclass=1, protocol=2`) and dynamically decodes **custom HID Report Descriptors** (`GET_DESCRIPTOR 0x2200`) including multi-button mice and 12-bit/16-bit high-DPI gaming mice.
  - Excludes Wacom tablets (`0x056A`) and Boot Keyboards (`subclass=1, protocol=1`) automatically.
- **Root Exclusive Mouse Grab (`evgrab --mouse`)**:
  - Locks `/dev/input/event*` mice exclusively via `EVIOCGRAB` and streams 4-byte reports (`[buttons, dx, dy, wheel]`), preventing a second Android cursor from moving on the phone screen.
- **Built-in Touchpad & Pointer Capture**:
  - **Touchpad gestures**: 1-finger drag to move cursor, tap for Left Click, 2-finger tap for Right Click, 2-finger vertical drag to scroll, plus tactile Left / Middle / Right hold buttons.
  - **Pointer Capture (`View.requestPointerCapture()`)**: Unrooted fallback for physical external USB mice using Android's captured `SOURCE_MOUSE_RELATIVE` deltas + configurable sensitivity (`0.5x`, `1.0x`, `1.5x`, `2.5x`).

### 4. USB OTG Wacom Drawing Tablet → Bluetooth Digitizer Pen (Report ID `2`)
- Native Android USB Host support for **Wacom CTL-471 (One / Bamboo)** and compatible Wacom OTG tablets (`0x056A`):
  - Automatically unlocks Wacom native pen mode (`Feature Report 0x02`) over USB control transfer.
  - Relays X/Y coordinates, tip/hover/eraser flags, barrel buttons, and 16-bit scaled pen pressure at high cadence (~125 Hz coalescing while preserving hover/tip transitions).
  - Supports **4 board orientations** (`0°`, `270° USB Top`, `90°`, `180°`), **pressure curves** (`Gamma 1.2`, `Linear 1.0`), and **barrel button mapping** (`Stylus Native` vs `Shortcuts`).

---

## Bluetooth HID Profiles & Report IDs

Switch profiles from the navigation drawer (`Menu & Settings`):

| Profile Mode | HID Subclass | Active Report IDs |
| :--- | :--- | :--- |
| **Combo (Default)** | Combo / Generic | `ID=1` Keyboard (8B) + `ID=2` Wacom Pen (7B) + `ID=3` Mouse (4B) — 223-byte SDP descriptor |
| **Keyboard Only** | Boot Keyboard (`0x40`) | `ID=1` (Report Mode) or `ID=0` (Boot / No-ID mode) |
| **Mouse Only** | Boot Mouse (`0x80`) | `ID=3` (Report Mode) or `ID=0` (Boot / No-ID mode) |
| **Wacom Pen Only** | Digitizer (`0x00`) | `ID=2` (7-byte absolute pen + pressure) |

- **Report ID Override** (`Auto` / `Force 1` / `Force 0`) is available in the drawer for target hosts that require fixed Report ID framing.

---

## Multilingual UI & Live Terminal Console

- All UI controls, status chips, foreground notifications, and real-time console logs dynamically switch across **10 languages** (`Auto`, `English`, `繁體中文`, `简体中文`, `日本語`, `한국어`, `Español`, `Français`, `Deutsch`, `Русский`, `Português`).
- Expandable OLED Pure Black terminal console fills the lower screen section for inspecting live Bluetooth HID SDP states, USB OTG attach/detach events, and TX diagnostics.

---

## Quick Usage

1. Grant Bluetooth (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`) and notification permissions on launch; expand **System Service Status** (or check its header summary) to verify **HID: Ready**.
2. Tap **Discoverable**, then pair and connect directly from your target computer/device's Bluetooth menu (tap **Paired Devices** to check existing bonded hosts).
3. Plug your USB OTG keyboard, mouse, Wacom CTL-471, or multi-port USB OTG hub into your phone:
   - **Keyboards**: Toggle **USB Keyboard Exclusive Grab (evgrab)** (Root) or leave the app open in foreground (Non-Root).
   - **Mice**: Tap **Start Mouse** (or the Mouse status chip) and grant Android USB Host permission if prompted; or expand **USB Mouse / Touchpad → BT Mouse** to use the Touchpad or **Capture Pointer**.
   - **Wacom**: Tap **Start Wacom** (or the Wacom status chip) and grant USB Host permission.

---

## Build & Unit Tests

- **Requirements**: Android SDK (minSdk `28`, targetSdk/compileSdk `36`), NDK / CMake (compiles `app/src/main/cpp/evgrab.c` into `libevgrab.so`).
- **Build Debug APK**:
  ```bash
  gradle :app:assembleDebug
  ```
- **Run JVM / Robolectric Unit Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
