#!/usr/bin/env bash
# Build LED Cycle APK without Gradle. Run from the android/ directory.
# Requires: JAVA_HOME (JDK 17), Android SDK build-tools + platform android-36.
set -euo pipefail

SDK="${ANDROID_SDK:-$LOCALAPPDATA/Android/Sdk}"
SDK="$(cygpath -u "$SDK" 2>/dev/null || echo "$SDK")"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
KS="${HOME}/.android/debug.keystore"

# .bat wrappers on Windows, bare names elsewhere
ext() { [ -f "$BT/$1.bat" ] && echo "$BT/$1.bat" || echo "$BT/$1"; }
AAPT2="$(ext aapt2)"; D8="$(ext d8)"; ZIPALIGN="$BT/zipalign.exe"; APKSIGNER="$(ext apksigner)"
[ -f "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign"

rm -rf "$OUT"; mkdir -p "$OUT/classes"

echo "[1/6] javac"
javac -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" \
      $(find "$HERE/src" -name '*.java')

echo "[2/6] d8 -> dex"
"$D8" --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

echo "[3/6] aapt2 link -> base.apk (manifest only, no res)"
"$AAPT2" link -o "$OUT/base.apk" -I "$JAR" \
      --manifest "$HERE/AndroidManifest.xml" \
      --min-sdk-version 31 --target-sdk-version 34

echo "[4/6] add classes.dex"
( cd "$OUT" && jar uf base.apk classes.dex )

echo "[5/6] zipalign"
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/ledcycle-aligned.apk"

echo "[6/6] sign"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
      --out "$OUT/ledcycle.apk" "$OUT/ledcycle-aligned.apk"

echo "Built: $OUT/ledcycle.apk"
