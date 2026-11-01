#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
APP="${1:?phone or watch}"
if [ "$APP" = phone ];then PKG=org.diy.liferecorder; PLATFORM="$ANDROID_JAR_34";else PKG=org.diy.watchrecorder; PLATFORM="$ANDROID_JAR_26";fi
OUT="$ROOT/work/$APP-tests-v2";mkdir -p "$OUT/classes" "$OUT/dex"
export PATH="$JAVA_HOME/bin:$PATH"
EXTRA_INSTRUMENTATION=""
if [ "$APP" = phone ]; then EXTRA_INSTRUMENTATION='<instrumentation android:name="org.diy.liferecorder.WorkflowTests" android:targetPackage="org.diy.liferecorder"/><instrumentation android:name="org.diy.liferecorder.ExportTests" android:targetPackage="org.diy.liferecorder"/><instrumentation android:name="org.diy.liferecorder.HourlySummaryTests" android:targetPackage="org.diy.liferecorder"/>'; fi
cat > "$OUT/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG.integration"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/><instrumentation android:name="$PKG.IntegrationTests" android:targetPackage="$PKG"/>$EXTRA_INSTRUMENTATION<application android:debuggable="true" android:allowBackup="false"/></manifest>
XML
"$BUILD_TOOLS/aapt" package -f -M "$OUT/AndroidManifest.xml" -I "$PLATFORM" -F "$OUT/resources.apk"
javac -encoding UTF-8 --release 8 -classpath "$PLATFORM:$ROOT/work/$APP-build/classes.jar:$ROOT/work/deps/sherpa/classes.jar:$ROOT/work/deps/kotlin-stdlib-2.2.0.jar" -d "$OUT/classes" "$ROOT/tests/android/$APP/"*.java
jar cf "$OUT/tests.jar" -C "$OUT/classes" .
java -cp "$ROOT/work/deps/r8-8.10.21.jar" com.android.tools.r8.D8 --min-api 26 --lib "$PLATFORM" --classpath "$ROOT/work/$APP-build/classes.jar" --output "$OUT/dex" "$OUT/tests.jar"
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$ROOT/work/$APP-build/debug.jks" --ks-key-alias androiddebugkey --ks-pass pass:android --out "$OUT/tests.apk" "$OUT/aligned.apk"
