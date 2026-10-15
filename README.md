# Linux Desktop for Android

An Android app that runs a real Debian (trixie, arm64) desktop with XFCE through proot,
shown full-screen in the app with touchpad-style mouse control.

## Build

    export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk   # JDK 17 (JDK 21 breaks d8 here)
    bash build.sh            # -> linux-desktop.apk (signed, debug key)
    # SDK packages: platforms;android-34 and build-tools;34.0.0

Native libraries are prebuilt:
- `prebuilt/<abi>/libproot.so`: proot 5.4.0 built with the NDK (`build-proot.sh`).
- `prebuilt/<abi>/libbusybox.so`: busybox 1.36.1 (tar, gzip). Built by the termish project.

## Rootfs

    bash tools/build-rootfs.sh

Builds the Debian trixie arm64 rootfs (debootstrap + XFCE, Xvfb, x11vnc, Firefox ESR,
Thunar, Mousepad, xterm) and writes `rootfs-debian-trixie-arm64.tar.gz` (about 300 MB) and a
`.sha256` file. Output goes to `$WORK` (default `/var/tmp/arena-storage/desk`), outside the
workspace. It needs sudo, debootstrap, and qemu-user-static with aarch64 binfmt registered.

The archives are not downloaded by the app. `build.sh` packages them into the APK as stored
(uncompressed) assets, so the phone only unpacks them. Build the three archives first, then:

    bash build.sh   # reads ASSETS_DIR (default /var/tmp/arena-storage/desk), writes APK_OUT

## Screens and flow

1. **Loading screen (first run only):** an animated ring and a percentage, showing only the
   extraction of the Linux system and then the Windows add-on. Nothing else is on the screen.
2. **Login screen:** the desktop opens on a sign-in page, like a PC. On the very first run it
   asks you to create a password. After that it asks for that password.
3. **Desktop:** after signing in you get the full XFCE desktop with shortcuts for the
   preinstalled apps (Terminal, Firefox, Text Editor, Files, and Windows Notepad / Explorer
   when the Windows add-on is installed).

Every time the app opens the desktop, the login screen comes up first. Closing the desktop
(Back) closes the app. The next launch starts from the loading screen again, and the desktop
asks for the password before showing anything.

The login screen is a small Tk program (`/usr/local/lib/desktop-login/greeter.py`). The
password is stored as PBKDF2-SHA256 in the rootfs, not in the app. The app does not need
PAM, because Android apps cannot read `/etc/shadow`.

The archives come from the APK's assets. They are unpacked while they are read, and their
SHA-256 values are checked. There is no settings screen and no network download.

## Developer tools (preinstalled)

The developer tools come in a third archive, `rootfs-agents-arm64.tar.gz`. It is installed after
the Windows add-on, so the Windows add-on must install first. Desktop shortcuts appear for each tool
that is installed.

- **Claude Code** (`claude`): the native build from npm `@anthropic-ai/claude-code`. Sign in with your
  Anthropic account when it starts.
- **Codex CLI** (`codex`): npm `@openai/codex`. Sign in with your ChatGPT account or an API key.
- **Freebuff CLI** (`freebuff`): npm `freebuff`. It needs no account, but it downloads its own binary
  the first time it runs, so it needs internet access.
- **Freebuff Desktop**: the Linux arm64 AppImage, unpacked to `/opt/freebuff` at build time and started
  with `--no-sandbox`.
- **VS Code**: Microsoft's `server-linux-arm64` build in `/opt/vscode-server`. The "VS Code" shortcut
  starts it on `127.0.0.1:8000` with a random connection token, then opens it in Firefox.

Size: this archive is about 680 MB, and the tools add about 1.6 GB on disk. Node.js and npm come
from Debian. Claude Code's musl build and most FreeBuff locales are removed.

## Using the desktop on the phone

- **Mouse (touchpad mode):** dragging one finger moves the cursor by the same amount.
  Touching the screen does not jump the cursor to your finger.
  - Tap: left click at the cursor.
  - Tap, then tap-and-drag: drag with the left button held.
  - Long press: right click.
  - Two-finger tap: right click. Two-finger drag: scroll.
- **Controller bar (bottom):** L, M, R hold the mouse buttons while pressed, so you can drag
  with them. Esc, Tab and Enter send keys. Ctrl, Alt, Shift and Super are sticky toggles.
  Kbd opens the keyboard. "Speed" cycles pointer speed (1 to 4).
- **Arrow (top right):** hides or shows the controller bar.
- Hardware keyboards and the soft keyboard send keys to the desktop.
- The desktop is 1280x720 and is scaled to fit the screen. The app reconnects if the
  connection drops while the desktop is still running.

## How it works

1. **Install:** the app unpacks the three archives from its APK assets into its files directory,
   checks SHA-256 for each, and unpacks them in Java. busybox picks its applet from argv[0], so it's called
   through a `tar` symlink.
2. **Start:** proot runs `/usr/local/bin/desktop-start` inside the rootfs. That starts Xvfb on
   :1, x11vnc on 127.0.0.1:5901 with a random per-launch password (and `-nocursor`, so the
   app draws its own cursor), and XFCE.
3. **Show:** `DesktopActivity` connects with `app/src/app/desktop/VncClient.java` and draws
   the framebuffer scaled to fit.
4. **Launcher:** the main screen starts apps (terminal, Firefox, text editor, files, xterm)
   on the desktop display through `app-run`.
5. **Stop:** `desktop-stop` kills XFCE, x11vnc and Xvfb, then the proot process is killed.

## Verified

- **VNC client against a live x11vnc (host):** `tests/VncTest.java` passes. It covers the
  handshake, VNC password auth, the first frame, pixel decoding, pointer and key events, and
  rejection of a wrong password.
- **XFCE desktop:** the desktop starts in the rootfs under qemu. Xvfb, x11vnc, XFCE and dbus
  run. `tests/desktop-screenshot.png` is a framebuffer capture of the XFCE desktop.
- **No cursor in the frame:** with `-nocursor`, the server does not paint its cursor into the
  picture, so the app's own cursor is the only one.
- **Unpacking:** the archive unpacks with busybox tar as a non-root user.
- **APK:** `linux-desktop.apk` (about 2.4 MB) builds with `build.sh`, signed with the debug
  key. The manifest sets min SDK 26 and target SDK 28. The target is kept at 28 so the
  rootfs binaries in the files directory can still be executed. It includes arm64-v8a and
  x86_64 libraries.

## Not verified

- **Nothing has run on an Android phone.** That covers proot inside the app (ptrace and
  SELinux), the install from the app, the VNC connection from the app, and the touch controls.
  Test on a real device before relying on it.
- **Audio:** not set up. There's no PulseAudio or PipeWire in the rootfs.
- **Touch controls on a real screen:** the touchpad logic was written from the design, not
  tested by touching a device.

## Windows apps (Wine add-on)

Windows support is a separate add-on archive, so the base rootfs does not grow by about 1 GB.

    bash tools/build-wine-addon.sh      # needs the base rootfs from build-rootfs.sh
    # -> rootfs-wine-addon-arm64.tar.gz (about 320 MB, overlay of new files only)
    # set INCLUDE_PREFIX=1 to also ship the initialized Wine prefix (+237 MB, faster first run)

The add-on adds:
- `box64` 0.3.4 (arm64, Debian trixie): runs x86_64 code on arm64.
- `wine64:amd64` and `libwine:amd64` 10.0 (Debian trixie, multiarch amd64 packages).
- `/usr/local/bin/wine-run`: runs `box64 /usr/lib/wine/wine64 <program>`.

In the app: fill in "Windows add-on URL" (https) and optional SHA-256, then tap
"Add Windows support". The add-on is unpacked over the installed rootfs. The base rootfs must
be installed first. Installing a new base rootfs clears the Windows flag, so add the add-on
again. The launcher's "Windows Explorer" and "Notepad (Windows)" entries stay disabled until
the add-on is installed.

Disk use on the phone: base about 1 GB unpacked, add-on about 1 GB unpacked, plus the Wine
prefix (created on first `wine-run` if not shipped).

### Verified in the sandbox (qemu-user, arm64 rootfs)

- `wine-run --version`: `wine-10.0 (Debian 10.0~repack-6)`.
- `wine-run cmd /c "echo WINE_OK && ver"`: prints `WINE_OK` and `Microsoft Windows 10.0.19043`.
- `wine-run notepad` on Xvfb `:1` in the chroot: creates the X window "Untitled - Notepad"
  (checked with `xwininfo -root -tree`).
- The add-on archive passes `gzip -t` and extracts cleanly with GNU tar to 975 MB, with
  `wine-run` and `box64` mode 755.

### Not verified

- **Box64 on a real phone:** all tests ran under qemu-user on x86_64, which emulates the arm64
  rootfs and so adds a second layer of emulation. Real Android arm64 speed and box64 dynarec
  behaviour are unknown. If box64 crashes on a device, try `BOX64_DYNAREC=0` (interpreter,
  slower).
- **Proot on Android:** the `wine-run` path has not been run through the app's proot. The
  prebuilt `libproot.so` and `libbusybox.so` are Android-linked (bionic), so they cannot run on
  this Linux host.
- **Graphics and performance:** Wine uses the X server only (no GPU). Heavy 3D apps will be slow.

## Not done

- **Android emulator:** not run. This sandbox has no KVM (`/dev/kvm` is absent), 2 vCPUs and
  about 1.9 GB RAM. An Android emulator without hardware acceleration would be far too slow to
  boot, so the APK still needs a real phone test.
- **Storage:** the APK is about 1.4 GB, and the unpacked system is about 4 GB. The phone needs
  roughly 5.5 GB free during install. The archives in `/var/tmp/arena-storage` do not persist
  across sandbox resets, so back them up before a reset.
