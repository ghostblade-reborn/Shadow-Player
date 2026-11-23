#!/usr/bin/env bash
# JVM test runner for the clip editor's pure logic (ClipLogic in
# ClipEditor.kt). Compiles only ClipEditor.kt + ClipEditorTest.kt
# against the SDK's android.jar stub — the suite never calls the
# Android engine half (ClipTrimmer). See CLIP-EDITOR.md.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="${KOTLINC:-$HOME/workspace/tools/kotlinc/bin/kotlinc}"
AJAR="${ANDROID_JAR:-$HOME/workspace/tools/android-sdk/platforms/android-34/android.jar}"
STDLIB="$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar"
OUT="${OUT:-/tmp/gbp-clip-tests.jar}"

"$KOTLINC" \
    src/com/ghostblade/player/ClipEditor.kt \
    tests/ClipEditorTest.kt \
    -cp "$AJAR" -d "$OUT"

"$JAVA_HOME/bin/java" -cp "$OUT:$STDLIB:$AJAR" "com.ghostblade.player.ClipEditorTestKt"
