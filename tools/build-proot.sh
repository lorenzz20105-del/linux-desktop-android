#!/usr/bin/env bash
# Rebuilds prebuilt/arm64-v8a/libproot.so and libtalloc.so from upstream proot master (Termux fork
# source, which handles the SIGSYS that Android's app seccomp filter raises). Needs the Android NDK.
#   NDK=/path/to/android-ndk-r27c tools/build-proot.sh
# Steps: fetch proot master, build a Bionic libtalloc from the Termux package, build proot with the
# NDK's aarch64 clang, rename its libtalloc dependency to libtalloc.so and set RUNPATH to $ORIGIN.
set -euo pipefail
cd "$(dirname "$0")/.."
NDK=${NDK:?set NDK to the Android NDK root}
WORK=${WORK:-/var/tmp/arena-storage/proot-build}
CLANG=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
mkdir -p "$WORK"
if [ ! -f "$WORK/talloc-headers/talloc.h" ]; then
  # Public talloc header, from the same talloc release the Termux package is built from.
  curl -sL -o "$WORK/talloc.tar.gz" https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz
  mkdir -p "$WORK/talloc-headers" && tar xzf "$WORK/talloc.tar.gz" -C "$WORK/talloc-headers" --strip-components=1 talloc-2.4.2/talloc.h
fi
cd "$WORK"
[ -d proot-master ] || { curl -sL -o proot.tar.gz https://github.com/termux/proot/archive/refs/heads/master.tar.gz; tar xzf proot.tar.gz; }
if [ ! -f libtalloc.so ]; then
  # Termux's libtalloc package (Bionic build). Pinned by its package path.
  curl -sL -o libtalloc.deb https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb
  rm -rf deb && mkdir deb && (cd deb && ar x ../libtalloc.deb && tar xf data.tar.xz)
  cp deb/data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0 libtalloc.so
  cp libtalloc.so libtalloc.so.2
fi
cd proot-master/src
make clean >/dev/null 2>&1 || true
# -Wno-implicit-function-declaration: upstream ashmem_memfd.c misses <string.h>; the warning is an error in newer clang.
make -j"$(nproc)" CC="$CLANG" STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  OBJCOPY="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy" \
  OBJDUMP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump" \
  LDFLAGS="-L$WORK -ltalloc -Wl,-z,noexecstack" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK/talloc-headers" \
  CFLAGS="-Wall -Wextra -O2 -Wno-implicit-function-declaration"
patchelf --replace-needed libtalloc.so.2 libtalloc.so --set-rpath '$ORIGIN' proot
cp proot "$OLDPWD/prebuilt/arm64-v8a/libproot.so"
cp "$WORK/libtalloc.so" "$OLDPWD/prebuilt/arm64-v8a/libtalloc.so"
echo "built $OLDPWD/prebuilt/arm64-v8a/libproot.so"
