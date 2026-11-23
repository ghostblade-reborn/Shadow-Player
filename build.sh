#!/usr/bin/env bash
# GhostBlade Player — build pipeline (no Gradle).
# aapt2 (resources+manifest) -> javac (R.java) -> kotlinc (app) ->
# d8 (app classes + kotlin-stdlib) -> NDK clang (FFmpeg JNI bridge) ->
# zipalign -> apksigner. Native libs (our FFmpeg build + bridge) are
# packaged under lib/arm64-v8a/.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/tools/android-sdk"
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/tools/kotlinc/bin/kotlinc"
STDLIB="$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar"
KEYSTORE="$PWD/debug.keystore"

# Project-local debug keystore (generated once, kept with the project).
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=GhostBlade Player,O=GhostBlade,C=BD"
fi

B=build
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "== native: FFmpeg JNI bridge (NDK clang) + engine libs =="
NDK="$HOME/workspace/tools/android-ndk-r27c"
NDKBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
FFOUT="$PWD/codec-lab/out"
mkdir -p "$B/lib/arm64-v8a"
"$NDKBIN/aarch64-linux-android24-clang" -O2 -fPIC -shared \
  -I"$FFOUT/include" \
  -o "$B/lib/arm64-v8a/libgbffmpeg.so" native/gbffmpeg.c \
  -L"$FFOUT/lib" -lavformat -lavcodec -lswresample -lswscale -lavutil \
  -landroid -llog -lm
"$NDKBIN/llvm-strip" "$B/lib/arm64-v8a/libgbffmpeg.so"
cp "$FFOUT/lib/libavcodec.so" "$FFOUT/lib/libavformat.so" \
   "$FFOUT/lib/libavutil.so" "$FFOUT/lib/libswresample.so" \
   "$FFOUT/lib/libswscale.so" "$B/lib/arm64-v8a/"
# v0.4: libass subtitle plane (Engine Team libass-lab build: FriBidi +
# FreeType + HarfBuzz + libass + the gbass JNI shim). FFmpegEngine's
# supportsAssRendering() probe loads these in order; shipping the set
# is what turns the capability on.
ASSOUT="$PWD/codec-lab/libass-lab/out/lib"
cp "$ASSOUT/libfribidi.so" "$ASSOUT/libfreetype.so" \
   "$ASSOUT/libharfbuzz.so" "$ASSOUT/libass.so" \
   "$ASSOUT/libgbass.so" "$B/lib/arm64-v8a/"
ls -la "$B/lib/arm64-v8a/"

echo "== aapt2: compile + link resources =="
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$AJAR" \
  --manifest AndroidManifest.xml -R "$B/res.zip" \
  --java "$B/gen" --auto-add-overlay

echo "== javac: R.java =="
javac --release 8 -cp "$AJAR" -d "$B/classes" $(find "$B/gen" -name '*.java')

echo "== kotlinc: app sources =="
"$KOTLINC" src/com/ghostblade/player/*.kt -cp "$AJAR:$B/classes" -d "$B/classes"

echo "== d8: dex (app + kotlin-stdlib) =="
(cd "$B/classes" && jar cf ../app.jar .)
"$BT/d8" --release --min-api 24 --lib "$AJAR" \
  --output "$B/dex" "$B/app.jar" "$STDLIB"

echo "== package + align + sign =="
# Reproducibility: pin the classes.dex zip entry to the zip epoch
# (1980-01-01, matching aapt2's deterministic entry stamps) and strip
# extra fields, so the entry carries no build-time timestamp.
(cd "$B/dex" && touch -d '1980-01-01 00:00:00 UTC' classes.dex && zip -X -q ../unsigned.apk classes.dex)
# Native libraries, stored under the standard APK lib path. The
# manifest leaves extractNativeLibs at its default (extracted at
# install), so compressed entries + zipalign are safe on API 24+.
# Pin the .so mtimes to the zip epoch like classes.dex above —
# without this the lib entries carry build-time stamps and the APK
# hash changes between otherwise identical builds (Monitoring P3-1).
(cd "$B" && touch -d '1980-01-01 00:00:00 UTC' lib/arm64-v8a/*.so && zip -X -q unsigned.apk lib/arm64-v8a/*.so)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
# Versioned output name so downloads never collide into "(1)", "(2)" duplicates.
VER=$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
OUT="ShadowPlayer-v${VER}.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$B/aligned.apk"
cp "$OUT" ShadowPlayer.apk

"$BT/apksigner" verify "$OUT"
# `|| true`: with pipefail, head closing the pipe early can SIGPIPE
# aapt2 and fail an otherwise good build.
"$BT/aapt2" dump badging "$OUT" | head -8 || true
ls -la "$OUT"
sha256sum "$OUT"
