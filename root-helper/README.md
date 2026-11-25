# Standalone Root Helper (`evgrab`)

All `su` / root execution code and `libevgrab.so` have been **separated from the Android app** so the Play Store release contains zero root binaries, zero `su` shell invocations, and zero native PIE executables.

The Play Store app handles:
- **USB OTG Keyboards** in foreground (no root required)
- **USB OTG Mice** via Android `UsbManager` + built-in Touchpad + Pointer Capture (no root required)
- **USB OTG Wacom Tablets** via Android `UsbManager` (no root required)
- **On-screen QWERTY Soft Keyboard** live relay (no root required)
- **Local TCP Socket Relay** listening on `127.0.0.1:9876` for external 8-byte Boot Keyboard reports `[modifier, 0, key1..key6]`

## Optional Background / Exclusive USB Keyboard Grab (Rooted Devices Only)

If you have a rooted Android device (or Termux with `tsu`) and want background `EVIOCGRAB` capture (so keystrokes do not type onto the phone itself):

1. Compile `evgrab.c` for your device ABI (example with Termux `clang` or Android NDK):
   ```sh
   clang -O2 -fPIE -pie evgrab.c -o evgrab
   ```
   Or cross-compile with Android NDK:
   ```sh
   $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang -O2 -fPIE -pie evgrab.c -o evgrab
   ```
2. Open **HID Forwarder** (which starts the local TCP server on `127.0.0.1:9876`), then run as root:
   ```sh
   su -c "sh /path/to/root-helper/evgrab_relay.sh /dev/input/eventN"
   ```
   Press **`Ctrl + Alt + Q`** on the grabbed USB keyboard at any time to release `EVIOCGRAB` and return normal input to the phone.
