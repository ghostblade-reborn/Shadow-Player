#!/bin/bash
# JVM test runner for GhostBlade Player's pure logic + subtitle
# parsers. Full-source route (v0.4): generates R.java with aapt2
# exactly like ./build.sh, compiles ALL app sources against
# android.jar, then compiles and runs each suite SEPARATELY (suites
# declare colliding top-level state, so they never share a compile).
# Nothing Android is *executed* — the suites only touch pure Kotlin.
set -e
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
KOTLINC="${KOTLINC:-$HOME/workspace/tools/kotlinc/bin/kotlinc}"
AJAR="${ANDROID_JAR:-$HOME/workspace/tools/android-sdk/platforms/android-34/android.jar}"
BT="${BT:-$HOME/workspace/tools/android-sdk/build-tools/35.0.0}"
STDLIB="$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar"
WORK="${WORK:-/tmp/gbp-tests}"
rm -rf "$WORK"; mkdir -p "$WORK/gen" "$WORK/classes"

echo "== aapt2: R.java =="
"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/unsigned.apk" -I "$AJAR" \
  --manifest AndroidManifest.xml -R "$WORK/res.zip" \
  --java "$WORK/gen" --auto-add-overlay
javac --release 8 -cp "$AJAR" -d "$WORK/classes" $(find "$WORK/gen" -name '*.java')

echo "== kotlinc: all app sources =="
"$KOTLINC" src/com/ghostblade/player/*.kt -cp "$AJAR:$WORK/classes" -d "$WORK/classes"

for t in PlayerLogicTest SubtitleTest SubtitleFormatsTest ClipEditorTest; do
    echo "--- $t"
    "$KOTLINC" "tests/$t.kt" -cp "$WORK/classes:$AJAR" -d "$WORK/$t.jar"
    "$JAVA_HOME/bin/java" -cp "$WORK/$t.jar:$WORK/classes:$STDLIB:$AJAR" \
        "com.ghostblade.player.${t}Kt"
done
echo "ALL SUITES DONE"
