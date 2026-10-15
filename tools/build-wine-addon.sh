#!/usr/bin/env bash
# Adds Windows app support to the Debian trixie arm64 rootfs:
#   - box64 (arm64, Debian trixie 0.3.4): runs x86_64 binaries on arm64
#   - wine64:amd64 (Debian trixie, Wine 10.0) and its x86_64 libraries, via dpkg multiarch
#   - /usr/local/bin/wine-run launcher
# Output is an OVERLAY archive: only files changed after the base rootfs was built.
# Extract it over the base rootfs to add Windows support.
# Output: $OUT_DIR/rootfs-wine-addon-arm64.tar.gz (+ .sha256)
# Requires: rootfs-arm64 built by build-rootfs.sh, sudo, qemu-user-static (aarch64 binfmt).
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-/var/tmp/arena-storage/desk}
OUT_DIR=${OUT_DIR:-$WORK}
R=$WORK/rootfs-arm64
STAMP=$WORK/wine-addon.stamp
PREFIX_INIT=${PREFIX_INIT:-1}          # create /root/.wine during the build (set 0 to skip)
INCLUDE_PREFIX=${INCLUDE_PREFIX:-0}    # 1 = ship the initialized prefix in the archive (about 770 MB)

[ -f "$R/etc/debian_version" ] || { echo "base rootfs missing at $R; run build-rootfs.sh first" >&2; exit 1; }

sudo cp /etc/resolv.conf "$R/etc/resolv.conf"
sudo mount -t proc proc "$R/proc" 2>/dev/null || true
sudo mount --bind /dev "$R/dev" 2>/dev/null || true
sudo mount --bind /sys "$R/sys" 2>/dev/null || true
cleanup() {
  sudo umount "$R/sys" 2>/dev/null || true
  sudo umount "$R/dev" 2>/dev/null || true
  sudo umount "$R/proc" 2>/dev/null || true
}
trap cleanup EXIT

# Marker for the overlay: everything newer than this is part of the add-on.
[ -f "$STAMP" ] || sudo touch "$STAMP"

sudo install -m 0755 "$HERE/rootfs/usr/local/bin/wine-run" "$R/usr/local/bin/wine-run"

sudo chroot "$R" /bin/sh -c "
  set -e
  export DEBIAN_FRONTEND=noninteractive
  dpkg --add-architecture amd64
  apt-get update
  apt-get install -y --no-install-recommends box64 wine64:amd64 libwine:amd64
  apt-get clean
  rm -rf /var/lib/apt/lists/*
"

if [ "$PREFIX_INIT" = "1" ]; then
  # Create the Windows prefix now so the first Windows app does not wait for wineboot on the phone.
  sudo chroot "$R" /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    WINEPREFIX=/root/.wine WINEARCH=win64 WINEDEBUG=-all BOX64_DYNAREC_SAFEFLAGS=1 \
    /bin/sh -c 'box64 /usr/lib/wine/wine64 wineboot --init' || echo "wineboot --init failed; prefix will be created on first run"
  sudo chroot "$R" /usr/bin/env -i HOME=/root USER=root PATH=/usr/bin:/bin \
    /bin/sh -c 'wineserver -w 2>/dev/null || true'
fi

sudo rm -f "$R/etc/resolv.conf" "$R/tmp/.X1-lock"
sudo rm -rf "$R"/tmp/wine-* "$R"/tmp/.wine-* 2>/dev/null || true
# Unmount the pseudo filesystems before archiving, or tar would read host kernel files.
cleanup
trap - EXIT
EXCLUDES="--exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp/* --exclude=./tmp/.X11-unix/*"
[ "$INCLUDE_PREFIX" = "1" ] || EXCLUDES="$EXCLUDES --exclude=./root/.wine"
ARCHIVE=$OUT_DIR/rootfs-wine-addon-arm64.tar.gz
# shellcheck disable=SC2086
sudo tar --numeric-owner -czf "$ARCHIVE" -N "$STAMP" $EXCLUDES -C "$R" .
sudo chown "$(id -u):$(id -g)" "$ARCHIVE"
(cd "$OUT_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
echo "wrote $ARCHIVE"
