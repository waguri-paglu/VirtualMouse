# Virtual Mouse for Rooted Android 🖱️📱

Turn your entire mobile screen into a responsive laptop-style trackpad with a genuine, system-wide mouse cursor arrow and full left/right-click capabilities.

Built specifically for rooted Android devices (Magisk, KernelSU, APatch) leveraging the Linux kernel's **`/dev/uinput`** subsystem.

---

## 🌟 Key Features

1. **System-Wide Linux Kernel Mouse**:
   - Registers a virtual hardware mouse device using `/dev/uinput`.
   - Android automatically displays the official OS arrow cursor across every app, home screen, and system menu.
   - Near-zero latency with direct kernel event piping.

2. **Full-Screen Trackpad Surface**:
   - The entire screen captures touch motions and translates them into relative pointer coordinates ($dx, dy$).
   - Built-in pointer sensitivity and scroll speed customization.

3. **Multi-Touch Gestures**:
   - **1-Finger Move**: Moves the mouse arrow.
   - **1-Finger Tap**: Left click (`BTN_LEFT`).
   - **2-Finger Tap**: Right click (`BTN_RIGHT` - triggers native context menus/back).
   - **2-Finger Swipe**: Smooth vertical and horizontal scrolling (`REL_WHEEL`, `REL_HWHEEL`).
   - **Long-Press & Drag**: Holds left click down for dragging icons or selecting text.

4. **Floating Physical Buttons & Draggable Dock**:
   - **Left Click ("L") Button**: Dedicated thumb button (supports tap and hold-drag).
   - **Right Click ("R") Button**: Dedicated thumb button for instant context menus.
   - **Mode Toggle ("MODE")**: Instantly toggles between **Trackpad Mode** (capturing input) and **Touch Passthrough Mode** (lets you tap underlying apps normally without closing the service).
   - **Drag Handle**: Reposition the floating dock anywhere on your screen.

---

## 🛠️ Requirements

- Android 8.0 (Oreo) to Android 14 / 15+.
- **Root access** (Magisk, KernelSU, or APatch) with `/dev/uinput` kernel support.
- **Display over other apps** (System Overlay) permission.

---

## 🚀 GitHub Actions Setup (Automated APK Build & Release)

This repository includes a ready-to-run GitHub Actions workflow in `.github/workflows/build.yml`.

### How to use:
1. Create a new GitHub repository.
2. Unzip the project files and push them to your repository:
   ```bash
   git init
   git add .
   git commit -m "Initial commit: Virtual Mouse for Rooted Android"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo-name>.git
   git push -u origin main
   ```
3. To trigger a build and create an official GitHub Release:
   - Go to your repository's **Actions** tab.
   - Select **Build & Release Virtual Mouse APK** and click **Run workflow**.
   - *Or* simply push a Git tag (e.g., `git tag v1.0.0 && git push origin v1.0.0`).
4. GitHub Actions will build the native C daemon, compile the APK, sign it, and automatically publish the release under **Releases** with the downloadable `VirtualMouse-Release.apk`.

---

## 📱 How to Use on Your Device

1. Download and install `VirtualMouse-Release.apk`.
2. Open **Virtual Mouse**.
3. Grant **Root Access** when prompted by Magisk/KernelSU.
4. Tap **Grant Overlay Permission** and allow "Display over other apps".
5. Adjust pointer sensitivity and scroll speed to your preference.
6. Tap **Start Mouse**.
7. The native mouse arrow will appear, and the floating dock will be pinned at the bottom.
8. Use the **MODE** button whenever you need to temporarily tap the screen directly!
