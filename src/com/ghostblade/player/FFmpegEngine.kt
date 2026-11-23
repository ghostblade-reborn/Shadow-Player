package com.ghostblade.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface

/**
 * FFmpegEngine — GhostBlade Player's OWN FFmpeg 7.1.1 engine (LGPL,
 * built from the official source tarball by codec-lab/build-ffmpeg.sh;
 * network compiled out). This is the decoder heart behind the MX-style
 * HW+ / SW audio modes: it decodes audio the phone's hardware path
 * cannot (AC3, E-AC3, DTS, TrueHD, APE, …) to PCM in software.
 *
 * The native side is native/gbffmpeg.c (libgbffmpeg.so) over
 * libavformat/libavcodec/libswresample/libswscale. Two session kinds:
 * [SwAudioSession] (audio decode — the HW+ heart, and the master
 * clock for every engine mode) and [SwVideoSession] (full software
 * VIDEO decode with native ANativeWindow rendering — MX's SW mode,
 * the FFPlayer parity path). In HW+ the hardware video (MediaPlayer)
 * is slaved to the engine's audio clock; in SW the engine's own video
 * renderer is slaved to that same clock, frame by frame.
 *
 * =====================================================================
 * API CONTRACT — Shadow Two consumes exactly this. Do not change
 * signatures without updating PlayerActivity in the same round.
 * =====================================================================
 *
 * [probe] — synchronous, opens the file on the calling thread; call it
 * from a background thread. Returns null for unreadable/unsupported
 * files. [MediaInfo] mirrors MX's Properties dialog fields.
 *
 * [SwAudioSession] — one decoded audio stream feeding an internally
 * managed AudioTrack.
 *
 *   open(path, streamIndex, startMs)
 *     streamIndex: an audio stream index from [probe] (StreamInfo.index),
 *     or -1 for the best/default audio stream. startMs: content position
 *     to begin at (keyframe-seek + discard; lands within a few ms).
 *     Returns false when the file has no decodable audio stream.
 *     After a successful open the session is PAUSED; call [SwAudioSession.play].
 *
 *   play() / pause()
 *     Start/pause the AudioTrack and the decode thread. Pause freezes
 *     [SwAudioSession.clockMs] (the clock derives from the AudioTrack
 *     playback head, so it cannot drift while paused).
 *
 *   seekTo(ms)
 *     Keyframe-aware seek with decode-and-discard landing (bridge-side).
 *     Precision: within a few ms for audio (resampler priming is the
 *     only residual error) — audio seek is effectively sample-accurate,
 *     unlike the video path which lands on video keyframes.
 *     Works while playing or paused. Costs one demux seek + refill,
 *     typically well under a second.
 *
 *   setGain(g)
 *     Linear PCM gain applied in the bridge: 1.0 = unity, 2.0 = MX's
 *     200% volume boost. Range clamped to 0.0–4.0 natively. This is
 *     the ONLY way past 100% volume (AudioTrack volume caps at 1.0).
 *
 *   setSpeed(s)
 *     Playback speed 0.25–4.0 (the player UI uses MX's 0.25–2.0 range).
 *     *** HONEST LIMIT — PITCH IS NOT CORRECTED. *** Speed is done by
 *     resampling (output rate = source rate / speed), i.e. a tape-style
 *     rate change: voices pitch up when faster, down when slower.
 *     Pitch-corrected tempo needs an atempo/SoundTouch-class time-
 *     stretcher; libswresample cannot do it and libavfilter is disabled
 *     in this LGPL build. If pitch-corrected speed matters, it is a
 *     future engine round, not a flag. The HW (MediaPlayer) path keeps
 *     its own pitch-corrected speed for files it can play.
 *
 *   clockMs()
 *     Current content position in ms, samples-played based: the
 *     AudioTrack playback head is converted through the bridge's chunk
 *     timestamps, so it tracks what has actually been HEARD, not what
 *     has been decoded/written. Accuracy is one AudioTrack buffer
 *     (~100–500 ms of scheduling jitter is already excluded — the head
 *     only counts consumed frames). Use this as the master clock for
 *     slaving video, the same design MX/ExoPlayer use.
 *
 *   isEnded()
 *     True once the stream hit EOF and the AudioTrack has played out
 *     everything written. Poll it; there is no callback.
 *
 *   close()
 *     Stops the decode thread, releases the AudioTrack and the native
 *     session. The session is unusable afterwards. Safe to call twice.
 *
 * =====================================================================
 * FIX-PASS ADDITIONS (Monitoring v0.2 findings P2-3 / P2-4)
 * =====================================================================
 *
 *   open(path, streamIndex, startMs, threads, allowedAudioCodecs)
 *     The 5-arg overload is the full form; the 3-arg open delegates
 *     with threads = 0 and no allowlist.
 *     - threads: the Decoder settings "Core limit" (core_limit,
 *       0 = default). Passed to the bridge and applied as the
 *       decoder's thread_count before it opens — a real consumer,
 *       not a record. After a successful open, [SwAudioSession.decoderThreads]
 *       reports the count FFmpeg actually runs with.
 *     - allowedAudioCodecs: the Decoder settings HW+ audio-codec
 *       list (omx_audio_codecs) as RAW FFmpeg codec names ("ac3",
 *       "dts", … — the names in StreamInfo.codecName). When non-null
 *       and the opened stream's codec is not in the set, open fails
 *       (returns false, session closed) so the player can fall back
 *       exactly as MX's try-HW rules intend. Null = everything allowed.
 *
 *   setDelayMs(ms) — the Audio settings audio / Bluetooth delay.
 *     *** SIGN CONVENTION: positive ms = audio is heard LATER than
 *     the video (audio lags); negative ms = audio heard EARLIER
 *     (audio leads). *** Range ±120 s, clamped.
 *     Mechanism (real, not cosmetic): the bridge offsets every chunk
 *     timestamp it reports by ms, so clockMs() — the master clock the
 *     video is slaved to — shifts by exactly ms against the heard
 *     audio. Changing the delay mid-playback re-bases the clock
 *     accounting by the delta, so the shift takes effect immediately,
 *     not at the next seek. Shadow Two: call session.setDelayMs(...)
 *     INSTEAD of folding the delay into EngineBridge.position() —
 *     with the native offset, position() must return clockMs()
 *     unmodified, or the delay is applied twice.
 *     Note: with a negative delay, clockMs() can read slightly
 *     negative at the very start of a stream (by up to |ms|); clamp
 *     for display.
 *
 *   switchAudioStream(streamIndex) — engine-mode audio track
 *     switching. The bridge holds no cross-session state and a native
 *     session is bound to one stream, so switching is a close/reopen
 *     at the current position, done inside the session: it captures
 *     the content position and play state, re-opens the same file on
 *     the new stream (rebuilding the AudioTrack, so a stream with a
 *     different sample rate / channel count is handled correctly),
 *     reapplies gain / speed / delay / threads / allowlist, and
 *     resumes playing if it was playing. Cost is one demux reopen +
 *     seek — the same order as a seek. On failure the session is
 *     CLOSED (same contract as a failed open): fall back to the HW
 *     path. Returns true on success.
 *
 *   Capability gates — supportsPassthrough / supportsDeinterlace /
 *   supportsColorFormatSelection / supportsSpeedupTricks /
 *   supportsAudioOutputSelection / supportsSwVideo /
 *   supportsStereoMode / supportsFixBrokenAudio, plus
 *   gateReason(settingKey) returning the user-visible reason string
 *   for a gated settings key (null = not engine-gated). These exist
 *   so the settings UI can disable a node WITH its reason instead of
 *   persisting a value nothing reads. All return false today; each
 *   KDoc states why, in terms of what the engine actually is.
 *
 * [SwVideoSession] — full software VIDEO decode (MX's SW mode).
 *
 *   open(path, videoStreamIndex, threads, speedupTricks, enabledCodecLabels)
 *     Demuxes + opens the video decoder natively. threads is the
 *     same core_limit input as audio. speedupTricks is the Decoder
 *     settings toggle, applied natively as skip_loop_filter =
 *     AVDISCARD_ALL. enabledCodecLabels, when non-null, is the SW
 *     video-codec list (Settings.OMX_VIDEO_CODECS) as MX LABELS:
 *     the opened stream's label — computed by [SwVideoAllowlist]
 *     from the raw codec name + 10-bit flag, so "H.264" and
 *     "H.264 10bits" are enforced separately, as MX's picker
 *     presents them — must be in the set or the open fails (false,
 *     session closed) and the player falls back, per MX's try-HW
 *     rules. Codecs with no MX label (AV1, Theora, RV…) are never
 *     blocked by the list, mirroring EngineAllowlist's rationale.
 *     After a successful open the session is PAUSED with no surface;
 *     call [SwVideoSession.attachSurface] + [SwVideoSession.start].
 *
 *   Rendering is NATIVE: decoded frames are converted by libswscale
 *   and written straight into the ANativeWindow behind the Surface
 *   — no frame ever crosses into Kotlin (a Kotlin-side copy + GPU
 *   re-upload per frame is exactly the smoothness gap Sir rejected).
 *   Display rotation from the container is applied natively during
 *   the blit; [SwVideoSession.videoWidth] / videoHeight are DISPLAY
 *   dimensions (post-rotation), so the player's aspect/zoom math is
 *   the hardware path's, unchanged.
 *
 *   start(clock) — clock is the MASTER clock provider: the paired
 *   SwAudioSession's clockMs (via EngineBridge.position), the same
 *   master HW+ slaves MediaPlayer to. The render thread runs the
 *   [SwFrameScheduler] policy against it: show each frame when the
 *   clock reaches its pts, skip a frame only when it is more than
 *   LATE_DROP_MS late, and prefetch the next frame during the wait —
 *   frame-accurate sync, versus the HW+ slave loop's 250 ms
 *   seek/hold corrections. Speed needs no video-side handling: the
 *   audio clock already runs at the playback rate, and the video
 *   follows it (pitch limit is the audio session's, unchanged).
 *
 *   seekTo(ms) — keyframe seek + native decode-and-discard landing
 *   (the first frame that COVERS the target is the first shown).
 *   The pre-seek frame stays on screen until then (no black flash).
 *
 *   Events (§S3 parity, v0.4): state is PUSHED, position is polled.
 *   [SwVideoSession.setListener] registers a Listener receiving
 *   prepared / video-size / seek-complete / completion / error in
 *   MX FFPlayer's what-code discipline ([SwVideoEvents]); with no
 *   listener set, the same events queue for
 *   [SwVideoSession.pollEvent] at the player's 100 ms tick.
 *   Completion and error are DISTINCT terminal events (a hard
 *   decode error fires onError, never onCompletion);
 *   [SwVideoSession.hasError] disambiguates the poll contract —
 *   isEnded() stays true for both, as existing consumers expect.
 *
 *   Sync certification (§S4, v0.4): the SW path's sync is INSIDE
 *   the engine. The paired SwAudioSession's clockMs() is the master
 *   clock; the render thread shows/skips each frame against it via
 *   [SwFrameScheduler] (show at pts, drop only when >100 ms late,
 *   prefetch during ≤10 ms wait slices). There is NO Java-side
 *   correction loop on this path and none is needed: the 250 ms
 *   AvSyncPolicy ticker exists ONLY to slave MediaPlayer (HW+),
 *   whose clock the engine cannot control. The Player Team may
 *   delete the ticker for the SW path — certified.
 *
 *   Counters for on-device diagnosis (no log spam, zero cost when
 *   unread): [SwVideoSession.framesShown], droppedFrames() (seek
 *   discards + scheduler skips, counted natively).
 *
 *   Honest limits of the SW path today: it requires the paired
 *   audio session (a video-only file fails the engine open and the
 *   player falls back to HW, exactly as HW+ behaves); embedded
 *   subtitle streams are not decoded (sidecar subtitles work);
 *   the decode-ahead pipeline is 2 frames deep, not MX's deeper
 *   queue; 10-bit/HDR is tone-mapped to 8-bit RGBA by libswscale
 *   (no HDR10 passthrough to the panel).
 *
 * Threading: all SwAudioSession methods may be called from the UI
 * thread; decoding and AudioTrack writes happen on a private thread.
 * One known minimal-player race, stated plainly: a seek issued in the
 * same instant a ≤4096-frame chunk (~85 ms at 48 kHz) is being written
 * can let that one chunk of pre-seek audio through. Generation checks
 * bound it to at most one chunk; it is inaudible in practice next to
 * the seek itself.
 *
 * Sync model (for the player): audio is master. After open/seek, the
 * first decoded chunk re-bases the clock, so a video renderer should
 * re-sync to clockMs() whenever it observes a jump (seek, speed
 * change) rather than integrating its own timeline.
 */
object FFmpegEngine {

    /** True when every native library loaded. All other entry points
     *  degrade gracefully (probe → null, open → false) when false. */
    val available: Boolean = try {
        // Dependency order: leaves first, bridge last.
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("swscale")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("gbffmpeg")
        true
    } catch (t: Throwable) {
        false
    }

    fun isAvailable(): Boolean = available

    /** e.g. "FFmpeg 7.1.1 (libavformat 61.7.100, libavcodec 61.19.101)". */
    fun version(): String =
        if (available) nativeVersion() else "FFmpeg unavailable"

    /** MX Properties-dialog data for [path]; null when unprobeable. */
    fun probe(path: String): MediaInfo? =
        if (available) nativeProbe(path) else null

    // ---- capability gates (fix pass) ----
    // Each function states what the engine really does today. They
    // exist so the settings UI can gate a node WITH its reason (see
    // gateReason) instead of persisting a value nothing consumes.
    // When an engine round makes a gate true, flip it here — one
    // source of truth. (supportsSwVideo / supportsSpeedupTricks went
    // true with the SW video path.)

    /** Audio passthrough (bitstream out over HDMI/USB): the engine
     *  decodes everything to PCM by design; there is no compressed
     *  output path. Gates `prefer_audio_passthrough_mode`. */
    fun supportsPassthrough(): Boolean = false

    /** Deinterlacing (MX's None/Yadif/W3fdif): a video-filter
     *  operation. libavfilter is disabled in this LGPL build, so
     *  neither the SW renderer nor anything else can run yadif —
     *  SW video is presented unfiltered. Gates
     *  `default_deinterlacer`. */
    fun supportsDeinterlace(): Boolean = false

    /** Colour format selection (rgb565 / rgbx8888 / yv12): the SW
     *  renderer writes exactly one surface format — RGBA8888 into
     *  the ANativeWindow — and the hardware path's surface format
     *  belongs to the system render path. There is no selectable
     *  format to offer. Gates `color_format`. */
    fun supportsColorFormatSelection(): Boolean = false

    /** MX's "Use speedup tricks": SW *video* decoder shortcuts.
     *  REAL since the SW video path landed: SwVideoSession.open's
     *  speedupTricks flag sets the decoder's skip_loop_filter =
     *  AVDISCARD_ALL before the decoder opens (PlayerActivity passes
     *  the Decoder settings toggle). Gates `use_speedup_tricks`. */
    fun supportsSpeedupTricks(): Boolean = available

    /** Audio output selection (Auto / OpenSL ES / AudioTrack): the
     *  engine's output is always an Android AudioTrack; OpenSL ES is
     *  not used anywhere in the app. The effective output for every
     *  `audio_device` value is therefore "audiotrack". Gates
     *  `audio_device`. */
    fun supportsAudioOutputSelection(): Boolean = false

    /** Full software VIDEO decoding: REAL — [SwVideoSession]
     *  demuxes and decodes the video stream with the same FFmpeg
     *  build and renders it natively into an ANativeWindow (see the
     *  SwVideoSession contract). The decoders were in the build from
     *  the start (codec-lab enables h264/hevc/vp8/vp9/av1/mpeg4/…);
     *  this gate is simply the engine libraries being loaded. */
    fun supportsSwVideo(): Boolean = available

    /** Stereo mode (Stereo/Mono/Reverse/Auto reverse): the bridge
     *  has no channel-remapping hook; PCM channel layout passes
     *  through as decoded. */
    fun supportsStereoMode(): Boolean = false

    /** MX's "Fix broken audio" resync: the bridge has no such hook;
     *  corrupt packets are already dropped silently at decode time,
     *  which is not the same feature and is not presented as it. */
    fun supportsFixBrokenAudio(): Boolean = false

    /** libass ASS/SSA styled-subtitle rendering (§S7 of the master
     *  spec: MX's SubStationAlpha path). OPTIONAL capability: the
     *  libass set (libfribidi + libfreetype + libharfbuzz + libass +
     *  the libgbass JNI shim, all LGPL-compatible) is a SEPARATE
     *  library set, built in codec-lab/libass-lab/ and NOT part of
     *  the main engine load — [available] never depends on it. This
     *  probe loads the set on first use and asks the shim for its
     *  version; any missing library or symbol reads as false. The
     *  flag stays false until the app actually ships the .so set in
     *  its native lib dir; the player wires ASS rendering only when
     *  this returns true. */
    val assRenderingAvailable: Boolean by lazy {
        try {
            System.loadLibrary("fribidi")
            System.loadLibrary("freetype")
            System.loadLibrary("harfbuzz")
            System.loadLibrary("ass")
            System.loadLibrary("gbass")
            nativeAssVersion() != null
        } catch (t: Throwable) {
            false
        }
    }

    /** See [assRenderingAvailable]. */
    fun supportsAssRendering(): Boolean = assRenderingAvailable

    /** Renders one ASS/SSA frame with libass (the libgbass shim).
     *  Only call when [supportsAssRendering] is true. [assText] is a
     *  complete ASS document (or a single dialogue body wrapped by
     *  the shim's default header when it has no [Script Info]).
     *  Renders the styled subtitle image for [timeMs] at
     *  [width]×[height] and returns packed RGBA bytes of exactly
     *  width*height*4, or null when nothing is on screen at that
     *  time / on any failure. The player draws the result into a
     *  Bitmap over the SurfaceView (MX's SubtitleOverlay pattern). */
    fun assRenderFrame(assText: String, timeMs: Long, width: Int, height: Int): ByteArray? =
        if (assRenderingAvailable) {
            try { nativeAssRenderFrame(assText, timeMs, width, height) }
            catch (t: Throwable) { null }
        } else null

    private external fun nativeAssVersion(): String?
    private external fun nativeAssRenderFrame(assText: String, timeMs: Long, width: Int, height: Int): ByteArray?

    /** User-visible reason a settings node is engine-gated, keyed by
     *  the MX settings key (SETTINGS-KEYS.md). Null = the key is not
     *  gated by the engine (it may still be gated for other reasons
     *  in the settings UI itself). */
    fun gateReason(settingKey: String): String? = when (settingKey) {
        "prefer_audio_passthrough_mode" ->
            "Not available: the built-in engine decodes audio to PCM, so there is no compressed stream to pass through."
        "default_deinterlacer" ->
            "Not available in the engine: deinterlacing needs a video filter and libavfilter is not in this build; software video is presented unfiltered."
        "color_format" ->
            "Not available in the engine: software video renders in RGBA8888 only, and the hardware path's surface format is the system's."
        "audio_device" ->
            "The engine always plays through the Android AudioTrack output; OpenSL ES is not used."
        // "use_speedup_tricks" and "omx_video_codecs.3" are no
        // longer engine-gated: the SW video path consumes both
        // (skip_loop_filter at decoder open; the codec list is the
        // SW open's allowlist, enforced by label incl. 10-bit).
        else -> null
    }

    // ---- native surface (private; SwAudioSession uses the wrappers) ----

    private external fun nativeVersion(): String
    private external fun nativeProbe(path: String): MediaInfo?
    private external fun nativeOpen(path: String, audioStreamIndex: Int, threads: Int): Long
    private external fun nativeDecode(handle: Long, out: ByteArray, meta: LongArray): Int
    private external fun nativeSeek(handle: Long, targetMs: Long): Boolean
    private external fun nativeSetGain(handle: Long, gain: Float)
    private external fun nativeSetSpeed(handle: Long, speed: Float)
    private external fun nativeSetDelayMs(handle: Long, delayMs: Long)
    private external fun nativeThreadCount(handle: Long): Int
    private external fun nativeAudioCodecName(handle: Long): String?
    private external fun nativeOutRate(handle: Long): Int
    private external fun nativeOutChannels(handle: Long): Int
    private external fun nativeBaseRate(handle: Long): Int
    private external fun nativeDurationMs(handle: Long): Long
    private external fun nativeClose(handle: Long)

    internal fun openSession(path: String, streamIndex: Int, threads: Int): Long =
        if (available) nativeOpen(path, streamIndex, threads) else 0L
    internal fun decode(handle: Long, out: ByteArray, meta: LongArray): Int =
        nativeDecode(handle, out, meta)
    internal fun seek(handle: Long, targetMs: Long): Boolean =
        nativeSeek(handle, targetMs)
    internal fun setGain(handle: Long, gain: Float) = nativeSetGain(handle, gain)
    internal fun setSpeed(handle: Long, speed: Float) = nativeSetSpeed(handle, speed)
    internal fun setDelayMs(handle: Long, delayMs: Long) = nativeSetDelayMs(handle, delayMs)
    internal fun threadCount(handle: Long): Int = nativeThreadCount(handle)
    internal fun audioCodecName(handle: Long): String? = nativeAudioCodecName(handle)
    internal fun outRate(handle: Long): Int = nativeOutRate(handle)
    internal fun outChannels(handle: Long): Int = nativeOutChannels(handle)
    internal fun baseRate(handle: Long): Int = nativeBaseRate(handle)
    internal fun durationMs(handle: Long): Long = nativeDurationMs(handle)
    internal fun closeSession(handle: Long) = nativeClose(handle)

    // ---- native SW video surface (SwVideoSession uses the wrappers) ----

    private external fun nativeVideoOpen(path: String, videoStreamIndex: Int, threads: Int, speedup: Boolean): Long
    private external fun nativeVideoSetSurface(handle: Long, surface: Surface?)
    private external fun nativeVideoPeekPts(handle: Long): Long
    private external fun nativeVideoPrefetch(handle: Long)
    private external fun nativeVideoShow(handle: Long): Boolean
    private external fun nativeVideoSkip(handle: Long)
    private external fun nativeVideoRepaint(handle: Long): Boolean
    private external fun nativeVideoSeek(handle: Long, targetMs: Long): Boolean
    private external fun nativeVideoWidth(handle: Long): Int
    private external fun nativeVideoHeight(handle: Long): Int
    private external fun nativeVideoRotation(handle: Long): Int
    private external fun nativeVideoFrameDurMs(handle: Long): Long
    private external fun nativeVideoDurationMs(handle: Long): Long
    private external fun nativeVideoDropped(handle: Long): Long
    private external fun nativeVideoIs10Bit(handle: Long): Boolean
    private external fun nativeVideoCodecName(handle: Long): String?
    private external fun nativeVideoClose(handle: Long)

    internal fun openVideoSession(path: String, streamIndex: Int, threads: Int, speedup: Boolean): Long =
        if (available) nativeVideoOpen(path, streamIndex, threads, speedup) else 0L
    internal fun videoSetSurface(handle: Long, surface: Surface?) = nativeVideoSetSurface(handle, surface)
    internal fun videoPeekPts(handle: Long): Long = nativeVideoPeekPts(handle)
    internal fun videoPrefetch(handle: Long) = nativeVideoPrefetch(handle)
    internal fun videoShow(handle: Long): Boolean = nativeVideoShow(handle)
    internal fun videoSkip(handle: Long) = nativeVideoSkip(handle)
    internal fun videoRepaint(handle: Long): Boolean = nativeVideoRepaint(handle)
    internal fun videoSeek(handle: Long, targetMs: Long): Boolean = nativeVideoSeek(handle, targetMs)
    internal fun videoWidth(handle: Long): Int = nativeVideoWidth(handle)
    internal fun videoHeight(handle: Long): Int = nativeVideoHeight(handle)
    internal fun videoRotation(handle: Long): Int = nativeVideoRotation(handle)
    internal fun videoFrameDurMs(handle: Long): Long = nativeVideoFrameDurMs(handle)
    internal fun videoDurationMs(handle: Long): Long = nativeVideoDurationMs(handle)
    internal fun videoDropped(handle: Long): Long = nativeVideoDropped(handle)
    internal fun videoIs10Bit(handle: Long): Boolean = nativeVideoIs10Bit(handle)
    internal fun videoCodecName(handle: Long): String? = nativeVideoCodecName(handle)
    internal fun closeVideoSession(handle: Long) = nativeVideoClose(handle)
}

/** One stream of a media file, in MX's Properties-dialog terms.
 *  [codec] is the MX-style label ("AC3", "H.265", "H.264 10bits", …);
 *  [codecName] is FFmpeg's raw codec name ("ac3", "hevc", …).
 *  [bitrate] is bits/s, 0 when unknown. width/height are 0 for
 *  non-video streams; sampleRate/channels are 0 for non-audio. */
data class StreamInfo(
    val index: Int,
    val type: String,       // "video" | "audio" | "subtitle" | "data" | "unknown"
    val codec: String,
    val codecName: String,
    val language: String?,
    val title: String?,
    val bitrate: Long,
    val width: Int,
    val height: Int,
    val sampleRate: Int,
    val channels: Int
)

/** Container-level probe result, mirroring MX's Properties dialog.
 *  [format] is the long container name ("Matroska / WebM"),
 *  [formatName] the short demuxer name ("matroska,webm").
 *  [durationMs] is -1 when the container doesn't declare one;
 *  [bitrate] is bits/s, 0 when unknown. */
data class MediaInfo(
    val format: String,
    val formatName: String,
    val durationMs: Long,
    val bitrate: Long,
    val streams: List<StreamInfo>
)

/**
 * A software-decoded audio playback session: FFmpeg decodes, an
 * internal AudioTrack plays. See the FFmpegEngine header for the full
 * contract, the sync model, and the honest speed/pitch limit.
 */
class SwAudioSession {

    private var handle: Long = 0
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var closed = false
    @Volatile private var playing = false
    @Volatile private var eofDrained = false

    private val stateLock = Object()

    // Clock accounting (see header): baseContentMs is the content
    // position that corresponded to the AudioTrack head headAtBase.
    @Volatile private var baseContentMs = 0L
    @Volatile private var headAtBase = 0
    @Volatile private var needBase = true
    @Volatile private var lastChunkEndMs = 0L
    @Volatile private var framesSinceBase = 0L
    @Volatile private var generation = 0

    /** Source (pre-speed) sample rate of the opened stream, 0 if closed. */
    @Volatile var sourceSampleRate: Int = 0
        private set

    /** Raw FFmpeg codec name of the opened stream ("ac3", "dts", …),
     *  null when closed. Same naming as StreamInfo.codecName. */
    @Volatile var audioCodecName: String? = null
        private set

    /** Decoder thread_count actually in effect (the core_limit value
     *  after FFmpeg's own clamping), 0 when closed. */
    @Volatile var decoderThreads: Int = 0
        private set

    // Open parameters, retained so switchAudioStream can reopen the
    // same file on another stream with identical options.
    private var openedPath: String? = null
    private var openedStreamIndex: Int = -1
    private var openedThreads: Int = 0
    private var openedAllowedCodecs: Set<String>? = null

    // Last-applied output shaping. These live in the session object
    // (not only natively) so a reopen — track switch — reapplies
    // them; open() applies whatever the session currently holds, so
    // a fresh session starts at unity gain / 1.0 speed / 0 delay and
    // a switched session keeps the user's settings.
    @Volatile private var appliedGain = 1.0f
    @Volatile private var appliedSpeed = 1.0f
    @Volatile private var appliedDelayMs = 0L

    // Content position of the last open/seek target. clockMs() is
    // only meaningful once a chunk has been decoded (needBase); this
    // is the position estimate until then, and what a track switch
    // reopens at when paused right after a seek.
    @Volatile private var lastTargetMs = 0L

    /** Duration from the container in ms, -1 when unknown/closed. */
    fun durationMs(): Long =
        if (handle != 0L) FFmpegEngine.durationMs(handle) else -1

    /** Opens with default engine options (FFmpeg-default thread
     *  count, every audio codec allowed). See the 5-arg overload. */
    fun open(path: String, streamIndex: Int, startMs: Long): Boolean =
        open(path, streamIndex, startMs, threads = 0, allowedAudioCodecs = null)

    /**
     * Full open. [threads] is the Decoder settings core limit
     * (0 = FFmpeg default) — applied natively as the decoder's
     * thread_count. [allowedAudioCodecs], when non-null, is the
     * HW+ audio-codec allowlist as raw FFmpeg codec names: if the
     * chosen stream's codec is not in the set, the open fails
     * (returns false, session closed) so the caller can fall back.
     * The session's current gain / speed / delay are (re)applied —
     * unity on a fresh session, the user's values after a track
     * switch. After a successful open the session is PAUSED.
     */
    fun open(path: String, streamIndex: Int, startMs: Long,
             threads: Int, allowedAudioCodecs: Set<String>?): Boolean {
        close()
        closed = false
        openedPath = path
        openedStreamIndex = streamIndex
        openedThreads = threads
        openedAllowedCodecs = allowedAudioCodecs
        if (!FFmpegEngine.isAvailable()) return false
        val h = FFmpegEngine.openSession(path, streamIndex, threads)
        if (h == 0L) return false
        handle = h
        val codec = FFmpegEngine.audioCodecName(h)
        if (allowedAudioCodecs != null && codec != null &&
            codec !in allowedAudioCodecs) {
            // Codec disabled in the Decoder settings HW+ list:
            // refuse the open so the player falls back per MX's
            // try-HW rules instead of playing a disabled codec.
            FFmpegEngine.closeSession(h); handle = 0; return false
        }
        audioCodecName = codec
        decoderThreads = FFmpegEngine.threadCount(h)
        val rate = FFmpegEngine.baseRate(h)
        val channels = FFmpegEngine.outChannels(h)
        if (rate <= 0 || channels <= 0) {
            FFmpegEngine.closeSession(h); handle = 0; return false
        }
        sourceSampleRate = rate
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO
                   else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(
            rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(if (minBuf > 0) minBuf * 2 else 0,
                            rate * channels * 2 / 2) // ~0.5 s of audio
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            FFmpegEngine.closeSession(h); handle = 0; return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            FFmpegEngine.closeSession(h); handle = 0; return false
        }
        track = t
        if (startMs > 0) FFmpegEngine.seek(h, startMs)
        // Reapply the session's output shaping to the fresh native
        // session (no-ops at the defaults on a first open; restores
        // the user's gain/speed/delay after a track switch).
        if (appliedGain != 1.0f) FFmpegEngine.setGain(h, appliedGain)
        if (appliedSpeed != 1.0f) FFmpegEngine.setSpeed(h, appliedSpeed)
        if (appliedDelayMs != 0L) FFmpegEngine.setDelayMs(h, appliedDelayMs)
        lastTargetMs = if (startMs > 0) startMs else 0L
        needBase = true
        eofDrained = false
        lastChunkEndMs = 0
        startThread()
        return true
    }

    /**
     * Switches the decoded audio stream mid-playback (engine-mode
     * audio track switching). Reopens the same file on [streamIndex]
     * at the current content position, preserving play/pause state,
     * gain, speed, delay, thread limit and codec allowlist; the
     * AudioTrack is rebuilt, so a stream with a different sample
     * rate or channel count works. Returns false when the session
     * is closed or the reopen fails — on failure the session is
     * CLOSED (the failed-open contract); fall back to the HW path.
     * See the FFmpegEngine header for the full recipe/semantics.
     */
    fun switchAudioStream(streamIndex: Int): Boolean {
        val path = openedPath ?: return false
        if (handle == 0L || closed) return false
        val wasPlaying = playing
        // clockMs() rides on the delay-offset reported timeline;
        // subtract the delay back out to get the true content
        // position to reopen at. Before the first decoded chunk
        // (needBase) the last open/seek target is the position.
        val contentPos = if (needBase) lastTargetMs
            else (clockMs() - appliedDelayMs).coerceAtLeast(0L)
        val ok = open(path, streamIndex, contentPos,
                      openedThreads, openedAllowedCodecs)
        if (ok && wasPlaying) play()
        return ok
    }

    fun play() {
        val t = track ?: return
        if (closed) return
        playing = true
        try { t.play() } catch (e: IllegalStateException) { /* released */ }
        synchronized(stateLock) { stateLock.notifyAll() }
    }

    fun pause() {
        playing = false
        val t = track ?: return
        try { t.pause() } catch (e: IllegalStateException) { /* released */ }
    }

    fun seekTo(ms: Long) {
        val h = handle; if (h == 0L || closed) return
        val target = if (ms < 0) 0 else ms
        lastTargetMs = target
        generation++
        FFmpegEngine.seek(h, target)
        val t = track
        if (t != null) {
            try { t.flush() } catch (e: IllegalStateException) { /* released */ }
        }
        needBase = true
        eofDrained = false
        synchronized(stateLock) { stateLock.notifyAll() }
    }

    /** Linear gain: 1.0 = unity, 2.0 = MX's 200% boost. */
    fun setGain(g: Float) {
        appliedGain = g
        val h = handle; if (h == 0L) return
        FFmpegEngine.setGain(h, g)
    }

    /** Speed 0.25–2.0 in the player UI. PITCH SHIFTS with speed —
     *  see the FFmpegEngine header for the honest limit. */
    fun setSpeed(s: Float) {
        appliedSpeed = s
        val h = handle; if (h == 0L || closed) return
        // Snapshot the clock under the old rate before switching.
        val now = clockMs()
        FFmpegEngine.setSpeed(h, s)
        baseContentMs = now
        headAtBase = track?.playbackHeadPosition ?: 0
        framesSinceBase = 0
        needBase = false
    }

    /**
     * Audio delay (the Audio settings audio / Bluetooth delay).
     * *** SIGN: positive = audio heard LATER than video (lags);
     * negative = audio heard EARLIER (leads). *** Clamped to
     * ±120 s. The bridge offsets every reported chunk timestamp by
     * this amount, so clockMs() — the master clock — shifts against
     * the heard audio by exactly this much; mid-playback changes
     * re-base the clock accounting by the delta and take effect
     * immediately. Safe to call before open (the value is stored
     * and applied by open). See the FFmpegEngine header.
     */
    fun setDelayMs(ms: Long) {
        val clamped = ms.coerceIn(-120_000L, 120_000L)
        val delta = clamped - appliedDelayMs
        appliedDelayMs = clamped
        val h = handle; if (h == 0L || closed) return
        FFmpegEngine.setDelayMs(h, clamped)
        if (delta != 0L && !needBase) {
            // Future chunks arrive already offset by the new delay;
            // shift the running clock base by the same delta so the
            // reported position steps by exactly the delay change
            // (that step IS the audio/video shift the user asked
            // for) instead of only drifting in at the next seek.
            baseContentMs += delta
            if (lastChunkEndMs > 0) lastChunkEndMs += delta
        }
    }

    /** The delay most recently set via [setDelayMs] (0 if none). */
    fun delayMs(): Long = appliedDelayMs

    /** Samples-played-based content position; see header. */
    fun clockMs(): Long {
        val t = track ?: return lastChunkEndMs
        if (needBase) return lastChunkEndMs
        val outRate = if (handle != 0L) FFmpegEngine.outRate(handle) else 0
        if (outRate <= 0) return lastChunkEndMs
        val playedFrames = (t.playbackHeadPosition - headAtBase).toLong()
        if (playedFrames < 0) return baseContentMs
        val ms = baseContentMs + playedFrames * 1000L / outRate
        return if (lastChunkEndMs > 0 && ms > lastChunkEndMs) lastChunkEndMs else ms
    }

    fun isEnded(): Boolean {
        if (!eofDrained) return false
        val t = track ?: return true
        if (needBase) return lastChunkEndMs > 0
        val playedFrames = (t.playbackHeadPosition - headAtBase).toLong()
        return playedFrames >= framesSinceBase - 1
    }

    fun close() {
        if (closed && handle == 0L) return
        closed = true
        playing = false
        synchronized(stateLock) { stateLock.notifyAll() }
        val t = track
        if (t != null) {
            // Unblock a decode thread parked in AudioTrack.write first.
            try { t.pause() } catch (e: Exception) { /* ignore */ }
            try { t.flush() } catch (e: Exception) { /* ignore */ }
        }
        val th = thread
        var threadDead = true
        if (th != null) {
            try { th.join(600) } catch (e: InterruptedException) { /* ignore */ }
            if (th.isAlive) {
                try { th.join(1000) } catch (e: InterruptedException) { /* ignore */ }
            }
            threadDead = !th.isAlive
            thread = null
        }
        track = null
        if (t != null) {
            try { t.release() } catch (e: Exception) { /* ignore */ }
        }
        val h = handle
        handle = 0
        // Only free the native session once the decode thread is dead;
        // in the pathological case we leak the handle rather than risk
        // a use-after-free crash.
        if (h != 0L && threadDead) FFmpegEngine.closeSession(h)
        sourceSampleRate = 0
        audioCodecName = null
        decoderThreads = 0
        openedPath = null
        openedStreamIndex = -1
        // appliedGain / appliedSpeed / appliedDelayMs deliberately
        // survive close(): open() reapplies them, which is what makes
        // switchAudioStream keep the user's output shaping.
    }

    // ---- decode thread ----

    private fun startThread() {
        val th = Thread {
            val chunkFrames = 4096
            val channels = if (handle != 0L) FFmpegEngine.outChannels(handle) else 2
            val buf = ByteArray(chunkFrames * channels * 2)
            val meta = LongArray(2)
            while (!closed) {
                if (!playing) {
                    synchronized(stateLock) {
                        if (!playing && !closed) {
                            try { stateLock.wait(200) } catch (e: InterruptedException) { /* loop */ }
                        }
                    }
                    continue
                }
                val h = handle
                val t = track
                if (h == 0L || t == null) break
                // Snapshot the generation BEFORE decoding: if a seek
                // lands while this chunk is in flight, the chunk is
                // pre-seek audio and must be discarded, not written.
                val gen = generation
                val n = FFmpegEngine.decode(h, buf, meta)
                if (n < 0) {          // fatal decode error: end the stream
                    eofDrained = true
                    break
                }
                if (n == 0) {
                    if (meta[1] == 1L) {
                        eofDrained = true
                        // Stay alive (paused semantics) so a later
                        // seekTo can revive the session.
                        synchronized(stateLock) {
                            try { stateLock.wait(200) } catch (e: InterruptedException) { /* loop */ }
                        }
                    } else {
                        // Decoder momentarily empty; brief yield.
                        try { Thread.sleep(5) } catch (e: InterruptedException) { /* loop */ }
                    }
                    continue
                }
                if (gen != generation) continue   // stale chunk after seek
                val outRate = FFmpegEngine.outRate(h)
                if (needBase) {
                    baseContentMs = meta[0]
                    headAtBase = t.playbackHeadPosition
                    framesSinceBase = 0
                    needBase = false
                }
                if (outRate > 0) {
                    val end = meta[0] + n.toLong() * 1000L / outRate
                    if (end > lastChunkEndMs) lastChunkEndMs = end
                }
                var off = 0
                val bytes = n * channels * 2
                while (off < bytes && !closed && gen == generation) {
                    val w = t.write(buf, off, bytes - off)
                    if (w < 0) break
                    off += w
                }
                if (gen == generation) framesSinceBase += n
                if (meta[1] == 1L) eofDrained = true
            }
        }
        th.name = "GbSwAudioDecode"
        th.isDaemon = true
        thread = th
        th.start()
    }
}

/**
 * The SW video frame-sync policy — pure logic, unit-tested on the
 * JVM (PlayerLogicTest). The render thread holds ONE pending frame's
 * pts (peeked from the native 2-frame decode-ahead ring) and asks:
 * given the master (audio) clock, should this frame be shown now,
 * skipped, or waited for?
 *
 * The thresholds ARE the smoothness behaviour:
 * - A frame is shown the moment the clock reaches its pts — no
 *   fixed cadence, no polling interval quantisation beyond the
 *   ≤[MAX_WAIT_MS] wait slices (themselves interrupted by any
 *   seek/pause/close notification).
 * - A frame is SKIPPED only when it is more than [LATE_DROP_MS]
 *   late: below that, showing a slightly late frame is smoother
 *   than dropping it (a drop is a visible judder step; a 40 ms-late
 *   frame is not). When the decoder falls behind (heavy file, slow
 *   seek), skips let the picture catch up to the audio in a few
 *   frames instead of drifting.
 * - EOF / hard error both read as ENDED: the last frame stays on
 *   screen (the native side keeps it in the window), and the
 *   player completes off the audio session's end as well.
 */
object SwFrameScheduler {
    /** Pending-pts sentinel: stream drained (native GBV_PEEK_EOF). */
    const val PEEK_EOF = -2L
    /** Pending-pts sentinel: fatal decode error (native GBV_PEEK_ERROR). */
    const val PEEK_ERROR = -1L

    /** How late a frame may be before dropping beats showing. */
    const val LATE_DROP_MS = 100L

    /** Longest single wait slice while a frame is not yet due. */
    const val MAX_WAIT_MS = 10L

    enum class Decision { SHOW, SKIP, WAIT, ENDED }

    fun decide(pendingPtsMs: Long, clockMs: Long): Decision = when {
        pendingPtsMs == PEEK_EOF || pendingPtsMs == PEEK_ERROR -> Decision.ENDED
        clockMs - pendingPtsMs > LATE_DROP_MS -> Decision.SKIP
        pendingPtsMs <= clockMs -> Decision.SHOW
        else -> Decision.WAIT
    }

    /** Sleep slice for a not-yet-due frame: until it is due, capped
     *  at [MAX_WAIT_MS] so a clock jump (seek, delay change) is
     *  picked up within one slice. Never less than 1 ms. */
    fun waitMs(pendingPtsMs: Long, clockMs: Long): Long =
        (pendingPtsMs - clockMs).coerceIn(1L, MAX_WAIT_MS)

    /** Display dimensions for a coded size + clockwise display
     *  rotation: 90/270 swap the axes, 0/180 (and any unknown value)
     *  keep them. Mirrors the native session's own computation —
     *  kept here so the rule is testable and reusable. */
    fun displaySize(codedW: Int, codedH: Int, rotationDeg: Int): Pair<Int, Int> =
        if (rotationDeg == 90 || rotationDeg == 270) codedH to codedW
        else codedW to codedH
}

/**
 * MX's SW video-codec picker (Settings.OMX_VIDEO_CODEC_LIST) in
 * label space. The list is enforced by LABEL, not raw codec name,
 * because MX's picker separates "H.264" from "H.264 10bits" while
 * both are the same FFmpeg codec — the 10-bit flag (from the
 * stream's pixel format, read natively) picks the label.
 *
 * A codec with NO MX label (AV1, Theora, RealVideo, …) maps to null
 * and is never blocked by the list — the same rationale as
 * EngineAllowlist's audio map: MX's 13 labels do not cover every
 * codec the engine decodes, and restricting unmapped codecs at
 * default would break those files for no user decision.
 */
object SwVideoAllowlist {
    fun labelFor(rawCodec: String?, is10Bit: Boolean): String? = when (rawCodec) {
        "h264" -> if (is10Bit) "H.264 10bits" else "H.264"
        "hevc" -> if (is10Bit) "H.265 10bits" else "H.265"
        "h263", "h263p" -> "H.263"
        "mpeg1video" -> "MPEG-1"
        "mpeg2video" -> "MPEG-2"
        "mpeg4", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3" -> "MPEG-4"
        "mjpeg", "mjpegb" -> "MJPEG"
        "vp8" -> "VP8"
        "vp9" -> "VP9"
        "wmv3" -> "WMV3"
        "vc1" -> "VC-1"
        else -> null
    }

    /** True when a stream with this label may open under
     *  [enabledLabels] (null set = no restriction). */
    fun allowed(rawCodec: String?, is10Bit: Boolean, enabledLabels: Set<String>?): Boolean {
        if (enabledLabels == null) return true
        val label = labelFor(rawCodec, is10Bit) ?: return true
        return label in enabledLabels
    }
}

/**
 * Event codes for [SwVideoSession], mirroring MX FFPlayer's
 * Handler.Callback message discipline (master spec §S3): the engine
 * PUSHES state, the player polls position only. A consumer uses
 * exactly one delivery style: a [SwVideoSession.Listener] (events
 * delivered as they happen), or [SwVideoSession.pollEvent] drained
 * from the player's 100 ms tick (events queue only while no listener
 * is set). [EVENT_VIDEO_SIZE]'s payload is the session's
 * videoWidth/videoHeight properties.
 */
object SwVideoEvents {
    const val EVENT_NONE = 0
    /** MX what=1: session opened, first frame decodable. */
    const val EVENT_PREPARED = 1
    /** MX what=2: stream drained to EOF (NOT an error end). */
    const val EVENT_COMPLETION = 2
    /** MX what=4: a seekTo() landed (landing frame follows). */
    const val EVENT_SEEK_COMPLETE = 4
    /** MX what=5: display size known/changed (w<<16|h in MX). */
    const val EVENT_VIDEO_SIZE = 5
    /** MX what=100: fatal decode error; see SwVideoSession.hasError. */
    const val EVENT_ERROR = 100
}

/**
 * A software-decoded video playback session: FFmpeg decodes, the
 * native bridge renders into an attached [Surface]. See the
 * FFmpegEngine header for the full contract and honest limits.
 *
 * Lifecycle mirrors [SwAudioSession]: open (paused) → attachSurface
 * → start(clock) → play/pause/seekTo → close. The render thread is
 * the only native caller while running; close() stops and joins it
 * before freeing the native session (leaking the handle rather than
 * risking a use-after-free in the pathological case, same as audio).
 */
class SwVideoSession {

    /** State callbacks, MX FFPlayer style (§S3): state is PUSHED,
     *  position is polled. Callbacks fire on the render thread
     *  (completion/error) or the caller's thread (prepared /
     *  video-size / seek-complete) — post to a Handler before
     *  touching UI. Exceptions thrown by a listener are swallowed:
     *  a broken listener must never kill the render thread. */
    interface Listener {
        fun onPrepared() {}
        fun onVideoSizeChanged(width: Int, height: Int) {}
        fun onSeekComplete() {}
        fun onCompletion() {}
        /** [what] is the SwFrameScheduler peek sentinel that failed
         *  (PEEK_ERROR); the session is terminal — hasError() true,
         *  isEnded() true for the player's existing poll contract. */
        fun onError(what: Int) {}
    }

    private var handle: Long = 0
    private var thread: Thread? = null

    @Volatile private var closed = false
    @Volatile private var playing = false
    @Volatile private var ended = false
    @Volatile private var started = false

    /** True when the terminal state was a fatal decode error rather
     *  than EOF. Distinct from [isEnded] (which stays true for both,
     *  preserving the poll contract the player already consumes). */
    @Volatile private var error = false
    @Volatile private var completionFired = false
    @Volatile private var errorFired = false

    @Volatile private var listener: Listener? = null
    private val eventLock = Object()
    private val pendingEvents = ArrayDeque<Int>()

    private val stateLock = Object()
    private var clockProvider: (() -> Long)? = null

    /** Registers (or clears, with null) the event listener. If the
     *  session is already open, the current video size is delivered
     *  immediately, so registering after open() cannot miss the
     *  MX what=5 event. While a listener is set, events are NOT
     *  queued for [pollEvent]. */
    fun setListener(l: Listener?) {
        listener = l
        if (l != null && handle != 0L && videoWidth > 0) {
            try { l.onVideoSizeChanged(videoWidth, videoHeight) }
            catch (t: Throwable) { /* listener's problem, not ours */ }
        }
    }

    /** Poll-style event delivery for consumers without a listener:
     *  returns the oldest queued [SwVideoEvents] code, or
     *  [SwVideoEvents.EVENT_NONE]. Cheap; safe at the 100 ms tick. */
    fun pollEvent(): Int = synchronized(eventLock) {
        if (pendingEvents.isEmpty()) SwVideoEvents.EVENT_NONE
        else pendingEvents.removeFirst()
    }

    /** True when playback ended on a fatal decode error. Check this
     *  before treating [isEnded] as a clean completion. */
    fun hasError(): Boolean = error

    private fun fireEvent(code: Int, errorWhat: Int = 0) {
        val l = listener
        if (l == null) {
            synchronized(eventLock) { pendingEvents.addLast(code) }
            return
        }
        try {
            when (code) {
                SwVideoEvents.EVENT_PREPARED -> l.onPrepared()
                SwVideoEvents.EVENT_VIDEO_SIZE ->
                    l.onVideoSizeChanged(videoWidth, videoHeight)
                SwVideoEvents.EVENT_SEEK_COMPLETE -> l.onSeekComplete()
                SwVideoEvents.EVENT_COMPLETION -> l.onCompletion()
                SwVideoEvents.EVENT_ERROR -> l.onError(errorWhat)
            }
        } catch (t: Throwable) { /* never kill the render thread */ }
    }

    /** Terminal-state bookkeeping for the render loop: EOF fires
     *  completion, a hard decode error fires error — exactly once
     *  per seek/open epoch (seekTo re-arms both). [isEnded] becomes
     *  true either way, as the player's existing poll expects. */
    private fun noteTerminal(peekSentinel: Long) {
        ended = true
        if (peekSentinel == SwFrameScheduler.PEEK_ERROR) {
            error = true
            if (!errorFired) {
                errorFired = true
                fireEvent(SwVideoEvents.EVENT_ERROR, SwFrameScheduler.PEEK_ERROR.toInt())
            }
        } else if (!completionFired) {
            completionFired = true
            fireEvent(SwVideoEvents.EVENT_COMPLETION)
        }
    }

    /** Display width (post-rotation), 0 when closed. */
    @Volatile var videoWidth: Int = 0
        private set
    /** Display height (post-rotation), 0 when closed. */
    @Volatile var videoHeight: Int = 0
        private set
    /** Clockwise display rotation applied natively (0/90/180/270). */
    @Volatile var rotationDeg: Int = 0
        private set
    /** One frame's duration in ms (from the stream's frame rate). */
    @Volatile var frameDurMs: Long = 0
        private set
    /** Raw FFmpeg codec name of the opened stream ("hevc", …). */
    @Volatile var videoCodecName: String? = null
        private set
    /** Frames actually handed to the window (diagnosis counter). */
    @Volatile var framesShown: Long = 0
        private set

    /** Duration from the container in ms, -1 when unknown/closed. */
    fun durationMs(): Long =
        if (handle != 0L) FFmpegEngine.videoDurationMs(handle) else -1

    /** Frames dropped so far (seek-landing discards + scheduler
     *  skips), counted natively. Diagnosis counter. */
    fun droppedFrames(): Long =
        if (handle != 0L) FFmpegEngine.videoDropped(handle) else 0

    fun isEnded(): Boolean = ended

    /**
     * Opens the video stream of [path]. [videoStreamIndex] is an
     * FFmpeg stream index from probe, or -1 for the best video
     * stream. [threads] is the Decoder settings core limit.
     * [speedupTricks] applies the native skip_loop_filter shortcut.
     * [enabledCodecLabels] is the SW video-codec list as MX labels
     * (see [SwVideoAllowlist]); a stream whose label is disabled
     * fails the open (returns false, session closed) so the player
     * can fall back. After a successful open the session is PAUSED
     * and has no surface.
     */
    fun open(path: String, videoStreamIndex: Int, threads: Int,
             speedupTricks: Boolean, enabledCodecLabels: Set<String>?): Boolean {
        close()
        closed = false
        if (!FFmpegEngine.isAvailable()) return false
        val h = FFmpegEngine.openVideoSession(path, videoStreamIndex, threads, speedupTricks)
        if (h == 0L) return false
        handle = h
        val codec = FFmpegEngine.videoCodecName(h)
        if (!SwVideoAllowlist.allowed(codec, FFmpegEngine.videoIs10Bit(h), enabledCodecLabels)) {
            // Codec disabled in the SW video-codec list: refuse the
            // open so the player falls back per MX's try-HW rules.
            FFmpegEngine.closeVideoSession(h); handle = 0; return false
        }
        videoCodecName = codec
        videoWidth = FFmpegEngine.videoWidth(h)
        videoHeight = FFmpegEngine.videoHeight(h)
        rotationDeg = FFmpegEngine.videoRotation(h)
        frameDurMs = FFmpegEngine.videoFrameDurMs(h)
        if (videoWidth <= 0 || videoHeight <= 0) {
            FFmpegEngine.closeVideoSession(h); handle = 0; return false
        }
        ended = false
        error = false
        completionFired = false
        errorFired = false
        synchronized(eventLock) { pendingEvents.clear() }
        framesShown = 0
        fireEvent(SwVideoEvents.EVENT_PREPARED)
        fireEvent(SwVideoEvents.EVENT_VIDEO_SIZE)
        return true
    }

    /** Attaches the render target. The native side (re)sets the
     *  window geometry to the display size and repaints the last
     *  shown frame, so a surface recreate (rotation, PiP, returning
     *  from background) restores the picture instead of flashing
     *  black. */
    fun attachSurface(surface: Surface) {
        val h = handle; if (h == 0L || closed) return
        FFmpegEngine.videoSetSurface(h, surface)
        FFmpegEngine.videoRepaint(h)
    }

    /** Detaches the render target. Decoding continues (audio-master
     *  playback in the background shows nothing, exactly like MX's
     *  background play with the SW decoder). */
    fun detachSurface() {
        val h = handle; if (h == 0L) return
        FFmpegEngine.videoSetSurface(h, null)
    }

    /** Starts the render thread slaved to [clock] (the master audio
     *  clock in ms; negative readings are treated as 0). Idempotent. */
    fun start(clock: () -> Long) {
        if (started || handle == 0L || closed) return
        started = true
        clockProvider = clock
        val th = Thread { renderLoop() }
        th.name = "GbSwVideoRender"
        th.isDaemon = true
        thread = th
        th.start()
    }

    fun play() {
        if (closed) return
        playing = true
        synchronized(stateLock) { stateLock.notifyAll() }
    }

    fun pause() {
        playing = false
        synchronized(stateLock) { stateLock.notifyAll() }
    }

    /** Keyframe seek with native decode-and-discard landing. Works
     *  playing or paused; a paused session re-presents the frame
     *  covering the target (the render thread wakes). */
    fun seekTo(ms: Long) {
        val h = handle; if (h == 0L || closed) return
        val ok = FFmpegEngine.videoSeek(h, if (ms < 0) 0 else ms)
        ended = false
        error = false
        completionFired = false
        errorFired = false
        synchronized(stateLock) { stateLock.notifyAll() }
        // The native seek is synchronous (demux seek + decoder flush
        // done before it returns; the landing frame is decoded on the
        // next peek), so seek-complete fires here on the caller's
        // thread, MX what=4 style.
        if (ok) fireEvent(SwVideoEvents.EVENT_SEEK_COMPLETE)
    }

    fun close() {
        if (closed && handle == 0L) return
        closed = true
        playing = false
        synchronized(stateLock) { stateLock.notifyAll() }
        val th = thread
        var threadDead = true
        if (th != null) {
            try { th.join(600) } catch (e: InterruptedException) { /* ignore */ }
            if (th.isAlive) {
                try { th.join(1000) } catch (e: InterruptedException) { /* ignore */ }
            }
            threadDead = !th.isAlive
            thread = null
        }
        started = false
        clockProvider = null
        val h = handle
        handle = 0
        // Only free the native session once the render thread is
        // dead; in the pathological case we leak the handle rather
        // than risk a use-after-free crash (SwAudioSession contract).
        if (h != 0L && threadDead) FFmpegEngine.closeVideoSession(h)
        videoWidth = 0
        videoHeight = 0
        rotationDeg = 0
        frameDurMs = 0
        videoCodecName = null
    }

    // ---- render thread ----

    private fun clockMs(): Long {
        val c = clockProvider ?: return 0L
        val v = try { c() } catch (t: Throwable) { 0L }
        return if (v < 0) 0L else v
    }

    private fun renderLoop() {
        val h = handle
        if (h == 0L) return
        while (!closed) {
            if (!playing) {
                // Paused: present the frame covering the frozen
                // clock once (this is what shows the seek target
                // frame and the first frame of a paused open), then
                // park until play/seek/close notifies.
                presentPausedFrame(h)
                synchronized(stateLock) {
                    if (!playing && !closed) {
                        try { stateLock.wait(200) } catch (e: InterruptedException) { /* loop */ }
                    }
                }
                continue
            }
            val pts = FFmpegEngine.videoPeekPts(h)
            val clock = clockMs()
            when (SwFrameScheduler.decide(pts, clock)) {
                SwFrameScheduler.Decision.ENDED -> {
                    noteTerminal(pts)
                    synchronized(stateLock) {
                        if (!closed) {
                            try { stateLock.wait(100) } catch (e: InterruptedException) { /* loop */ }
                        }
                    }
                }
                SwFrameScheduler.Decision.SKIP -> FFmpegEngine.videoSkip(h)
                SwFrameScheduler.Decision.SHOW -> {
                    if (FFmpegEngine.videoShow(h)) framesShown++
                }
                SwFrameScheduler.Decision.WAIT -> {
                    // Fill the second decode-ahead slot while waiting,
                    // then sleep in an interruptible slice.
                    FFmpegEngine.videoPrefetch(h)
                    synchronized(stateLock) {
                        if (playing && !closed) {
                            try {
                                stateLock.wait(SwFrameScheduler.waitMs(pts, clock))
                            } catch (e: InterruptedException) { /* loop */ }
                        }
                    }
                }
            }
        }
    }

    /** Paused presentation: if the pending frame covers the frozen
     *  clock (pts <= clock + one frame), show it. Later frames stay
     *  pending — the last shown frame remains on screen, which is
     *  the frame the user paused on. */
    private fun presentPausedFrame(h: Long) {
        if (ended) return
        val pts = FFmpegEngine.videoPeekPts(h)
        if (pts == SwFrameScheduler.PEEK_EOF || pts == SwFrameScheduler.PEEK_ERROR) {
            noteTerminal(pts)
            return
        }
        val tolerance = if (frameDurMs > 0) frameDurMs else 33L
        if (pts <= clockMs() + tolerance) {
            if (FFmpegEngine.videoShow(h)) framesShown++
        }
    }
}
