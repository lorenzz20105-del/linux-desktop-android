# Linux Desktop for Android

An Android app that runs a Debian trixie **arm64** XFCE desktop under proot and displays it through an in-app VNC viewer.

## Build

```sh
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
bash tools/build-rootfs.sh
bash tools/build-wine-addon.sh
bash tools/build-agents-addon.sh
bash build.sh
```

`build.sh` expects the three archives in `ASSETS_DIR` (default `/var/tmp/arena-storage/desk`) and writes the APK to `APK_OUT` (default `/var/tmp/arena-storage/linux-desktop.apk`). It compiles Java with JDK 11 or 17 and Android SDK platform 34 / build-tools 34.0.0. The build computes SHA-256 values from the actual archives, packages the archives as stored APK assets, and includes only arm64 Android libraries. Rootfs archives and build output are intentionally kept outside Git.

The shipped arm64 proot is Termux proot 5.1.107.96, dynamically linked to the bundled `libtalloc.so` and `libandroid-shmem.so`. Rebuild it with `NDK=/path/to/android-ndk-r27c tools/build-proot.sh`. **Do not use the legacy root-level `build-proot.sh` recipe**; it is retained as a compatibility wrapper to the current recipe. `libbusybox.so` is presently bundled but unused by the Java installer.

The APK is debug-signed. The generated `debug.keystore` is local and ignored by Git; back it up if you need to issue in-place updates from the same signing identity. This is a sideload/development build, not a Play Store release.

## Startup and use

On first launch, the app extracts the base system, Windows add-on and developer-tools add-on from APK assets. Extraction is performed in Java and verified against build-generated SHA-256 hashes. The app then runs diagnostics, starts Xvfb `:1`, x11vnc on `127.0.0.1:5901`, and XFCE under proot, and connects its VNC viewer. The desktop shows a Tk password prompt; first use creates a password stored as PBKDF2-SHA256 in the guest rootfs. The controller lets you move the cursor with one finger, click by tapping, right-click with a long press or two-finger tap, scroll with two fingers, and send keys using its bar or the keyboard.

Back closes the desktop view/app; it does not stop the guest session. On a later open the app attempts to show the guest login prompt again. **This behavior and process survival have not been validated on a physical Android device.**

### Security limitations

- The VNC server is bound to loopback TCP, which is reachable by other apps running under the same Android user. VNC's legacy DES password scheme is weak and the login prompt is not a secure OS boundary. Do not enter secrets or treat it as a security lock screen.
- VS Code Server uses a token and loopback, but currently uses a fixed port (8000).
- Apps run as guest root through proot, not as an isolated Android user. The project is not a security sandbox.

## Optional add-ons

- **Windows support:** `tools/build-wine-addon.sh` creates an overlay adding Box64 and Wine. It must be built after the base rootfs. `INCLUDE_PREFIX=1` includes an initialized Wine prefix; otherwise it is created on first run.
- **Developer tools:** `tools/build-agents-addon.sh` requires the base and Wine add-on first. It adds Node/npm, Claude Code, Codex CLI, Freebuff CLI/Desktop and VS Code Server. These upstream inputs are not all pinned, so builds are not yet reproducible; check the vendors' licenses and terms before redistribution. Claude Code is proprietary software.

Install order is base → Wine → agents. The app has no URL-based add-on installer or settings screen; all three archives are packaged into the APK. Add-on archives are overlays and cannot represent deletions. The build scripts use mutable upstream inputs for some components.

## Repository map

- `app/src/app/desktop/`: Android activities, installer, proot session, touch view and minimal RFB client.
- `rootfs/usr/local/bin/`: guest launch/lock scripts; `rootfs/usr/local/lib/desktop-login/greeter.py`: guest login prompt.
- `tools/build-rootfs.sh`, `tools/build-wine-addon.sh`, `tools/build-agents-addon.sh`: rootfs and overlays.
- `tools/build-proot.sh`: current arm64 native proot build.
- `tools/seccomp-sim/`, `tools/syscalltest/`: seccomp investigation tools. The syscall test binary is diagnostic and is not shipped in the APK.
- `tests/VncTest.java`, `tests/VncShot.java`: host-side VNC client tests/utilities.

## Verification status and known constraints

The repository contains a VNC test and an XFCE screenshot from sandbox/qemu testing. `tools/seccomp-sim/RESULTS.md` also records tests performed on an Infinix X6528 / Android 13. **The complete Android app flow, installer, current proot build, VNC client and touch controls have not been verified end-to-end on a physical phone.** Audio is not configured. The guest is arm64-only; x86_64 Android libraries that previously shipped did not provide an x86_64 Debian guest and are no longer packaged. The checked-in arm64 `libproot.so` still has ELF load segments not aligned for 16 KB pages, so that prebuilt may fail on 16 KB page-size devices. `tools/build-proot.sh` now requests 16 KB linker alignment; rebuild and verify the prebuilt before targeting those devices.

Storage is substantial: the APK is roughly 1.4 GB with the current archives; extraction needs several additional gigabytes. Exact sizes vary by build. A foreground service now keeps the Android app process prioritized while the guest is running, with a Stop action in its notification. Android or device-vendor process policies may still stop child processes; validate session survival on each target device.
