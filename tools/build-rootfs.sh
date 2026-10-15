#!/usr/bin/env bash
# Builds the Debian trixie arm64 rootfs used by the desktop app.
# Output: $OUT_DIR/rootfs-debian-trixie-arm64.tar.gz (+ .sha256)
# Requires: sudo, debootstrap, qemu-user-static with binfmt registration for aarch64.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-/var/tmp/arena-storage/desk}
OUT_DIR=${OUT_DIR:-$WORK}
R=$WORK/rootfs-arm64
MIRROR=${MIRROR:-http://deb.debian.org/debian}

BASE_PKGS=bash,ca-certificates,locales,procps,nano,less,sudo
DESKTOP_PKGS="xvfb x11vnc xfce4 xfce4-terminal xfce4-session thunar mousepad dbus-daemon dbus-x11 \
fonts-dejavu-core xauth x11-utils xterm adwaita-icon-theme procps firefox-esr fonts-noto-core"

mkdir -p "$WORK" "$OUT_DIR"
if [ ! -f "$R/etc/debian_version" ]; then
  sudo rm -rf "$R"
  sudo debootstrap --arch=arm64 --variant=minbase --include="$BASE_PKGS" trixie "$R" "$MIRROR"
fi

sudo cp /etc/resolv.conf "$R/etc/resolv.conf"
sudo chroot "$R" /bin/sh -c "
  export DEBIAN_FRONTEND=noninteractive
  # dbus-user-session pulls systemd login support that does not configure under qemu. Keep it out.
  dpkg --remove --force-remove-reinstreq dbus-user-session libpam-systemd >/dev/null 2>&1 || true
  apt-get update
  apt-get install -y --no-install-recommends $DESKTOP_PKGS
  apt-get clean
  rm -rf /var/lib/apt/lists/*
"

# Desktop launcher scripts live inside the rootfs.
sudo install -m 0755 "$HERE/rootfs/usr/local/bin/desktop-start" "$R/usr/local/bin/desktop-start"
sudo install -m 0755 "$HERE/rootfs/usr/local/bin/desktop-stop" "$R/usr/local/bin/desktop-stop"
sudo install -m 0755 "$HERE/rootfs/usr/local/bin/app-run" "$R/usr/local/bin/app-run"

sudo rm -f "$R/etc/resolv.conf"
sudo mkdir -p "$R/tmp" "$R/tmp/.X11-unix"
sudo chmod 1777 "$R/tmp" "$R/tmp/.X11-unix"

ARCHIVE=$OUT_DIR/rootfs-debian-trixie-arm64.tar.gz
sudo tar --numeric-owner -czf "$ARCHIVE" -C "$R" .
sudo chown "$(id -u):$(id -g)" "$ARCHIVE"
(cd "$OUT_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
echo "wrote $ARCHIVE"
