set -e
# 工具链路径都可以用环境变量覆盖，默认值是作者自己构建机上的路径。
# ANDROID_ROOT: 含 aapt2/zipalign 的 Debian 根目录；JDK_BIN: JDK 的 bin；BUILD_TOOLS: 含 lib/d8.jar 的 build-tools；ANDROID_JAR: android-34 的 android.jar
# BUN_SO: arm64 的 bun 可执行文件，打包为 lib/arm64-v8a/libmagpiebun.so
R=${ANDROID_ROOT:-/workspace/work/android/root}
export LD_LIBRARY_PATH=$R/usr/lib/aarch64-linux-gnu/android:$R/usr/lib/aarch64-linux-gnu:$R/usr/lib/7zip:/workspace/work/android/croot/usr/lib/aarch64-linux-gnu:$R/usr/lib
AAPT2=${AAPT2:-$R/usr/lib/android-sdk/build-tools/debian/aapt2}; ZIPALIGN=${ZIPALIGN:-$R/usr/lib/android-sdk/build-tools/debian/zipalign}
JDK=${JDK_BIN:-/workspace/work/android/jdk/bin}
BT=${BUILD_TOOLS:-/workspace/work/android/bt/android-14}
AJ=${ANDROID_JAR:-/workspace/work/android/plat/android-34/android.jar}
cd "$(dirname "$0")"
BUN_SO=${BUN_SO:-/workspace/work/magpie/apk/lib/arm64-v8a/libmagpiebun.so}
[ -f "$BUN_SO" ] || BUN_SO=lib/arm64-v8a/libmagpiebun.so
rm -rf build && mkdir -p build/gen build/classes build/dex build/stage/lib/arm64-v8a
$AAPT2 compile --dir res -o build/res.zip
$AAPT2 link -I $AJ --manifest AndroidManifest.xml -A assets --java build/gen -o build/base.apk build/res.zip --min-sdk-version 34 --target-sdk-version 28 --version-code 27 --version-name 1.4.7 --auto-add-overlay
$JDK/javac -nowarn -source 11 -target 11 -encoding UTF-8 -classpath $AJ -d build/classes $(find src build/gen -name "*.java") 2>&1 | grep -v "bootstrap\|^warning\|^1 warning" || true
ls build/classes/com/magpie/android/MainActivity.class >/dev/null
$JDK/java -cp $BT/lib/d8.jar com.android.tools.r8.D8 --release --min-api 34 --lib $AJ --output build/dex $(find build/classes -name "*.class")
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -X ../unsigned.apk classes.dex)
cp "$BUN_SO" build/stage/lib/arm64-v8a/libmagpiebun.so
(cd build/stage && zip -q -X -r ../unsigned.apk lib)
$ZIPALIGN -f -p 4 build/unsigned.apk build/aligned.apk
