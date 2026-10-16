#!/usr/bin/env bash
# Rebuilds prebuilt/arm64-v8a/libproot.so, libtalloc.so and libandroid-shmem.so the way Termux's
# proot package does: the tagged Termux proot release (5.1.107.96, which has the SIGSYS handling
# that Android's app seccomp filter needs), with Termux's libtalloc and libandroid-shmem packages.
#   NDK=/path/to/android-ndk-r27c tools/build-proot.sh
# Pinned inputs: proot tag v5.1.107.96, talloc 2.5.0 header (samba), Termux 2.5.0 libtalloc and
# 0.7 libandroid-shmem debs. Termux's recipe applies no patches to proot.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
NDK=${NDK:?set NDK to the Android NDK root}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
WORK=${WORK:-/var/tmp/arena-storage/proot-build}
mkdir -p "$WORK/deb" "$WORK/link"
cd "$WORK"
[ -d proot-5.1.107.96 ] || { curl -sL -o proot.tar.gz https://github.com/termux/proot/archive/refs/tags/v5.1.107.96.tar.gz; tar xzf proot.tar.gz; }
[ -f talloc.h ] || { curl -sL -o talloc.tar.gz https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz; tar xzf talloc.tar.gz talloc-2.5.0/talloc.h; mv talloc-2.5.0/talloc.h .; }
deb() { # deb <package path> <library file inside the package>
  curl -sL -o "$WORK/deb/$1.deb" "https://packages.termux.dev/apt/termux-main/$2"
  (cd "$WORK/deb" && rm -rf "$1" && mkdir "$1" && cd "$1" && ar x "../$1.deb" && tar xf data.tar.xz)
}
deb libtalloc pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb
deb libandroid-shmem pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb
cp "$WORK/deb/libtalloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0" "$WORK/link/libtalloc.so"
cp "$WORK/link/libtalloc.so" "$WORK/link/libtalloc.so.2"
cp "$WORK/deb/libandroid-shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so" "$WORK/link/"
cd proot-5.1.107.96/src
make clean >/dev/null 2>&1 || true
# -Wno-implicit-function-declaration: upstream ashmem_memfd.c misses <string.h>; newer clang errors on it.
make -j"$(nproc)" CC="$TC/aarch64-linux-android26-clang" STRIP="$TC/llvm-strip" \
  OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump" PROOT_WITH_LIBANDROID_SHMEM=true \
  LDFLAGS="-L$WORK/link -ltalloc -landroid-shmem -Wl,-z,noexecstack -Wl,-z,max-page-size=16384" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"5.1.107.96\\\" -I. -I$WORK" \
  CFLAGS="-Wall -Wextra -O2 -Wno-implicit-function-declaration"
# Android's linker reads DT_RUNPATH with $ORIGIN, so the libraries are found next to libproot.so.
patchelf --replace-needed libtalloc.so.2 libtalloc.so --set-rpath '$ORIGIN' proot
cp proot "$ROOT/prebuilt/arm64-v8a/libproot.so"
cp "$WORK/link/libtalloc.so" "$ROOT/prebuilt/arm64-v8a/libtalloc.so"
cp "$WORK/link/libandroid-shmem.so" "$ROOT/prebuilt/arm64-v8a/libandroid-shmem.so"
echo "built prebuilt/arm64-v8a/{libproot,libtalloc,libandroid-shmem}.so"
