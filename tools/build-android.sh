#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
: "${JAVA_HOME:?Set JAVA_HOME to JDK17}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS to Android build-tools34}"
export PATH="$JAVA_HOME/bin:$PATH"
APP="${1:?phone or watch}"
if [ "$APP" = phone ]; then
  : "${ANDROID_JAR_34:?Set ANDROID_JAR_34}"
  SOURCE="$ROOT/apps/phone"; PLATFORM="$ANDROID_JAR_34"; OUT="$ROOT/work/phone-build"; NAME="life-recorder-phone-0.5.4-debug.apk"
elif [ "$APP" = watch ]; then
  : "${ANDROID_JAR_26:?Set ANDROID_JAR_26}"
  SOURCE="$ROOT/apps/watch-legacy"; PLATFORM="$ANDROID_JAR_26"; OUT="$ROOT/work/watch-build"; NAME="watch2-recorder-0.4.5-debug.apk"
else
  exit 2
fi
rm -rf "$OUT/classes" "$OUT/generated" "$OUT/dex" "$OUT/native"
mkdir -p "$ROOT/outputs" "$OUT/classes" "$OUT/generated" "$OUT/dex" "$OUT/native/lib"
if [ "$APP" = watch ]; then python3 tools/fetch-watch-wakeword.py; fi
ASSETS=()
if [ -d "$SOURCE/app/src/main/assets" ]; then ASSETS=(-A "$SOURCE/app/src/main/assets"); fi
if [ "$APP" = watch ]; then ASSETS+=(-A "$ROOT/work/watch-wakeword-assets"); fi
"$BUILD_TOOLS/aapt" package -f -m -M "$SOURCE/app/src/main/AndroidManifest.xml" -S "$SOURCE/app/src/main/res" -I "$PLATFORM" -J "$OUT/generated" -F "$OUT/resources.apk" ${ASSETS[@]+"${ASSETS[@]}"}
CP="$PLATFORM"; LIBS=()
if [ "$APP" = phone ]; then
 CP="$CP:$ROOT/work/deps/webrtc/classes.jar:$ROOT/work/deps/sherpa/classes.jar:$ROOT/work/deps/kotlin-stdlib-2.2.0.jar"
 LIBS=("$ROOT/work/deps/sherpa/classes.jar" "$ROOT/work/deps/webrtc/classes.jar" "$ROOT/work/deps/kotlin-stdlib-2.2.0.jar")
 cp -R "$ROOT/work/deps/webrtc/jni/" "$OUT/native/lib/"
 cp -R "$ROOT/work/deps/sherpa/jni/" "$OUT/native/lib/"
else
 CP="$CP:$ROOT/work/deps/sherpa/classes.jar:$ROOT/work/deps/kotlin-stdlib-2.2.0.jar"
 LIBS=("$ROOT/work/deps/sherpa/classes.jar" "$ROOT/work/deps/kotlin-stdlib-2.2.0.jar")
 for arch in armeabi-v7a arm64-v8a; do cp -R "$ROOT/work/deps/sherpa/jni/$arch" "$OUT/native/lib/"; done
fi
SOURCES=()
while IFS= read -r -d '' f; do SOURCES+=("$f"); done < <(find "$SOURCE/app/src/main/java" "$ROOT/packages/android-core/src/main/java" "$OUT/generated" -name '*.java' -print0)
javac -encoding UTF-8 --release 8 -classpath "$CP" -d "$OUT/classes" "${SOURCES[@]}"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
if [ "$APP" = phone ]; then
 java -cp "$ROOT/work/deps/r8-8.10.21.jar" com.android.tools.r8.D8 --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar" "${LIBS[@]}"
else
 java -cp "$ROOT/work/deps/r8-8.10.21.jar" com.android.tools.r8.D8 --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar" "${LIBS[@]}"
fi
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes*.dex)
(cd "$OUT/native" && zip -qr "$OUT/unsigned.apk" lib)
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$OUT/debug.jks" ]; then
 keytool -genkeypair -keystore "$OUT/debug.jks" -storepass android -keypass android -alias androiddebugkey -dname 'CN=LifeRecord Local Debug' -keyalg RSA -keysize 2048 -validity 10000
fi
"$BUILD_TOOLS/apksigner" sign --ks "$OUT/debug.jks" --ks-key-alias androiddebugkey --ks-pass pass:android --out "$ROOT/outputs/$NAME" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$ROOT/outputs/$NAME"
"$BUILD_TOOLS/aapt" dump badging "$ROOT/outputs/$NAME"
