# Changelog — Virtual Mouse for Rooted Android

All notable changes to this project are documented in this file.

## [v1.2.0] - 2026-11-01
### Added
- **Quick Settings (QS) Toggle Tile**: Added a single-tap Quick Settings tile (`VirtualMouseTileService`) in the notification shade to start and stop Virtual Mouse directly from anywhere.
- **Material You (Material 3) + Monet Dynamic Theme**: Complete UI overhaul inspired by Google Pixel & Google Play Store, with automatic Android 12+ wallpaper-based Dynamic Colors (`DynamicColors`).
- **2-Tab Navigation (`Home` & `Settings`)**: Decluttered the main screen into a clean Home dashboard and a dedicated Settings tab.
- **1-Finger Hold + 2nd-Finger Drag Gesture**: Hold 1st finger on the screen to lock Left Mouse Button (`BTN_LEFT=1`) down, and move a 2nd finger to drag the cursor across the screen (with multi-stroke support).
- **Bi-Directional 3-Finger Tap Mode Swap**: Tap with 3 fingers anywhere on the screen to switch between **Trackpad Mode** and **Normal Touch Mode** in both directions.
- **Auto-Touch Mode on Soft Keyboard (IME)**: Persistent root monitor (`dumpsys input_method`) + window inset listener automatically switches to **Touch Mode (`TOUCH (KB)`)** when Android waits for text input and the keyboard appears, then restores **Trackpad Mode** when the keyboard closes.
- **Hide Floating Mouse Buttons (`✕`)**: Added an on-dock `✕` button, a Settings switch, and a notification action (`Hide Buttons` / `Show Buttons`) to hide the floating mouse buttons completely.

### Fixed
- **Multi-Touch Slot Overwrite Bug (`ABS_X`/`ABS_Y`)**: Removed legacy single-touch `ABS_X`/`ABS_Y` fallbacks in `handle_touch_events()` that caused the Linux kernel single-touch pointer emulation (`Slot 0`) to overwrite `Slot 1` coordinates during 1-finger hold + 2nd-finger drag.
- **Display Context Crash on Android 11+**: Replaced `context.display` in `OverlayService` with `DisplayManager.getDisplay(Display.DEFAULT_DISPLAY)`.

## [v1.1.0] - 2026-10-31
### Added
- **Kernel `EVIOCGRAB` Touchscreen Capture**: Eliminated full-screen overlay window so `/dev/uinput` hardware clicks reach apps directly and Android `TouchInputMapper` never hides or teleports the hardware mouse cursor.
- **Zero-Latency C JNI Loopback UDP Bridge (`127.0.0.1:29876`)**: Sub-2-microsecond IPC between the app and root daemon.

## [v1.0.0] - 2026-10-30
### Added
- Initial release with `/dev/uinput` hardware mouse daemon (`EV_REL` + `BTN_LEFT`/`BTN_RIGHT`/`BTN_MIDDLE`) and floating overlay controls.
