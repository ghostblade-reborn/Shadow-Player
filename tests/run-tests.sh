#!/usr/bin/env bash
# GhostBlade Player — JVM tests for Shadow One's pure logic
# (Library grouping/sorting/filter, Store JSON round-trip, Fmt).
# Compiles the app sources (minus MainActivity) + tests against the
# android.jar STUB: only pure code paths run — no Android API is
# actually invoked on the JVM.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/tools/android-sdk"
AJAR="$SDK/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/tools/kotlinc/bin/kotlinc"
STDLIB="$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar"

OUT=build/tests
mkdir -p "$OUT"

"$KOTLINC" \
  src/com/ghostblade/player/Models.kt \
  src/com/ghostblade/player/Settings.kt \
  src/com/ghostblade/player/Store.kt \
  src/com/ghostblade/player/Library.kt \
  src/com/ghostblade/player/FileOps.kt \
  tests/TestMain.kt \
  -cp "$AJAR" -d "$OUT/tests.jar"

java -cp "$OUT/tests.jar:$AJAR:$STDLIB" TestMainKt | tee tests/TEST-RESULTS.txt
