#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${ANDROID_JAR:?Set ANDROID_JAR}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS}"
: "${BUILD_DIR:?Set BUILD_DIR to the main APK build directory}"
export PATH="$JAVA_HOME/bin:$PATH"
TOOLS="$BUILD_TOOLS"
OUT="${TEST_BUILD_DIR:-$BUILD_DIR/tests}"
APP="$PWD"
mkdir -p "$OUT/classes" "$OUT/dex"
"$TOOLS/aapt" package -f -M "$APP/tests/AndroidManifest.xml" -I "$ANDROID_JAR" -F "$OUT/resources.apk"
javac -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR:$BUILD_DIR/classes.jar" -d "$OUT/classes" "$APP/tests/PhoneSmokeTests.java"
jar cf "$OUT/tests.jar" -C "$OUT/classes" .
"$TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --classpath "$BUILD_DIR/classes.jar" --output "$OUT/dex" "$OUT/tests.jar"
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks "$BUILD_DIR/debug.jks" --ks-key-alias androiddebugkey --ks-pass pass:android --out "$OUT/tests.apk" "$OUT/aligned.apk"
