#!/usr/bin/env bash
# Builds the Linux Desktop APK without Gradle. The APK carries the three rootfs archives plus a
# small current-script overlay as uncompressed assets, so the phone does not download anything.
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
VERSION_CODE=${VERSION_CODE:-2}
VERSION_NAME=${VERSION_NAME:-1.1.0}
# The bundled Debian guest and all add-ons are arm64; shipping x86_64 Android libraries
# only made the APK appear multi-ABI while providing no runnable x86_64 guest.
ABIS="arm64-v8a"
ARCHIVES="rootfs-debian-trixie-arm64.tar.gz rootfs-wine-addon-arm64.tar.gz rootfs-agents-arm64.tar.gz"
for f in $ARCHIVES; do
  [ -s "$ASSETS_DIR/$f" ] || { echo "missing $ASSETS_DIR/$f" >&2; exit 1; }
done
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/lib" "$OUT/stage/assets" "$OUT/fixup-root/usr/local/bin" "$OUT/fixup-root/usr/local/lib/desktop-login"

# Bundle current repo scripts as a small overlay so updating the app also updates guest scripts,
# even when the large rootfs archives were built earlier.
FIXUP_FILES="app-run desktop-start desktop-stop desktop-login desktop-lock vscode-start wine-run freebuff-desktop"
for f in $FIXUP_FILES; do
  cp "rootfs/usr/local/bin/$f" "$OUT/fixup-root/usr/local/bin/$f"
  chmod 0755 "$OUT/fixup-root/usr/local/bin/$f"
done
cp rootfs/usr/local/lib/desktop-login/greeter.py "$OUT/fixup-root/usr/local/lib/desktop-login/greeter.py"
chmod 0755 "$OUT/fixup-root/usr/local/lib/desktop-login/greeter.py"
tar --numeric-owner --owner=0 --group=0 -czf "$OUT/stage/assets/rootfs-app-fixups.tar.gz" \
  -C "$OUT/fixup-root" usr/local/bin usr/local/lib/desktop-login

# 1. Native libraries: copy the arm64 proot runtime into lib/<abi>/. Busybox is not used.
for ABI in $ABIS; do
  mkdir -p "$OUT/lib/$ABI"
  for lib in libproot.so libtalloc.so libandroid-shmem.so; do
    [ -s "prebuilt/$ABI/$lib" ] || { echo "missing prebuilt/$ABI/$lib" >&2; exit 1; }
    cp "prebuilt/$ABI/$lib" "$OUT/lib/$ABI/"
  done
done

# 2. Manifest-only APK (the UI is built in code, so no resources).
$BT/aapt package -f -M app/AndroidManifest.xml -I "$PLATFORM" --min-sdk-version 26 \
    --target-sdk-version 28 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -F "$OUT/base.apk"

# 3. Java -> DEX.
# Generate hashes from the exact packaged archives so rebuilding rootfs assets cannot leave stale
# checksums in the app. Assets remain offline and are verified while extracted.
cat > $OUT/AssetHashes.java <<EOF
package app.desktop;
final class AssetHashes {
    static final String BASE = "$(sha256sum "$ASSETS_DIR/rootfs-debian-trixie-arm64.tar.gz" | cut -d' ' -f1)";
    static final String WINE = "$(sha256sum "$ASSETS_DIR/rootfs-wine-addon-arm64.tar.gz" | cut -d' ' -f1)";
    static final String AGENTS = "$(sha256sum "$ASSETS_DIR/rootfs-agents-arm64.tar.gz" | cut -d' ' -f1)";
    static final String FIXUPS = "$(sha256sum "$OUT/stage/assets/rootfs-app-fixups.tar.gz" | cut -d' ' -f1)";
    private AssetHashes() { }
}
EOF
find app/src -name '*.java' > $OUT/sources.txt
echo "$OUT/AssetHashes.java" >> $OUT/sources.txt
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
