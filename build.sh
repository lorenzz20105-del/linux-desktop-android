#!/usr/bin/env bash
# Builds the Linux Desktop APK without Gradle. The APK carries the three rootfs archives as
# uncompressed assets, so the phone only extracts them and never downloads anything.
# Needs: JDK 11 or 17 (javac), Android SDK (platforms;android-34, build-tools;34.0.0).
# Native libs (libproot.so, libbusybox.so) are in prebuilt/<abi>/. Rebuild proot with build-proot.sh.
# Inputs: ASSETS_DIR holds the three archives (default /var/tmp/arena-storage/desk).
# Outputs: APK_OUT (default /var/tmp/arena-storage/linux-desktop.apk). Build files go to BUILD_DIR,
# outside the repo, because the APK is over 1 GB.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
PLATFORM=$SDK/platforms/android-34/android.jar
BT=$SDK/build-tools/34.0.0
OUT=${BUILD_DIR:-/var/tmp/arena-storage/apkbuild}
ASSETS_DIR=${ASSETS_DIR:-/var/tmp/arena-storage/desk}
APK_OUT=${APK_OUT:-/var/tmp/arena-storage/linux-desktop.apk}
ABIS="arm64-v8a x86_64"
ARCHIVES="rootfs-debian-trixie-arm64.tar.gz rootfs-wine-addon-arm64.tar.gz rootfs-agents-arm64.tar.gz"
for f in $ARCHIVES; do
  [ -s "$ASSETS_DIR/$f" ] || { echo "missing $ASSETS_DIR/$f" >&2; exit 1; }
done
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex $OUT/lib $OUT/stage/assets

# 1. Native libraries: copy proot and busybox into lib/<abi>/.
for ABI in $ABIS; do
  mkdir -p $OUT/lib/$ABI
  cp prebuilt/$ABI/libproot.so prebuilt/$ABI/libbusybox.so $OUT/lib/$ABI/
done

# 2. Manifest-only APK (the UI is built in code, so no resources).
$BT/aapt package -f -M app/AndroidManifest.xml -I "$PLATFORM" --min-sdk-version 26 \
    --target-sdk-version 28 --version-code 1 --version-name 0.1.0 -F $OUT/base.apk

# 3. Java -> DEX.
find app/src -name '*.java' > $OUT/sources.txt
javac -encoding UTF-8 -Xlint:-options --release 8 -classpath "$PLATFORM" -d $OUT/classes @$OUT/sources.txt
$BT/d8 --release --min-api 26 --lib "$PLATFORM" --output $OUT/dex $(find $OUT/classes -name '*.class')

# 4. Assemble: dex, native libs, and the archives as stored (already compressed) assets.
(cd $OUT/dex && zip -q -j ../base.apk classes.dex)
(cd $OUT && zip -q -r base.apk lib)
for f in $ARCHIVES; do
  ln -f "$ASSETS_DIR/$f" "$OUT/stage/assets/$f" 2>/dev/null || cp "$ASSETS_DIR/$f" "$OUT/stage/assets/$f"
done
(cd $OUT/stage && zip -q -0 -r ../base.apk assets)

# 5. Align and sign. The debug keystore is reused from the termish project.
KS=debug.keystore
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -storepass android -keypass android -alias desktop \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Linux Desktop Debug" >/dev/null 2>&1
fi
$BT/zipalign -f -p 4 $OUT/base.apk $OUT/aligned.apk
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias desktop --out "$APK_OUT" $OUT/aligned.apk
$BT/apksigner verify --print-certs "$APK_OUT" | head -3
ls -la "$APK_OUT"
