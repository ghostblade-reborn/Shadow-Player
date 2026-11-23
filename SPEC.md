# GhostBlade Player — v0.1 SPEC (Sir's order, 2026-11-22)

Our own MX Player-style local video player. Android only, **100% Kotlin code** (framework-only: no AndroidX, no Gradle, no Compose — same manual build pipeline as GhostBlade Task / GhostBlade Notion), **fully offline: NO INTERNET permission, no trackers, no analytics**, data only in app-private storage on Sir's phone.

- App name: **GhostBlade Player** · package `com.ghostblade.player` · minSdk 24 / targetSdk 34
- Project dir: `~/workspace/projects/ghostblade-player/`
- Build: `./build.sh` (aapt2 → kotlinc → d8 → zipalign → apksigner), project-local debug keystore, output `GhostBladePlayer-v0.1.apk`
- Permissions (minimal, media access is the app's job): `READ_MEDIA_VIDEO` (API 33+), `READ_EXTERNAL_STORAGE` (maxSdkVersion 32), `WAKE_LOCK`. Nothing else. NO INTERNET.

## Honest engine note (goes in FINDINGS/delivery, never hidden)
v0.1 plays through Android's system media engine (MediaPlayer) — the equivalent of MX Player's "HW decoder". It plays what the phone's hardware supports (MP4, HEVC, VP9, most MKV on modern devices). It does NOT bundle an FFmpeg software decoder, so exotic codecs (some AC3/DTS audio, old AVI/WMV) may play video without audio or not at all. That is a v0.1 limit, stated to Sir up front.

## File ownership (no two teams touch the same file)
- **Shadow One** owns: `build.sh`, `AndroidManifest.xml`, `src/com/ghostblade/player/Models.kt`, `Store.kt`, `Library.kt` (MediaStore scan + folder grouping), `MainActivity.kt` (library UI), `res/` (icon, strings).
- **Shadow Two** owns: `src/com/ghostblade/player/PlayerActivity.kt` (player screen + gestures + controls), `Subtitle.kt` (pure-Kotlin SRT parser + cue engine), `PlayerState.kt` if needed.
- **Shadow Monitoring** owns nothing — read-only verification + tests + report.

## Shared contract (fixed — both teams build against this)
- `data class VideoItem(val id: Long, val title: String, val path: String, val durationMs: Long, val sizeBytes: Long, val folder: String, val dateAddedSec: Long, val width: Int, val height: Int)` in Models.kt (Shadow One).
- Player launch: `Intent` to `PlayerActivity` with extras `"path"` (String), `"title"` (String). PlayerActivity is declared in the manifest by Shadow One: `android:name=".PlayerActivity"`, `android:configChanges="orientation|screenSize|keyboardHidden"`, `android:screenOrientation="sensorLandscape"` NOT forced (use full sensor), exported=false.
- `object Store` (Shadow One): `fun init(ctx: Context)`, `fun resumePos(path: String): Long`, `fun saveResumePos(path: String, posMs: Long)`, `fun isFinished(path: String): Boolean`, `fun markFinished(path: String)`, settings: `var brightness: Float`, `var subtitleTextSizeSp: Float`, `var lastFolder: String?` — persisted as JSON in app-private files dir, same byte-style as GhostBlade projects.
- `object SrtParser` (Shadow Two, pure Kotlin, JVM-testable): `fun parse(text: String): List<Cue>`, `data class Cue(val startMs: Long, val endMs: Long, val text: String)`, `fun cueAt(cues: List<Cue>, posMs: Long, offsetMs: Long): String?`.

## v0.1 features
### Library (Shadow One)
1. MediaStore scan of all device videos; list with thumbnail, title, duration, size, resolution.
2. **Folders view** (group by folder, MX-style) + **All videos** view; sort by name/date/duration/size.
3. Search by title. Resume badge ("▶ 12:34 left" style: show remaining or progress bar) on partially watched videos; finished marker.
4. Tap → launch PlayerActivity. Long-press → details dialog (path, size, resolution, duration).
5. Empty/permission states handled honestly (rationale + request flow for media permission).

### Player (Shadow Two)
1. SurfaceView + MediaPlayer; auto-play; keep-screen-on while playing.
2. Controls overlay (tap to show/hide, auto-hide 3.5s): back, title, play/pause, seek bar with current/total time, prev/next (via playlist extras `"playlist"` ArrayList<String> + `"index"` Int — MainActivity passes the current folder's list), lock button, speed button, aspect button, subtitle button, audio info.
3. **Gestures (MX signature):** vertical swipe left half = brightness, right half = volume (with on-screen indicator); horizontal swipe = seek with preview label; double-tap left/right thirds = −10s/+10s; single tap toggles controls.
4. Speed control 0.25×–2.0× (PlaybackParams, API 24+ fine). Aspect modes: Fit / Fill (crop) / Stretch / 16:9 / 4:3.
5. **Resume:** on open, seek to Store.resumePos (if >5s and not finished); save position every 5s and on pause/exit; mark finished at ≥95%.
6. **Subtitles:** auto-load sidecar `<videoname>.srt` when readable from the video's directory; manual load via system file picker (ACTION_OPEN_DOCUMENT); overlay TextView rendered from SrtParser with adjustable delay (−5s…+5s, 0.5s steps) and text size (from Store settings); CC toggle.
7. Audio track info via MediaPlayer.getTrackInfo (list audio tracks, select where API allows).
8. Errors shown honestly (unsupported format → message naming the file + likely codec issue, back to library).

## Tests (Monitoring runs, builders keep them green)
- JVM tests for pure logic: SrtParser (timing parse, malformed lines, cueAt + offset), Library grouping/sorting helpers, Store JSON round-trip (positions/settings), time formatting.
- Static verification: kotlinc compile clean, aapt2 badging (package/version/min/target), permissions dump = exactly the 3 above with INTERNET absent, apksigner verify, no .html/.js in APK, APK size + SHA-256 recorded.

## Out of scope for v0.1 (later rounds)
FFmpeg software decoding, network streaming/OTT, Chromecast, downloads, MX Transfer-style sharing, video editor, background audio play, PiP, ASS styling beyond plain text, thumbnail scrubbing previews.
