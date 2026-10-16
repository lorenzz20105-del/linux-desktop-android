#!/usr/bin/env bash
# Adds the preinstalled coding tools to the Debian trixie arm64 rootfs:
#   - Node.js and npm (Debian packages), used by the npm tools below
#   - Claude Code (npm @anthropic-ai/claude-code)  -> claude
#   - Codex CLI (npm @openai/codex)                -> codex
#   - Freebuff CLI (npm freebuff)                  -> freebuff
#   - Freebuff Desktop (Linux arm64 AppImage, unpacked to /opt/freebuff) -> freebuff-desktop
#   - VS Code server (Microsoft server-linux-arm64, /opt/vscode-server)  -> vscode-start
# Output is an OVERLAY archive: only files changed after this build started. It sits on top of the
# base rootfs AND the Windows add-on, because it is built from that tree.
# Output: $OUT_DIR/rootfs-agents-arm64.tar.gz (+ .sha256)
# Requires: rootfs-arm64 built by build-rootfs.sh and build-wine-addon.sh, sudo, network access.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-/var/tmp/arena-storage/desk}
OUT_DIR=${OUT_DIR:-$WORK}
R=$WORK/rootfs-arm64
STAMP=$WORK/agents-addon.stamp
VSCODE_URL=${VSCODE_URL:-https://update.code.visualstudio.com/latest/server-linux-arm64/stable}
FREEBUFF_URL=${FREEBUFF_URL:-https://freebuff.com/api/desktop/download/linux-arm64}
# Pin npm tool versions; override explicitly when upgrading the curated image.
CODEX_VERSION=${CODEX_VERSION:-0.163.0}
CLAUDE_VERSION=${CLAUDE_VERSION:-2.1.301}
FREEBUFF_VERSION=${FREEBUFF_VERSION:-0.2.29}

[ -f "$R/etc/debian_version" ] || { echo "base rootfs missing at $R; run build-rootfs.sh first" >&2; exit 1; }
[ -x "$R/usr/local/bin/wine-run" ] || { echo "Windows add-on missing at $R; run build-wine-addon.sh first" >&2; exit 1; }

sudo cp /etc/resolv.conf "$R/etc/resolv.conf"
sudo mount -t proc proc "$R/proc" 2>/dev/null || true
sudo mount --bind /dev "$R/dev" 2>/dev/null || true
sudo mount --bind /sys "$R/sys" 2>/dev/null || true
cleanup() {
  # Unmount everything under the rootfs, repeating in case of stacked mounts.
  for _ in 1 2 3; do
    for m in sys dev proc; do
      sudo umount "$R/$m" 2>/dev/null || true
    done
  done
}
trap cleanup EXIT

# Marker for the overlay: everything newer than this is part of the add-on.
[ -f "$STAMP" ] || sudo touch "$STAMP"

# Launchers and desktop-start come from this repo.
sudo install -m 0755 "$HERE/rootfs/usr/local/bin/desktop-start" "$R/usr/local/bin/desktop-start"
sudo install -m 0755 "$HERE/rootfs/usr/local/bin/vscode-start" "$R/usr/local/bin/vscode-start"
sudo install -m 0755 "$HERE/rootfs/usr/local/bin/freebuff-desktop" "$R/usr/local/bin/freebuff-desktop"

sudo chroot "$R" /bin/sh -c "
  set -e
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y --no-install-recommends nodejs npm
  apt-get clean
  rm -rf /var/lib/apt/lists/*
  npm install -g --no-audit --no-fund @openai/codex@${CODEX_VERSION} @anthropic-ai/claude-code@${CLAUDE_VERSION} freebuff@${FREEBUFF_VERSION}
  npm cache clean --force
  rm -rf /root/.npm /tmp/npm-*
"

# VS Code server: the official self-contained build (it carries its own Node runtime).
VSCODE_TGZ=$WORK/vscode-server-linux-arm64.tar.gz
[ -s "$VSCODE_TGZ" ] || curl -fL --retry 3 -o "$VSCODE_TGZ.part" "$VSCODE_URL"
[ -s "$VSCODE_TGZ" ] || mv "$VSCODE_TGZ.part" "$VSCODE_TGZ"
sudo rm -rf "$R/opt/vscode-server"
sudo mkdir -p "$R/opt/vscode-server"
sudo tar -xzf "$VSCODE_TGZ" --strip-components=1 -C "$R/opt/vscode-server"

# Freebuff Desktop: unpack the arm64 AppImage on the host with qemu-user (no FUSE needed).
# Running it inside the chroot fails because the binfmt interpreter is not visible there.
FB=$WORK/freebuff-linux-arm64.AppImage
[ -s "$FB" ] || curl -fL --retry 3 -o "$FB.part" "$FREEBUFF_URL"
[ -s "$FB" ] || mv "$FB.part" "$FB"
FBX=$(mktemp -d "$WORK/fbx.XXXX")
cp "$FB" "$FBX/freebuff.AppImage"
chmod 755 "$FBX/freebuff.AppImage"
(cd "$FBX" && qemu-aarch64-static ./freebuff.AppImage --appimage-extract >/dev/null)
sudo rm -rf "$R/opt/freebuff"
sudo mkdir -p "$R/opt"
sudo mv "$FBX/squashfs-root" "$R/opt/freebuff"
sudo chown -R 0:0 "$R/opt/freebuff"
rm -rf "$FBX"
[ -x "$R/opt/freebuff/AppRun" ] || { echo "Freebuff Desktop extraction failed" >&2; exit 1; }

# Trim what the phone never uses: the musl build of Claude Code (this is a glibc device), the
# FreeBuff locale packs other than English, FreeBuff's software GPU library (the app runs with
# --disable-gpu), and source maps. Only files this add-on created are touched.
sudo chroot "$R" /bin/sh -c '
  rm -rf /usr/local/lib/node_modules/@anthropic-ai/claude-code/node_modules/@anthropic-ai/claude-code-linux-arm64-musl
  rm -f /opt/freebuff/libvk_swiftshader.so
  cd /opt/freebuff/locales && ls | grep -v "^en-US[.]pak$" | xargs rm -f || true
  find /opt/freebuff /opt/vscode-server -name "*.map" -delete
'

sudo chroot "$R" /bin/sh -c 'rm -rf /root/.npm /root/.cache /var/cache/apt/archives/*.deb /tmp/* 2>/dev/null; true'
sudo rm -f "$R/etc/resolv.conf" "$R/tmp/.X1-lock"
# Unmount the pseudo filesystems before archiving, or tar would read host kernel files.
cleanup
trap - EXIT

ARCHIVE=$OUT_DIR/rootfs-agents-arm64.tar.gz
EXCLUDES="--exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp/* --exclude=./tmp/.X11-unix/*"
sudo tar --numeric-owner -czf "$ARCHIVE" -N "$STAMP" $EXCLUDES -C "$R" .
sudo chown "$(id -u):$(id -g)" "$ARCHIVE"
(cd "$OUT_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
echo "wrote $ARCHIVE"
