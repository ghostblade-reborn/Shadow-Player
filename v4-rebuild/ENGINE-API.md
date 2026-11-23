# ENGINE-API — GhostBlade Player v0.4, Step 1 (Shadow Engine Team)

What the Player Team consumes from the engine, exactly as it exists in
`src/com/ghostblade/player/FFmpegEngine.kt` + `native/gbffmpeg.c` after
this round. Everything below is additive over the v0.3 engine API —
**no existing signature changed, nothing the player already calls was
removed or re-typed.**

Spec basis: MASTER-SCENE-SPEC §S1–S4, §S6, §S7 + Step 1.

---

## 1. SW surface path (§S1) — VERIFIED end-to-end at source level

The path mirrors MX's hand-off exactly: one `setSurface`-style JNI
entry, native owns the ANativeWindow, no frame crosses into Kotlin.

| Step | Call | What happens |
|---|---|---|
| Open | `SwVideoSession.open(path, videoStreamIndex, threads, speedupTricks, enabledCodecLabels)` | Native demux + decoder open (thread_count / skip_loop_filter applied pre-open2). Session is PAUSED, surfaceless. Fires EVENT_PREPARED + EVENT_VIDEO_SIZE (see §2). Returns false → player falls back per MX try-HW rules. |
| Attach | `SwVideoSession.attachSurface(surface: android.view.Surface)` | The single surface entry: JNI `nativeVideoSetSurface` → `ANativeWindow_fromSurface`, buffer geometry set to the DISPLAY size (post-rotation), format RGBA8888; then a repaint of the last shown frame, so a surface recreate restores the picture instead of flashing black. Hand it the SurfaceView's `holder.surface`. |
| Detach | `SwVideoSession.detachSurface()` | `nativeVideoSetSurface(handle, null)`; window released natively. Decoding continues (background play). **Player duty: call this from `surfaceDestroyed`** — the engine cannot know the Surface died. |
| Start | `SwVideoSession.start(clock: () -> Long)` | Starts the render thread slaved to the master clock (the paired `SwAudioSession` via `EngineBridge.position`, exactly as today). Idempotent. Then `play()` / `pause()`. |
| Seek | `SwVideoSession.seekTo(ms)` | Native keyframe seek + decode-and-discard landing; pre-seek frame stays on screen (no black flash). Fires EVENT_SEEK_COMPLETE on success. |
| Close | `SwVideoSession.close()` | Stops + joins the render thread BEFORE the native session is freed (handle leaked rather than use-after-free in the pathological case — same contract as audio). Safe to call twice. |

Verified invariants: surface attach/detach/re-attach mid-playback is
safe (native mutex serialises setSurface against blits); rotation is
applied natively during the blit and `videoWidth`/`videoHeight` are
DISPLAY dimensions, so player aspect/zoom math is unchanged.

**HW+ and probe() regression status:** the audio native code and the
audio Kotlin code were not modified in this round at all — HW+
(engine audio) and `FFmpegEngine.probe()` are unregressed by
construction. `SwAudioSession`'s documented contract (clock,
setDelayMs sign convention, switchAudioStream, allowlists) is
unchanged.

**Defect found & fixed this round (in scope):** the render loop
treated a fatal decode error (peek = PEEK_ERROR) identically to EOF —
`ended` was set and the player's poll then fired a clean completion
for a broken stream. Error is now a distinct terminal state (see §2);
`isEnded()` still returns true for both, preserving the existing poll
contract, and the new `hasError()` disambiguates.

## 2. Event callbacks (§S3) — NEW, MX what-code discipline

MX's FFPlayer pushes state by callback and polls position only. The
SW video session now does the same. Codes live in `SwVideoEvents`:

| Code | Constant | Fires when |
|---|---|---|
| 1 | `EVENT_PREPARED` | open() succeeded |
| 2 | `EVENT_COMPLETION` | stream drained to EOF (never for an error end) |
| 4 | `EVENT_SEEK_COMPLETE` | seekTo()'s native seek returned success (synchronous demux seek + flush; the landing frame is decoded on the next peek) |
| 5 | `EVENT_VIDEO_SIZE` | size known at open(); payload = `videoWidth`/`videoHeight` |
| 100 | `EVENT_ERROR` | fatal decode error; `hasError()` == true |

Two consumption styles — **use exactly one**:

```kotlin
// (a) Listener (push). Callbacks fire on the render thread
//     (completion/error) or the caller's thread (prepared/size/seek)
//     — post to your Handler before touching UI. Listener exceptions
//     are swallowed by the engine.
sv.setListener(object : SwVideoSession.Listener {
    override fun onVideoSizeChanged(width: Int, height: Int) { … }
    override fun onSeekComplete() { … }
    override fun onCompletion() { … }
    override fun onError(what: Int) { … }   // what = SwFrameScheduler.PEEK_ERROR
})
// Registering after open() is safe: the current video size is
// delivered immediately, so what=5 cannot be missed.

// (b) Poll (no listener set): events queue FIFO; drain at the
//     100 ms tick. EVENT_VIDEO_SIZE's payload is sv.videoWidth/Height.
when (sv.pollEvent()) { … }   // SwVideoEvents.EVENT_* or EVENT_NONE (0)
```

Completion/error fire **exactly once per open/seek epoch** (seekTo
re-arms both). While a listener is set, events are not queued.

Audio session events: unchanged — `SwAudioSession` remains poll-only
(`clockMs()`, `isEnded()`), which is sufficient because in every
engine mode the audio session is the clock master, and SW completion
is defined by the player as audio-ended AND video-terminal (the
video side now pushes its half).

## 3. Cheap clock polls (§S2) — what to call at 100 ms

| Need | Call | Cost |
|---|---|---|
| Position (HW+ and SW) | `SwAudioSession.clockMs()` (via `EngineBridge.position(handle)`) | Kotlin arithmetic on the AudioTrack playback head + one JNI getter (`nativeOutRate`, mutex-guarded int read). No decode, no allocation. This IS the master clock — MX's `clock()` poll equivalent. |
| Duration | `SwAudioSession.durationMs()` / `SwVideoSession.durationMs()` | Plain field read natively. |
| Video pending-frame pts | `nativeVideoPeekPts` via the render loop only | **Not a UI poll** — peek tops up the decode ring (it decodes). The player must never call it for position; position is the audio clock, full stop. |
| Terminal state | `SwVideoSession.isEnded()` / `hasError()`, `SwAudioSession.isEnded()` | Volatile flag reads. (Or consume the §2 events and skip polling state entirely.) |

Verdict: the existing clock API is adequate for MX's 100 ms cadence
for both sessions. No new clock entry was needed.

## 4. A/V sync certification (§S4)

**SW path — sync is INSIDE the engine; the Java ticker is NOT needed
and is certified deletable for this path.** Precisely: the paired
`SwAudioSession.clockMs()` (samples-played-based, delay-offset via
`setDelayMs`) is the master clock. The video render thread runs
`SwFrameScheduler` against it per frame: show the pending frame the
moment the clock reaches its pts; skip it only when it is more than
100 ms late (`LATE_DROP_MS`); while a frame is not yet due, prefetch
the next frame into the 2-deep native decode-ahead ring and wait in
≤10 ms interruptible slices (so seeks/pause/delay changes land within
one slice). There is no periodic correction, no hold, no hard-seek —
drift cannot accumulate because every frame is scheduled against the
master clock, not against a video-side timeline. (Corroboration: the
Player Team's in-flight code already posts the sync runnable only
when `engineActive && !swMode`.)

**HW+ path — the ticker STAYS.** There the video renderer is
MediaPlayer, whose clock the engine cannot schedule; the 250 ms
`AvSyncPolicy` loop (hold past 180 ms drift, hard-seek past ±400 ms)
is the staging mechanism that slaves it to the engine audio clock.
Deleting it is only safe for the SW path (and for pure HW, where
MediaPlayer is self-clocked and the ticker never ran).

**HW path:** untouched; MediaPlayer listeners + its own clock, as
before.

## 5. libass (§S7) — LANDED (lab-verified), capability flag OFF in the shipped app

Built for arm64-v8a / API 24 with NDK r27c (meson cross build,
recipe: `codec-lab/libass-lab/build-libass.sh`, artefacts in
`codec-lab/libass-lab/out/lib/`):

| Library | Version | Size | Licence |
|---|---|---|---|
| libfribidi.so | 1.0.16 | 125,664 B | LGPL-2.1 |
| libfreetype.so | 2.13.3 | 824,688 B | FTL (used under FTL) |
| libharfbuzz.so | 10.4.0 | 1,517,552 B | MIT |
| libass.so | 0.17.3 | 472,296 B | ISC |
| libgbass.so (JNI shim, ours) | — | 10,072 B | ours |

All ELF64/AArch64 (readelf-verified). libass's DT_NEEDED = libm,
libfreetype, libfribidi, libharfbuzz, libc — no other deps.
`libgbass.so` compiles warning-free, links against the set, and
exports exactly the two JNI symbols FFmpegEngine declares:
`nativeAssVersion` and `nativeAssRenderFrame` (assjni.c — cached
library/renderer/track per document, source-over compositing of
ASS_Image layers into an RGBA buffer).

Engine integration (in FFmpegEngine.kt, additive):

```kotlin
FFmpegEngine.supportsAssRendering(): Boolean   // runtime probe
FFmpegEngine.assRenderFrame(assText: String, timeMs: Long,
                            width: Int, height: Int): ByteArray?
// → packed RGBA width*height*4 for the styled frame at timeMs,
//   or null when nothing is on screen / capability absent.
//   Draw it into a Bitmap over the SurfaceView (MX's
//   SubtitleOverlay pattern). A bare dialogue body is accepted —
//   the shim wraps it in a minimal ASS document.
```

The probe lazily `loadLibrary`s fribidi → freetype → harfbuzz → ass
→ gbass and asks the shim its version; any miss reads false, and the
main engine `available` flag never depends on it. **The flag is
false in the shipped app until the coordinator's build ships this
.so set in the app's native lib dir** — that packaging step is
outside the Engine Team's two files, and the flag turns true
automatically once the libs are present. No player wiring exists
yet; default off is the correct state.

Honest limits of this libass build:
- **No fontconfig / no system font provider** (deliberately disabled;
  none exists on Android). libass resolves fonts that are embedded
  in the ASS document or added programmatically; family-name lookup
  against system fonts does NOT work in this build. The Player Team
  should ship one default font and register it (a follow-up shim
  entry) before wiring styled ASS as the default path.
- Link-verified on this VM, **not yet run on a device** — first
  on-device render is part of the Step 8 audit.
- libunibreak disabled (libass's internal line-breaking fallback).

## 6. What did NOT change

- All `SwAudioSession` / `SwVideoSession` / `probe()` signatures.
- Native audio JNI surface (15 exports) — byte-identical.
- Native video JNI surface — byte-identical this round; the event
  work is entirely Kotlin-side, driven by the existing peek
  sentinels (PEEK_EOF / PEEK_ERROR), which already carried the
  information — it was the Kotlin terminal handling that conflated
  them.
- No new permissions, no INTERNET anywhere, no MX code or binaries
  used at any point.
