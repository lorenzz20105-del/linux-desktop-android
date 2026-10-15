#!/usr/bin/env bash
# Rebuild prebuilt proot for arm64-v8a and x86_64 with the NDK.
# Needs: NDK 26.3.11579264 under $ANDROID_HOME/ndk, and the tarballs in src/.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/android-sdk}
NDK=$SDK/ndk/26.3.11579264
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
SRC=$HERE/src
BUILD=$HERE/build
TALLOC=$SRC/talloc-2.4.2
PROOT=$SRC/proot-5.4.0

mkdir -p "$SRC" "$BUILD"
[ -d "$PROOT" ] || { curl -sSL -o "$SRC/proot.tar.gz" https://github.com/proot-me/proot/archive/refs/tags/v5.4.0.tar.gz; tar xzf "$SRC/proot.tar.gz" -C "$SRC"; }
[ -d "$TALLOC" ] || { curl -sSL -o "$SRC/talloc.tar.gz" https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz; tar xzf "$SRC/talloc.tar.gz" -C "$SRC"; }

# Minimal replace.h shim, since the samba talloc tarball does not include lib/replace.
SHIM=$BUILD/shim
mkdir -p "$SHIM"
cat > "$SHIM/replace.h" <<'EOF'
#ifndef _REPLACE_H
#define _REPLACE_H
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <time.h>
#include <ctype.h>
#include <assert.h>
#include <inttypes.h>
#include <stdbool.h>
#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
#define HAVE_VA_COPY 1
#endif
EOF

for ABI in arm64-v8a x86_64; do
  case $ABI in arm64-v8a) T=aarch64;; x86_64) T=x86_64;; esac
  OUT=$BUILD/$ABI
  mkdir -p "$OUT/pc"
  CC=$TC/$T-linux-android26-clang
  "$CC" -c -fPIC -O2 -DTALLOC_BUILD_VERSION_MAJOR=2 -DTALLOC_BUILD_VERSION_MINOR=4 \
    -DTALLOC_BUILD_VERSION_RELEASE=2 -I"$TALLOC" -I"$SHIM" "$TALLOC/talloc.c" -o "$OUT/talloc.o"
  "$TC/llvm-ar" rcs "$OUT/libtalloc.a" "$OUT/talloc.o"
  cat > "$OUT/pc/talloc.pc" <<EOF
prefix=$OUT
libdir=\${prefix}
includedir=$TALLOC
Name: talloc
Version: 2.4.2
Description: talloc
Libs: -L\${libdir} -ltalloc
Cflags: -I\${includedir} -I$SHIM
EOF
  rm -rf "$OUT/proot-src" && cp -r "$PROOT" "$OUT/proot-src"
  make -C "$OUT/proot-src/src" proot CC="$CC" LD="$CC" STRIP="$TC/llvm-strip" \
    OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump" PKG_CONFIG_PATH="$OUT/pc"
  mkdir -p "$HERE/prebuilt/$ABI"
  cp "$OUT/proot-src/src/proot" "$HERE/prebuilt/$ABI/libproot.so"
  echo "built $ABI"
done
