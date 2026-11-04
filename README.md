<p align="center">
  <img src="docs/icon.png" width="128" height="128" alt="Arrowroot Icon" />
</p>

<h1 align="center">Arrowroot 🏹🌱</h1>
<p align="center">
  <strong>Kernel-Level Hardware Trackpad &amp; Pointer Engine for Rooted Android</strong><br/>
  <em>v1.3.0 • Linux <code>/dev/uinput</code> + <code>EVIOCGRAB</code> + Material You (Monet)</em>
</p>

---

**Arrowroot** *(formerly Virtual Mouse)* transforms your rooted Android screen into a zero-latency laptop-style trackpad with a genuine, system-wide OS mouse cursor arrow, multi-touch gestures, and smart soft-keyboard automation.

Built specifically for rooted Android devices (**Magisk**, **KernelSU**, **APatch**) using the Linux kernel's **`/dev/uinput`** and **`EVIOCGRAB`** subsystems.

---

## ✨ Key Features in v1.3.0

1. **Genuine `/dev/uinput` Hardware Pointer**:
   - Registers a real virtual USB mouse (`Arrowroot Hardware Pointer`) inside the Linux kernel.
   - Android renders the native OS arrow cursor across all apps, games, system dialogs, and split-screen windows with sub-pixel velocity acceleration.

2. **Kernel `EVIOCGRAB` Touchscreen Capture & Slot Flushing**:
   - Captures raw multi-touch slots directly from `/dev/input/eventX` (`INPUT_PROP_DIRECT`) without any full-screen overlay window blocking clicks.
   - Cleanly flushes all 10 multi-touch slots (`ABS_MT_SLOT 0..9 -> -1`) across mode transitions so Android's `InputReader` never retains ghost fingers.

3. **Session-Authenticated Loopback IPC**:
   - Every daemon session is protected by a 32-bit cryptographic session token (`SecureRandom`) over a thread-safe JNI bridge, with strict `0700` root-only binary permissions and graceful `SIGTERM` teardown.

4. **Multi-Touch Gestures**:
   - **1-Finger Glide & Tap**: Move the native mouse cursor and tap to Left Click (`BTN_LEFT`).
   - **Bi-Directional 1-Finger Hold + 2nd-Finger Drag**: Hold one finger stationary on the glass (`BTN_LEFT=1`) and slide a second finger to drag icons, sliders, or text selections (with multi-stroke support).
   - **2-Finger Tap & Hi-Res Scroll**: Tap two fingers for Right Click (`BTN_RIGHT`); glide two fingers together for smooth vertical and horizontal wheel scrolling (`REL_WHEEL_HI_RES` / `REL_HWHEEL_HI_RES`).
   - **Bi-Directional 3-Finger Tap**: Tap three fingers anywhere on screen to swap between **Trackpad Mode** and **Normal Touch Mode** in either direction.

5. **Smart Keyboard Auto-Touch & Floating Dock**:
   - Combines an instant 1px `WindowInsets` IME sensor with a lifecycle-bound root IME monitor to automatically switch to **Touch Mode (`TOUCH • KB`)** when the keyboard opens and restore **Trackpad Mode** when it closes.
   - Draggable floating pill dock (`L`, `ARROWROOT MODE`, `R`, `✕`) with persistent position memory, screen-bounds clamping, and **double-tap on the grip** to snap back to bottom-center.

---

## 🛠️ Requirements

- **Android 8.0 (API 26)** through **Android 15+**.
- **Root access** (**Magisk**, **KernelSU**, or **APatch**) with `/dev/uinput` kernel support.
- **Display over other apps** (`SYSTEM_ALERT_WINDOW`) permission.

---

## 📱 Quick Start

1. Download and install the latest **`Arrowroot-v1.3.0-build-*.apk`** from the [**Releases**](../../releases) page.
2. Open **Arrowroot** and grant **Superuser (Root)** permission when prompted by KernelSU / Magisk / APatch.
3. Tap **Grant Overlay Permission** if not yet enabled.
4. Verify that the **System Diagnostics** card shows `Superuser: Granted`, `Kernel uinput: Ready`, and `Overlay: Enabled`.
5. Tap **Start Arrowroot** (or toggle the **Arrowroot** Quick Settings tile in your notification shade).
6. Use a **3-finger tap** or the floating dock's **MODE** button anytime to switch between **Trackpad** and **Touch** mode!
