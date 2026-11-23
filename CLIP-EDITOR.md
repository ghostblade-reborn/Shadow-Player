# Clip editor (spec #107) — wiring + behaviour notes

Delivered by the Shadow Clip Team as the last MISSING surface in
MONITOR-REPORT-V02.md (§4.6, #107).

## Files

- `src/com/ghostblade/player/ClipEditor.kt` — `ClipLogic` (pure,
  JVM-tested) + `ClipTrimmer` (MediaExtractor → MediaMuxer stream
  copy, verify-before-success).
- `src/com/ghostblade/player/ClipEditorActivity.kt` — the surface:
  VideoView preview, dual-handle `RangeSliderView`, readouts,
  Set start / Set end, Save with progress.
- `tests/ClipEditorTest.kt` + `tests/run-clip-tests.sh` — 51 JVM
  checks over `ClipLogic`. Run: `./tests/run-clip-tests.sh`.
- `AndroidManifest.xml` — one `<activity>` entry for
  `.ClipEditorActivity` (added by this team, at the end of its work).

## Launch contract

```kotlin
startActivity(ClipEditorActivity.intent(context, videoPath))
```

`intent(ctx, path)` puts the source path in extra `"path"`.

### Hooks wired

- **Library item menu (MainActivity):** "Create clip" appended as
  the last row of `showItemMenu` (index 12 — append-only, no
  existing rows renumbered). Added only after MainActivity.kt had
  been quiet for the whole session and its owner (Shadow One) had
  reported complete.

### Hooks left for the parent to wire

- **Player Tools menu (Shadow Two's PlayerActivity.kt):** add a
  "Create clip" row that runs
  `startActivity(ClipEditorActivity.intent(this, currentPath))`.
  Not added by this team — PlayerActivity was mid-fix-pass.

## Behaviour (honest stream-copy semantics)

- No re-encoding: samples are copied with MediaMuxer. The saved
  clip starts at the nearest keyframe at or before the chosen
  start (may begin slightly earlier) and ends at the last sample
  at or before the chosen end. The UI says this on screen.
- Output is verified with MediaMetadataRetriever before success
  is reported (duration must be within `ClipLogic.verifyDurationOk`
  tolerance); partial outputs are deleted on any failure.
- Subtitle / timed-text tracks are NOT copied into the clip.
- Minimum clip length: 1 s (`ClipLogic.MIN_CLIP_MS`).
- Output name: `<source base>_clip.<ext>` with " (n)" collision
  suffixes. (MX's exact output suffix wasn't extractable from the
  mined spec; `_clip` is our convention.)

## Format support matrix (as implemented)

| Source codecs | Output | Condition |
|---|---|---|
| H.264 / MPEG-4 SP / H.263 video + AAC audio (or no audio) | `.mp4` | any API |
| HEVC video + AAC | `.mp4` | API 26+ |
| VP8 / VP9 video + Vorbis audio | `.webm` | any API |
| VP8 / VP9 video + Opus audio | `.webm` | API 29+ |
| Anything else (MPEG-2, MP3 audio, AC3/DTS audio, …) | refused with the codec named | would need re-encoding, which this build doesn't do |

Every audio track must fit the chosen container; one incompatible
track sinks the plan (no silent track dropping).

## Where clips are saved

- API 29+: MediaStore insert into the source's own folder when it
  is on primary shared storage, else `Movies/GhostBladePlayer`.
  The entry is IS_PENDING until the trim verifies, then published.
- API 24–28: the app's Movies folder (`getExternalFilesDir`),
  scanned into MediaStore via `FileOps.scanPaths` so it appears in
  the library.
- The result dialog always names the destination; "Play" opens the
  clip in the player when a real path can be resolved.
