#!/usr/bin/env bash
# Builds the Linux Desktop APK without Gradle.
# Needs: JDK 17, Android SDK (platforms;android-34, build-tools;34.0.0).
# Native libs (libproot.so, libbusybox.so) are in prebuilt/<abi>/. Rebuild proot with build-proot.sh.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
PLATFORM=$SDK/platforms/android-34/android.jar
BT=$SDK/build-tools/34.0.0
OUT=build
ABIS="arm64-v8a x86_64"
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex $OUT/lib

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

# 4. Assemble: dex + native libs.
(cd $OUT/dex && zip -q -j ../base.apk classes.dex)
(cd $OUT && zip -q -r base.apk lib)

# 5. Align and sign. The debug keystore is reused from the termish project.
KS=debug.keystore
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -storepass android -keypass android -alias desktop \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Linux Desktop Debug" >/dev/null 2>&1
fi
$BT/zipalign -f -p 4 $OUT/base.apk $OUT/aligned.apk
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias desktop --out linux-desktop.apk $OUT/aligned.apk
$BT/apksigner verify --print-certs linux-desktop.apk | head -3
ls -la linux-desktop.apk
