package com.ghostblade.player

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * GhostBlade Player v0.4 — player screen (Shadow Player Team), MX Player carbon copy.
 * v0.4 rebuild: MASTER-SCENE-SPEC §S1–S5 + scenes P1–P6 (decoded
 * layouts in mxplayer-analysis/decoded win over the v0.3 frame spec
 * wherever they disagree).
 * v0.3 chrome is built to carbon/MX-UI-FRAME-SPEC.md (frame-by-frame
 * from Sir's recording of the real MX Player); v0.2's was built to
 * the mined spec alone.
 *
 * Copy basis: carbon/MX-COPY-SPEC.md (§2.2 player layout, §2.3 full menu,
 * §2.5 gestures, §2.7/§2.8 settings, §3 icon redraws, §6 decoder model).
 * CUT per Sir's ruling: internet, ads, MX Share, location — none of that
 * exists here. Everything else is copied item by item.
 *
 * Engines:
 *  - HW  = the phone's system pipeline (MediaPlayer/MediaCodec), as v0.1.
 *  - HW+ = our own FFmpeg engine (Engine Team's FFmpegEngine) decoding AUDIO
 *          to PCM while video stays on the hardware path; the engine session
 *          is the master clock and the video is slaved to it.
 *  - SW  = full software decode — only offered when the engine reports
 *          video-frame output; otherwise the dialog says so honestly.
 * The engine is reached ONLY through [EngineBridge] reflection: this file
 * compiles and runs with no engine present, and every engine-backed menu
 * item disables or explains itself instead of faking a mode.
 *
 * Settings: consumed through [PSet] — a single facade whose keys/defaults
 * are MX's (§2.7/§2.8). Store keeps owning what it already owns (resume
 * positions, brightness, subtitle text size, last folder). When Shadow
 * One's SETTINGS-KEYS.md lands, PSet is the one place to re-point.
 *
 * Shared contract consumed (SPEC.md): intent extras "path" / "title" /
 * "playlist" (ArrayList<String>) / "index" (Int); optional extra
 * "decoder" (Int, MX values 0/1/4/2) from the library's "Play using…".
 */

// =====================================================================
// Pure logic — no Android calls, JVM-tested (tests/PlayerLogicTest.kt)
// =====================================================================

/** A–B repeat state machine (MX Play ▸ "A - B Repeat").
 *  Convention: press A sets the start point; press B sets the end point
 *  (must be after A); pressing again clears. [onPosition] returns the
 *  seek target when playback reaches/passes B. */
object ABRepeat {
    var aMs: Long = -1L
        private set
    var bMs: Long = -1L
        private set

    val active: Boolean get() = aMs >= 0 && bMs > aMs

    fun pressA(posMs: Long): String {
        aMs = posMs
        if (bMs <= aMs) bMs = -1L
        return "A set: ${Fmt.ms(aMs)}"
    }

    fun pressB(posMs: Long): String {
        if (aMs < 0) return "Set A first"
        if (posMs <= aMs) return "B must be after A"
        bMs = posMs
        return "B set: ${Fmt.ms(bMs)} — repeating"
    }

    fun clear() {
        aMs = -1L
        bMs = -1L
    }

    fun reset() = clear()

    /** Seek target when the playhead reaches B, else null. */
    fun onPosition(posMs: Long): Long? =
        if (active && posMs >= bMs) aMs else null
}

/** Sleep-timer math (MX Tools ▸ "Sleep Timer"): presets + custom
 *  time-of-day, with MX's confirmation wording. */
object SleepTimerCalc {
    val PRESET_MINUTES = intArrayOf(15, 30, 45, 60)
    val PRESET_LABELS = arrayOf("Off", "15m", "30m", "45m", "60m", "Custom")

    /** Deadline (epoch ms) for a preset [minutes] from [nowMs]. */
    fun presetDeadline(nowMs: Long, minutes: Int): Long = nowMs + minutes * 60_000L

    /** Next occurrence of [hour]:[minute] strictly after [nowMs]
     *  (today if still ahead, otherwise tomorrow). */
    fun customDeadline(nowMs: Long, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun isTomorrow(nowMs: Long, deadlineMs: Long): Boolean {
        val a = Calendar.getInstance(); a.timeInMillis = nowMs
        val b = Calendar.getInstance(); b.timeInMillis = deadlineMs
        return a.get(Calendar.YEAR) != b.get(Calendar.YEAR) ||
            a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)
    }

    /** MX wording: "Your sleep timer is now set. Will stop playing at %s."
     *  / "…tomorrow at %s." — [timeText] is the formatted clock time. */
    fun confirmText(nowMs: Long, deadlineMs: Long, timeText: String): String =
        if (isTomorrow(nowMs, deadlineMs))
            "Your sleep timer is now set. Will stop playing tomorrow at $timeText."
        else
            "Your sleep timer is now set. Will stop playing at $timeText."
}

/** Pinch / button zoom + pan math for the video surface.
 *  Scale 1f = "fit to screen". Quick-zoom steps per MX's "Quick zoom"
 *  setting (skip steps between 100% and fit: 150%, 200%, …). */
object ZoomMath {
    val QUICK_STEPS = floatArrayOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 4.0f
    private const val EPS = 0.01f

    fun clampScale(s: Float): Float = s.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Next step strictly above [current] (quick) or +0.25 (fine). */
    fun stepUp(current: Float, quick: Boolean): Float {
        if (!quick) return clampScale(current + 0.25f)
        for (step in QUICK_STEPS) if (step > current + EPS) return step
        return MAX_SCALE
    }

    /** Next step strictly below [current] (quick) or −0.25 (fine). */
    fun stepDown(current: Float, quick: Boolean): Float {
        if (!quick) return clampScale(current - 0.25f)
        for (step in QUICK_STEPS.reversed()) if (step < current - EPS) return step
        return 1.0f
    }

    /** Clamp a pan offset so the scaled video never leaves a gap:
     *  max shift on an axis = (scaled − view) / 2, and 0 when the
     *  scaled video is smaller than the view on that axis. */
    fun clampPan(offset: Float, viewSize: Float, scaledSize: Float): Float {
        val maxShift = max(0f, (scaledSize - viewSize) / 2f)
        return offset.coerceIn(-maxShift, maxShift)
    }
}

/** Aspect-cycle state machine for the MX transport-row aspect
 *  button (frame spec §2): one tap steps FIT TO SCREEN → STRETCH →
 *  CROP → 100% → FIT TO SCREEN… The button's icon shows the NEXT
 *  mode's glyph, and each step fires the giant centre OSD word.
 *  Modes are the player's zoomMode strings; "custom" (pinch) is
 *  outside the cycle — the next tap lands on FIT. */
object AspectCycle {
    val MODES = listOf("fit", "stretch", "crop", "100")

    /** The mode one tap applies from [current]. */
    fun next(current: String): String {
        val idx = MODES.indexOf(current)
        return if (idx < 0) MODES[0] else MODES[(idx + 1) % MODES.size]
    }

    /** Exact OSD wording per mode (frame spec §2). */
    fun osdText(mode: String): String = when (mode) {
        "fit" -> "FIT TO SCREEN"
        "stretch" -> "STRETCH"
        "crop" -> "CROP"
        "100" -> "100%"
        else -> "CUSTOM"
    }
}

/** Small pure helpers shared by the player (also JVM-tested). */
object PlayerLogic {
    /** MX speed dialog values (§2.3): fixed steps + Custom (25–400%). */
    val SPEED_STEPS = floatArrayOf(0.25f, 0.5f, 1.0f, 2.0f, 3.0f)

    fun clampSpeedPercent(pct: Int): Int = pct.coerceIn(25, 400)

    /** Subtitle lookup time, MX's formula (§S2): the 100 ms tick
     *  feeds SubView with (pos − offset) × speedFactor. MX's offset
     *  sign is the opposite of ours (Subtitle.kt: positive offset =
     *  cues earlier), so with our offset the same formula reads
     *  (pos + offset) × speed — identical at speed 1, and the speed
     *  now scales the offset too, exactly as MX's does. */
    fun subLookupMs(posMs: Long, speed: Float, offsetMs: Long): Long =
        ((posMs + offsetMs) * speed).toLong()

    /** MX decoder values (§6.1): Default=0, HW=1, SW=2, HW+=4. */
    fun decoderLabel(value: Int): String = when (value) {
        1 -> "HW"
        4 -> "HW+"
        2 -> "SW"
        else -> "Default"
    }

    /** Smart Previous Button (§2.7.17): restart the current file when
     *  the playhead is past [thresholdMs], else go to the previous file. */
    fun smartPrevRestarts(posMs: Long, thresholdMs: Long = 3000L): Boolean = posMs > thresholdMs

    /** Whether [item] is excluded in HW mode (§6.3 exclusion table):
     *  boost>100%, equalizer, audio/BT delay, passthrough, preferred
     *  audio language, deinterlace. */
    fun hwExcluded(item: String): Boolean = when (item) {
        "boost", "equalizer", "audio_delay", "bt_delay",
        "passthrough", "pref_audio_lang", "deinterlace" -> true
        else -> false
    }
}

/** Media-button routing (§2.7.16, spec #60) — pure: maps a media key
 *  (+ the headset-hook press-burst count) and the four MX media-button
 *  prefs to a transport action. Key codes are android.view.KeyEvent
 *  constants (inlined at compile time, so this stays JVM-testable).
 *
 *  MX prefs: "Media buttons" (master) · "Honour headset hook
 *  multi-press" (double → Next, triple → Prev) · "Previous/Next
 *  buttons act as RW/FF" (Next/Prev become seek ∓move-interval) ·
 *  "Toggle on media play button" (the Play key toggles instead of
 *  only starting playback). */
object MediaButtonLogic {
    enum class Action { NONE, TOGGLE, PLAY, PAUSE, NEXT, PREV, FF, REW, STOP }

    // android.view.KeyEvent values (stable AOSP constants).
    const val KEY_PLAY = 126
    const val KEY_PAUSE = 127
    const val KEY_PLAY_PAUSE = 85
    const val KEY_HEADSETHOOK = 79
    const val KEY_NEXT = 87
    const val KEY_PREVIOUS = 88
    const val KEY_STOP = 86

    fun isMediaKey(keyCode: Int): Boolean = keyCode in intArrayOf(
        KEY_PLAY, KEY_PAUSE, KEY_PLAY_PAUSE, KEY_HEADSETHOOK,
        KEY_NEXT, KEY_PREVIOUS, KEY_STOP
    )

    /** [hookPressCount] is the burst size for hook/play-pause keys
     *  (1 = single press); it is ignored for dedicated keys. */
    fun keyAction(
        keyCode: Int,
        hookPressCount: Int,
        buttonsEnabled: Boolean,
        multiPress: Boolean,
        prevNextToFfRew: Boolean,
        toggleOnPlayButton: Boolean
    ): Action {
        if (!buttonsEnabled) return Action.NONE
        val nextAction = if (prevNextToFfRew) Action.FF else Action.NEXT
        val prevAction = if (prevNextToFfRew) Action.REW else Action.PREV
        return when (keyCode) {
            KEY_PLAY_PAUSE, KEY_HEADSETHOOK -> when {
                multiPress && hookPressCount >= 3 -> prevAction
                multiPress && hookPressCount == 2 -> nextAction
                else -> Action.TOGGLE
            }
            KEY_PLAY -> if (toggleOnPlayButton) Action.TOGGLE else Action.PLAY
            KEY_PAUSE -> Action.PAUSE
            KEY_NEXT -> nextAction
            KEY_PREVIOUS -> prevAction
            KEY_STOP -> Action.STOP
            else -> Action.NONE
        }
    }
}

/** Counts a burst of headset-hook presses: presses landing within
 *  [windowMs] of the previous one extend the burst; a longer gap
 *  starts a new burst at 1. Pure — hosts supply the clock. */
class HookPressTracker(private val windowMs: Long = 500L) {
    private val times = ArrayList<Long>()

    /** Records a press at [nowMs]; returns the current burst size. */
    fun onPress(nowMs: Long): Int {
        val last = times.lastOrNull()
        if (last == null || nowMs - last > windowMs) times.clear()
        times.add(nowMs)
        return times.size
    }

    fun reset() = times.clear()
}

/** AV-sync ticker policy (§4.2 #67) — pure decision for one tick of
 *  the engine-clock sync loop, so the "AV sync" menu toggle is a real
 *  consumer: OFF means no drift correction at all (and a video frame
 *  the loop previously held is released). Thresholds are the ones the
 *  loop has always used: hold past 180 ms of drift, seek past 400. */
object AvSyncPolicy {
    enum class Decision { NONE, HOLD, RESUME, SEEK }

    fun decide(
        enabled: Boolean,
        driftMs: Long,
        playWhenReady: Boolean,
        videoPlaying: Boolean,
        userSeeking: Boolean
    ): Decision {
        if (!enabled) {
            // Sync off: never correct — but release a hold the loop
            // imposed while it was on, or the video stays frozen.
            return if (playWhenReady && !videoPlaying && !userSeeking) {
                Decision.RESUME
            } else {
                Decision.NONE
            }
        }
        return when {
            driftMs > 400 || driftMs < -400 -> Decision.SEEK
            driftMs > 180 -> if (videoPlaying) Decision.HOLD else Decision.NONE
            else -> if (playWhenReady && !videoPlaying && !userSeeking) {
                Decision.RESUME
            } else {
                Decision.NONE
            }
        }
    }
}

/** Engine-clock reading for the audio/video delay (§4.2 #67): the
 *  delay is applied NATIVELY by the engine session
 *  (SwAudioSession.setDelayMs offsets the bridge's reported chunk
 *  timestamps), so clockMs() IS the delay-shifted master clock and
 *  position reporting must return it unmodified — folding the delay
 *  in a second time here would apply it twice. The only transform
 *  left is the display clamp the engine header prescribes: with a
 *  negative delay the clock can read slightly negative at the very
 *  start of a stream. */
/** Brightness-bar mapping (MASTER-SCENE-SPEC §P6, decoded
 *  ScreenVerticalBar): the gesture fraction runs through a 1.8
 *  exponent into the window brightness, whose framework scale is
 *  0..255 with a minimum of 0.05 (the decoded fallback when the
 *  framework min/max dimens are unreadable; max is 255). The bar's
 *  label is the raw fraction as "%d%%". Pure, so it stays testable. */
object BrightnessMap {
    const val MIN_FRACTION = 0.05f
    const val FACTOR = 1.8f
    fun toWindowFraction(frac: Float): Float {
        val f = frac.coerceIn(0f, 1f)
        return (MIN_FRACTION + f.pow(FACTOR) * (1f - MIN_FRACTION)).coerceIn(MIN_FRACTION, 1f)
    }
    /** The same value on the framework's 0..255 brightness scale. */
    fun toSystemLevel(frac: Float): Int = (toWindowFraction(frac) * 255f).toInt()
    fun percentLabel(frac: Float): String = "${(frac.coerceIn(0f, 1f) * 100).toInt()}%"
}

object EngineClockMath {
    /** The master-clock reading, delay already inside it; clamped
     *  at 0 for display as the engine header prescribes. */
    fun displayPosMs(engineClockMs: Long): Long = engineClockMs.coerceAtLeast(0L)
}

/** HW+ audio-codec allowlist mapping (Decoder settings, §6.2): the
 *  settings store MX's display labels ("AC3", "G.711 A-law", … in
 *  Settings.OMX_AUDIO_CODEC_LIST) while SwAudioSession.open expects
 *  RAW FFmpeg codec names (StreamInfo.codecName: "ac3", "dca", …).
 *  This is the translation, kept pure so it is JVM-tested.
 *
 *  Semantics: when every label is enabled (the default) the result
 *  is null — NO restriction — because MX's 18 labels do not cover
 *  every codec the engine can decode (TrueHD, APE, WMA, Cook… have
 *  no label); restricting to the mapped set at default would break
 *  those files for no user decision. As soon as the user disables
 *  any label, the mapped set of the remaining labels is enforced
 *  exactly: a disabled codec fails the engine open and the player
 *  falls back to HW, per MX's try-HW rules. */
object EngineAllowlist {
    private val LABEL_TO_RAW: Map<String, Set<String>> = mapOf(
        "AAC" to setOf("aac", "aac_latm", "aac_fixed"),
        "ALAC" to setOf("alac"),
        "AMR NB" to setOf("amr_nb"),
        "AMR WB" to setOf("amr_wb"),
        "FLAC" to setOf("flac"),
        "G.711 A-law" to setOf("pcm_alaw"),
        "G.711 μ-law" to setOf("pcm_mulaw"),
        "GSM" to setOf("gsm", "gsm_ms"),
        "MP1" to setOf("mp1", "mp1float"),
        "MP2" to setOf("mp2", "mp2float"),
        "MP3" to setOf("mp3", "mp3float", "mp3on4", "mp3adu", "mp3adufloat"),
        "Opus" to setOf("opus"),
        "PCM" to setOf(
            "pcm_s8", "pcm_u8", "pcm_s16le", "pcm_s16be", "pcm_s24le",
            "pcm_s24be", "pcm_s32le", "pcm_s32be", "pcm_f16le",
            "pcm_f24le", "pcm_f32le", "pcm_f32be", "pcm_f64le",
            "pcm_f64be", "pcm_dvd", "pcm_bluray", "pcm_lxf"
        ),
        "QCELP" to setOf("qcelp"),
        "Vorbis" to setOf("vorbis"),
        "AC3" to setOf("ac3", "ac3_fixed"),
        "EAC3" to setOf("eac3"),
        "DTS" to setOf("dca", "dts")
    )

    /** Raw FFmpeg codec names for [enabledLabels], or null when the
     *  enabled set covers every known label (= no restriction). */
    fun allowedRawNames(enabledLabels: Set<String>): Set<String>? {
        if (enabledLabels.containsAll(LABEL_TO_RAW.keys)) return null
        val out = HashSet<String>()
        for (label in enabledLabels) {
            LABEL_TO_RAW[label]?.let { out.addAll(it) }
        }
        return out
    }
}

/** Screen-lock unlock gestures (§2.7.29 "Mode #1/#2/#3"). MX's own
 *  per-mode semantics are code-generated and not recoverable from
 *  the APK (the spec notes the labels are "not invented"), so the
 *  three modes are wired to three genuinely different unlock
 *  gestures of our definition — the mode is no longer inert, and the
 *  lock toast states the active gesture in plain words. */
object LockGesturePolicy {
    enum class Gesture { TAP, LONG_PRESS, DOUBLE_TAP }

    fun unlockGesture(mode: Int): Gesture = when (mode) {
        1 -> Gesture.LONG_PRESS
        2 -> Gesture.DOUBLE_TAP
        else -> Gesture.TAP // Mode #1 + any unknown value
    }

    fun unlocks(mode: Int, gesture: Gesture): Boolean =
        unlockGesture(mode) == gesture

    fun hint(mode: Int): String = when (unlockGesture(mode)) {
        Gesture.TAP -> "Tap the lock button to unlock"
        Gesture.LONG_PRESS -> "Press and hold the lock button to unlock"
        Gesture.DOUBLE_TAP -> "Double-tap the lock button to unlock"
    }
}

/** Time/size formatting shared with the library look (pure). */
object Fmt {
    fun ms(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    /** MX seek-row format (frame spec §1.4/§7 #11): minutes are
     *  zero-padded under one hour ("00:12"); at/over one hour the
     *  H:MM:SS form is identical to [ms]. */
    fun msPadded(ms: Long): String {
        if (ms < 0) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    fun remaining(ms: Long): String = "-" + ms(max(0L, ms))

    fun clock(timeInMillis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeInMillis
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}

// =====================================================================
// Settings facade — MX keys/defaults (§2.7, §2.8, §6.2), one surface.
// Backed by SharedPreferences until Shadow One's SETTINGS-KEYS.md lands;
// every default below is MX's extracted default, cited per group.
// =====================================================================
object PSet {
    private const val PREFS = "gbp_settings_v2"
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun b(key: String, def: Boolean): Boolean = prefs?.getBoolean(key, def) ?: def
    private fun i(key: String, def: Int): Int = prefs?.getInt(key, def) ?: def
    private fun f(key: String, def: Float): Float = prefs?.getFloat(key, def) ?: def
    private fun s(key: String, def: String): String = prefs?.getString(key, def) ?: def
    private fun put(key: String, v: Boolean) { prefs?.edit()?.putBoolean(key, v)?.apply() }
    private fun put(key: String, v: Int) { prefs?.edit()?.putInt(key, v)?.apply() }
    private fun put(key: String, v: Float) { prefs?.edit()?.putFloat(key, v)?.apply() }
    private fun put(key: String, v: String) { prefs?.edit()?.putString(key, v)?.apply() }

    // ---- Player ▸ Playback (§2.7.8–19) ----
    /** "resume" / "startover" / "ask" — MX default Ask at Startup. */
    var resumeMode: String get() = s("resume_mode", "ask"); set(v) = put("resume_mode", v)
    var resumeFirstOnly: Boolean get() = b("resume_first_only", false); set(v) = put("resume_first_only", v)
    var defaultSpeedPct: Int get() = i("default_speed_pct", 100); set(v) = put("default_speed_pct", v)
    var rememberSelections: Boolean get() = b("remember_selections", true); set(v) = put("remember_selections", v)
    var backToList: Boolean get() = b("back_to_list", false); set(v) = put("back_to_list", v)
    var fastSeeking: Boolean get() = b("fast_seeking", true); set(v) = put("fast_seeking", v)
    var suppressErrors: Boolean get() = b("suppress_errors", false); set(v) = put("suppress_errors", v)
    var doubleTapBackExit: Boolean get() = b("dbl_tap_back_exit", false); set(v) = put("dbl_tap_back_exit", v)
    var quickZoom: Boolean get() = b("quick_zoom", true); set(v) = put("quick_zoom", v)
    var smartPrev: Boolean get() = b("smart_prev", true); set(v) = put("smart_prev", v)
    var playAlone: Boolean get() = b("play_alone", true); set(v) = put("play_alone", v)
    var mediaButtons: Boolean get() = b("media_buttons", true); set(v) = put("media_buttons", v)
    var previewWhileSeeking: Boolean get() = b("preview_seeking", false); set(v) = put("preview_seeking", v)
    var loadingAnimation: Boolean get() = b("loading_animation", true); set(v) = put("loading_animation", v)

    // ---- Player ▸ Background (§2.7.20–23) ----
    /** "stop" / "background" / "pip" — MX default PiP. */
    var bgMode: String get() = s("bg_mode", "pip"); set(v) = put("bg_mode", v)
    var bgAudio: Boolean get() = b("bg_audio", true); set(v) = put("bg_audio", v)

    // ---- Player ▸ Interface / Screen tuner (§2.7.1–3) ----
    var playbackTheme: String get() = s("theme_playback", "black_blueAccent"); set(v) = put("theme_playback", v)
    /** "land" / "rev_land" / "auto_land" / "auto" / "system" / "video". */
    var orientationMode: String get() = s("orientation_mode", "auto_land"); set(v) = put("orientation_mode", v)
    /** "on" / "off" / "auto" (Full Screen: On/Off/Auto Switch). */
    var fullScreen: String get() = s("full_screen", "auto"); set(v) = put("full_screen", v)
    var elapsedTime: Boolean get() = b("elapsed_time", true); set(v) = put("elapsed_time", v)
    var batteryClock: Boolean get() = b("battery_clock", true); set(v) = put("battery_clock", v)
    var showRemaining: Boolean get() = b("show_remaining", false); set(v) = put("show_remaining", v)
    var keepScreenOn: Boolean get() = b("keep_screen_on", true); set(v) = put("keep_screen_on", v)
    var rotationButton: Boolean get() = b("rotation_button", true); set(v) = put("rotation_button", v)
    var autoHideMs: Long get() = i("auto_hide_ms", 2000).toLong(); set(v) = put("auto_hide_ms", v.toInt())

    // ---- Player ▸ Controls tuner (§2.7.4) ----
    /** Index into MX's Touch action entries:
     *  0 "Show Interface → Pause/Resume", 1 "Show interface + pause/resume",
     *  2 "Pause/resume", 3 "Show/hide interface". */
    var touchAction: Int get() = i("touch_action", 0); set(v) = put("touch_action", v)
    var gSeek: Boolean get() = b("g_seek", true); set(v) = put("g_seek", v)
    var gZoomPan: Boolean get() = b("g_zoom_pan", true); set(v) = put("g_zoom_pan", v)
    var gVideoZoom: Boolean get() = b("g_video_zoom", true); set(v) = put("g_video_zoom", v)
    var gVideoPan: Boolean get() = b("g_video_pan", true); set(v) = put("g_video_pan", v)
    var gVolume: Boolean get() = b("g_volume", true); set(v) = put("g_volume", v)
    var gBrightness: Boolean get() = b("g_brightness", true); set(v) = put("g_brightness", v)
    var gDblTapPlayPause: Boolean get() = b("g_dbl_pp", true); set(v) = put("g_dbl_pp", v)
    var gDblTapZoom: Boolean get() = b("g_dbl_zoom", false); set(v) = put("g_dbl_zoom", v)
    var gDblTapSeek: Boolean get() = b("g_dbl_seek", true); set(v) = put("g_dbl_seek", v)
    var gLongPressFF: Boolean get() = b("g_long_ff", true); set(v) = put("g_long_ff", v)
    var gTwoFingerSpeed: Boolean get() = b("g_2f_speed", true); set(v) = put("g_2f_speed", v)
    var gSubScroll: Boolean get() = b("g_sub_scroll", true); set(v) = put("g_sub_scroll", v)
    var gSubUpDown: Boolean get() = b("g_sub_updown", true); set(v) = put("g_sub_updown", v)
    var gSubZoom: Boolean get() = b("g_sub_zoom", true); set(v) = put("g_sub_zoom", v)
    /** Screen lock: values 0/1/2 ("Mode #1/#2/#3", §2.7.29). */
    var lockMode: Int get() = i("lock_mode", 0); set(v) = put("lock_mode", v)
    /** "input" / "rotation" (§2.7.4 Lock target). */
    var lockTarget: String get() = s("lock_target", "input"); set(v) = put("lock_target", v)
    var showLockedUi: Boolean get() = b("show_locked_ui", true); set(v) = put("show_locked_ui", v)

    // ---- Player ▸ Navigation tuner (§2.7.5) ----
    var seekSpeed: Float get() = f("seek_speed", 1.0f); set(v) = put("seek_speed", v)
    var fwdBackButtons: Boolean get() = b("fwdbk_buttons", true); set(v) = put("fwdbk_buttons", v)
    var moveIntervalSec: Int get() = i("move_interval_sec", 10); set(v) = put("move_interval_sec", v)
    var prevNextButtons: Boolean get() = b("prevnext_buttons", true); set(v) = put("prevnext_buttons", v)
    var showPosWhileSeeking: Boolean get() = b("show_pos_seeking", true); set(v) = put("show_pos_seeking", v)

    // ---- Audio (§2.8) ----
    var volumeBoostGate: Boolean get() = b("volume_boost", false); set(v) = put("volume_boost", v)
    /** "AV sync" menu toggle (§4.2 #67): gates the engine-clock
     *  drift-correction loop (AvSyncPolicy). */
    var avSync: Boolean get() = b("av_sync", true); set(v) = put("av_sync", v)
    var systemVolume: Boolean get() = b("system_volume", true); set(v) = put("system_volume", v)
    var audioDelayMs: Long get() = i("audio_delay_ms", 0).toLong(); set(v) = put("audio_delay_ms", v.toInt())
    var btDelayMs: Long get() = i("bt_delay_ms", 0).toLong(); set(v) = put("bt_delay_ms", v.toInt())
    var prefAudioLang: String get() = s("pref_audio_lang", ""); set(v) = put("pref_audio_lang", v)
    var passthrough: Boolean get() = b("passthrough", true); set(v) = put("passthrough", v)
    var equalizerGate: Boolean get() = b("equalizer_enabled", false); set(v) = put("equalizer_enabled", v)
    var fadeOnStart: Boolean get() = b("fade_start", true); set(v) = put("fade_start", v)
    var fadeOnSeek: Boolean get() = b("fade_seek", true); set(v) = put("fade_seek", v)
    var pauseHeadsetOff: Boolean get() = b("pause_headset_off", true); set(v) = put("pause_headset_off", v)

    // ---- Decoder (§6.2) ----
    /** MX values: 0 Default, 1 HW, 4 HW+, 2 SW. */
    var decoderDefault: Int get() = i("decoder_default", 0); set(v) = put("decoder_default", v)
    var speedupTricks: Boolean get() = b("speedup_tricks", false); set(v) = put("speedup_tricks", v)
    /** 0 None, 1 Yadif, 2 W3fdif. */
    var deinterlace: Int get() = i("deinterlace", 0); set(v) = put("deinterlace", v)
    var calibratePosMs: Long get() = i("calibrate_pos_ms", 0).toLong(); set(v) = put("calibrate_pos_ms", v.toInt())

    // ---- Subtitle (§2.8) ----
    var subEncoding: String get() = s("sub_encoding", "Auto"); set(v) = put("sub_encoding", v)
    var prefSubLang: String get() = s("pref_sub_lang", ""); set(v) = put("pref_sub_lang", v)
    var subDefaultSyncMs: Long get() = i("sub_default_sync_ms", 0).toLong(); set(v) = put("sub_default_sync_ms", v.toInt())
    var subHwSyncMs: Long get() = i("sub_hw_sync_ms", 0).toLong(); set(v) = put("sub_hw_sync_ms", v.toInt())
    var subFont: String get() = s("sub_font", "Default"); set(v) = put("sub_font", v)
    var subFontFolder: String get() = s("sub_font_folder", ""); set(v) = put("sub_font_folder", v)
    var subScalePct: Int get() = i("sub_scale_pct", 100); set(v) = put("sub_scale_pct", v)
    var subColor: Int get() = i("sub_color", Color.WHITE); set(v) = put("sub_color", v)
    var subBold: Boolean get() = b("sub_bold", false); set(v) = put("sub_bold", v)
    var subBorder: Boolean get() = b("sub_border", true); set(v) = put("sub_border", v)
    var subShadow: Boolean get() = b("sub_shadow", true); set(v) = put("sub_shadow", v)
    var subImproveStroke: Boolean get() = b("sub_improve_stroke", false); set(v) = put("sub_improve_stroke", v)
    /** "left" / "center" / "right". */
    var subAlign: String get() = s("sub_align", "center"); set(v) = put("sub_align", v)
    var subBottomMarginDp: Int get() = i("sub_bottom_margin_dp", 24); set(v) = put("sub_bottom_margin_dp", v)
    var subBg: Boolean get() = b("sub_bg", false); set(v) = put("sub_bg", v)
    var subBgColor: Int get() = i("sub_bg_color", Color.argb(128, 0, 0, 0)); set(v) = put("sub_bg_color", v)
    var subItalicEffect: Boolean get() = b("sub_italic_effect", false); set(v) = put("sub_italic_effect", v)
    var subForceLtr: Boolean get() = b("sub_force_ltr", true); set(v) = put("sub_force_ltr", v)
    var subHw: Boolean get() = b("sub_hw", false); set(v) = put("sub_hw", v)
    var subFolder: String get() = s("sub_folder", ""); set(v) = put("sub_folder", v)
    var subSpeed: Float get() = f("sub_speed", 1.0f); set(v) = put("sub_speed", v)

    // ---- Quick Shortcuts strip (§2.2 set; v0.3 frame-spec order) ----
    // Storage key bumped for v0.3: the default ORDER changed to
    // Sir's recorded MX customisation, and a stored v0.2 default
    // would otherwise mask it. A hand-customised v0.2 order does
    // not carry over (the Customise editor rebuilds it in-app).
    var railShortcuts: String
        get() = s("rail_shortcuts_v3", DEFAULT_RAIL)
        set(v) = put("rail_shortcuts_v3", v)

    // ---- Custom playlists (§2.9 "Add to playlist") ----
    /** Serialised as lines: name \t path1 | path2 | … */
    var customPlaylists: String get() = s("custom_playlists", ""); set(v) = put("custom_playlists", v)

    // ---- Per-file remembered selections (§2.7.11) + history (§2.9) ----
    private fun fileKey(path: String, what: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "file.$hex.$what"
    }
    fun fileDecoder(path: String): Int = i(fileKey(path, "decoder"), -1)
    fun setFileDecoder(path: String, v: Int) = put(fileKey(path, "decoder"), v)
    fun fileAudioTrack(path: String): Int = i(fileKey(path, "audio"), -1)
    fun setFileAudioTrack(path: String, v: Int) = put(fileKey(path, "audio"), v)
    /** Engine-mode audio choice: an FFmpeg STREAM index (probe
     *  space), kept apart from fileAudioTrack's MediaPlayer track
     *  index so the two index spaces never corrupt each other. */
    fun fileAudioStream(path: String): Int = i(fileKey(path, "audio_stream"), -1)
    fun setFileAudioStream(path: String, v: Int) = put(fileKey(path, "audio_stream"), v)
    /** Subtitle choice descriptor: "none", "embedded:<trackIndex>", "file:<abs path>". */
    fun fileSubtitle(path: String): String = s(fileKey(path, "sub"), "")
    fun setFileSubtitle(path: String, v: String) = put(fileKey(path, "sub"), v)
    fun fileLastPlayedAt(path: String): Long =
        (prefs?.getLong(fileKey(path, "played_at"), 0L) ?: 0L)
    fun setFileLastPlayedAt(path: String, v: Long) {
        prefs?.edit()?.putLong(fileKey(path, "played_at"), v)?.apply()
    }
    /** Per-file bookmarks (Play ▸ Bookmark; the decoded
     *  BookmarkSeekBar draws them on the seek track). Stored as a
     *  sorted comma-separated list of positions in ms. */
    fun fileBookmarks(path: String): List<Long> =
        s(fileKey(path, "bookmarks"), "")
            .split(",").mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0 }.sorted()
    fun setFileBookmarks(path: String, marks: List<Long>) =
        put(fileKey(path, "bookmarks"), marks.sorted().joinToString(","))

    /** v0.3 default = Sir's recorded MX strip order (frame spec
     *  §1.3): the expanded strip lists these 13; the collapsed
     *  strip pins {equalizer, playback_speed, screenshot, sticky,
     *  screen_rotation} out of it — exactly what his MX shows. */
    const val DEFAULT_RAIL =
        "night_mode,customise_items,shuffle,loop,mute,sleep_timer,repeat_ab,audioEffect," +
        "equalizer,playback_speed,screenshot,sticky,screen_rotation"
}

// =====================================================================
// Engine bridge — direct adapter over the Engine Team's delivered
// FFmpegEngine / SwAudioSession API (see FFmpegEngine.kt header for
// the contract). The engine decodes AUDIO to PCM via our own FFmpeg
// 7.1.1 (LGPL, network compiled out); in HW+ the hardware video is
// slaved to the engine's audio clock — MX's HW+ design. In SW mode
// the engine ALSO decodes the video (SwVideoSession, native
// rendering), slaved to that same audio clock frame by frame.
// Consequences, stated honestly:
//   * supportsSwVideo() delegates to FFmpegEngine.supportsSwVideo()
//     — TRUE since the SW video path landed: the SW decoder row is
//     a real mode, not a gate.
//   * The audio/BT delay is REAL in the engine: setAudioDelay
//     forwards to SwAudioSession.setDelayMs, which shifts the
//     master clock natively. position() therefore returns clockMs()
//     UNMODIFIED (only the header-prescribed ≥0 display clamp) —
//     folding the delay in here as well would apply it twice.
//   * Capability gates (stereo, fix-broken-audio, deinterlace,
//     speedup tricks, passthrough…) read FFmpegEngine.supportsX() /
//     gateReason(key) — the engine is the one source of truth, and
//     callers refuse with the reason instead of faking a state.
// =====================================================================
object EngineBridge {
    private val sessions = HashMap<Long, SwAudioSession>()
    private val streamOf = HashMap<Long, Int>()
    private var nextId = 1L

    fun isAvailable(): Boolean = try {
        FFmpegEngine.isAvailable()
    } catch (t: Throwable) {
        false
    }

    fun supportsSwVideo(): Boolean = try {
        FFmpegEngine.supportsSwVideo()
    } catch (t: Throwable) {
        false
    }

    /** Opens an engine audio session for [path] on [streamIndex]
     *  (-1 = best/default audio stream, an FFmpeg stream index from
     *  probe — NOT a MediaPlayer track index); 0 = failure. The
     *  Decoder settings are real inputs: core_limit becomes the
     *  decoder thread count, and the HW+ audio-codec allowlist
     *  (translated from MX labels by EngineAllowlist) makes the open
     *  fail for a disabled codec so the caller falls back to HW. */
    fun open(path: String, streamIndex: Int = -1): Long {
        if (!isAvailable()) return 0L
        return try {
            val session = SwAudioSession()
            val threads = Store.intSetting(Settings.CORE_LIMIT)
            val allow = EngineAllowlist.allowedRawNames(
                Store.stringSetSetting(Settings.OMX_AUDIO_CODECS)
            )
            if (!session.open(path, streamIndex, 0L, threads, allow)) return 0L
            val id = nextId++
            sessions[id] = session
            streamOf[id] = streamIndex
            id
        } catch (t: Throwable) {
            0L
        }
    }

    fun start(h: Long) { sessions[h]?.play() }
    fun pause(h: Long) { sessions[h]?.pause() }
    fun seek(h: Long, ms: Long) { runCatching { sessions[h]?.seekTo(ms) } }

    /** Master-clock position: clockMs() UNMODIFIED (the engine has
     *  already folded any setDelayMs offset into it natively),
     *  clamped at 0 for display per the engine header. This single
     *  reading feeds the seek bar, resume points, A–B marks AND the
     *  sync loop — there is no second, delay-adjusted reading. */
    fun position(h: Long): Long {
        val s = sessions[h] ?: return -1L
        return EngineClockMath.displayPosMs(s.clockMs())
    }

    /** The FFmpeg stream index this session is decoding (-1 =
     *  default), for the audio-track chooser's current marker. */
    fun currentStream(h: Long): Int = streamOf[h] ?: -1

    /** Engine-mode audio-track switching: close/reopen inside the
     *  session at the current position, preserving gain/speed/
     *  delay/threads/allowlist. On false the session is CLOSED —
     *  the handle is dropped here and the caller must fall back
     *  to the HW path. */
    fun switchAudio(h: Long, streamIndex: Int): Boolean {
        val s = sessions[h] ?: return false
        val ok = try {
            s.switchAudioStream(streamIndex)
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            streamOf[h] = streamIndex
        } else {
            sessions.remove(h)
            streamOf.remove(h)
        }
        return ok
    }

    fun duration(h: Long): Long = sessions[h]?.durationMs() ?: -1L
    fun setGain(h: Long, gain: Float) { sessions[h]?.setGain(gain) }
    fun setSpeed(h: Long, speed: Float) { sessions[h]?.setSpeed(speed) }

    /** Audio/BT delay: forwarded to the session's NATIVE delay
     *  (sign: positive = audio heard later than video). Never folded
     *  into position() — the engine clock already carries it. */
    fun setAudioDelay(h: Long, ms: Long) { sessions[h]?.setDelayMs(ms) }
    fun isEnded(h: Long): Boolean = sessions[h]?.isEnded() ?: false

    fun close(h: Long) {
        streamOf.remove(h)
        runCatching { sessions.remove(h)?.close() }
    }
}

// =====================================================================
// Background-play bridge (§4.2 #50 + #60): the foreground service and
// its MediaSession never touch the player directly — they route
// through [PlayerController], whose [Transport] the live
// PlayerActivity registers. If no activity is registered (process
// restarted without one), service commands are no-ops and the service
// stops itself: transport honestly requires the activity.
// =====================================================================
object PlayerController {
    interface Transport {
        fun play()
        fun pause()
        fun toggle()
        fun next()
        fun prev()
        /** Relative seek — the Next/Prev→FF/Rew media-button remap. */
        fun seekBy(deltaMs: Long)
        fun stopAndExit()
        fun isPlaying(): Boolean
        fun title(): String
    }

    @Volatile
    var transport: Transport? = null

    /** Set by PlaybackService while it runs; invoked (on the main
     *  thread by the activity) whenever play state or track changes,
     *  so the notification + session state stay truthful. */
    @Volatile
    var stateListener: (() -> Unit)? = null

    fun notifyStateChanged() {
        stateListener?.invoke()
    }
}

/** Foreground playback service — MX's PlayService model (§4.2 #50).
 *
 *  Design (5 lines): the service owns NO player; it keeps the process
 *  alive with a real foreground notification (MediaStyle, wired to
 *  its MediaSession) while PlayerActivity keeps owning playback.
 *  Notification buttons are explicit PendingIntents back into this
 *  service; the MediaSession callback and those intents both route
 *  to PlayerController.transport on the main thread. The session is
 *  active for the service's whole life, so headset/Bluetooth media
 *  buttons route here too (§4.2 #60). If the manifest entry is not
 *  present yet (see PLAYER-SERVICE-NEEDS.md), PlayerActivity detects
 *  that via [isDeclared] and falls back to in-activity background
 *  play — this class is simply never started. */
class PlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.ghostblade.player.bg.START"
        const val ACTION_STOP_SERVICE = "com.ghostblade.player.bg.STOP"
        const val ACTION_PLAY = "com.ghostblade.player.bg.PLAY"
        const val ACTION_PAUSE = "com.ghostblade.player.bg.PAUSE"
        const val ACTION_TOGGLE = "com.ghostblade.player.bg.TOGGLE"
        const val ACTION_NEXT = "com.ghostblade.player.bg.NEXT"
        const val ACTION_PREV = "com.ghostblade.player.bg.PREV"
        private const val NOTIF_ID = 77 // same id the activity used pre-service
        private const val CHANNEL_ID = "gbp_background"

        /** True when the manifest declares this service (Jervis
         *  reconciles PLAYER-SERVICE-NEEDS.md); callers must check
         *  before starting so a missing entry degrades, not crashes. */
        fun isDeclared(ctx: Context): Boolean = try {
            ctx.packageManager.getServiceInfo(
                ComponentName(ctx, PlaybackService::class.java), 0
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (t: Throwable) { /* undeclared/racing: caller falls back */ }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (t: Throwable) { /* not running */ }
        }
    }

    private var session: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundStarted = false
    private val hookTracker = HookPressTracker()
    private var pendingHookAction: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The media-button prefs live in PSet/Store; the service reads
        // the same stores the settings screens write.
        PSet.init(applicationContext)
        Store.init(applicationContext)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Background playback",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val s = MediaSession(this, "GhostBladePlayer")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = route { it.play() }
            override fun onPause() = route { it.pause() }
            override fun onSkipToNext() = routeMedia(MediaButtonLogic.KEY_NEXT, 1)
            override fun onSkipToPrevious() = routeMedia(MediaButtonLogic.KEY_PREVIOUS, 1)
            override fun onStop() = route { it.stopAndExit() }
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(
                    Intent.EXTRA_KEY_EVENT
                ) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return true
                handleMediaKey(ev.keyCode)
                return true
            }
        })
        s.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        s.isActive = true
        session = s
        PlayerController.stateListener = {
            mainHandler.post {
                updateSessionState()
                if (foregroundStarted) {
                    val nm2 = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm2.notify(NOTIF_ID, buildNotification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // No live player activity → nothing to keep alive for.
        if (PlayerController.transport == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(true)
                foregroundStarted = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> route { it.play() }
            ACTION_PAUSE -> route { it.pause() }
            ACTION_TOGGLE -> route { it.toggle() }
            ACTION_NEXT -> routeMedia(MediaButtonLogic.KEY_NEXT, 1)
            ACTION_PREV -> routeMedia(MediaButtonLogic.KEY_PREVIOUS, 1)
        }
        ensureForeground()
        updateSessionState()
        return START_STICKY
    }

    override fun onDestroy() {
        PlayerController.stateListener = null
        pendingHookAction?.let { mainHandler.removeCallbacks(it) }
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    // ---- routing ----

    private fun route(action: (PlayerController.Transport) -> Unit) {
        val t = PlayerController.transport ?: return
        mainHandler.post { action(t) }
    }

    /** Dedicated transport keys (next/prev) honour the same pref
     *  routing as the activity's key handling. */
    private fun routeMedia(keyCode: Int, hookCount: Int) {
        val t = PlayerController.transport ?: return
        mainHandler.post { dispatchMediaAction(t, keyCode, hookCount) }
    }

    private fun handleMediaKey(keyCode: Int) {
        if (!MediaButtonLogic.isMediaKey(keyCode)) return
        if (keyCode == MediaButtonLogic.KEY_HEADSETHOOK ||
            keyCode == MediaButtonLogic.KEY_PLAY_PAUSE
        ) {
            // Hook keys are ambiguous until the burst ends: wait out
            // the multi-press window, then route the final count.
            val count = hookTracker.onPress(System.currentTimeMillis())
            pendingHookAction?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                val t = PlayerController.transport ?: return@Runnable
                dispatchMediaAction(t, keyCode, count)
                hookTracker.reset()
            }
            pendingHookAction = runnable
            mainHandler.postDelayed(runnable, 500L)
        } else {
            routeMedia(keyCode, 1)
        }
    }

    /** Shared by the session callback and notification intents.
     *  FF/REW (the Next/Prev→FF/Rew remap) seek by the player's move
     *  interval through the transport's seek slot — same behaviour
     *  as the activity's own key path. */
    private fun dispatchMediaAction(
        t: PlayerController.Transport, keyCode: Int, hookCount: Int
    ) {
        val intervalMs = PSet.moveIntervalSec * 1000L
        when (MediaButtonLogic.keyAction(
            keyCode, hookCount,
            buttonsEnabled = PSet.mediaButtons,
            multiPress = Store.boolSetting(Settings.HEADSET_MULTI_PRESS),
            prevNextToFfRew = Store.boolSetting(Settings.PREVNEXT_TO_REWFF),
            toggleOnPlayButton = Store.boolSetting(Settings.TOGGLE_ON_MEDIA_PLAY_BUTTON)
        )) {
            MediaButtonLogic.Action.TOGGLE -> t.toggle()
            MediaButtonLogic.Action.PLAY -> t.play()
            MediaButtonLogic.Action.PAUSE -> t.pause()
            MediaButtonLogic.Action.NEXT -> t.next()
            MediaButtonLogic.Action.PREV -> t.prev()
            MediaButtonLogic.Action.FF -> t.seekBy(intervalMs)
            MediaButtonLogic.Action.REW -> t.seekBy(-intervalMs)
            MediaButtonLogic.Action.STOP -> t.stopAndExit()
            MediaButtonLogic.Action.NONE -> Unit
        }
    }

    // ---- foreground notification + session state ----

    private fun ensureForeground() {
        if (foregroundStarted) return
        startForeground(NOTIF_ID, buildNotification())
        foregroundStarted = true
    }

    private fun serviceIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): android.app.Notification {
        val t = PlayerController.transport
        val playing = t?.isPlaying() == true
        val open = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            android.app.Notification.Builder(this)
        }
        return builder
            .setSmallIcon(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            .setContentTitle(t?.title() ?: "Shadow Player")
            .setContentText(if (playing) "Playing in background" else "Paused (background)")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                serviceIntent(ACTION_PREV)
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                serviceIntent(if (playing) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                serviceIntent(ACTION_NEXT)
            )
            .setStyle(
                android.app.Notification.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateSessionState() {
        val s = session ?: return
        val t = PlayerController.transport ?: return
        val state = if (t.isPlaying()) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
        s.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, t.title())
                .build()
        )
    }
}

// =====================================================================
// Icon drawables — redrawn from MX-COPY-SPEC §3 descriptions.
// MX's asset files are never lifted; these are our own Canvas drawings
// of the same glyphs (white on transparency unless noted).
// =====================================================================
class MxIcon(private val kind: Int, private val tint: Int = Color.WHITE) : Drawable() {

    companion object {
        const val PLAY = 1
        const val PAUSE = 2
        const val NEXT = 3
        const val PREV = 4
        const val FORWARD = 5
        const val BACKWARD = 6
        const val LOCK = 7
        const val UNLOCK = 8
        const val ROTATION = 9
        const val AUDIO_TRACK = 10
        const val SUBTITLE = 11
        const val MORE = 12
        const val BACK = 13
        const val CLOSE = 14
        const val PIP = 15
        const val BRIGHTNESS = 16
        const val VOLUME = 17
        const val VOLUME_MUTE = 18
        const val SCREENSHOT = 19
        const val PLAYLIST = 20
        const val CHECK = 21
        const val ZOOM_IN = 22
        const val ZOOM_OUT = 23
        const val SPEED = 24
        const val SLEEP = 25
        const val NIGHT = 26
        const val MIRROR = 27
        const val FLIP = 28
        const val EQUALIZER = 29
        const val REPEAT_AB = 30
        const val SHUFFLE = 31
        const val LOOP = 32
        const val STICKY = 33
        // v0.3 frame-spec glyphs (MX-UI-FRAME-SPEC §§1.3/1.5/2/5).
        const val CHEVRON_RIGHT = 34
        const val ASPECT_FIT = 35
        const val ASPECT_STRETCH = 36
        const val ASPECT_CROP = 37
        const val ASPECT_100 = 38
        const val PENCIL = 39
        const val HEADPHONES = 40
        const val STOPWATCH = 41
        const val WAVEFORM = 42
        const val FOLDER = 43
        const val QUEUE = 44
        const val SLIDERS_H = 45
        const val BOOKMARK = 46
        const val CUT = 47
        const val HEART = 48
        const val LIST_PLUS = 49
        const val LIST = 50
        const val SHARE_ARROW = 51
        const val BULB = 52
        const val ROTATE_PHONE = 53
        const val PIP_ENTER = 54
        const val ASPECT_RATIO = 55
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val u = min(w, h) / 24f // 24-unit grid, like MX's 24dp vectors
        stroke.strokeWidth = 1.6f * u
        when (kind) {
            PLAY -> { // v0.5: MX ink 22x26.7dp on a 32dp canvas (measured raster) -> 24-grid ink 16.5x20
                val p = Path()
                p.moveTo(4f * u, 2f * u); p.lineTo(20.5f * u, 12f * u); p.lineTo(4f * u, 22f * u)
                p.close(); canvas.drawPath(p, fill)
            }
            PAUSE -> { // v0.5: MX ink 23x26.7dp, bars ~8.7dp, gap ~5.7dp -> grid bars 6.3u, gap 4.1u
                canvas.drawRoundRect(RectF(3.6f * u, 2f * u, 9.9f * u, 22f * u), 1.5f * u, 1.5f * u, fill)
                canvas.drawRoundRect(RectF(14f * u, 2f * u, 20.3f * u, 22f * u), 1.5f * u, 1.5f * u, fill)
            }
            NEXT -> { // v0.5: MX ink 18x18dp of 24 -> grid ink 18x18u
                val p = Path()
                p.moveTo(3f * u, 3f * u); p.lineTo(15f * u, 12f * u); p.lineTo(3f * u, 21f * u)
                p.close(); canvas.drawPath(p, fill)
                canvas.drawRect(RectF(17f * u, 3f * u, 21f * u, 21f * u), fill)
            }
            PREV -> { // v0.5: mirror of NEXT, MX ink 18x18u
                val p = Path()
                p.moveTo(21f * u, 3f * u); p.lineTo(9f * u, 12f * u); p.lineTo(21f * u, 21f * u)
                p.close(); canvas.drawPath(p, fill)
                canvas.drawRect(RectF(3f * u, 3f * u, 7f * u, 21f * u), fill)
            }
            FORWARD, BACKWARD -> { // v0.5: MX glyph is a double-triangle (ink 18.7x16.7), NOT a circular arrow; the "10" numeral overlays it
                if (kind == FORWARD) {
                    val p = Path()
                    p.moveTo(2f * u, 4f * u); p.lineTo(11.5f * u, 12f * u); p.lineTo(2f * u, 20f * u)
                    p.close(); canvas.drawPath(p, fill)
                    val p2 = Path()
                    p2.moveTo(10f * u, 4f * u); p2.lineTo(19.5f * u, 12f * u); p2.lineTo(10f * u, 20f * u)
                    p2.close(); canvas.drawPath(p2, fill)
                } else {
                    val p = Path()
                    p.moveTo(22f * u, 4f * u); p.lineTo(12.5f * u, 12f * u); p.lineTo(22f * u, 20f * u)
                    p.close(); canvas.drawPath(p, fill)
                    val p2 = Path()
                    p2.moveTo(14f * u, 4f * u); p2.lineTo(4.5f * u, 12f * u); p2.lineTo(14f * u, 20f * u)
                    p2.close(); canvas.drawPath(p2, fill)
                }
            }
            LOCK, UNLOCK -> { // v0.5: device-measured ink 19x22dp -> body (2.5,10)-(21.5,22), shackle (5.5,1)-(18.5,11.5)
                // v0.5.2: ink spans y 1..22 (centre 11.5u) — shift
                // down 0.5u so the glyph's optical centre sits on
                // the grid centre like every other transport icon.
                canvas.save()
                canvas.translate(0f, 0.5f * u)
                val p = Path()
                if (kind == LOCK) {
                    p.moveTo(5.5f * u, 11f * u); p.lineTo(5.5f * u, 7f * u)
                    p.arcTo(RectF(5.5f * u, 1f * u, 18.5f * u, 11.5f * u), 180f, 180f)
                    p.lineTo(18.5f * u, 11f * u)
                } else {
                    p.moveTo(18.5f * u, 11f * u); p.lineTo(18.5f * u, 7f * u)
                    p.arcTo(RectF(5.5f * u, 1f * u, 18.5f * u, 11.5f * u), 0f, -140f)
                }
                canvas.drawPath(p, stroke)
                canvas.drawRoundRect(RectF(2.5f * u, 10f * u, 21.5f * u, 22f * u), 1.5f * u, 1.5f * u, fill)
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; style = Paint.Style.FILL
                }
                canvas.drawCircle(12f * u, 15.2f * u, 1.7f * u, hole)
                canvas.drawRect(RectF(11.2f * u, 15.2f * u, 12.8f * u, 18.4f * u), hole)
                canvas.restore()
            }
            ROTATION -> { // v0.5: MX glyph = tilted phone + lock (ink 22x19.3), not a circular arrow
                canvas.save()
                canvas.rotate(-20f, 10.5f * u, 12f * u)
                canvas.drawRoundRect(RectF(5.5f * u, 3.5f * u, 15.5f * u, 20.5f * u), 1.8f * u, 1.8f * u, stroke)
                canvas.restore()
                canvas.drawRoundRect(RectF(13.5f * u, 13.5f * u, 21.5f * u, 20.5f * u), 1.2f * u, 1.2f * u, fill)
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; style = Paint.Style.FILL
                }
                canvas.drawCircle(17.5f * u, 16.6f * u, 1.2f * u, hole)
            }
            AUDIO_TRACK -> { // v0.5: MX ink 13x18dp -> head (4,14.5)-(10.5,21), stem to y3, flag to x17
                canvas.drawOval(RectF(4f * u, 14.5f * u, 10.5f * u, 21f * u), fill)
                canvas.drawRect(RectF(9.6f * u, 3f * u, 11.2f * u, 16.5f * u), fill)
                val p = Path()
                p.moveTo(9.6f * u, 3f * u); p.quadTo(14.5f * u, 3.5f * u, 17f * u, 5.5f * u)
                p.lineTo(17f * u, 11f * u); p.quadTo(14f * u, 8f * u, 9.6f * u, 5.5f * u)
                p.close(); canvas.drawPath(p, fill)
            }
            SUBTITLE -> { // v0.5: MX ink 20x20dp speech card -> card (2,2)-(22,22)
                canvas.drawRoundRect(RectF(2f * u, 2f * u, 22f * u, 22f * u), 2.5f * u, 2.5f * u, stroke)
                canvas.drawRect(RectF(6.5f * u, 9f * u, 17.5f * u, 10.8f * u), fill)
                canvas.drawRect(RectF(6.5f * u, 13.4f * u, 13.5f * u, 15.2f * u), fill)
            }
            MORE -> { // v0.5: MX dots pitch 6dp, span 16 -> cy 6/12/18
                for (cy in floatArrayOf(6f, 12f, 18f))
                    canvas.drawCircle(12f * u, cy * u, 2f * u, fill)
            }
            BACK -> { // v0.5: MX ink 15.7x15.3dp -> head (11,4.3)-(4,12)-(11,19.7) + shaft to x19.7
                val p = Path()
                p.moveTo(11f * u, 4.3f * u); p.lineTo(4f * u, 12f * u); p.lineTo(11f * u, 19.7f * u)
                p.close(); canvas.drawPath(p, fill)
                canvas.drawRect(RectF(10f * u, 10.6f * u, 19.7f * u, 13.4f * u), fill)
            }
            CLOSE -> {
                stroke.strokeWidth = 2.2f * u
                canvas.drawLine(6f * u, 6f * u, 18f * u, 18f * u, stroke)
                canvas.drawLine(18f * u, 6f * u, 6f * u, 18f * u, stroke)
            }
            PIP -> { // v0.5: MX ink 22x18dp -> outer (1.5,3)-(22.5,21), inner solid (13,12.5)-(20,17)
                canvas.drawRoundRect(RectF(1.5f * u, 3f * u, 22.5f * u, 21f * u), 1.5f * u, 1.5f * u, stroke)
                canvas.drawRect(RectF(13f * u, 12.5f * u, 20f * u, 17f * u), fill)
            }
            BRIGHTNESS -> { // §3 #31: sun, left half filled, 8 rays
                canvas.drawArc(RectF(8f * u, 8f * u, 16f * u, 16f * u), 90f, 180f, true, fill)
                canvas.drawCircle(12f * u, 12f * u, 4f * u, stroke)
                stroke.strokeWidth = 1.4f * u
                val rays = arrayOf(
                    floatArrayOf(12f, 2.5f, 12f, 5f), floatArrayOf(12f, 19f, 12f, 21.5f),
                    floatArrayOf(2.5f, 12f, 5f, 12f), floatArrayOf(19f, 12f, 21.5f, 12f),
                    floatArrayOf(5.3f, 5.3f, 7f, 7f), floatArrayOf(17f, 17f, 18.7f, 18.7f),
                    floatArrayOf(18.7f, 5.3f, 17f, 7f), floatArrayOf(7f, 17f, 5.3f, 18.7f)
                )
                for (r in rays) canvas.drawLine(r[0] * u, r[1] * u, r[2] * u, r[3] * u, stroke)
            }
            VOLUME, VOLUME_MUTE -> { // speaker + arcs (or ×)
                val p = Path()
                p.moveTo(4f * u, 9.5f * u); p.lineTo(7.5f * u, 9.5f * u); p.lineTo(12.5f * u, 5f * u)
                p.lineTo(12.5f * u, 19f * u); p.lineTo(7.5f * u, 14.5f * u); p.lineTo(4f * u, 14.5f * u)
                p.close(); canvas.drawPath(p, fill)
                if (kind == VOLUME) {
                    canvas.drawArc(RectF(12f * u, 8.5f * u, 18f * u, 15.5f * u), -55f, 110f, false, stroke)
                    canvas.drawArc(RectF(13.5f * u, 6f * u, 21.5f * u, 18f * u), -55f, 110f, false, stroke)
                } else {
                    stroke.strokeWidth = 1.8f * u
                    canvas.drawLine(15f * u, 9.5f * u, 20.5f * u, 15f * u, stroke)
                    canvas.drawLine(20.5f * u, 9.5f * u, 15f * u, 15f * u, stroke)
                }
            }
            SCREENSHOT -> { // §3 #37: camera in viewfinder brackets
                stroke.strokeWidth = 1.7f * u
                canvas.drawLine(3.5f * u, 8f * u, 3.5f * u, 3.5f * u, stroke)
                canvas.drawLine(3.5f * u, 3.5f * u, 8f * u, 3.5f * u, stroke)
                canvas.drawLine(16f * u, 3.5f * u, 20.5f * u, 3.5f * u, stroke)
                canvas.drawLine(20.5f * u, 3.5f * u, 20.5f * u, 8f * u, stroke)
                canvas.drawLine(20.5f * u, 16f * u, 20.5f * u, 20.5f * u, stroke)
                canvas.drawLine(20.5f * u, 20.5f * u, 16f * u, 20.5f * u, stroke)
                canvas.drawLine(8f * u, 20.5f * u, 3.5f * u, 20.5f * u, stroke)
                canvas.drawLine(3.5f * u, 20.5f * u, 3.5f * u, 16f * u, stroke)
                canvas.drawCircle(12f * u, 12f * u, 3.4f * u, stroke)
            }
            PLAYLIST -> { // v0.5: MX = lines + ONE play triangle at right, ink 20.3x13 -> lines x2-13, triangle (15,5.5)-(22.3,12.5)-(15,19.5)
                canvas.drawRect(RectF(2f * u, 7f * u, 13f * u, 8.8f * u), fill)
                canvas.drawRect(RectF(2f * u, 15.2f * u, 13f * u, 17f * u), fill)
                val p = Path()
                p.moveTo(15f * u, 5.5f * u); p.lineTo(22.3f * u, 12.5f * u); p.lineTo(15f * u, 19.5f * u)
                p.close(); canvas.drawPath(p, fill)
            }
            CHECK -> { // §3 #43: tick
                stroke.strokeWidth = 2.4f * u
                val p = Path()
                p.moveTo(4.5f * u, 12.8f * u); p.lineTo(9.8f * u, 17.8f * u); p.lineTo(19.5f * u, 6.5f * u)
                canvas.drawPath(p, stroke)
            }
            ZOOM_IN, ZOOM_OUT -> { // magnifier ± (zoom step buttons)
                canvas.drawCircle(10.5f * u, 10.5f * u, 6f * u, stroke)
                stroke.strokeWidth = 2f * u
                canvas.drawLine(15.2f * u, 15.2f * u, 20.5f * u, 20.5f * u, stroke)
                canvas.drawLine(7.5f * u, 10.5f * u, 13.5f * u, 10.5f * u, stroke)
                if (kind == ZOOM_IN) canvas.drawLine(10.5f * u, 7.5f * u, 10.5f * u, 13.5f * u, stroke)
            }
            SPEED -> { // gauge: semicircle + needle
                canvas.drawArc(RectF(4f * u, 6f * u, 20f * u, 20f * u), 180f, 180f, false, stroke)
                stroke.strokeWidth = 1.8f * u
                canvas.drawLine(12f * u, 13f * u, 16.5f * u, 8.5f * u, stroke)
                canvas.drawCircle(12f * u, 13f * u, 1.3f * u, fill)
            }
            SLEEP -> { // crescent moon
                val p = Path()
                p.addCircle(12f * u, 12f * u, 8f * u, Path.Direction.CW)
                val cut = Path()
                cut.addCircle(15.5f * u, 9f * u, 6.6f * u, Path.Direction.CW)
                p.op(cut, Path.Op.DIFFERENCE)
                canvas.drawPath(p, fill)
            }
            NIGHT -> { // moon + star (night mode)
                val p = Path()
                p.addCircle(10.5f * u, 13f * u, 7f * u, Path.Direction.CW)
                val cut = Path()
                cut.addCircle(13.8f * u, 10.2f * u, 5.8f * u, Path.Direction.CW)
                p.op(cut, Path.Op.DIFFERENCE)
                canvas.drawPath(p, fill)
                canvas.drawCircle(17.5f * u, 6f * u, 1.1f * u, fill)
                canvas.drawCircle(20f * u, 10.5f * u, 0.9f * u, fill)
            }
            MIRROR -> { // two triangles facing across a centre line
                stroke.strokeWidth = 1.4f * u
                canvas.drawLine(12f * u, 4f * u, 12f * u, 20f * u, stroke)
                val l = Path()
                l.moveTo(9.5f * u, 8f * u); l.lineTo(4.5f * u, 12f * u); l.lineTo(9.5f * u, 16f * u)
                l.close(); canvas.drawPath(l, fill)
                val r = Path()
                r.moveTo(14.5f * u, 8f * u); r.lineTo(19.5f * u, 12f * u); r.lineTo(14.5f * u, 16f * u)
                r.close(); canvas.drawPath(r, fill)
            }
            FLIP -> { // vertical flip: triangles across a horizontal line
                stroke.strokeWidth = 1.4f * u
                canvas.drawLine(4f * u, 12f * u, 20f * u, 12f * u, stroke)
                val t = Path()
                t.moveTo(8f * u, 9.5f * u); t.lineTo(12f * u, 4.5f * u); t.lineTo(16f * u, 9.5f * u)
                t.close(); canvas.drawPath(t, fill)
                val btm = Path()
                btm.moveTo(8f * u, 14.5f * u); btm.lineTo(12f * u, 19.5f * u); btm.lineTo(16f * u, 14.5f * u)
                btm.close(); canvas.drawPath(btm, fill)
            }
            EQUALIZER -> { // three sliders
                stroke.strokeWidth = 1.7f * u
                for (x in floatArrayOf(7f, 12f, 17f))
                    canvas.drawLine(x * u, 4.5f * u, x * u, 19.5f * u, stroke)
                canvas.drawCircle(7f * u, 9f * u, 2.1f * u, fill)
                canvas.drawCircle(12f * u, 14.5f * u, 2.1f * u, fill)
                canvas.drawCircle(17f * u, 7.5f * u, 2.1f * u, fill)
            }
            REPEAT_AB -> { // "A-B" rendered as text glyph
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tint; textSize = 9.5f * u; typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("A-B", 12f * u, 15.5f * u, tp)
            }
            SHUFFLE -> { // crossing arrows
                stroke.strokeWidth = 1.7f * u
                val p = Path()
                p.moveTo(3.5f * u, 7.5f * u); p.cubicTo(9f * u, 7.5f * u, 12f * u, 16.5f * u, 17.5f * u, 16.5f * u)
                canvas.drawPath(p, stroke)
                val p2 = Path()
                p2.moveTo(3.5f * u, 16.5f * u); p2.cubicTo(9f * u, 16.5f * u, 12f * u, 7.5f * u, 17.5f * u, 7.5f * u)
                canvas.drawPath(p2, stroke)
                val a1 = Path()
                a1.moveTo(15f * u, 4.8f * u); a1.lineTo(20.3f * u, 7.4f * u); a1.lineTo(15f * u, 10f * u)
                a1.close(); canvas.drawPath(a1, fill)
                val a2 = Path()
                a2.moveTo(15f * u, 14f * u); a2.lineTo(20.3f * u, 16.6f * u); a2.lineTo(15f * u, 19.2f * u)
                a2.close(); canvas.drawPath(a2, fill)
            }
            LOOP -> { // two chasing arcs
                canvas.drawArc(RectF(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u), -50f, 130f, false, stroke)
                canvas.drawArc(RectF(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u), 130f, 130f, false, stroke)
                val a1 = Path()
                a1.moveTo(19.8f * u, 2.4f * u); a1.lineTo(20.1f * u, 7.4f * u); a1.lineTo(15.4f * u, 5.3f * u)
                a1.close(); canvas.drawPath(a1, fill)
                val a2 = Path()
                a2.moveTo(4.2f * u, 21.6f * u); a2.lineTo(3.9f * u, 16.6f * u); a2.lineTo(8.6f * u, 18.7f * u)
                a2.close(); canvas.drawPath(a2, fill)
            }
            STICKY -> { // pin
                val p = Path()
                p.moveTo(9f * u, 3.5f * u); p.lineTo(15f * u, 3.5f * u); p.lineTo(15f * u, 5.5f * u)
                p.lineTo(13.5f * u, 8f * u); p.lineTo(13.5f * u, 13f * u); p.lineTo(17f * u, 13f * u)
                p.lineTo(17f * u, 15f * u); p.lineTo(7f * u, 15f * u); p.lineTo(7f * u, 13f * u)
                p.lineTo(10.5f * u, 13f * u); p.lineTo(10.5f * u, 8f * u); p.lineTo(9f * u, 5.5f * u)
                p.close(); canvas.drawPath(p, fill)
                canvas.drawRect(RectF(11.2f * u, 15f * u, 12.8f * u, 20.5f * u), fill)
            }
            CHEVRON_RIGHT -> { // ">" expander / More-grid door
                stroke.strokeWidth = 2.2f * u
                val p = Path()
                p.moveTo(9f * u, 5f * u); p.lineTo(16f * u, 12f * u); p.lineTo(9f * u, 19f * u)
                canvas.drawPath(p, stroke)
            }
            ASPECT_FIT -> { // v0.5: shared MX outline ink 22x18 -> rect (1,3)-(23,21); inner bars rescaled
                stroke.strokeWidth = 1.7f * u
                canvas.drawRoundRect(RectF(1f * u, 3f * u, 23f * u, 21f * u), 1.5f * u, 1.5f * u, stroke)
                canvas.drawRect(RectF(8.6f * u, 8f * u, 10.4f * u, 16f * u), fill)
                canvas.drawRect(RectF(13.6f * u, 8f * u, 15.4f * u, 16f * u), fill)
            }
            ASPECT_STRETCH -> { // rect + horizontal ↔ arrows
                stroke.strokeWidth = 1.7f * u
                canvas.drawRoundRect(RectF(1f * u, 3f * u, 23f * u, 21f * u), 1.5f * u, 1.5f * u, stroke)
                stroke.strokeWidth = 1.5f * u
                canvas.drawLine(6.5f * u, 12f * u, 17.5f * u, 12f * u, stroke)
                val l = Path()
                l.moveTo(9f * u, 9.4f * u); l.lineTo(5.6f * u, 12f * u); l.lineTo(9f * u, 14.6f * u)
                l.close(); canvas.drawPath(l, fill)
                val r = Path()
                r.moveTo(15f * u, 9.4f * u); r.lineTo(18.4f * u, 12f * u); r.lineTo(15f * u, 14.6f * u)
                r.close(); canvas.drawPath(r, fill)
            }
            ASPECT_CROP -> { // rect + diagonal expand arrows (↗↙)
                stroke.strokeWidth = 1.7f * u
                canvas.drawRoundRect(RectF(1f * u, 3f * u, 23f * u, 21f * u), 1.5f * u, 1.5f * u, stroke)
                stroke.strokeWidth = 1.5f * u
                canvas.drawLine(9f * u, 15f * u, 15f * u, 9f * u, stroke)
                val a1 = Path()
                a1.moveTo(11.8f * u, 8.2f * u); a1.lineTo(16.4f * u, 8.2f * u); a1.lineTo(16.4f * u, 12.8f * u)
                a1.close(); canvas.drawPath(a1, fill)
                val a2 = Path()
                a2.moveTo(12.2f * u, 15.8f * u); a2.lineTo(7.6f * u, 15.8f * u); a2.lineTo(7.6f * u, 11.2f * u)
                a2.close(); canvas.drawPath(a2, fill)
            }
            ASPECT_100 -> { // plain rectangle outline (§2: the 100% glyph)
                stroke.strokeWidth = 1.7f * u
                canvas.drawRoundRect(RectF(1f * u, 3f * u, 23f * u, 21f * u), 1.5f * u, 1.5f * u, stroke)
            }
            ASPECT_RATIO -> { // grid item: rect with a filled top-left block (f_028)
                stroke.strokeWidth = 1.7f * u
                canvas.drawRoundRect(RectF(4f * u, 5f * u, 20f * u, 19f * u), 2f * u, 2f * u, stroke)
                canvas.drawRect(RectF(6.2f * u, 7.2f * u, 12.5f * u, 12.5f * u), fill)
            }
            PENCIL -> { // Customise Items: diagonal pencil
                val p = Path()
                p.moveTo(4.5f * u, 19.5f * u); p.lineTo(5.6f * u, 15.2f * u)
                p.lineTo(16.6f * u, 4.2f * u); p.lineTo(19.8f * u, 7.4f * u)
                p.lineTo(8.8f * u, 18.4f * u); p.close()
                canvas.drawPath(p, stroke)
                stroke.strokeWidth = 1.4f * u
                canvas.drawLine(14.8f * u, 6f * u, 18f * u, 9.2f * u, stroke)
            }
            HEADPHONES -> { // Background Play
                stroke.strokeWidth = 1.8f * u
                canvas.drawArc(RectF(4.5f * u, 4f * u, 19.5f * u, 17f * u), 180f, 180f, false, stroke)
                canvas.drawRoundRect(RectF(3.8f * u, 12.5f * u, 7.6f * u, 18.5f * u), 1.6f * u, 1.6f * u, fill)
                canvas.drawRoundRect(RectF(16.4f * u, 12.5f * u, 20.2f * u, 18.5f * u), 1.6f * u, 1.6f * u, fill)
            }
            STOPWATCH -> { // Sleep Timer
                stroke.strokeWidth = 1.8f * u
                canvas.drawCircle(12f * u, 13.5f * u, 7.5f * u, stroke)
                canvas.drawRect(RectF(10.6f * u, 2.6f * u, 13.4f * u, 4.6f * u), fill)
                canvas.drawRect(RectF(11.3f * u, 4.4f * u, 12.7f * u, 6.2f * u), fill)
                stroke.strokeWidth = 1.6f * u
                canvas.drawLine(12f * u, 13.5f * u, 12f * u, 9.2f * u, stroke)
                canvas.drawLine(12f * u, 13.5f * u, 15f * u, 15f * u, stroke)
            }
            WAVEFORM -> { // Audio Effect: vertical bars of varied height
                stroke.strokeWidth = 1.9f * u
                val bars = arrayOf(
                    floatArrayOf(4.5f, 10f, 14f), floatArrayOf(8f, 7f, 17f),
                    floatArrayOf(11.5f, 4.5f, 19.5f), floatArrayOf(15f, 8.5f, 15.5f),
                    floatArrayOf(18.5f, 10.5f, 13.5f)
                )
                for (bar in bars) canvas.drawLine(bar[0] * u, bar[1] * u, bar[0] * u, bar[2] * u, stroke)
            }
            FOLDER -> { // v0.5: MX = FILLED closed-folder silhouette, ink 20x16 -> fill (2,4)-(22,20)
                val p = Path()
                p.moveTo(2f * u, 20f * u); p.lineTo(2f * u, 4.5f * u); p.lineTo(9f * u, 4.5f * u)
                p.lineTo(11.5f * u, 7f * u); p.lineTo(22f * u, 7f * u); p.lineTo(22f * u, 20f * u)
                p.close(); canvas.drawPath(p, fill)
            }
            QUEUE -> { // Playing Queue: lines + play triangle bottom-right
                canvas.drawRect(RectF(3.5f * u, 5.5f * u, 14f * u, 7.3f * u), fill)
                canvas.drawRect(RectF(3.5f * u, 10f * u, 14f * u, 11.8f * u), fill)
                canvas.drawRect(RectF(3.5f * u, 14.5f * u, 9.5f * u, 16.3f * u), fill)
                val p = Path()
                p.moveTo(13.5f * u, 13.8f * u); p.lineTo(20.5f * u, 17.3f * u); p.lineTo(13.5f * u, 20.8f * u)
                p.close(); canvas.drawPath(p, fill)
            }
            SLIDERS_H -> { // Display Settings: two horizontal sliders
                stroke.strokeWidth = 1.7f * u
                canvas.drawLine(4f * u, 8.5f * u, 20f * u, 8.5f * u, stroke)
                canvas.drawLine(4f * u, 15.5f * u, 20f * u, 15.5f * u, stroke)
                canvas.drawCircle(15f * u, 8.5f * u, 2.6f * u, fill)
                canvas.drawCircle(8.5f * u, 15.5f * u, 2.6f * u, fill)
            }
            BOOKMARK -> { // ribbon bookmark
                stroke.strokeWidth = 1.7f * u
                val p = Path()
                p.moveTo(7f * u, 4f * u); p.lineTo(17f * u, 4f * u); p.lineTo(17f * u, 20f * u)
                p.lineTo(12f * u, 15.8f * u); p.lineTo(7f * u, 20f * u); p.close()
                canvas.drawPath(p, stroke)
            }
            CUT -> { // frame + play (clip editor)
                stroke.strokeWidth = 1.7f * u
                canvas.drawRoundRect(RectF(4f * u, 5f * u, 20f * u, 19f * u), 1.5f * u, 1.5f * u, stroke)
                val p = Path()
                p.moveTo(10f * u, 9f * u); p.lineTo(15.5f * u, 12f * u); p.lineTo(10f * u, 15f * u)
                p.close(); canvas.drawPath(p, fill)
            }
            HEART -> { // Favourite
                stroke.strokeWidth = 1.8f * u
                val p = Path()
                p.moveTo(12f * u, 20f * u)
                p.cubicTo(4f * u, 14.5f * u, 3.6f * u, 9.5f * u, 6.6f * u, 6.6f * u)
                p.cubicTo(8.8f * u, 4.4f * u, 11.4f * u, 5.4f * u, 12f * u, 7.6f * u)
                p.cubicTo(12.6f * u, 5.4f * u, 15.2f * u, 4.4f * u, 17.4f * u, 6.6f * u)
                p.cubicTo(20.4f * u, 9.5f * u, 20f * u, 14.5f * u, 12f * u, 20f * u)
                canvas.drawPath(p, stroke)
            }
            LIST_PLUS -> { // Add To Playlist: lines + plus
                canvas.drawRect(RectF(3.5f * u, 5.5f * u, 13f * u, 7.3f * u), fill)
                canvas.drawRect(RectF(3.5f * u, 10f * u, 13f * u, 11.8f * u), fill)
                canvas.drawRect(RectF(3.5f * u, 14.5f * u, 9f * u, 16.3f * u), fill)
                stroke.strokeWidth = 2f * u
                canvas.drawLine(16.8f * u, 12.8f * u, 16.8f * u, 20f * u, stroke)
                canvas.drawLine(13.2f * u, 16.4f * u, 20.4f * u, 16.4f * u, stroke)
            }
            LIST -> { // Information: bullets + lines
                for (cy in floatArrayOf(6.5f, 12f, 17.5f)) {
                    canvas.drawCircle(5.5f * u, cy * u, 1.5f * u, fill)
                    canvas.drawRect(RectF(9.5f * u, cy * u - 0.9f * u, 20f * u, cy * u + 0.9f * u), fill)
                }
            }
            SHARE_ARROW -> { // curved share arrow
                stroke.strokeWidth = 1.9f * u
                val p = Path()
                p.moveTo(5f * u, 18.5f * u)
                p.cubicTo(5f * u, 11f * u, 10f * u, 7.5f * u, 17.5f * u, 7.2f * u)
                canvas.drawPath(p, stroke)
                val a = Path()
                a.moveTo(13.6f * u, 3.6f * u); a.lineTo(19.6f * u, 7.1f * u); a.lineTo(13.9f * u, 11.2f * u)
                a.close(); canvas.drawPath(a, fill)
            }
            BULB -> { // Tutorial: light bulb
                stroke.strokeWidth = 1.7f * u
                canvas.drawCircle(12f * u, 10.5f * u, 6.3f * u, stroke)
                canvas.drawRect(RectF(9.8f * u, 17.8f * u, 14.2f * u, 19.2f * u), fill)
                canvas.drawRect(RectF(10.4f * u, 20.2f * u, 13.6f * u, 21.4f * u), fill)
            }
            ROTATE_PHONE -> { // Screen Rotation: tilted phone + orbit arcs
                canvas.save()
                canvas.rotate(45f, 12f * u, 12f * u)
                stroke.strokeWidth = 1.6f * u
                canvas.drawRoundRect(RectF(8.2f * u, 4.5f * u, 15.8f * u, 19.5f * u), 1.6f * u, 1.6f * u, stroke)
                canvas.restore()
                canvas.drawArc(RectF(2.8f * u, 2.8f * u, 21.2f * u, 21.2f * u), -30f, 70f, false, stroke)
                canvas.drawArc(RectF(2.8f * u, 2.8f * u, 21.2f * u, 21.2f * u), 150f, 70f, false, stroke)
            }
            PIP_ENTER -> { // rect + arrow into the small bottom-right rect (f_001)
                stroke.strokeWidth = 1.7f * u
                canvas.drawRoundRect(RectF(3f * u, 5f * u, 21f * u, 19f * u), 1.5f * u, 1.5f * u, stroke)
                canvas.drawRoundRect(RectF(13f * u, 12.5f * u, 19f * u, 17f * u), 1f * u, 1f * u, stroke)
                stroke.strokeWidth = 1.5f * u
                canvas.drawLine(7f * u, 8.5f * u, 12.4f * u, 13.9f * u, stroke)
                val a = Path()
                a.moveTo(8.6f * u, 13.6f * u); a.lineTo(13.2f * u, 13.8f * u); a.lineTo(13f * u, 9.2f * u)
                a.close(); canvas.drawPath(a, fill)
            }
            else -> canvas.drawCircle(12f * u, 12f * u, 5f * u, fill)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter; stroke.colorFilter = colorFilter
    }
    @Deprecated("Drawable opacity")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

// =====================================================================
// The player activity
// =====================================================================
class PlayerActivity : Activity() {

    companion object {
        private const val REQ_SUBTITLE = 41
        private const val REQ_AUDIO_FILE = 42
        private const val SAVE_INTERVAL_MS = 5000L
        /** §S2: ONE unified 100 ms UI loop (progress + time labels +
         *  subtitle cues) — MX's what=1 tick. The old separate 500 ms
         *  progress / 200 ms subtitle ticks are deleted. */
        private const val UI_TICK_MS = 100L
        /** §S2: volume changes ramp on a 50 ms loop (MX what=3). */
        private const val VOLUME_RAMP_MS = 50L
        /** §S4 staging: the 250 ms AvSyncPolicy ticker survives ONLY
         *  for HW+ (MediaPlayer video slaved to the engine's audio
         *  staging). It never runs in SW mode — there the engine's
         *  video renderer follows the engine clock natively.
         *  ENGINE-API.md did not exist when this landed, so native
         *  sync for the SW path is NOT certified and nothing was
         *  deleted on that path (the ticker already early-returned
         *  there; it is now also never posted there). */
        private const val SYNC_TICK_MS = 250L
        private const val FINISH_FRACTION = 0.95
        private const val RESUME_MIN_MS = 5000L
        private const val NOTIF_CHANNEL = "gbp_playback"
        private const val NOTIF_ID = 7
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        // MX tokens (§1.8): accent/played, chrome, page, scrim, text ramp
        private var ACCENT = Color.parseColor("#3C8CF0")
        private val CHROME = Color.parseColor("#1C2939")
        private val PAGE = Color.parseColor("#16202B")
        private val TITLE_C = Color.parseColor("#DADDE4")
        private val SECONDARY = Color.parseColor("#85929C")
        private val TERTIARY = Color.parseColor("#96A2BA")
        private val SCRIM = Color.parseColor("#99000000")
        private val IMMERSIVE_BG = Color.parseColor("#111111")
    }

    // ---- intent state ----
    private var path: String = ""
    /** v0.5.3: set from the resume_exact intent extra (the library's
     *  play-last button) — the first resume handler that meets this
     *  path seeks straight to the saved position, bypassing the
     *  Ask/Start-over prompt, then clears the flag. */
    private var exactResumePath: String? = null
    private var title: String = ""
    private var playlist: ArrayList<String> = arrayListOf()
    private var index: Int = 0

    // ---- player state ----
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var surfaceReady = false
    private var prepared = false
    private var durationMs = 0
    private var videoW = 0
    private var videoH = 0
    private var currentSpeed = 1.0f
    private var wasPlayingBeforePause = false
    private var finishedMarked = false
    private var errorShown = false
    private var playWhenReady = true
    private var inPip = false
    private var backgrounded = false

    // ---- decoder state ----
    /** Resolved mode for this file: 1 HW, 4 HW+, 2 SW (MX values). */
    private var decoderMode = 1
    private var engineHandle = 0L
    private val engineActive: Boolean get() = engineHandle != 0L
    private var boostPct = 100 // 100..200, engine modes only

    // ---- SW video state (decoderMode == 2) ----
    /** The engine's software video session; null outside SW mode.
     *  MediaPlayer is never given the file in SW mode — the engine
     *  decodes audio (master clock, via engineHandle) AND video
     *  (this session, rendered natively into the same Surface). */
    private var swVideo: SwVideoSession? = null
    private val swMode: Boolean get() = decoderMode == 2
    /** Completion latch for SW mode: with no MediaPlayer there is
     *  no OnCompletionListener, so updateProgress() polls the two
     *  engine sessions' end state and fires onPlaybackComplete once. */
    private var swCompletionFired = false

    // ---- playback modes ----
    private var loopOne = false
    private var loopAll = false
    private var shuffle = false
    private var muted = false
    private var lastVolume = 0.7f

    // ---- zoom / aspect ----
    private var zoomScale = 1.0f
    private var panX = 0f
    private var panY = 0f
    /** "fit" / "stretch" / "crop" / "100" / "custom" (pinch) — MX Video zoom.
     *  DEFAULT is "crop" (v0.2.1, Sir's order): the video fills the
     *  whole screen — on a 20:9 phone a 16:9 video in "fit" leaves
     *  black bars on both sides, which Sir rejected ("I want this app
     *  work full screen"). Crop scales to cover the full window
     *  (which, with the cutout SHORT_EDGES fix, is the full physical
     *  display) and centre-crops the overflow. Still switchable in
     *  the Video zoom dialog; the choice holds for the session. */
    private var zoomMode = "crop"
    /** Aspect override: null = Default, else width/height ratio. */
    private var aspectOverride: Float? = null
    private var mirror = false
    private var vflip = false
    private var nightMode = false

    // ---- subtitle state ----
    private data class SubSource(
        val label: String,
        val kind: Int, // 0 none, 1 embedded, 2 file
        val trackIndex: Int = -1,
        val filePath: String = ""
    )
    private var subSources: List<SubSource> = listOf(SubSource("None", 0))
    private var subCurrent: SubSource = subSources[0]
    private var cues: List<Cue> = emptyList()
    private var subSessionOffsetMs = 0L
    private var subFileBytes: ByteArray? = null // for re-decode on encoding change
    private var subFileName: String? = null

    // ---- sleep timer ----
    private var sleepDeadlineMs = 0L
    private var sleepPlayToEnd = false
    private var sleepFired = false

    // ---- lock ----
    private var locked = false
    private var backPressedOnce = false
    private var lastUnlockTapMs = 0L

    // ---- speed FF (long press) ----
    private var ffActive = false
    private var speedBeforeFF = 1.0f

    // ---- UI ----
    private lateinit var root: FrameLayout
    private lateinit var videoView: SurfaceView
    private lateinit var topChrome: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var decoderBtn: TextView
    private lateinit var bottomChrome: LinearLayout
    private lateinit var playBtn: android.widget.ImageButton
    private lateinit var seekView: MxSeekBar
    private lateinit var curTimeView: TextView
    private lateinit var durTimeView: TextView
    private lateinit var stripBox: LinearLayout
    private lateinit var stripCollapsedRow: LinearLayout
    private lateinit var stripExpandedScroll: HorizontalScrollView
    private lateinit var stripExpandedRow: LinearLayout
    private var stripExpanded = false
    private lateinit var fwdTextView: TextView
    private lateinit var backTextView: TextView
    private lateinit var subPanelBar: LinearLayout
    private lateinit var subPanelChips: LinearLayout
    private var floatBar: LinearLayout? = null
    private var floatBarKind: String = ""
    private var floatBarValue: android.widget.EditText? = null
    private lateinit var aspectBtn: android.widget.ImageButton
    private lateinit var aspectOsd: TextView
    private lateinit var sheetScrim: View
    private lateinit var sheetPanel: FrameLayout
    private lateinit var sheetBox: LinearLayout
    private var sheetVisible = false
    /** Top inset (status bar / cutout) the chrome pads for — the
     *  frame spec §7 anchoring: video full-bleed, chrome content
     *  keeps MX's positions inside the safe area. */
    private var chromePadTop = 0
    private var audioDisabled = false
    private lateinit var subtitleView: TextView
    private lateinit var indicatorView: TextView
    private lateinit var brightBar: VerticalBar
    private lateinit var soundBar: VerticalBar
    private lateinit var seekPreview: LinearLayout
    private lateinit var seekPreviewText: TextView
    private lateinit var seekPreviewImage: android.widget.ImageView
    private lateinit var bufferingView: LinearLayout
    private lateinit var ffIndicator: TextView
    private lateinit var tapSeekLeft: TextView
    private lateinit var tapSeekRight: TextView
    private lateinit var nightOverlay: View
    private lateinit var menuScrim: View
    private lateinit var menuPanel: LinearLayout
    private lateinit var menuList: LinearLayout
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerList: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var unlockBtn: android.widget.ImageButton
    private var controlsVisible = false
    private var userSeeking = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var audioManager: AudioManager

    // ---- gesture session state ----
    private enum class GestureMode { NONE, BRIGHTNESS, VOLUME, SEEK, SUB_MOVE, SUB_SYNC }
    private var gestureMode = GestureMode.NONE
    private var gestureBaseValue = 0f
    private var gestureBasePosMs = 0L
    private var scrubTargetMs = 0L
    private var scrubWasPlaying = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var longPressFired = false
    private var subDragBaseMargin = 0
    private var subDragBaseOffset = 0L
    private var touchOnSubtitle = false

    // ---- background-play transport (§4.2 #50/#60) ----
    /** Registered in PlayerController for the activity's whole life:
     *  the foreground service + MediaSession drive playback through
     *  this while the activity is backgrounded. */
    private val transportImpl = object : PlayerController.Transport {
        override fun play() = startPlayback()
        override fun pause() = pausePlayback()
        override fun toggle() = togglePlayPause()
        override fun next() = playNext()
        override fun prev() = playPrev()
        override fun seekBy(deltaMs: Long) = skipBy(deltaMs)
        override fun stopAndExit() = exitPlayer()
        override fun isPlaying(): Boolean = this@PlayerActivity.isPlaying()
        override fun title(): String = title
    }

    // ---- foreground media keys (§4.2 #60) ----
    private val hookTracker = HookPressTracker()
    private var pendingHookAction: Runnable? = null

    private val hideRunnable = Runnable {
        // Frame spec §1.6/§6: while paused the controls STAY UP —
        // MX never auto-hides over a paused frame (and there is no
        // "Paused" pill; the ▶ swap is the whole affordance).
        if (prepared && !isPlaying()) return@Runnable
        hideControls()
    }
    private val unlockHideRunnable = Runnable { unlockBtn.visibility = View.GONE }
    private val saveRunnable = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }
    /** §S2 unified 100 ms loop: progress + time labels + subtitle
     *  cue update in one fan-out, polling the engine clock on engine
     *  paths (currentPos() already prefers EngineBridge.position). */
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            updateSubtitle()
            handler.postDelayed(this, UI_TICK_MS)
        }
    }
    /** §S2 volume ramp (what=3 equivalent): steps the MediaPlayer
     *  volume toward [volumeRampTarget] every 50 ms. Engine paths
     *  keep their own gain; this drives the HW path's fades. */
    private var volumeRampTarget = 1f
    private var volumeRampCurrent = 1f
    private val volumeRampRunnable = object : Runnable {
        override fun run() {
            val step = 0.2f
            volumeRampCurrent = if (volumeRampTarget > volumeRampCurrent) {
                min(volumeRampTarget, volumeRampCurrent + step)
            } else {
                max(volumeRampTarget, volumeRampCurrent - step)
            }
            if (!engineActive) runCatching {
                mp?.setVolume(volumeRampCurrent, volumeRampCurrent)
            }
            if (volumeRampCurrent != volumeRampTarget && isPlaying()) {
                handler.postDelayed(this, VOLUME_RAMP_MS)
            }
        }
    }
    private fun rampVolumeTo(target: Float) {
        volumeRampTarget = target.coerceIn(0f, 1f)
        handler.removeCallbacks(volumeRampRunnable)
        handler.post(volumeRampRunnable)
    }
    private val syncRunnable = object : Runnable {
        override fun run() {
            syncEngineClock()
            handler.postDelayed(this, SYNC_TICK_MS)
        }
    }

    // =================================================================
    // Lifecycle
    // =================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        avSyncEnabled = PSet.avSync
        // Playback theme (§2.7.21): chrome tokens stay MX-dark for
        // every dark theme (they share them per spec §1); the theme's
        // accent is applied live. The "white" theme's light player
        // chrome is a follow-up — flagged in the delivery report.
        ACCENT = Settings.themeColors(PSet.playbackTheme).accent
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        path = intent.getStringExtra("path") ?: ""
        if (intent.getBooleanExtra("resume_exact", false)) exactResumePath = path
        title = intent.getStringExtra("title") ?: File(path).name
        @Suppress("DEPRECATION")
        playlist = intent.getStringArrayListExtra("playlist") ?: arrayListOf(path)
        index = intent.getIntExtra("index", 0).coerceIn(0, maxOf(0, playlist.size - 1))
        if (playlist.isEmpty()) playlist = arrayListOf(path)
        if (path.isEmpty()) path = playlist[index]

        // Decoder resolution: intent extra ("Play using…") > per-file
        // remembered (Remember selections) > global default > HW.
        val intentDecoder = intent.getIntExtra("decoder", -1)
        decoderMode = resolveDecoder(intentDecoder)

        currentSpeed = PSet.defaultSpeedPct / 100f

        PlayerController.transport = transportImpl

        buildUi()
        setContentView(root)
        applyFullscreen()
        applyOrientation()
        applyStoredBrightness()
        gestureDetector = GestureDetector(this, GestureListener())
        scaleDetector = ScaleGestureDetector(this, ScaleListener())
        registerNoisyReceiver()
        showControls()
        if (path.isEmpty()) showError("No video was provided to the player.")
    }

    override fun onResume() {
        super.onResume()
        if (backgrounded) {
            // Returned from background play: playback never stopped;
            // drop the notification and resume normal chrome.
            backgrounded = false
            PlaybackService.stop(this)
            cancelNotification()
        }
        // F2 fix carried from v0.1: tickers restart whenever the player
        // is prepared and the activity is resumed (idempotent).
        if (prepared && (mp != null || swMode)) {
            startTickers()
            if (wasPlayingBeforePause && !backgrounded) {
                startPlayback()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        wasPlayingBeforePause = isPlaying()
        if (!inPip && !backgrounded) {
            pausePlayback()
            stopTickers()
        }
        savePosition()
    }

    override fun onStop() {
        super.onStop()
        if (!inPip && !backgrounded) {
            // Activity fully gone (not PiP, not background play): release.
            releaseAll()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (PlayerController.transport === transportImpl) {
            PlayerController.transport = null
        }
        PlaybackService.stop(this)
        releaseAll()
        cancelNotification()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!prepared || locked) return
        when (PSet.bgMode) {
            "pip" -> enterPip()
            "background" -> if (PSet.bgAudio) startBackgroundPlay()
            else -> Unit // "stop": onPause handles it
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration?
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            hideControls(instant = true)
            menuPanel.visibility = View.GONE
            drawerPanel.visibility = View.GONE
            sheetVisible = false
            sheetTag = ""
            sheetPanel.visibility = View.GONE
            sheetScrim.visibility = View.GONE
            hideFloatBar()
            if (::subPanelBar.isInitialized) subPanelBar.visibility = View.GONE
        } else {
            showControls()
        }
    }

    // =================================================================
    // Foreground media keys (§4.2 #60): wired headsets deliver media
    // keys to the focused activity; Bluetooth/system keys route to
    // the MediaSession in PlaybackService while backgrounded. Both
    // paths share MediaButtonLogic + the MX media-button prefs.
    // =================================================================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PSet.mediaButtons && prepared && !locked &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            MediaButtonLogic.isMediaKey(event.keyCode)
        ) {
            handleMediaKey(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleMediaKey(keyCode: Int) {
        if (keyCode == MediaButtonLogic.KEY_HEADSETHOOK ||
            keyCode == MediaButtonLogic.KEY_PLAY_PAUSE
        ) {
            // Ambiguous until the press burst ends (double → Next,
            // triple → Prev when multi-press is honoured).
            if (!Store.boolSetting(Settings.HEADSET_MULTI_PRESS)) {
                dispatchMediaAction(keyCode, 1)
                return
            }
            val count = hookTracker.onPress(System.currentTimeMillis())
            pendingHookAction?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                dispatchMediaAction(keyCode, count)
                hookTracker.reset()
            }
            pendingHookAction = runnable
            handler.postDelayed(runnable, 500L)
        } else {
            dispatchMediaAction(keyCode, 1)
        }
    }

    private fun dispatchMediaAction(keyCode: Int, hookCount: Int) {
        val intervalMs = PSet.moveIntervalSec * 1000L
        when (MediaButtonLogic.keyAction(
            keyCode, hookCount,
            buttonsEnabled = PSet.mediaButtons,
            multiPress = Store.boolSetting(Settings.HEADSET_MULTI_PRESS),
            prevNextToFfRew = Store.boolSetting(Settings.PREVNEXT_TO_REWFF),
            toggleOnPlayButton = Store.boolSetting(Settings.TOGGLE_ON_MEDIA_PLAY_BUTTON)
        )) {
            MediaButtonLogic.Action.TOGGLE -> togglePlayPause()
            MediaButtonLogic.Action.PLAY -> startPlayback()
            MediaButtonLogic.Action.PAUSE -> pausePlayback()
            MediaButtonLogic.Action.NEXT -> playNext()
            MediaButtonLogic.Action.PREV -> playPrev()
            MediaButtonLogic.Action.FF -> skipBy(intervalMs)
            MediaButtonLogic.Action.REW -> skipBy(-intervalMs)
            MediaButtonLogic.Action.STOP -> exitPlayer()
            MediaButtonLogic.Action.NONE -> Unit
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (locked) {
            pokeUnlock()
            return
        }
        if (sheetVisible) {
            closeSheet()
            return
        }
        if (floatBar != null) {
            hideFloatBar()
            return
        }
        if (::subPanelBar.isInitialized && subPanelBar.visibility == View.VISIBLE) {
            subPanelBar.visibility = View.GONE
            return
        }
        if (drawerPanel.visibility == View.VISIBLE) {
            drawerPanel.visibility = View.GONE
            return
        }
        if (menuPanel.visibility == View.VISIBLE) {
            closeMenu()
            return
        }
        if (PSet.doubleTapBackExit && !backPressedOnce) {
            backPressedOnce = true
            toast("Press back again to exit")
            handler.postDelayed({ backPressedOnce = false }, 2000)
            return
        }
        exitPlayer()
    }

    // =================================================================
    // UI construction (all in code — framework-only project)
    // =================================================================
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(IMMERSIVE_BG) }

        // Video layer (§S1 render-path swap): a framework SurfaceView
        // created in code, exactly MX's pattern (ActivityScreen's
        // code-created SurfaceView child of the top layout; no
        // TextureView anywhere in MX's player). HW path: MediaPlayer
        // targets the holder's Surface. Engine paths:
        // SwVideoSession.attachSurface(holder.surface) — the Java
        // Surface goes straight to native, which owns the window.
        // Aspect/zoom/pan are SURFACE SIZING (the SurfaceView's layout
        // rect, see applyVideoTransform), not view matrices.
        // holder.setFixedSize is deliberately NOT used: on the HW
        // path the decoder owns the buffer size and on the SW path
        // the native side sets its own window geometry in
        // attachSurface — the layout rect is the single sizing
        // authority, so the two never fight.
        videoView = SurfaceView(this)
        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                surfaceReady = true
                if (mp == null) createPlayer() else runCatching { mp?.setSurface(surface) }
                // SW mode: the engine renders into this same Surface
                // natively (attachSurface also repaints the current
                // frame, so a recreated surface restores the picture).
                if (swMode) surface?.let { swVideo?.attachSurface(it) }
                if (mp != null && !prepared && !errorShown) loadCurrent()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                applyVideoTransform()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                savePosition()
                if (swMode) swVideo?.detachSurface()
                runCatching { mp?.setSurface(null) }
                surface = null
            }
        })
        root.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        // Night-mode overlay (warm dim) — sits above video, below chrome.
        nightOverlay = View(this).apply {
            setBackgroundColor(Color.argb(70, 60, 30, 0))
            visibility = View.GONE
            isClickable = false
        }
        root.addView(nightOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Subtitle layer (§2.2 SubView, anchored bottom).
        subtitleView = TextView(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(3), dp(12), dp(3))
        }
        root.addView(
            subtitleView,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        applySubtitleStyle()

        buildTopBar()
        buildBottomBar()

        // Aspect OSD (frame spec §2): one huge bold bare word, dead
        // centre, no pill — FIT TO SCREEN / STRETCH / CROP / 100%.
        aspectOsd = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = false
        }
        root.addView(aspectOsd, FrameLayout.LayoutParams(MATCH, MATCH))

        // OSD: volume (left) / brightness (right) vertical bars.
        // v0.5.4: MX's decoded layouts put SoundBar on the LEFT edge
        // and BrightnessBar on the RIGHT edge (soundbar.xml /
        // brightbar.xml) — v0.5 had them swapped. Both are 42dp wide
        // with a 16dp screen margin, vertically centred.
        soundBar = VerticalBar(this, "Volume")
        root.addView(
            soundBar,
            FrameLayout.LayoutParams(dp(42), dp(160), Gravity.LEFT or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = dp(16) }
        )
        brightBar = VerticalBar(this, "Brightness")
        root.addView(
            brightBar,
            FrameLayout.LayoutParams(dp(42), dp(160), Gravity.RIGHT or Gravity.CENTER_VERTICAL)
                .apply { rightMargin = dp(16) }
        )

        // Centre OSD indicator (gesture feedback, speed, boost…).
        indicatorView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundedBg(Color.argb(170, 10, 14, 20))
            visibility = View.GONE
        }
        root.addView(indicatorView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // Seek preview (position text + optional frame image, §2.7.13).
        seekPreview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        seekPreviewImage = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        seekPreview.addView(seekPreviewImage, LinearLayout.LayoutParams(dp(176), dp(99)))
        seekPreviewText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundedBg(Color.argb(170, 10, 14, 20))
        }
        seekPreview.addView(seekPreviewText, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(
            seekPreview,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(72) }
        )

        // Double-tap seek indicators (§2.5 TapSeekLayout).
        tapSeekLeft = tapSeekIndicator("◂◂ 10s")
        root.addView(
            tapSeekLeft,
            FrameLayout.LayoutParams(dp(120), MATCH, Gravity.LEFT)
        )
        tapSeekRight = tapSeekIndicator("10s ▸▸")
        root.addView(
            tapSeekRight,
            FrameLayout.LayoutParams(dp(120), MATCH, Gravity.RIGHT)
        )

        // Speed-FF indicator (long-press 2×).
        ffIndicator = TextView(this).apply {
            text = "2.0× »"
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundedBg(Color.argb(170, 10, 14, 20))
            visibility = View.GONE
        }
        root.addView(
            ffIndicator,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(72) }
        )

        // Buffering (§2.2: progress circle + "Buffering...").
        bufferingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        val spinner = android.widget.ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
        }
        bufferingView.addView(spinner, LinearLayout.LayoutParams(dp(44), dp(44)))
        bufferingView.addView(TextView(this).apply {
            text = "Buffering..."
            setTextColor(TITLE_C)
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(bufferingView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        buildMenuPanel()
        buildDrawer()
        buildSheets()
        buildSubPanelBar()

        // Error layer (v0.1's honest error view, kept).
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(IMMERSIVE_BG)
            visibility = View.GONE
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        errorText = TextView(this).apply {
            setTextColor(TITLE_C)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        errorView.addView(errorText)
        errorView.addView(TextView(this).apply {
            text = "Back to library"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = roundedBg(ACCENT)
            setOnClickListener { exitPlayer() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(18) })
        root.addView(errorView, FrameLayout.LayoutParams(MATCH, MATCH))

        unlockBtn = android.widget.ImageButton(this).apply {
            setImageDrawable(MxIcon(MxIcon.UNLOCK))
            background = roundedBg(Color.argb(170, 10, 14, 20))
            visibility = View.GONE
            // Unlock gesture depends on the lock mode (§2.7.29):
            // Mode #1 tap, Mode #2 long-press, Mode #3 double-tap —
            // see LockGesturePolicy. A wrong gesture re-shows the
            // button with the hint instead of doing nothing.
            setOnClickListener {
                val now = System.currentTimeMillis()
                val gesture = if (now - lastUnlockTapMs <= 350L) {
                    LockGesturePolicy.Gesture.DOUBLE_TAP
                } else {
                    LockGesturePolicy.Gesture.TAP
                }
                lastUnlockTapMs = now
                if (LockGesturePolicy.unlocks(PSet.lockMode, gesture)) {
                    unlockControls()
                } else {
                    pokeUnlock()
                    showIndicator(LockGesturePolicy.hint(PSet.lockMode), 1500)
                }
            }
            setOnLongClickListener {
                if (LockGesturePolicy.unlocks(
                        PSet.lockMode, LockGesturePolicy.Gesture.LONG_PRESS
                    )
                ) {
                    unlockControls()
                    true
                } else {
                    false
                }
            }
        }
        root.addView(unlockBtn, FrameLayout.LayoutParams(dp(50), dp(46), Gravity.CENTER))

        root.setOnTouchListener { _, ev -> onRootTouch(ev) }

        // Chrome anchoring (frame spec §7): the video is full-bleed
        // under the cutout, but chrome CONTENT pads for the system
        // insets so its visual positions match MX's inset window.
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyChromeInsets(insets)
            insets
        }
    }

    /** Pad the floating chrome (and the right-hand panels' top)
     *  for status bar / cutout / nav insets. Panels stay flush to
     *  the physical right edge, per the frame spec. */
    private fun applyChromeInsets(insets: android.view.WindowInsets) {
        var left = insets.systemWindowInsetLeft
        var top = insets.systemWindowInsetTop
        var right = insets.systemWindowInsetRight
        var bottom = insets.systemWindowInsetBottom
        if (Build.VERSION.SDK_INT >= 28) {
            insets.displayCutout?.let { cut ->
                left = max(left, cut.safeInsetLeft)
                top = max(top, cut.safeInsetTop)
                right = max(right, cut.safeInsetRight)
                bottom = max(bottom, cut.safeInsetBottom)
            }
        }
        chromePadTop = top
        if (::topChrome.isInitialized) {
            topChrome.setPadding(left, top, right, 0)
        }
        if (::bottomChrome.isInitialized) {
            bottomChrome.setPadding(left, 0, right, bottom)
        }
        if (::sheetBox.isInitialized) {
            sheetBox.setPadding(dp(24), top + dp(16), dp(16), dp(16))
        }
    }

    /** Top chrome (frame spec §1.1/§1.2): NO solid band — the bar
     *  and the quick-actions strip float on the video over a subtle
     *  top gradient scrim. Exactly MX's six top-bar items: back ·
     *  title · audio-track · subtitle · decoder as PLAIN TEXT · ⋮. */
    private fun buildTopBar() {
        topChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(128, 0, 0, 0), Color.TRANSPARENT)
            )
            visibility = View.GONE
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = chromeButton(MxIcon.BACK) { exitPlayer() }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            text = title
            setPadding(dp(2), 0, dp(8), 0)
        }
        decoderBtn = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            // MX press state: a circular ripple on the plain text.
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            )
            setOnClickListener { showDecoderDialog() }
        }
        updateDecoderBadge()
        val audioBtn = chromeButton(MxIcon.AUDIO_TRACK) { showAudioTrackChooser() }
        val subBtn = chromeButton(MxIcon.SUBTITLE) { showSubtitleChooser() }
        val moreBtn = chromeButton(MxIcon.MORE) { openMenu() }
        topBar.addView(backBtn)
        topBar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(audioBtn)
        topBar.addView(subBtn)
        topBar.addView(decoderBtn)
        topBar.addView(moreBtn)
        topChrome.addView(topBar, LinearLayout.LayoutParams(MATCH, dp(56)))

        buildStrip()
        topChrome.addView(stripBox)
        root.addView(topChrome, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        rebuildStrip()
    }

    /** A bare top-bar / transport icon slot: 48dp, glyph ≈ 24dp,
     *  no background (MX chrome floats). */
    private fun chromeButton(kind: Int, onClick: () -> Unit): android.widget.ImageButton =
        android.widget.ImageButton(this).apply {
            setImageDrawable(MxIcon(kind))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onClick() }
        }

    // =================================================================
    // Quick-actions strip (frame spec §1.3): floating black circles
    // under the top bar. Collapsed = Sir's 5 pinned + a small ">"
    // circle; expanded = the full customised order as labelled,
    // horizontally scrollable circles. The item set/order is the
    // existing customisable rail list (PSet.railShortcuts +
    // Settings.SHORTCUTS + shortcutFor) — a new face on the same
    // store, so Customise Items keeps working.
    // =================================================================
    private fun buildStrip() {
        stripBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (railVisibleFlag) View.VISIBLE else View.GONE
        }
        stripCollapsedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        // Decoded screen.xml: @id/screen_shortcut_list height = dp50.
        stripBox.addView(stripCollapsedRow, LinearLayout.LayoutParams(MATCH, dp(50)))
        stripExpandedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(6))
        }
        stripExpandedScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(stripExpandedRow)
        }
        // v0.5.1: the expanded strip wraps its content height — a
        // cell is the 40dp circle PLUS its label (~73dp total). At
        // the collapsed row's 50dp the labels were clipped to
        // slivers (Sir's screenshot).
        stripBox.addView(stripExpandedScroll, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** The collapsed strip's pinned subset (frame spec §1.3). */
    private val stripPinnedIds =
        setOf("equalizer", "playback_speed", "screenshot", "sticky", "screen_rotation")

    private fun railIds(): List<String> =
        PSet.railShortcuts.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Strip label per id — MX's wording (frame spec §1.3); the
     *  rail editor's "Playback Speed" reads "Speed" in the strip. */
    private fun stripLabel(id: String): String = when (id) {
        "playback_speed" -> "Speed"
        else -> Settings.SHORTCUTS.firstOrNull { it.first == id }?.second ?: id
    }

    /** One strip item (decoded screen_shortcut_* layouts): 56dp-wide
     *  cell, 40dp circle with 8dp margins, 24dp glyph centred (or the
     *  speed TEXT), optional red-dot badge, circular ripple press.
     *  Dimmed when its toggle is inactive — the v0.2 rail's convention.
     *  The strip host stays a framework HorizontalScrollView row at
     *  the decoded 50dp height (coordinator ruling: no AndroidX
     *  RecyclerView in this app). */
    private fun stripCircle(id: String): View {
        val (_, action) = shortcutFor(id) ?: (0 to {})
        val wrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            alpha = if (shortcutActive(id)) 1f else 0.55f
        }
        val disc = View(this).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(158, 0, 0, 0))
                },
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            )
            isClickable = true
            setOnClickListener { action() }
        }
        wrap.addView(disc, FrameLayout.LayoutParams(dp(40), dp(40)))
        if (id == "playback_speed") {
            // MX renders the speed item as TEXT ("1X"), not a glyph.
            wrap.addView(TextView(this).apply {
                text = "${trimSpeed(currentSpeed)}X"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = false
            }, FrameLayout.LayoutParams(dp(40), dp(40)))
        } else {
            val (icon, _) = shortcutFor(id) ?: (0 to {})
            wrap.addView(android.widget.ImageView(this).apply {
                setImageDrawable(MxIcon(icon))
                isClickable = false
            }, FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                // v0.5.1: the glyph box is 24dp, centred in the 40dp
                // disc by the 8dp margins. (v0.5 mistakenly gave the
                // box the full 40dp WITH the margins, pushing the
                // glyph centre 8dp down-right and clipping its
                // bottom — Sir's "icons not aligned" screenshots.)
                leftMargin = dp(8); topMargin = dp(8)
                rightMargin = dp(8); bottomMargin = dp(8)
            })
        }
        if (id == "audioEffect") {
            // Red-dot badge at the circle's top-right (f_008).
            wrap.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F43F5E"))
                }
                isClickable = false
            }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.RIGHT).apply {
                topMargin = dp(2); rightMargin = dp(2)
            })
        }
        return wrap
    }

    private fun rebuildStrip() {
        if (!::stripCollapsedRow.isInitialized) return
        stripCollapsedRow.removeAllViews()
        stripExpandedRow.removeAllViews()
        val ids = railIds()
        // Collapsed: pinned subset in list order + the ">" circle.
        val pinned = ids.filter { it in stripPinnedIds }
        for (id in pinned) {
            val cell = stripCircle(id)
            (cell.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
            (cell.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
            stripCollapsedRow.addView(cell)
        }
        val chevronDisc = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(158, 0, 0, 0))
            }
            isClickable = true
            setOnClickListener { setStripExpanded(true) }
        }
        val chevron = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                leftMargin = dp(13)
            }
        }
        chevron.addView(chevronDisc, FrameLayout.LayoutParams(dp(30), dp(30)))
        chevron.addView(android.widget.ImageView(this).apply {
            setImageDrawable(MxIcon(MxIcon.CHEVRON_RIGHT))
            isClickable = false
        }, FrameLayout.LayoutParams(dp(14), dp(14)).apply {
            // v0.5.1: 14dp glyph box centred in the 30dp disc by the
            // 8dp margins (same fix as stripCircle).
            leftMargin = dp(8); topMargin = dp(8)
            rightMargin = dp(8); bottomMargin = dp(8)
        })
        stripCollapsedRow.addView(chevron)
        // Expanded: every item, labelled, on the ≈60dp pitch.
        for (id in ids) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(56), WRAP)
            }
            cell.addView(stripCircle(id))
            cell.addView(TextView(this).apply {
                text = stripLabel(id)
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(1), dp(5), dp(1), 0)
            }, LinearLayout.LayoutParams(dp(56), WRAP))
            stripExpandedRow.addView(cell)
        }
        stripExpandedScroll.scrollTo(0, 0)
    }

    private fun setStripExpanded(expanded: Boolean) {
        stripExpanded = expanded
        stripCollapsedRow.visibility = if (expanded) View.GONE else View.VISIBLE
        stripExpandedScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        keepControls()
    }

    /** MX press state for transport buttons (frame spec §1.5):
     *  a solid MX-blue rectangle behind the glyph while pressed. */
    private fun blueRectPressBg(): android.graphics.drawable.StateListDrawable =
        android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(3).toFloat()
                }
            )
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }

    /** Bottom chrome (frame spec §1.4/§1.5): floating on the video
     *  over a bottom gradient scrim — seek row (zero-padded labels
     *  OUTSIDE the hairline track ends, solid blue dot thumb), then
     *  the transport row: lock alone at left · prev/play/next
     *  centred on a ≈98dp pitch · aspect + PiP at right. The ±10s
     *  and magnifier buttons are gone (double-tap seek and pinch
     *  zoom remain, as in MX). */
    /** Bottom chrome — DECODED truth (screen.xml PlaybackController +
     *  screen_subnavibar.xml), superseding the v0.3 recording geometry:
     *  progressPanel (padding L/R 8dip) = posText 12sp/padTop 10dp ·
     *  BookmarkSeekBar · durationText; then the subnavibar: top padder
     *  12dip, every button 50dp wide x 46dip high
     *  (screen_subnavibar_height), bottom padder 15dip, order
     *  lock | backward(+text) | prev | playpause CENTRED | next |
     *  forward(+text) | zb_left (zoom) | zb_right (PiP). The +/-seek
     *  buttons are MX's ZoomButtons with the interval as a 10sp
     *  overlay text; long-press play/pause and zoom keep their v0.3
     *  behaviours via the existing handlers where they exist. */
    private fun buildBottomBar() {
        bottomChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(140, 0, 0, 0))
            )
            visibility = View.GONE
        }

        // Progress panel (decoded): paddingLeft/Right 8dip.
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        curTimeView = timeLabel()
        durTimeView = timeLabel()
        durTimeView.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        durTimeView.setOnClickListener {
            PSet.showRemaining = !PSet.showRemaining
            updateProgress()
        }
        seekView = MxSeekBar(this).apply {
            onSeekStart = {
                userSeeking = true
                keepControls()
            }
            onSeekPreview = { ms -> curTimeView.text = Fmt.msPadded(ms) }
            onSeekEnd = { ms ->
                userSeeking = false
                seekTo(ms)
                keepControls()
            }
        }
        seekRow.addView(curTimeView)
        seekRow.addView(
            seekView,
            LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                leftMargin = dp(10); rightMargin = dp(10)
            }
        )
        seekRow.addView(durTimeView)
        bottomChrome.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Subnavibar (decoded): 12dip top padder, 50x46 buttons, 15dip bottom padder.
        bottomChrome.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(12)))
        val nav = FrameLayout(this)
        fun subnavButton(kind: Int, onClick: () -> Unit): android.widget.ImageButton =
            android.widget.ImageButton(this).apply {
                setImageDrawable(MxIcon(kind))
                background = blueRectPressBg()
                setPadding(dp(13), dp(11), dp(13), dp(11))
                setOnClickListener { onClick() }
            }
        fun intervalLabel(): TextView = TextView(this).apply {
            // backward_text / forward_text: 10sp white, centred on the button.
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = false
        }
        // Left cluster: lock only. v0.5.2: the backward button
        // moved into the centre cluster so the seek pair sits
        // symmetrically around prev/next — Sir measured its old
        // far-left position as misaligned against the forward
        // button beside next.
        val leftCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lockBtn = subnavButton(MxIcon.LOCK) { lockControls() }
        leftCluster.addView(lockBtn, LinearLayout.LayoutParams(dp(50), dp(46)).apply { leftMargin = dp(8) })
        val backCell = FrameLayout(this)
        val backBtn = subnavButton(MxIcon.BACKWARD) { skipBy(-PSet.moveIntervalSec * 1000L) }
        backCell.addView(backBtn, FrameLayout.LayoutParams(dp(50), dp(46)))
        backTextView = intervalLabel()
        backTextView.text = "${PSet.moveIntervalSec}"
        backCell.addView(backTextView, FrameLayout.LayoutParams(dp(50), dp(46)))
        nav.addView(leftCluster, FrameLayout.LayoutParams(WRAP, dp(46), Gravity.LEFT or Gravity.CENTER_VERTICAL))
        // Centre cluster: backward | prev | playpause (centre) |
        // next | forward — the seek pair mirrors around the middle.
        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        centre.addView(backCell, LinearLayout.LayoutParams(dp(50), dp(46)))
        centre.addView(subnavButton(MxIcon.PREV) { playPrev() }, LinearLayout.LayoutParams(dp(50), dp(46)))
        playBtn = android.widget.ImageButton(this).apply {
            setImageDrawable(MxIcon(MxIcon.PAUSE))
            background = blueRectPressBg()
            // v0.5: MX renders play/pause at a 32dp glyph box inside
            // the 50x46 button (ink 22x26.7dp) — padding (9,7) leaves
            // exactly 32x32 for the drawable.
            setPadding(dp(9), dp(7), dp(9), dp(7))
            setOnClickListener { togglePlayPause() }
        }
        // v0.5: MX's ~99dp centre pitch (prev/play/next centres) in
        // landscape, spread by the play button's margins; the gap is
        // recomputed on every configuration change (see
        // updateTransportGap) because the player handles rotation
        // itself and is usually entered from the portrait library.
        centre.addView(playBtn, LinearLayout.LayoutParams(dp(50), dp(46)).apply {
            leftMargin = dp(transportGapDp()); rightMargin = dp(transportGapDp())
        })
        centre.addView(subnavButton(MxIcon.NEXT) { playNext() }, LinearLayout.LayoutParams(dp(50), dp(46)))
        val fwdCell = FrameLayout(this)
        val fwdBtn = subnavButton(MxIcon.FORWARD) { skipBy(PSet.moveIntervalSec * 1000L) }
        fwdCell.addView(fwdBtn, FrameLayout.LayoutParams(dp(50), dp(46)))
        fwdTextView = intervalLabel()
        fwdTextView.text = "${PSet.moveIntervalSec}"
        fwdCell.addView(fwdTextView, FrameLayout.LayoutParams(dp(50), dp(46)))
        centre.addView(fwdCell, LinearLayout.LayoutParams(dp(50), dp(46)))
        nav.addView(centre, FrameLayout.LayoutParams(WRAP, dp(46), Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL))
        // Right cluster: zb_left (zoom/aspect) | zb_right (PiP, marginRight 8dp).
        val rightCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        aspectBtn = android.widget.ImageButton(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            setOnClickListener { cycleAspect() }
        }
        rightCluster.addView(aspectBtn, LinearLayout.LayoutParams(dp(50), dp(46)))
        val pipBtn = subnavButton(MxIcon.PIP_ENTER) { enterPip() }
        pipBtn.background = null
        pipBtn.setBackgroundColor(Color.TRANSPARENT)
        rightCluster.addView(pipBtn, LinearLayout.LayoutParams(dp(50), dp(46)).apply { rightMargin = dp(8) })
        nav.addView(rightCluster, FrameLayout.LayoutParams(WRAP, dp(46), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        bottomChrome.addView(nav, LinearLayout.LayoutParams(MATCH, dp(46)))
        bottomChrome.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(15)))

        root.addView(bottomChrome, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        refreshAspectButton()
    }

    /** v0.5: centre-pitch gap for the transport row — half the
     *  width left after the side clusters (216dp) and the five
     *  centre buttons (250dp), clamped to 0..49dp (49 = MX's ~99dp
     *  pitch; 0 = the decoded layout's contiguous chain, used on
     *  narrow/portrait widths so the clusters never overlap). */
    private fun transportGapDp(): Int =
        (((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 416) / 2) // v0.5.2: 416 = 166dp side clusters + 250dp centre (backward button moved into the centre)
            .coerceIn(0, 49)

    private fun updateTransportGap() {
        if (!::playBtn.isInitialized) return
        val lp = playBtn.layoutParams as? LinearLayout.LayoutParams ?: return
        val g = dp(transportGapDp())
        if (lp.leftMargin != g || lp.rightMargin != g) {
            lp.leftMargin = g; lp.rightMargin = g
            playBtn.layoutParams = lp
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTransportGap()
    }

    /** The aspect button shows the NEXT mode's glyph (frame spec
     *  §2 — cross-checked against the recording's frames). */
    private fun refreshAspectButton() {
        if (!::aspectBtn.isInitialized) return
        val kind = when (AspectCycle.next(zoomMode)) {
            "fit" -> MxIcon.ASPECT_FIT
            "stretch" -> MxIcon.ASPECT_STRETCH
            "crop" -> MxIcon.ASPECT_CROP
            else -> MxIcon.ASPECT_100
        }
        aspectBtn.setImageDrawable(MxIcon(kind))
    }

    /** One tap = one aspect step (frame spec §2), with the giant
     *  bare-text centre OSD. Pinch "custom" is outside the cycle:
     *  the tap first resets scale/pan, then steps. */
    private fun cycleAspect() {
        val next = AspectCycle.next(zoomMode)
        zoomMode = next
        zoomScale = 1f; panX = 0f; panY = 0f
        applyVideoTransform()
        refreshAspectButton()
        showAspectOsd(AspectCycle.osdText(next))
        keepControls()
    }

    private val hideAspectOsdRunnable = Runnable {
        if (::aspectOsd.isInitialized) {
            aspectOsd.animate().alpha(0f).setDuration(300)
                .withEndAction { aspectOsd.visibility = View.GONE }
                .start()
        }
    }

    private fun showAspectOsd(text: String) {
        aspectOsd.animate().cancel()
        handler.removeCallbacks(hideAspectOsdRunnable)
        aspectOsd.text = text
        aspectOsd.alpha = 1f
        aspectOsd.visibility = View.VISIBLE
        handler.postDelayed(hideAspectOsdRunnable, 650)
    }

    private fun buildMenuPanel() {
        menuScrim = View(this).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { closeMenu() }
        }
        root.addView(menuScrim, FrameLayout.LayoutParams(MATCH, MATCH))

        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE)
            visibility = View.GONE
        }
        val header = TextView(this).apply {
            text = "Menu"
            setTextColor(TITLE_C)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        menuPanel.addView(header)
        val scroll = ScrollView(this)
        menuList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(menuList)
        menuPanel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(
            menuPanel,
            // v0.5 corrected: MX's player-menu container is 200x360dp,
            // bottom-right anchored (view_player_menu.xml) — the
            // audit caught this fix initially landing on the wrong
            // panel (the playlist drawer below).
            FrameLayout.LayoutParams(dp(200), dp(360), Gravity.RIGHT or Gravity.BOTTOM)
        )
    }

    private fun buildDrawer() {
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE)
            visibility = View.GONE
        }
        drawerPanel.addView(TextView(this).apply {
            text = "Playlist"
            setTextColor(TITLE_C)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(10))
        })
        val scroll = ScrollView(this)
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(drawerList)
        drawerPanel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(drawerPanel, FrameLayout.LayoutParams(dp(300), MATCH, Gravity.RIGHT))
    }

    // =================================================================
    // Right slide-in sheets (frame spec §3): Subtitle + Audio Track.
    // Panel ≈42% of screen width, NO solid background — content
    // floats over a full-screen dim scrim; slide ≈250–300ms;
    // dismiss by tapping the scrim or pressing back.
    // =================================================================
    private fun buildSheets() {
        sheetScrim = View(this).apply {
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { closeSheet() }
        }
        root.addView(sheetScrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheetBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Refined by applyChromeInsets once insets arrive.
            setPadding(dp(24), dp(16), dp(16), dp(16))
        }
        sheetPanel = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true // consume touches so gestures don't leak to the video
            addView(sheetBox, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(sheetPanel, FrameLayout.LayoutParams(dp(366), MATCH, Gravity.RIGHT))
    }

    private fun sheetWidthPx(): Int {
        val w = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        return (w * 0.42f).toInt().coerceAtLeast(dp(260))
    }

    /** Identity of the sheet currently open ("" when none) — an
     *  async builder (the engine audio probe) must not populate a
     *  different sheet that replaced its own. */
    private var sheetTag: String = ""

    /** Rebuild [sheetBox] via [build], then slide the sheet in. */
    private fun openSheet(tag: String = "", build: (LinearLayout) -> Unit) {
        hideFloatBar()
        sheetTag = tag
        sheetBox.removeAllViews()
        build(sheetBox)
        val lp = sheetPanel.layoutParams as FrameLayout.LayoutParams
        lp.width = sheetWidthPx()
        sheetPanel.layoutParams = lp
        sheetVisible = true
        sheetScrim.animate().cancel()
        sheetScrim.alpha = 0f
        sheetScrim.visibility = View.VISIBLE
        sheetScrim.animate().alpha(1f).setDuration(200).start()
        sheetPanel.animate().cancel()
        sheetPanel.translationX = lp.width.toFloat()
        sheetPanel.visibility = View.VISIBLE
        sheetPanel.animate().translationX(0f).setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        keepControls()
    }

    private fun closeSheet() {
        if (!sheetVisible) return
        sheetVisible = false
        sheetTag = ""
        val w = sheetPanel.width.takeIf { it > 0 } ?: sheetWidthPx()
        sheetPanel.animate().translationX(w.toFloat()).setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!sheetVisible) sheetPanel.visibility = View.GONE
            }
            .start()
        sheetScrim.animate().alpha(0f).setDuration(200)
            .withEndAction {
                if (!sheetVisible) sheetScrim.visibility = View.GONE
            }
            .start()
    }

    // ---- sheet row builders (frame spec §3: ≈45dp pitch, control
    // ≈16dp in from the panel's left edge — the box's padding —
    // labels after it) ----
    private fun sheetHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 25f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(12))
    }

    private fun sheetRadioRow(
        label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (enabled) 1f else 0.4f
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
        }
        row.addView(MxRadioView(this, checked), LinearLayout.LayoutParams(dp(24), dp(24)))
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.layoutParams = LinearLayout.LayoutParams(MATCH, dp(45))
        return row
    }

    private fun sheetCheckRow(
        label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (enabled) 1f else 0.4f
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
        }
        row.addView(MxCheckView(this, checked), LinearLayout.LayoutParams(dp(24), dp(24)))
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.layoutParams = LinearLayout.LayoutParams(MATCH, dp(45))
        return row
    }

    private fun sheetTextRow(label: String, enabled: Boolean = true, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (enabled) 1f else 0.4f
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(45))
        }

    private fun sheetDivider(): View = View(this).apply {
        setBackgroundColor(Color.argb(51, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        }
    }

    // =================================================================
    // P3 subtitle panel — DECODED subtitle_panel.xml: a bottom bar
    // (height bottom_bar_min_height = 48dp): loadSubtitle button
    // (16dp side padding) at left, a horizontally scrolling chip row
    // of subtitle tracks in the middle, close button at right. This
    // replaces "Panel" opening the sheet: MX's panel IS this bar.
    // Chips are single-select like the sheet (one active renderer
    // track); the sheet itself remains for the top-bar button.
    // =================================================================
    private fun buildSubPanelBar() {
        subPanelBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PAGE)
            visibility = View.GONE
            isClickable = true
        }
        val loadBtn = android.widget.ImageButton(this).apply {
            setImageDrawable(MxIcon(MxIcon.FOLDER))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                subPanelBar.visibility = View.GONE
                pickSubtitleFile()
            }
        }
        subPanelBar.addView(loadBtn, LinearLayout.LayoutParams(WRAP, MATCH))
        subPanelChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), 0, 0, 0)
            addView(subPanelChips)
        }
        subPanelBar.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        val closeBtn = android.widget.ImageButton(this).apply {
            setImageDrawable(MxIcon(MxIcon.CLOSE))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { subPanelBar.visibility = View.GONE }
        }
        subPanelBar.addView(closeBtn, LinearLayout.LayoutParams(WRAP, MATCH))
        root.addView(subPanelBar, FrameLayout.LayoutParams(MATCH, dp(48), Gravity.BOTTOM))
    }

    private fun showSubtitlePanelBar() {
        closeSheet()
        hideFloatBar()
        subPanelChips.removeAllViews()
        for (src in subSources) {
            val chip = TextView(this).apply {
                text = src.label
                textSize = 14f
                setTextColor(if (src == subCurrent) Color.WHITE else SECONDARY)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = roundedBg(if (src == subCurrent) ACCENT else Color.argb(60, 255, 255, 255), 14f)
                isClickable = true
                setOnClickListener {
                    val player = mp
                    if (player != null) {
                        applySubtitleSource(if (src == subCurrent) subSources[0] else src, player)
                    } else if (swMode) {
                        // SW has no MediaPlayer track table; file/None
                        // sources still apply (embedded timed-text
                        // selection is a MediaPlayer-only operation).
                        val target = if (src == subCurrent) subSources[0] else src
                        subCurrent = target
                        cues = emptyList()
                        if (target.kind == 2) loadSubtitleFile(target.filePath)
                        subtitleView.visibility = View.GONE
                        updateSubtitle()
                    }
                    showSubtitlePanelBar()
                }
            }
            subPanelChips.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
        subPanelBar.visibility = View.VISIBLE
        keepControls()
    }

    // =================================================================
    // P3/P6 floating bars — DECODED subtitle_speed_bar.xml /
    // subtitle_sync_bar.xml / zoom_bar.xml share one shape: a floating
    // bar (bk_playback_floating_bar, 4dp margins) with a header row
    // (14sp title, paddingLeft 8dp + 42dp close button) and a stepper
    // row (4dp side padding, centred): minus button, 15sp #CCCCCC
    // editable value (minWidth 56sp), 13sp unit, plus button. The
    // decoded value field is an EditText; ours commits on stepper
    // taps and on close (typed values are honoured then).
    // =================================================================
    private fun hideFloatBar() {
        floatBar?.let { root.removeView(it) }
        floatBar = null
        floatBarKind = ""
        floatBarValue = null
    }

    private fun floatBarRefresh(kind: String, value: android.widget.EditText) {
        value.setText(when (kind) {
            "sub_speed" -> "${(PSet.subSpeed * 100).toInt()}"
            "sub_sync" -> "${effectiveSubSyncMs() / 1000.0}"
            else -> "${(zoomScale * 100).toInt()}"
        })
    }

    private fun showFloatBar(kind: String) {
        hideFloatBar()
        floatBarKind = kind
        val title = when (kind) {
            "sub_speed" -> "Subtitle speed"
            "sub_sync" -> "Subtitle sync"
            else -> "Zoom"
        }
        val unit = when (kind) {
            "sub_speed" -> "%"
            "sub_sync" -> "s"
            else -> "%"
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.argb(230, 24, 30, 40), 6f)
            isClickable = true
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(android.widget.ImageButton(this).apply {
            setImageDrawable(MxIcon(MxIcon.CLOSE))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { commitFloatBarEdit(); hideFloatBar() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        bar.addView(header)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        val value = android.widget.EditText(this).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(56)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            background = null
        }
        floatBarValue = value
        fun stepper(label: String, delta: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = roundedBg(Color.argb(50, 255, 255, 255), 21f)
            isClickable = true
            setOnClickListener { commitFloatBarEdit(); delta(); floatBarRefresh(kind, value) }
        }
        val dec: () -> Unit = {
            when (kind) {
                "sub_speed" -> { PSet.subSpeed = (PSet.subSpeed - 0.05f).coerceIn(0.25f, 4f); updateSubtitle() }
                "sub_sync" -> { subSessionOffsetMs = (subSessionOffsetMs - 500).coerceIn(-60_000L, 60_000L); updateSubtitle() }
                else -> zoomStep(down = true)
            }
        }
        val inc: () -> Unit = {
            when (kind) {
                "sub_speed" -> { PSet.subSpeed = (PSet.subSpeed + 0.05f).coerceIn(0.25f, 4f); updateSubtitle() }
                "sub_sync" -> { subSessionOffsetMs = (subSessionOffsetMs + 500).coerceIn(-60_000L, 60_000L); updateSubtitle() }
                else -> zoomStep(down = false)
            }
        }
        row.addView(stepper("−", dec), LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
        row.addView(TextView(this).apply {
            text = unit
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(13)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        row.addView(stepper("+", inc), LinearLayout.LayoutParams(dp(52), dp(52)))
        bar.addView(row)
        floatBarRefresh(kind, value)
        floatBar = bar
        root.addView(bar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(124)
        })
        keepControls()
    }

    /** Applies a typed value from the floating bar's EditText. */
    private fun commitFloatBarEdit() {
        val v = floatBarValue ?: return
        val num = v.text?.toString()?.toFloatOrNull() ?: return
        when (floatBarKind) {
            "sub_speed" -> {
                val pct = num.coerceIn(25f, 400f)
                if (abs(pct - PSet.subSpeed * 100f) > 0.5f) {
                    PSet.subSpeed = pct / 100f
                    updateSubtitle()
                }
            }
            "sub_sync" -> {
                val target = (num * 1000).toLong().coerceIn(-60_000L, 60_000L)
                val base = if (decoderMode == 1) PSet.subHwSyncMs else PSet.subDefaultSyncMs
                subSessionOffsetMs = target - base
                updateSubtitle()
            }
            "zoom" -> {
                val pct = num.coerceIn(50f, 400f)
                if (abs(pct - zoomScale * 100f) > 0.5f) {
                    zoomMode = "custom"
                    zoomScale = ZoomMath.clampScale(pct / 100f)
                    if (zoomScale == 1.0f) { panX = 0f; panY = 0f; zoomMode = "crop" }
                    applyVideoTransform()
                }
            }
        }
    }

    /** Play ▸ Bookmark: mark the current position on this
     *  file. Marks persist per file and draw on the seek track;
     *  Play ▸ Bookmarks lists them for jumping. */
    private fun addBookmark() {
        val pos = currentPos()
        if (pos <= 0L || durationMs <= 0) {
            toast("Nothing to bookmark yet.")
            return
        }
        val marks = PSet.fileBookmarks(path).toMutableList()
        if (pos !in marks) {
            marks.add(pos)
            PSet.setFileBookmarks(path, marks)
        }
        seekView.bookmarks = PSet.fileBookmarks(path)
        showIndicator("Bookmarked ${Fmt.msPadded(pos)}")
    }

    // ---- small UI helpers ----
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedBg(color: Int, radiusDp: Float = 8f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    private fun iconButton(kind: Int, onClick: () -> Unit): android.widget.ImageButton =
        android.widget.ImageButton(this).apply {
            setImageDrawable(MxIcon(kind))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }

    private fun timeLabel(): TextView = TextView(this).apply {
        // Decoded screen.xml progressPanel: 12sp, paddingTop 10dp.
        setTextColor(Color.WHITE)
        textSize = 12f
        text = "00:00"
        setPadding(dp(2), dp(10), dp(2), 0)
    }

    private fun tapSeekIndicator(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        visibility = View.GONE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showIndicator(text: String, ms: Long = 900) {
        indicatorView.text = text
        indicatorView.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, ms)
    }

    private val hideIndicatorRunnable = Runnable { indicatorView.visibility = View.GONE }

    /** Vertical OSD bar for brightness/volume (§1.3 colours, 2dp radius). */
    class VerticalBar(ctx: Context, private val label: String) : View(ctx) {
        var fraction: Float = 0f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x4D, 255, 255, 255); style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C8CF0"); style = Paint.Style.FILL
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x99, 0, 0, 0); style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xCC, 255, 255, 255); textSize = 24f; textAlign = Paint.Align.CENTER
        }

        init { visibility = View.GONE }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // v0.5.4: rebuilt to MX's decoded brightbar.xml /
            // soundbar.xml structure exactly: a pill container
            // (bg_supreme_top_container: radius 80dp, #99000000),
            // the level text "%d%%" 13sp semibold at the TOP
            // (marginTop 10dp), the 4x92dp bar under it (marginTop
            // 6dp; track #4DFFFFFF, fill dark_sky_blue #3C8CF0,
            // growing from the bottom, corner radius 2dp), and the
            // 20dp icon at the BOTTOM (marginTop 4dp, marginBottom
            // 12dp). v0.5 had icon-top / text-bottom and no pill.
            val d = resources.displayMetrics.density
            val sd = resources.displayMetrics.scaledDensity
            canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, pillPaint)
            textPaint.textSize = 13f * sd
            val textBase = 10f * d + 13f * sd * 0.85f
            canvas.drawText("${(fraction * 100).toInt()}%", w / 2f, textBase, textPaint)
            val barW = 4f * d
            val barH = 92f * d
            val barX = (w - barW) / 2f
            val barTop = textBase + 6f * d + 4f * d
            val track = RectF(barX, barTop, barX + barW, barTop + barH)
            canvas.drawRoundRect(track, 2f * d, 2f * d, trackPaint)
            val fillH = barH * fraction
            val fill = RectF(barX, barTop + barH - fillH, barX + barW, barTop + barH)
            canvas.drawRoundRect(fill, 2f * d, 2f * d, fillPaint)
            val iconKind = if (label == "Brightness") MxIcon.BRIGHTNESS else MxIcon.VOLUME
            val icon = MxIcon(iconKind)
            val isz = (20f * d).toInt()
            val ix = ((w - isz) / 2f).toInt()
            val iy = (barTop + barH + 4f * d).toInt()
            icon.setBounds(ix, iy, ix + isz, iy + isz)
            icon.draw(canvas)
        }
    }

    /** MX seek bar (frame spec §1.4): a 2dp hairline track — played
     *  portion MX blue over a 30%-white unplayed track — with a
     *  solid blue dot thumb (Ø17dp), plus bookmark dots ON the bar
     *  (decoded BookmarkSeekBar: #99ffffff, 4dp). Touch scrubs with start/preview/end
     *  callbacks; the row's time labels live outside the track ends. */
    class MxSeekBar(ctx: Context) : View(ctx) {
        var durationMs: Long = 0
        var positionMs: Long = 0
            set(v) { field = v; if (!dragging) invalidate() }
        var bufferedMs: Long = 0
            set(v) { field = v; invalidate() }
        var bookmarks: List<Long> = emptyList()
            set(v) { field = v; invalidate() }
        var onSeekStart: (() -> Unit)? = null
        var onSeekPreview: ((Long) -> Unit)? = null
        var onSeekEnd: ((Long) -> Unit)? = null

        private var dragging = false
        private var dragPosMs = 0L
        private val density = resources.displayMetrics.density
        private val blue = Color.parseColor("#3C8CF0")
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x4D, 255, 255, 255); style = Paint.Style.FILL
        }
        private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x73, 255, 255, 255); style = Paint.Style.FILL
        }
        private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue; style = Paint.Style.FILL
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue; style = Paint.Style.FILL
        }
        // Decoded BookmarkSeekBar: dot_color #99ffffff, dot_size 4dp —
        // bookmark DOTS centred ON the track, not ticks across it.
        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x99, 255, 255, 255); style = Paint.Style.FILL
        }

        private fun shownPos(): Long = if (dragging) dragPosMs else positionMs

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val cy = height / 2f
            val trackH = 2f * density
            canvas.drawRect(0f, cy - trackH / 2, w, cy + trackH / 2, trackPaint)
            if (durationMs > 0) {
                val bw = w * (bufferedMs.coerceIn(0L, durationMs).toFloat() / durationMs)
                canvas.drawRect(0f, cy - trackH / 2, bw, cy + trackH / 2, bufferedPaint)
                val pw = w * (shownPos().coerceIn(0L, durationMs).toFloat() / durationMs)
                canvas.drawRect(0f, cy - trackH / 2, pw, cy + trackH / 2, playedPaint)
                for (mark in bookmarks) {
                    if (mark <= 0 || mark >= durationMs) continue
                    val x = w * (mark.toFloat() / durationMs)
                    canvas.drawCircle(x, cy, 2f * density, markPaint)
                }
                canvas.drawCircle(pw, cy, 8.5f * density, thumbPaint)
            } else {
                canvas.drawCircle(0f, cy, 8.5f * density, thumbPaint)
            }
        }

        private fun posForX(x: Float): Long {
            if (durationMs <= 0 || width <= 0) return 0L
            val frac = (x / width).coerceIn(0f, 1f)
            return (frac * durationMs).toLong()
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragPosMs = posForX(ev.x)
                    onSeekStart?.invoke()
                    onSeekPreview?.invoke(dragPosMs)
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        dragPosMs = posForX(ev.x)
                        onSeekPreview?.invoke(dragPosMs)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragPosMs = posForX(ev.x)
                        dragging = false
                        positionMs = dragPosMs
                        onSeekEnd?.invoke(dragPosMs)
                        performClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        onSeekEnd?.invoke(positionMs)
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** MX radio (frame spec §§3–4): blue ring + centre dot when
     *  selected, slate-gray ring otherwise. Ø ≈ 21–23dp drawn
     *  centred in a 24dp box. */
    class MxRadioView(ctx: Context, checkedInit: Boolean) : View(ctx) {
        var checked: Boolean = checkedInit
            set(v) { field = v; invalidate() }
        private val density = resources.displayMetrics.density
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C8CF0"); style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = 10f * density
            ringPaint.strokeWidth = 2f * density
            ringPaint.color = if (checked) Color.parseColor("#3C8CF0")
                else Color.parseColor("#71808F")
            canvas.drawCircle(cx, cy, r - density, ringPaint)
            if (checked) canvas.drawCircle(cx, cy, 5f * density, dotPaint)
        }
    }

    /** MX checkbox (frame spec §3): slate-gray outline square;
     *  checked = MX-blue fill + white tick. ≈ 20dp in a 24dp box. */
    class MxCheckView(ctx: Context, checkedInit: Boolean) : View(ctx) {
        var checked: Boolean = checkedInit
            set(v) { field = v; invalidate() }
        private val density = resources.displayMetrics.density
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f
            color = Color.parseColor("#71808F")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C8CF0"); style = Paint.Style.FILL
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val half = 10f * density
            val rect = RectF(
                width / 2f - half, height / 2f - half,
                width / 2f + half, height / 2f + half
            )
            val radius = 2f * density
            if (checked) {
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                tickPaint.strokeWidth = 2.2f * density
                val p = Path()
                p.moveTo(rect.left + 4.5f * density, rect.top + 10.5f * density)
                p.lineTo(rect.left + 8.5f * density, rect.top + 14.5f * density)
                p.lineTo(rect.left + 16f * density, rect.top + 5.5f * density)
                canvas.drawPath(p, tickPaint)
            } else {
                boxPaint.strokeWidth = 2f * density
                canvas.drawRoundRect(rect, radius, radius, boxPaint)
            }
        }
    }

    // =================================================================
    // Decoder resolution + engine session
    // =================================================================
    /** MX values: 0 Default, 1 HW, 4 HW+, 2 SW. Resolution order:
     *  intent extra > per-file remembered > global default > HW. */
    private fun resolveDecoder(intentValue: Int): Int {
        var mode = when {
            intentValue in intArrayOf(1, 2, 4) -> intentValue
            PSet.rememberSelections && PSet.fileDecoder(path) in intArrayOf(1, 2, 4) ->
                PSet.fileDecoder(path)
            PSet.decoderDefault in intArrayOf(1, 2, 4) -> PSet.decoderDefault
            else -> 1
        }
        // Never fake a mode: engine-backed modes need the engine.
        if ((mode == 4 || mode == 2) && !EngineBridge.isAvailable()) mode = 1
        if (mode == 2 && !EngineBridge.supportsSwVideo()) {
            // Safety net only: supportsSwVideo() is true whenever
            // the engine libraries load (the SW video path is real),
            // so this remap can now fire only if the engine's video
            // half is ever removed — never fake SW in that case.
            mode = 4
        }
        return mode
    }

    private fun updateDecoderBadge() {
        if (::decoderBtn.isInitialized) {
            decoderBtn.text = PlayerLogic.decoderLabel(decoderMode)
        }
    }

    private fun setDecoderMode(mode: Int) {
        if (mode == decoderMode) return
        val pos = currentPos()
        val wasPlaying = isPlaying()
        decoderMode = mode
        if (PSet.rememberSelections) PSet.setFileDecoder(path, mode)
        updateDecoderBadge()
        stopEngineSession()
        prepared = false
        runCatching { mp?.reset() }
        loadCurrent(startPosOverride = pos, autoplayOverride = wasPlaying)
        showIndicator("${PlayerLogic.decoderLabel(mode)} decoder")
    }

    private fun startEngineSession(): Boolean {
        if (decoderMode == 1) return false
        // Per-file remembered engine stream (§2.7.11) reopens the
        // audio stream the user last picked in engine mode.
        val stream = if (PSet.rememberSelections) PSet.fileAudioStream(path) else -1
        val h = EngineBridge.open(path, stream)
        if (h == 0L) {
            toast("${PlayerLogic.decoderLabel(decoderMode)} decoder is not supported for this file.")
            return false
        }
        engineHandle = h
        EngineBridge.setGain(h, boostPct / 100f)
        EngineBridge.setSpeed(h, currentSpeed)
        val delay = PSet.audioDelayMs + if (isBluetoothAudio()) PSet.btDelayMs else 0L
        if (delay != 0L) EngineBridge.setAudioDelay(h, delay)
        return true
    }

    private fun stopEngineSession() {
        closeSwVideo()
        if (engineHandle != 0L) {
            EngineBridge.close(engineHandle)
            engineHandle = 0L
        }
    }

    private fun closeSwVideo() {
        swVideo?.close()
        swVideo = null
    }

    private fun isBluetoothAudio(): Boolean =
        audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn

    /** Engine is the master clock in HW+/SW modes (§ tasking): the
     *  hardware video is slaved to the engine's audio position. The
     *  slave target is EngineBridge.position() — the engine clock,
     *  which already carries any audio/BT delay natively
     *  (setDelayMs), so there is exactly one clock reading. The
     *  "AV sync" menu toggle is a real consumer (AvSyncPolicy):
     *  off = no drift correction at all, and a hold imposed while
     *  it was on is released. §S4 status: this ticker runs ONLY on
     *  the HW+ staging path; SW mode returns above before any of it
     *  (the engine renderer follows the clock natively) and the
     *  ticker is never posted in SW mode. ENGINE-API.md was absent
     *  at build time, so native sync is not certified in writing —
     *  nothing was deleted beyond the SW posting. */
    private fun syncEngineClock() {
        if (!engineActive || !prepared) return
        // SW mode has no MediaPlayer to slave: its video renderer
        // follows the engine clock natively, frame by frame.
        if (swMode) return
        val player = mp ?: return
        val target = EngineBridge.position(engineHandle)
        if (target < 0) return
        val videoPos = try { player.currentPosition.toLong() } catch (t: Throwable) { return }
        val drift = videoPos - target
        val decision = AvSyncPolicy.decide(
            enabled = avSyncEnabled,
            driftMs = drift,
            playWhenReady = playWhenReady,
            videoPlaying = try { player.isPlaying } catch (t: Throwable) { false },
            userSeeking = userSeeking
        )
        try {
            when (decision) {
                AvSyncPolicy.Decision.SEEK -> preciseSeek(player, target)
                AvSyncPolicy.Decision.HOLD -> player.pause()
                AvSyncPolicy.Decision.RESUME -> player.start()
                AvSyncPolicy.Decision.NONE -> Unit
            }
        } catch (t: Throwable) { /* player mid-transition */ }
    }

    // =================================================================
    // Player setup / loading
    // =================================================================
    private fun createPlayer() {
        val player = MediaPlayer()
        mp = player
        player.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        surface?.let { player.setSurface(it) }
        player.setOnPreparedListener { onPrepared(it) }
        player.setOnCompletionListener { onPlaybackComplete() }
        player.setOnErrorListener { _, what, extra -> onPlayerError(what, extra) }
        player.setOnVideoSizeChangedListener { _, w, h ->
            videoW = w; videoH = h
            applyVideoTransform()
        }
        player.setOnBufferingUpdateListener { _, percent ->
            seekView.bufferedMs = if (durationMs > 0) (percent * durationMs / 100).toLong() else 0L
        }
        player.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START ->
                    if (PSet.loadingAnimation) bufferingView.visibility = View.VISIBLE
                MediaPlayer.MEDIA_INFO_BUFFERING_END ->
                    bufferingView.visibility = View.GONE
            }
            false
        }
        player.setOnTimedTextListener { _, text ->
            // Embedded subtitle rendering (system timed-text path).
            if (subCurrent.kind == 1) {
                val t = text?.text
                if (t.isNullOrBlank()) subtitleView.visibility = View.GONE
                else {
                    subtitleView.text = styleCueText(t)
                    subtitleView.visibility = View.VISIBLE
                }
            }
        }
        if (surfaceReady && !prepared && !errorShown) loadCurrent()
    }

    private fun loadCurrent(startPosOverride: Long = -1L, autoplayOverride: Boolean? = null) {
        // v0.5.4: EVERY item load records the last-played path,
        // whatever route opened it (library, external "open with"
        // intent, playlist navigation, delete-advance) — the
        // library's resume FAB keys off Store.lastPlayedPath(). In
        // v0.5.3 only library launches recorded it, so a video opened
        // any other way left the path unset and Sir's resume button
        // never appeared on the Folders screen.
        if (path.isNotEmpty()) Store.recordPlayed(path)
        // SW mode never touches MediaPlayer: the engine decodes
        // audio + video itself (see loadSwCurrent).
        if (swMode) {
            loadSwCurrent(startPosOverride, autoplayOverride)
            return
        }
        val player = mp ?: return
        errorShown = false
        finishedMarked = Store.isFinished(path)
        titleView.text = title
        PSet.setFileLastPlayedAt(path, System.currentTimeMillis())
        try {
            player.reset()
            stopEngineSession()
            player.setDataSource(path)
            player.prepareAsync()
            pendingStartPos = startPosOverride
            pendingAutoplay = autoplayOverride
        } catch (t: Throwable) {
            onPlayerError(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    private var pendingStartPos = -1L
    private var pendingAutoplay: Boolean? = null

    // =================================================================
    // SW loading — the onPrepared equivalent for decoderMode == 2.
    // The engine audio session opens FIRST: it is the master clock
    // every other engine piece slaves to, and if it cannot open, SW
    // cannot run honestly (same contract as HW+'s engine failure).
    // The video session renders natively into the SurfaceView's
    // Surface; zoom/pan/aspect are surface sizing (applyVideoTransform)
    // and the screenshot path is retriever-based, so both work
    // exactly as in HW.
    // =================================================================
    private fun loadSwCurrent(startPosOverride: Long, autoplayOverride: Boolean?) {
        errorShown = false
        swCompletionFired = false
        finishedMarked = Store.isFinished(path)
        titleView.text = title
        PSet.setFileLastPlayedAt(path, System.currentTimeMillis())
        stopEngineSession()
        if (!startEngineSession()) {
            // startEngineSession already toasted the reason.
            fallbackFromSw()
            return
        }
        val sv = SwVideoSession()
        val threads = Store.intSetting(Settings.CORE_LIMIT)
        val allow = Store.stringSetSetting(Settings.OMX_VIDEO_CODECS)
        if (!sv.open(path, -1, threads, PSet.speedupTricks, allow)) {
            toast("SW decoder is not supported for this file.")
            fallbackFromSw()
            return
        }
        swVideo = sv
        surface?.let { sv.attachSurface(it) }
        sv.start { EngineBridge.position(engineHandle) }

        val dur = if (sv.durationMs() > 0) sv.durationMs()
                  else EngineBridge.duration(engineHandle)
        durationMs = dur.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        videoW = sv.videoWidth
        videoH = sv.videoHeight
        audioDisabled = false
        seekView.durationMs = durationMs.toLong()
        seekView.positionMs = 0L
        seekView.bookmarks = PSet.fileBookmarks(path)
        durTimeView.text = if (PSet.showRemaining) "-" + Fmt.msPadded(durationMs.toLong())
            else Fmt.msPadded(durationMs.toLong())
        refreshAspectButton()
        applyVideoTransform()
        prepared = true

        discoverSwSubtitles()

        val autoplay = autoplayOverride ?: true
        when {
            startPosOverride >= 0 -> {
                if (startPosOverride > 0) seekTo(startPosOverride)
                if (autoplay) startPlayback() else pausePlayback()
            }
            else -> swHandleResume(autoplay)
        }
        startTickers()
        updateProgress()
        keepScreenOnIfNeeded()
    }

    /** SW could not run on this file: drop to HW and load through
     *  the normal MediaPlayer path — the same honest fallback the
     *  onPrepared engine failure performs, one level earlier. */
    private fun fallbackFromSw() {
        stopEngineSession()
        prepared = false
        decoderMode = 1
        updateDecoderBadge()
        loadCurrent()
    }

    /** handleResume's logic for SW (no MediaPlayer to seek: the
     *  shared seekTo/startPlayback/pausePlayback route to the
     *  engine sessions). Keep the two in step on resume semantics. */
    private fun swHandleResume(autoplay: Boolean) {
        val saved = Store.resumePos(path)
        // v0.5.3: play-last launches resume exactly, no prompt.
        if (exactResumePath != null && exactResumePath == path) {
            exactResumePath = null
            if (saved > RESUME_MIN_MS && !Store.isFinished(path)) {
                seekTo(saved)
                if (autoplay) startPlayback()
                toast("Resumed from ${Fmt.ms(saved)}")
                return
            }
        }
        val firstFile = index == 0
        val resumeApplies = saved > RESUME_MIN_MS && !Store.isFinished(path) &&
            (!PSet.resumeFirstOnly || firstFile)
        if (!resumeApplies) {
            if (autoplay) startPlayback()
            return
        }
        when (PSet.resumeMode) {
            "resume" -> {
                seekTo(saved)
                if (autoplay) startPlayback()
                toast("Resumed from ${Fmt.ms(saved)}")
            }
            "startover" -> if (autoplay) startPlayback()
            else -> { // "ask" — MX default: Ask at Startup (§2.7.8)
                if (autoplay) startPlayback()
                AlertDialog.Builder(this)
                    .setTitle("Resume")
                    .setMessage("Resume from ${Fmt.ms(saved)}?")
                    .setPositiveButton("Resume") { _, _ -> seekTo(saved) }
                    .setNegativeButton("Start over", null)
                    .show()
            }
        }
    }

    /** Subtitle discovery for SW: there is no MediaPlayer, so
     *  embedded timed-text tracks cannot be listed — the engine
     *  does not decode subtitle streams (sidecar files work exactly
     *  as in the other modes; updateSubtitle renders kind-2 cues
     *  off currentPos(), which is the engine clock in SW). */
    private fun discoverSwSubtitles() {
        val sources = mutableListOf(SubSource("None", 0))
        val videoFile = File(path)
        val dirs = listOfNotNull(
            videoFile.parentFile,
            PSet.subFolder.takeIf { it.isNotEmpty() }?.let { File(it) }
        )
        val seen = HashSet<String>()
        for (dir in dirs) {
            val names = dir.list()?.toList() ?: continue
            for (name in FileOps.sidecarSubtitles(names, videoFile.name)) {
                val f = File(dir, name)
                if (seen.add(f.absolutePath)) {
                    sources.add(SubSource(name, 2, filePath = f.absolutePath))
                }
            }
        }
        subSources = sources
        var chosen: SubSource? = null
        if (PSet.rememberSelections) {
            val mem = PSet.fileSubtitle(path)
            chosen = when {
                mem == "none" -> sources[0]
                mem.startsWith("file:") ->
                    sources.firstOrNull {
                        it.kind == 2 && it.filePath == mem.removePrefix("file:")
                    }
                else -> null
            }
        }
        if (chosen == null) {
            chosen = sources.firstOrNull { it.kind == 2 } ?: sources[0]
        }
        subCurrent = chosen
        cues = emptyList()
        subFileBytes = null
        subFileName = null
        if (chosen.kind == 2) loadSubtitleFile(chosen.filePath)
        if (PSet.rememberSelections) {
            PSet.setFileSubtitle(
                path, if (chosen.kind == 2) "file:${chosen.filePath}" else "none"
            )
        }
        subtitleView.visibility = View.GONE
        applySubtitleStyle()
        updateSubtitle()
    }

    private fun onPrepared(player: MediaPlayer) {
        prepared = true
        durationMs = try { player.duration } catch (t: Throwable) { 0 }
        if (durationMs < 0) durationMs = 0
        videoW = player.videoWidth
        videoH = player.videoHeight
        audioDisabled = false
        seekView.durationMs = durationMs.toLong()
        seekView.positionMs = 0L
        seekView.bookmarks = PSet.fileBookmarks(path)
        durTimeView.text = if (PSet.showRemaining) "-" + Fmt.msPadded(durationMs.toLong())
            else Fmt.msPadded(durationMs.toLong())
        refreshAspectButton()
        applyVideoTransform()

        // Engine session for HW+/SW (audio from our FFmpeg, video HW).
        val engineOk = startEngineSession()
        if (decoderMode != 1 && !engineOk) {
            // Engine failed on this file: honest fallback to HW.
            decoderMode = 1
            updateDecoderBadge()
        }
        player.setVolume(if (engineActive) 0f else 1f, if (engineActive) 0f else 1f)

        // Per-file remembered audio track (§2.7.11).
        if (PSet.rememberSelections) {
            val savedAudio = PSet.fileAudioTrack(path)
            if (savedAudio >= 0) runCatching { player.selectTrack(savedAudio) }
            else applyPreferredAudioLanguage(player)
        } else {
            applyPreferredAudioLanguage(player)
        }

        discoverSubtitles(player)

        // Start position: explicit override > resume rules (§2.7.8).
        val autoplay = pendingAutoplay ?: true
        pendingAutoplay = null
        val override = pendingStartPos
        pendingStartPos = -1L
        when {
            override >= 0 -> {
                if (override > 0) preciseSeek(player, override)
                if (autoplay) startPlayback() else pausePlayback()
            }
            else -> handleResume(player, autoplay)
        }
        startTickers()
        updateProgress()
        keepScreenOnIfNeeded()
    }

    private fun handleResume(player: MediaPlayer, autoplay: Boolean) {
        val saved = Store.resumePos(path)
        // v0.5.3: play-last launches resume exactly, no prompt.
        if (exactResumePath != null && exactResumePath == path) {
            exactResumePath = null
            if (saved > RESUME_MIN_MS && !Store.isFinished(path)) {
                preciseSeek(player, saved)
                if (autoplay) startPlayback()
                toast("Resumed from ${Fmt.ms(saved)}")
                return
            }
        }
        val firstFile = index == 0
        val resumeApplies = saved > RESUME_MIN_MS && !Store.isFinished(path) &&
            (!PSet.resumeFirstOnly || firstFile)
        if (!resumeApplies) {
            if (autoplay) startPlayback()
            return
        }
        when (PSet.resumeMode) {
            "resume" -> {
                preciseSeek(player, saved)
                if (autoplay) startPlayback()
                toast("Resumed from ${Fmt.ms(saved)}")
            }
            "startover" -> if (autoplay) startPlayback()
            else -> { // "ask" — MX default: Ask at Startup (§2.7.8)
                if (autoplay) startPlayback()
                AlertDialog.Builder(this)
                    .setTitle("Resume")
                    .setMessage("Resume from ${Fmt.ms(saved)}?")
                    .setPositiveButton("Resume") { _, _ -> preciseSeek(player, saved) }
                    .setNegativeButton("Start over", null)
                    .show()
            }
        }
    }

    private fun applyPreferredAudioLanguage(player: MediaPlayer) {
        val lang = PSet.prefAudioLang
        if (lang.isEmpty() || engineActive) return // MX: not used with HW decoder
        try {
            val infos = player.trackInfo ?: return
            for (info in infos) {
                if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    val l = info.getLanguage()
                    if (l != null && l.startsWith(lang, ignoreCase = true)) {
                        player.selectTrack(infos.indexOf(info))
                        return
                    }
                }
            }
        } catch (t: Throwable) { /* best effort, as MX itself warns */ }
    }

    // =================================================================
    // Transport
    // =================================================================
    fun isPlaying(): Boolean {
        if (engineActive) return playWhenReady && prepared
        return try { mp?.isPlaying == true } catch (t: Throwable) { false }
    }

    private fun currentPos(): Long {
        if (engineActive) {
            val p = EngineBridge.position(engineHandle)
            if (p >= 0) return p
        }
        return try { mp?.currentPosition?.toLong() ?: 0L } catch (t: Throwable) { 0L }
    }

    private fun startPlayback() {
        if (swMode) {
            // No MediaPlayer in the SW path: the engine audio
            // session (master clock) and the SW video renderer run.
            playWhenReady = true
            requestAudioFocusIfNeeded()
            if (engineActive) EngineBridge.start(engineHandle)
            swVideo?.play()
            playBtn.setImageDrawable(MxIcon(MxIcon.PAUSE))
            keepScreenOnIfNeeded()
            keepControls()
            PlayerController.notifyStateChanged()
            return
        }
        val player = mp ?: return
        playWhenReady = true
        requestAudioFocusIfNeeded()
        try {
            if (engineActive) {
                EngineBridge.start(engineHandle)
                player.setVolume(0f, 0f)
            }
            if (!player.isPlaying) player.start()
            if (PSet.fadeOnStart && !engineActive) fadeVolumeIn(player)
        } catch (t: Throwable) { /* mid-transition */ }
        playBtn.setImageDrawable(MxIcon(MxIcon.PAUSE))
        keepScreenOnIfNeeded()
        // Restart the auto-hide timer on a (re)start — the paused
        // rule in hideRunnable must not leave chrome up forever
        // after a resume driven from outside the UI.
        keepControls()
        PlayerController.notifyStateChanged()
    }

    private fun pausePlayback() {
        playWhenReady = false
        try {
            if (engineActive) EngineBridge.pause(engineHandle)
            swVideo?.pause()
            if (mp?.isPlaying == true) mp?.pause()
        } catch (t: Throwable) { /* mid-transition */ }
        playBtn.setImageDrawable(MxIcon(MxIcon.PLAY))
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PlayerController.notifyStateChanged()
    }

    private fun togglePlayPause() {
        if (isPlaying()) pausePlayback() else startPlayback()
        keepControls()
    }

    private fun fadeVolumeIn(player: MediaPlayer) {
        // MX "Fade on start" (§2.8.7) on the §S2 50 ms ramp loop:
        // start from silence and ramp to full over ~250 ms.
        volumeRampCurrent = 0f
        runCatching { if (!engineActive) player.setVolume(0f, 0f) }
        rampVolumeTo(1f)
    }

    /** F1 fix carried from v0.1: precise seek on API 26+, plain below.
     *  "Fast seeking" (§2.7.18) trades precision for speed: keyframe
     *  seek instead of closest-frame seek. */
    private fun preciseSeek(player: MediaPlayer, ms: Long) {
        val target = ms.coerceIn(0L, max(0, durationMs).toLong())
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val flag = if (PSet.fastSeeking) MediaPlayer.SEEK_PREVIOUS_SYNC
                    else MediaPlayer.SEEK_CLOSEST
                player.seekTo(target, flag)
            } else {
                @Suppress("DEPRECATION")
                player.seekTo(target.toInt())
            }
        } catch (t: Throwable) { /* not prepared yet */ }
    }

    private fun seekTo(ms: Long) {
        if (swMode) {
            // Both engine sessions seek to the same content point:
            // the audio session re-bases the master clock there and
            // the video session lands on the frame covering it.
            if (engineActive) EngineBridge.seek(engineHandle, ms)
            swVideo?.seekTo(ms)
            if (ms < durationMs) swCompletionFired = false
            updateProgress()
            return
        }
        val player = mp ?: return
        val target = (ms + PSet.calibratePosMs).coerceIn(0L, max(0, durationMs).toLong())
        preciseSeek(player, target)
        if (engineActive) EngineBridge.seek(engineHandle, ms)
        if (PSet.fadeOnSeek && !engineActive && isPlaying()) fadeVolumeIn(player)
        updateProgress()
    }

    private fun skipBy(deltaMs: Long) {
        seekTo((currentPos() + deltaMs).coerceIn(0L, max(0, durationMs).toLong()))
        showIndicator((if (deltaMs >= 0) "+" else "−") + "${abs(deltaMs) / 1000}s")
    }

    // =================================================================
    // Playlist navigation + completion (§2.10)
    // =================================================================
    private fun playAt(newIndex: Int, autoplay: Boolean = true) {
        if (newIndex !in playlist.indices) return
        savePosition()
        index = newIndex
        path = playlist[index]
        title = File(path).name
        decoderMode = resolveDecoder(-1)
        updateDecoderBadge()
        ABRepeat.clear()
        cues = emptyList()
        subCurrent = SubSource("None", 0)
        prepared = false
        loadCurrent(autoplayOverride = autoplay)
        showControls()
        PlayerController.notifyStateChanged()
    }

    private fun playNext(autoplay: Boolean = true) {
        if (playlist.isEmpty()) return
        val next = when {
            shuffle && playlist.size > 1 -> {
                var n = index
                while (n == index) n = playlist.indices.random()
                n
            }
            index + 1 < playlist.size -> index + 1
            loopAll -> 0
            else -> -1
        }
        if (next >= 0) playAt(next, autoplay)
    }

    private fun playPrev() {
        if (playlist.isEmpty()) return
        // Smart Previous Button (§2.7.17): restart when past threshold.
        if (PSet.smartPrev && PlayerLogic.smartPrevRestarts(currentPos())) {
            seekTo(0)
            return
        }
        val prev = when {
            index - 1 >= 0 -> index - 1
            loopAll -> playlist.size - 1
            else -> -1
        }
        if (prev >= 0) playAt(prev) else seekTo(0)
    }

    private fun onPlaybackComplete() {
        savePosition(finished = true)
        if (sleepPlayToEnd && sleepDeadlineMs > 0 && !sleepFired) {
            fireSleepTimer()
            return
        }
        when {
            loopOne -> {
                seekTo(0)
                startPlayback()
            }
            else -> {
                val hasNext = shuffle || index + 1 < playlist.size || loopAll
                if (hasNext) playNext()
                else if (PSet.backToList) exitPlayer()
                else {
                    pausePlayback()
                    showControls()
                }
            }
        }
    }

    private fun onPlayerError(what: Int, extra: Int): Boolean {
        prepared = false
        stopEngineSession()
        if (PSet.suppressErrors && playlist.size > 1) {
            toast("Skipped: ${File(path).name}")
            playNext()
            return true
        }
        val reason = when (extra) {
            MediaPlayer.MEDIA_ERROR_IO -> "the file could not be read (I/O error)"
            MediaPlayer.MEDIA_ERROR_MALFORMED -> "the file appears to be malformed"
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED ->
                "${PlayerLogic.decoderLabel(decoderMode)} decoder is not supported."
            MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "the operation timed out"
            else -> "the built-in player could not decode this file"
        }
        showError(
            "Could not play \"${File(path).name}\".\n\nReason: $reason.\n\n" +
                "This decoder path could not handle the file. Shadow Player " +
                "also has its own FFmpeg engine: if the hardware decoder failed, " +
                "try the SW decoder (tap the decoder badge), which decodes audio " +
                "and video in software. A file that fails every decoder is the " +
                "honest limit, not a hidden error."
        )
        return true
    }

    private fun showError(message: String) {
        errorShown = true
        bufferingView.visibility = View.GONE
        errorText.text = message
        errorView.visibility = View.VISIBLE
        hideControls()
    }

    // =================================================================
    // Position persistence (§2.9 history + Store contract)
    // =================================================================
    private fun savePosition(finished: Boolean = false) {
        if (path.isEmpty() || !prepared) return
        val pos = currentPos()
        if (finished || (durationMs > 0 && pos >= durationMs * FINISH_FRACTION)) {
            Store.markFinished(path)
            finishedMarked = true
        } else if (pos > 0) {
            Store.saveResumePos(path, pos)
        }
    }

    // =================================================================
    // Tickers — idempotent start (v0.1 F2 fix), single stop
    // =================================================================
    private fun startTickers() {
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(syncRunnable)
        handler.post(progressRunnable)
        handler.post(saveRunnable)
        // §S4: the AvSyncPolicy ticker is HW+ staging only (it slaves
        // MediaPlayer video to the engine audio clock). In SW mode
        // sync is the engine renderer's job — never post it there.
        if (engineActive && !swMode) handler.post(syncRunnable)
    }

    private fun stopTickers() {
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(syncRunnable)
        handler.removeCallbacks(volumeRampRunnable)
    }

    private fun updateProgress() {
        // SW completion: no MediaPlayer listener exists, so poll
        // both engine sessions — the file is over when the audio
        // has played out AND the video stream has drained.
        if (swMode && prepared && !swCompletionFired &&
            EngineBridge.isEnded(engineHandle) && swVideo?.isEnded() == true) {
            swCompletionFired = true
            onPlaybackComplete()
            return
        }
        if (!prepared || userSeeking) {
            checkSleepTimer()
            return
        }
        val pos = currentPos()
        seekView.positionMs = pos
        seekView.durationMs = durationMs.toLong()
        if (PSet.elapsedTime) curTimeView.text = Fmt.msPadded(pos) else curTimeView.text = ""
        durTimeView.text = if (PSet.showRemaining) "-" + Fmt.msPadded(durationMs - pos)
            else Fmt.msPadded(durationMs.toLong())
        // A–B repeat enforcement.
        ABRepeat.onPosition(pos)?.let { target ->
            seekTo(target)
            showIndicator("A–B repeat", 600)
        }
        syncExternalAudio()
        checkSleepTimer()
    }

    private fun checkSleepTimer() {
        if (sleepDeadlineMs > 0 && !sleepFired &&
            System.currentTimeMillis() >= sleepDeadlineMs
        ) {
            if (sleepPlayToEnd && isPlaying()) {
                // "Play last media to the end": fire at completion.
                return
            }
            fireSleepTimer()
        }
    }

    private fun fireSleepTimer() {
        sleepFired = true
        sleepDeadlineMs = 0
        pausePlayback()
        toast("Sleep timer")
        exitPlayer()
    }

    // =================================================================
    // Video transform: aspect + zoom modes + pinch zoom/pan + flip
    // =================================================================
    /** §S1: aspect/zoom/pan as SURFACE SIZING. The SurfaceView's
     *  layout rect (centred in the root, offset by the clamped pan)
     *  is the displayed video size; SurfaceFlinger scales the
     *  decoder/native buffer into it. Modes keep their v0.3 user-
     *  visible behaviour: fit = whole frame visible, crop = fill,
     *  stretch = fill ignoring ratio, 100 = 1:1 video pixels,
     *  custom = pinch (fit baseline × zoomScale). Mirror/v-flip ride
     *  the view's scaleX/scaleY, which the compositor applies to the
     *  surface layer (honest note: on very old devices that transform
     *  may flip only the empty view, not the buffer — the state and
     *  rail dimming still track correctly). */
    private fun applyVideoTransform() {
        if (!::videoView.isInitialized || videoW <= 0 || videoH <= 0) return
        val cw = root.width.toFloat()
        val ch = root.height.toFloat()
        if (cw <= 0 || ch <= 0) return
        val ratio = aspectOverride ?: (videoW.toFloat() / videoH.toFloat())
        val viewRatio = cw / ch
        var dispW: Float
        var dispH: Float
        when (zoomMode) {
            "stretch" -> { dispW = cw; dispH = ch }
            "100" -> { dispW = videoW.toFloat(); dispH = videoH.toFloat() }
            "crop" -> {
                if (ratio > viewRatio) { dispH = ch; dispW = ch * ratio }
                else { dispW = cw; dispH = cw / ratio }
            }
            else -> { // "fit" + "custom" (pinch baseline is fit)
                if (ratio > viewRatio) { dispW = cw; dispH = cw / ratio }
                else { dispH = ch; dispW = ch * ratio }
            }
        }
        dispW *= zoomScale
        dispH *= zoomScale
        panX = ZoomMath.clampPan(panX, cw, dispW)
        panY = ZoomMath.clampPan(panY, ch, dispH)
        val lp = videoView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
        val nw = dispW.toInt().coerceAtLeast(1)
        val nh = dispH.toInt().coerceAtLeast(1)
        val nx = panX.toInt()
        val ny = panY.toInt()
        if (lp.width != nw || lp.height != nh || lp.leftMargin != nx || lp.topMargin != ny) {
            lp.width = nw; lp.height = nh
            lp.gravity = Gravity.CENTER
            lp.leftMargin = nx; lp.topMargin = ny
            lp.rightMargin = -nx; lp.bottomMargin = -ny
            videoView.layoutParams = lp
        }
        videoView.scaleX = if (mirror) -1f else 1f
        videoView.scaleY = if (vflip) -1f else 1f
    }

    private fun zoomStep(down: Boolean) {
        zoomMode = "custom"
        zoomScale = if (down) ZoomMath.stepDown(zoomScale, PSet.quickZoom)
            else ZoomMath.stepUp(zoomScale, PSet.quickZoom)
        if (zoomScale == 1.0f) {
            panX = 0f; panY = 0f
            // Back at 100% the baseline is the default mode — "crop"
            // since v0.2.1 (see the zoomMode declaration), not "fit".
            zoomMode = "crop"
        }
        applyVideoTransform()
        showIndicator("Zoom ${(zoomScale * 100).toInt()}%")
    }

    // =================================================================
    // Chrome: orientation, brightness, controls
    // =================================================================
    private fun applyOrientation() {
        requestedOrientation = when (PSet.orientationMode) {
            "land" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "rev_land" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "auto_land" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            "system" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            "video" -> if (videoH > videoW) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun applyStoredBrightness() {
        val b = Store.brightness
        if (b in 0.01f..1.0f) {
            val lp = window.attributes
            lp.screenBrightness = b
            window.attributes = lp
        }
    }

    private fun setWindowBrightness(frac: Float) {
        // §P6: the decoded bar's floor is 0.05 (framework min fallback).
        val lp = window.attributes
        lp.screenBrightness = frac.coerceIn(BrightnessMap.MIN_FRACTION, 1f)
        window.attributes = lp
        Store.brightness = lp.screenBrightness
    }

    // NOTE (v0.3, frame spec §8.1): MX's top bar carries NO
    // battery/clock in the recording — the visible clock/battery
    // were the phone's status bar. The v0.2 battery+clock view and
    // its receiver are removed from the player chrome; the
    // PSet.batteryClock key remains for the Settings screens.

    /** Chrome fades in/out over 100 ms (§S5: MX's alpha_show /
     *  alpha_hide pair) — the whole floating group (top bar + strip,
     *  seek + transport) animates as one. */
    private fun showControls() {
        if (locked) return
        controlsVisible = true
        for (chrome in listOf(topChrome, bottomChrome)) {
            chrome.animate().cancel()
            if (chrome.visibility != View.VISIBLE) {
                chrome.alpha = 0f
                chrome.visibility = View.VISIBLE
            }
            chrome.animate().alpha(1f).setDuration(100).start()
        }
        // A fresh show always starts from the collapsed strip
        // (MX collapses it once the chrome has been away).
        if (stripExpanded) setStripExpanded(false)
        keepControls()
    }

    private fun hideControls(instant: Boolean = false) {
        controlsVisible = false
        for (chrome in listOf(topChrome, bottomChrome)) {
            chrome.animate().cancel()
            if (instant) {
                chrome.alpha = 0f
                chrome.visibility = View.GONE
                chrome.alpha = 1f
            } else {
                chrome.animate().alpha(0f).setDuration(100)
                    .withEndAction {
                        if (!controlsVisible) {
                            chrome.visibility = View.GONE
                            chrome.alpha = 1f
                        }
                    }
                    .start()
            }
        }
    }

    private fun keepControls() {
        handler.removeCallbacks(hideRunnable)
        if (controlsVisible && !locked) handler.postDelayed(hideRunnable, PSet.autoHideMs)
    }

    private fun keepScreenOnIfNeeded() {
        if (PSet.keepScreenOn && isPlaying()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun releaseAll() {
        stopTickers()
        stopEngineSession()
        releaseExternalAudio()
        unregisterNoisyReceiver()
        try {
            mp?.stop()
        } catch (t: Throwable) { /* not prepared */ }
        mp?.release()
        mp = null
        prepared = false
    }

    private fun exitPlayer() {
        savePosition()
        releaseAll()
        finish()
    }

    // ---- audio focus ("Play alone", §2.7.15) ----
    private var focusHeld = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) pausePlayback()
    }

    private fun requestAudioFocusIfNeeded() {
        if (!PSet.playAlone || focusHeld) return
        val result = audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // =================================================================
    // Full player menu (§2.3 "More") — drill-in panel, MX order.
    // [CUT] items (Network Stream, Online subtitles, Check for
    // updates, Bug Report) are omitted entirely per Sir's ruling.
    // =================================================================
    private data class MenuRow(
        val label: String,
        val checked: Boolean? = null,
        val value: String? = null,
        val enabled: Boolean = true,
        val action: () -> Unit
    )

    private fun openMenu() {
        // §S5/P2: options menu animates at 100 ms (options_menu_in).
        menuScrim.animate().cancel()
        menuScrim.alpha = 0f
        menuScrim.visibility = View.VISIBLE
        menuScrim.animate().alpha(1f).setDuration(100).start()
        menuPanel.animate().cancel()
        menuPanel.alpha = 0f
        menuPanel.visibility = View.VISIBLE
        menuPanel.animate().alpha(1f).setDuration(100).start()
        showMenuMain()
        keepControls()
    }

    private fun closeMenu() {
        if (menuPanel.visibility != View.VISIBLE) {
            menuScrim.visibility = View.GONE
            return
        }
        // §S5: options_menu_out, 100 ms.
        menuPanel.animate().alpha(0f).setDuration(100)
            .withEndAction {
                menuPanel.visibility = View.GONE
                menuPanel.alpha = 1f
            }
            .start()
        menuScrim.animate().alpha(0f).setDuration(100)
            .withEndAction {
                menuScrim.visibility = View.GONE
                menuScrim.alpha = 1f
            }
            .start()
    }

    private fun menuHeaderRow(text: String, back: Boolean = false) {
        val row = TextView(this).apply {
            this.text = if (back) "‹  $text" else text
            setTextColor(if (back) ACCENT else SECONDARY)
            textSize = if (back) 15f else 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(6))
            if (back) setOnClickListener { showMenuMain() }
        }
        menuList.addView(row)
    }

    private fun menuItemRow(row: MenuRow) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(12), dp(11))
            isEnabled = row.enabled
            alpha = if (row.enabled) 1f else 0.38f
            setOnClickListener { if (row.enabled) row.action() }
        }
        if (row.checked != null) {
            val box = TextView(this).apply {
                text = if (row.checked) "✓" else ""
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                background = if (row.checked) roundedBg(ACCENT, 3f)
                    else roundedBg(Color.argb(40, 255, 255, 255), 3f)
            }
            line.addView(box, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
        }
        line.addView(TextView(this).apply {
            text = row.label
            setTextColor(TITLE_C)
            textSize = 15f
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (row.value != null) {
            line.addView(TextView(this).apply {
                text = row.value
                setTextColor(TERTIARY)
                textSize = 13f
            })
        }
        menuList.addView(line)
    }

    private fun showMenuMain() {
        menuList.removeAllViews()
        val groups = listOf(
            "Play" to { showPlayMenu() },
            "Display" to { showDisplayMenu() },
            "Audio" to { showAudioMenu() },
            "Subtitle" to { showSubtitleMenu() },
            "Tools" to { showToolsMenu() },
            "Help" to { showHelpMenu() }
        )
        for ((label, action) in groups) {
            menuItemRow(MenuRow(label, value = "›", action = action))
        }
        menuItemRow(MenuRow("Quit", action = { closeMenu(); exitPlayer() }))
    }

    private fun showPlayMenu() {
        menuList.removeAllViews()
        menuHeaderRow("Play", back = true)
        val engineMode = decoderMode != 1
        menuItemRow(MenuRow("Speed", value = "${trimSpeed(currentSpeed)}×") { closeMenu(); showSpeedDialog() })
        menuItemRow(MenuRow("Background Play", checked = PSet.bgAudio) {
            PSet.bgAudio = !PSet.bgAudio
            showPlayMenu()
        })
        menuItemRow(MenuRow("Loop one", checked = loopOne) {
            loopOne = !loopOne
            if (loopOne) loopAll = false
            rebuildRail(); showPlayMenu()
        })
        menuItemRow(MenuRow("Loop All", checked = loopAll) {
            loopAll = !loopAll
            if (loopAll) loopOne = false
            rebuildRail(); showPlayMenu()
        })
        menuItemRow(MenuRow("Shuffle", checked = shuffle) {
            shuffle = !shuffle
            rebuildRail(); showPlayMenu()
        })
        menuItemRow(MenuRow(
            "Use speedup tricks", checked = PSet.speedupTricks, enabled = engineMode
        ) {
            // Honest-gate pattern: the toggle accepts only where a
            // real consumer exists (SW mode's decoder open); in HW+
            // it refuses rather than persisting a state nothing in
            // that mode would read.
            if (!applySpeedupTricks(!PSet.speedupTricks)) {
                toast(
                    FFmpegEngine.gateReason("use_speedup_tricks")
                        ?: "Not available in this engine build."
                )
            } else {
                PSet.speedupTricks = !PSet.speedupTricks
            }
            showPlayMenu()
        })
        menuItemRow(MenuRow("A - B Repeat", value = if (ABRepeat.active) "on" else null) {
            closeMenu(); showABPanel()
        })
        menuItemRow(MenuRow("Playing Queue") { closeMenu(); togglePlaylistDrawer() })
        menuItemRow(MenuRow("Bookmark") { closeMenu(); addBookmark() })
        val marks = PSet.fileBookmarks(path)
        menuItemRow(MenuRow(
            "Bookmarks",
            value = if (marks.isEmpty()) "none" else "${marks.size}"
        ) { showBookmarksMenu() })
    }

    /** Play ▸ Bookmarks: jump list for this file's marks (added
     *  from Play ▸ Bookmark), plus a clear row. */
    private fun showBookmarksMenu() {
        menuList.removeAllViews()
        menuHeaderRow("Bookmarks", back = true)
        val marks = PSet.fileBookmarks(path)
        if (marks.isEmpty()) {
            menuItemRow(MenuRow("No bookmarks for this file", enabled = false) {})
            return
        }
        for (mark in marks) {
            menuItemRow(MenuRow(Fmt.msPadded(mark)) {
                closeMenu()
                seekTo(mark)
            })
        }
        menuItemRow(MenuRow("Clear bookmarks") {
            PSet.setFileBookmarks(path, emptyList())
            seekView.bookmarks = emptyList()
            showBookmarksMenu()
        })
    }

    private fun showDisplayMenu() {
        menuList.removeAllViews()
        menuHeaderRow("Display", back = true)
        menuItemRow(MenuRow("Quick Shortcuts", checked = railVisible()) {
            setRailVisible(!railVisible())
            showDisplayMenu()
        })
        menuItemRow(MenuRow("Video", checked = videoView.visibility == View.VISIBLE) {
            videoView.visibility =
                if (videoView.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            showDisplayMenu()
        })
        menuItemRow(MenuRow("Screen Rotation", checked = PSet.rotationButton) {
            PSet.rotationButton = !PSet.rotationButton
            syncRotationShortcut()
            showDisplayMenu()
        })
        menuItemRow(MenuRow(
            "Deinterlace",
            value = arrayOf("None", "Yadif", "W3fdif")[PSet.deinterlace],
            enabled = engineActive // MX: "works only with SW decoder"
        ) { closeMenu(); showDeinterlaceDialog() })
        menuItemRow(MenuRow("Video zoom", value = zoomModeLabel()) {
            closeMenu(); showVideoZoomDialog()
        })
        menuItemRow(MenuRow("Aspect Ratio", value = aspectLabel()) {
            closeMenu(); showAspectDialog()
        })
        menuItemRow(MenuRow("Chapter", enabled = false, value = "No chapter source") {
            // Chapters ride the engine (§4.2 #64); neither the system
            // decoder nor the engine probe exposes chapter data, so
            // there is no chapter list to show — the reason is the
            // row's visible value text, not just this comment.
        })
        menuItemRow(MenuRow("Settings") { closeMenu(); showDisplaySettingsPanel() })
    }

    private fun showAudioMenu() {
        menuList.removeAllViews()
        menuHeaderRow("Audio", back = true)
        val hw = decoderMode == 1
        menuItemRow(MenuRow("Equalizer", enabled = PSet.equalizerGate) {
            closeMenu(); openEqualizer()
        })
        menuItemRow(MenuRow("Open") { closeMenu(); pickExternalAudio() })
        menuItemRow(MenuRow("Audio Track", value = currentAudioTrackLabel()) {
            closeMenu(); showAudioTrackChooser()
        })
        menuItemRow(MenuRow("Stereo mode", enabled = !hw, value = stereoLabel()) {
            closeMenu(); showStereoDialog()
        })
        menuItemRow(MenuRow("AV sync", checked = avSyncEnabled, enabled = engineActive) {
            avSyncEnabled = !avSyncEnabled
            PSet.avSync = avSyncEnabled
            showAudioMenu()
        })
        menuItemRow(MenuRow("Synchronization", enabled = !hw) {
            closeMenu(); showAudioSyncPanel()
        })
        menuItemRow(MenuRow(
            "Fix broken audio (experimental)", checked = fixBrokenAudio,
            enabled = engineActive
        ) {
            fixBrokenAudio = !fixBrokenAudio
            if (!applyFixBrokenAudio(fixBrokenAudio)) {
                fixBrokenAudio = !fixBrokenAudio
                toast("Not available in this engine build.")
            }
            showAudioMenu()
        })
    }

    private fun showSubtitleMenu() {
        menuList.removeAllViews()
        menuHeaderRow("Subtitle", back = true)
        menuItemRow(MenuRow("Open") { closeMenu(); pickSubtitleFile() })
        menuItemRow(MenuRow("Synchronization") { closeMenu(); showFloatBar("sub_sync") })
        menuItemRow(MenuRow("Speed", value = "${trimSpeed(PSet.subSpeed)}×") {
            closeMenu(); showFloatBar("sub_speed")
        })
        menuItemRow(MenuRow("Panel") { closeMenu(); showSubtitlePanel() })
        menuItemRow(MenuRow("Settings") { closeMenu(); showSubtitleSettingsPanel() })
    }

    private fun showToolsMenu() {
        menuList.removeAllViews()
        menuHeaderRow("Tools", back = true)
        menuItemRow(MenuRow("Favourite", checked = Store.isFavourite(path)) {
            val now = !Store.isFavourite(path)
            Store.setFavourite(path, now)
            toast(if (now) "Added to favourites" else "Removed from favourites")
            showToolsMenu()
        })
        menuItemRow(MenuRow("Share") { closeMenu(); shareCurrent() })
        menuItemRow(MenuRow("Delete") { closeMenu(); confirmDelete() })
        menuItemRow(MenuRow("Rename") { closeMenu(); showRenameDialog() })
        menuItemRow(MenuRow("Lock") { closeMenu(); lockControls() })
        menuItemRow(MenuRow(
            "Sleep Timer", checked = sleepDeadlineMs > 0,
            value = if (sleepDeadlineMs > 0) Fmt.clock(sleepDeadlineMs) else null
        ) { closeMenu(); showSleepDialog() })
        menuItemRow(MenuRow("Properties") { closeMenu(); showProperties() })
        menuItemRow(MenuRow("Add to Playlist") { closeMenu(); showAddToPlaylistDialog() })
        menuItemRow(MenuRow("Settings") { closeMenu(); showDisplaySettingsPanel() })
        menuItemRow(MenuRow("Create clip") { closeMenu(); startActivity(ClipEditorActivity.intent(this, path)) })
    }

    private fun showHelpMenu() {
        menuList.removeAllViews()
        menuHeaderRow("Help", back = true)
        menuItemRow(MenuRow("What's New") {
            closeMenu()
            infoDialog(
                "What's New",
                "Shadow Player — the player screen now matches " +
                    "MX's real chrome frame-for-frame: floating controls, " +
                    "the quick-actions circle strip, slide-in Subtitle / " +
                    "Audio Track panels, the decoded right menu, one-tap " +
                    "aspect cycling and bookmarks. Decoder selection " +
                    "(HW / HW+ / SW via our own FFmpeg engine), 8 subtitle " +
                    "formats, A–B repeat, sleep timer, background play " +
                    "and PiP. No internet, no ads, no tracking."
            )
        })
        menuItemRow(MenuRow("Features") {
            closeMenu()
            infoDialog(
                "Features",
                "• MX gesture set: brightness / volume / seek swipes, " +
                    "double-tap seek, pinch zoom & pan, long-press 2× speed\n" +
                    "• Subtitles: SRT, ASS/SSA, SAMI, WebVTT, MicroDVD, MPL2, " +
                    "PJS, PowerDivX, SubViewer + embedded tracks, styling & sync\n" +
                    "• Resume modes, sleep timer, A–B repeat, loop & shuffle\n" +
                    "• Fully offline. Your files never leave this phone."
            )
        })
        menuItemRow(MenuRow("FAQ") {
            closeMenu()
            infoDialog(
                "FAQ",
                "Q: A file has no sound?\n" +
                    "A: Its audio codec needs the SW/HW+ engine — open the " +
                    "decoder dialog (top bar badge) and pick HW+ or SW.\n\n" +
                    "Q: Subtitles look garbled?\n" +
                    "A: Pick the right character encoding under " +
                    "Subtitle » Settings."
            )
        })
        menuItemRow(MenuRow("About") {
            closeMenu()
            infoDialog("About", "Shadow Player\nVersion 0.5.5\n\nA fully offline video player.")
        })
    }

    private fun infoDialog(title: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- menu state helpers ----
    private var railVisibleFlag = true
    private fun railVisible(): Boolean = railVisibleFlag
    private fun setRailVisible(v: Boolean) {
        railVisibleFlag = v
        if (::stripBox.isInitialized) {
            stripBox.visibility = if (v) View.VISIBLE else View.GONE
        }
    }

    private fun syncRotationShortcut() {
        val ids = PSet.railShortcuts.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .toMutableList()
        if (PSet.rotationButton && "screen_rotation" !in ids) ids.add(0, "screen_rotation")
        if (!PSet.rotationButton) ids.remove("screen_rotation")
        PSet.railShortcuts = ids.joinToString(",")
        rebuildRail()
    }

    private var avSyncEnabled = true
    private var fixBrokenAudio = false

    private fun applyFixBrokenAudio(on: Boolean): Boolean {
        if (!engineActive) return false
        // Capability gate: the engine is the one source of truth
        // (FFmpegEngine.supportsFixBrokenAudio — false today: the
        // bridge has no such hook). Absence = not available, said so.
        return FFmpegEngine.supportsFixBrokenAudio()
    }

    private fun applySpeedupTricks(on: Boolean): Boolean {
        if (!engineActive) return false
        // Capability gate: FFmpegEngine.supportsSpeedupTricks is
        // true (the SW decoder applies skip_loop_filter at open),
        // but the trick is an SW-decoder property — in HW+ there is
        // no software video decoder for it to act on, so the toggle
        // only accepts in SW mode. The flag itself is consumed at
        // SW session open (loadSwCurrent passes PSet.speedupTricks),
        // i.e. it takes effect on the next file / decoder switch,
        // like the core-limit setting.
        return swMode && FFmpegEngine.supportsSpeedupTricks()
    }

    private fun zoomModeLabel(): String = when (zoomMode) {
        "fit" -> "Fit to screen"
        "stretch" -> "Stretch"
        "crop" -> "Crop"
        "100" -> "100%"
        else -> "Custom"
    }

    private fun aspectLabel(): String {
        val r = aspectOverride ?: return "Default"
        return when (r) {
            1f -> "1:1"
            4f / 3f -> "4:3"
            16f / 9f -> "16:9"
            16f / 10f -> "16:10"
            21f / 9f -> "21:9 (2.33:1)"
            64f / 27f -> "64:27 (2.37:1)"
            2.21f -> "2.21:1"
            2.35f -> "2.35:1"
            2.39f -> "2.39:1"
            5f / 4f -> "5:4"
            18f / 9f -> "18:9"
            9f / 18f -> "9:18"
            else -> "Custom"
        }
    }

    private fun stereoLabel(): String = arrayOf(
        "Stereo", "Mono", "Reverse stereo", "Auto reverse stereo"
    )[stereoMode]

    private var stereoMode = 0

    // =================================================================
    // Dialogs: decoder / speed / sleep / A–B / zoom / aspect / stereo
    // =================================================================
    /** Select decoder (frame spec §4): MX's flat charcoal box —
     *  centred, barely-rounded corners, title + three 45dp radio
     *  rows, NO buttons; tapping a row selects and dismisses.
     *  Opened by tapping the plain-text decoder badge. */
    private fun showDecoderDialog() {
        val swVideo = EngineBridge.supportsSwVideo()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(23), dp(12), dp(23), dp(14))
        }
        box.addView(TextView(this).apply {
            text = "Select decoder"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        })
        val rows = listOf(
            "HW decoder" to 1, "HW+ decoder" to 4, "SW decoder" to 2
        )
        for ((label, mode) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    if (mode == 2 && !swVideo) {
                        // Safety net only: supportsSwVideo() tracks
                        // engine availability, so this fires solely
                        // when the engine libraries failed to load.
                        AlertDialog.Builder(this@PlayerActivity)
                            .setTitle("SW decoder")
                            .setMessage(
                                "The built-in FFmpeg engine could not be " +
                                    "loaded, so software decoding is " +
                                    "unavailable for this session."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        return@setOnClickListener
                    }
                    setDecoderMode(mode)
                }
            }
            row.addView(
                MxRadioView(this, decoderMode == mode),
                LinearLayout.LayoutParams(dp(24), dp(24))
            )
            row.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(dp(16), 0, 0, 0)
            })
            box.addView(row, LinearLayout.LayoutParams(MATCH, dp(45)))
        }
        // Decoded dialog_options_menu.xml footer: Cancel (16sp,
        // tertiary) left of Done (16sp, accent #3C8CF0), 10dp padding,
        // 4dp top margin, over an 8dp bottom placeholder; the panel
        // itself pads 12dp at the top. (The decoded layout's list is
        // a RecyclerView — framework rows here per the no-AndroidX
        // ruling; row height stays the recording's 45dp because the
        // decoded layout carries no row height.)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        footer.addView(TextView(this).apply {
            text = "Cancel"
            setTextColor(TERTIARY)
            textSize = 16f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { dialog.dismiss() }
        })
        footer.addView(TextView(this).apply {
            text = "Done"
            setTextColor(ACCENT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { dialog.dismiss() }
        })
        box.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        box.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(8)))
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            )
            val screenW = resources.displayMetrics.widthPixels
            setLayout(min(dp(568), (screenW * 0.92f).toInt()), WRAP)
            setDimAmount(0.5f)
        }
        // §S5: dialog fade at MX's fast 100 ms class.
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(100).start()
        dialog.show()
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.25×", "0.5×", "1.0×", "2.0×", "3.0×", "Custom")
        AlertDialog.Builder(this)
            .setTitle("Speed")
            .setItems(labels) { _, which ->
                if (which < PlayerLogic.SPEED_STEPS.size) {
                    setSpeed(PlayerLogic.SPEED_STEPS[which], persist = true)
                    showIndicator("Speed ${trimSpeed(currentSpeed)}×")
                } else {
                    showCustomSpeedDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomSpeedDialog() {
        val seek = SeekBar(this).apply {
            max = 375 // 25..400%
            progress = (currentSpeed * 100).toInt() - 25
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val label = TextView(this).apply {
            text = "${(currentSpeed * 100).toInt()}%"
            gravity = Gravity.CENTER
            setTextColor(TITLE_C)
            textSize = 16f
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "${p + 25}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(seek)
        }
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val pct = PlayerLogic.clampSpeedPercent(seek.progress + 25)
                setSpeed(pct / 100f, persist = true)
                showIndicator("Speed ${trimSpeed(currentSpeed)}×")
            }
            .setNeutralButton("Reset") { _, _ -> setSpeed(1.0f, persist = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSleepDialog() {
        val labels = SleepTimerCalc.PRESET_LABELS
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val playToEnd = android.widget.CheckBox(this).apply {
            text = "Play last media to the end"
            isChecked = sleepPlayToEnd
            setTextColor(TITLE_C)
        }
        box.addView(playToEnd)
        var chosen = 0
        val group = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.forEachIndexed { idx, label ->
            val rb = android.widget.RadioButton(this).apply {
                text = label
                id = idx
                setTextColor(TITLE_C)
            }
            group.addView(rb)
        }
        group.check(0)
        group.setOnCheckedChangeListener { _, id -> chosen = id }
        box.addView(group)
        AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setView(box)
            .setPositiveButton("Done") { _, _ ->
                sleepPlayToEnd = playToEnd.isChecked
                when (chosen) {
                    0 -> {
                        sleepDeadlineMs = 0
                        sleepFired = false
                        toast("Sleep timer off")
                    }
                    in 1..4 -> {
                        val mins = SleepTimerCalc.PRESET_MINUTES[chosen - 1]
                        sleepDeadlineMs = SleepTimerCalc.presetDeadline(
                            System.currentTimeMillis(), mins
                        )
                        sleepFired = false
                        toast(
                            SleepTimerCalc.confirmText(
                                System.currentTimeMillis(), sleepDeadlineMs,
                                Fmt.clock(sleepDeadlineMs)
                            )
                        )
                    }
                    else -> showSleepCustomTime()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSleepCustomTime() {
        val now = Calendar.getInstance()
        android.app.TimePickerDialog(
            this,
            { _, hour, minute ->
                sleepDeadlineMs = SleepTimerCalc.customDeadline(
                    System.currentTimeMillis(), hour, minute
                )
                sleepFired = false
                toast(
                    SleepTimerCalc.confirmText(
                        System.currentTimeMillis(), sleepDeadlineMs,
                        Fmt.clock(sleepDeadlineMs)
                    )
                )
            },
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true
        ).show()
    }

    private fun showABPanel() {
        val pos = currentPos()
        val labels = arrayOf(
            "Set A point  (${if (ABRepeat.aMs >= 0) Fmt.ms(ABRepeat.aMs) else "—"})",
            "Set B point  (${if (ABRepeat.bMs >= 0) Fmt.ms(ABRepeat.bMs) else "—"})",
            "Clear A–B repeat"
        )
        AlertDialog.Builder(this)
            .setTitle("A - B Repeat")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showIndicator(ABRepeat.pressA(pos), 1200)
                    1 -> showIndicator(ABRepeat.pressB(pos), 1200)
                    2 -> {
                        ABRepeat.clear()
                        showIndicator("A–B repeat off", 900)
                    }
                }
                rebuildRail()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeinterlaceDialog() {
        val labels = arrayOf("None", "yadif", "w3fdif")
        AlertDialog.Builder(this)
            .setTitle("Deinterlace")
            .setSingleChoiceItems(labels, PSet.deinterlace) { dialog, which ->
                if (which == 0) {
                    // "None" is a real state (filter off) — persist it.
                    PSet.deinterlace = 0
                } else if (FFmpegEngine.supportsDeinterlace()) {
                    PSet.deinterlace = which
                } else {
                    // Capability gate: deinterlacing is a video filter
                    // and the engine decodes audio only. Refuse with
                    // the engine's own reason; persist nothing fake.
                    toast(
                        FFmpegEngine.gateReason("default_deinterlacer")
                            ?: "Deinterlace is not available in this engine build."
                    )
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoZoomDialog() {
        val labels = arrayOf("Fit to screen", "Stretch", "Crop", "100%", "Custom")
        val modes = arrayOf("fit", "stretch", "crop", "100", "custom")
        AlertDialog.Builder(this)
            .setTitle("Video zoom")
            .setSingleChoiceItems(labels, modes.indexOf(zoomMode).coerceAtLeast(0)) { dialog, which ->
                zoomMode = modes[which]
                if (zoomMode != "custom") {
                    zoomScale = 1f; panX = 0f; panY = 0f
                }
                applyVideoTransform()
                refreshAspectButton()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAspectDialog() {
        val portrait = resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        val entries: List<Pair<String, Float?>> = if (portrait) {
            listOf(
                "Default" to null, "1:1" to 1f, "4:3" to 4f / 3f, "16:9" to 16f / 9f,
                "18:9" to 18f / 9f, "9:18" to 9f / 18f, "5:4" to 5f / 4f
            )
        } else {
            listOf(
                "Default" to null, "1:1" to 1f, "4:3" to 4f / 3f, "16:9" to 16f / 9f,
                "16:10" to 16f / 10f, "21:9 (2.33:1)" to 21f / 9f,
                "64:27 (2.37:1)" to 64f / 27f, "2.21:1" to 2.21f, "2.35:1" to 2.35f,
                "2.39:1" to 2.39f, "5:4" to 5f / 4f
            )
        }
        val labels = entries.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Aspect Ratio")
            .setItems(labels) { _, which ->
                aspectOverride = entries[which].second
                applyVideoTransform()
                showIndicator("Aspect: ${entries[which].first}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStereoDialog() {
        val labels = arrayOf("Stereo", "Mono", "Reverse stereo", "Auto reverse stereo")
        AlertDialog.Builder(this)
            .setTitle("Stereo mode")
            .setSingleChoiceItems(labels, stereoMode) { dialog, which ->
                dialog.dismiss()
                if (engineActive && !FFmpegEngine.supportsStereoMode()) {
                    // Capability gate: the bridge has no channel-
                    // remapping hook (PCM layout passes through as
                    // decoded) — say so instead of pretending.
                    toast("Stereo mode is not available in this engine build.")
                    return@setSingleChoiceItems
                }
                stereoMode = which
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Fullscreen (§2.7.2 Full Screen: Auto switch / On / Off) ----
    private fun applyFullscreen() {
        val fs = when (PSet.fullScreen) {
            "on" -> true
            "off" -> false
            else -> resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        }
        // Display cutout (v0.2.1 fix): in fullscreen the window must
        // draw edge-to-edge UNDER the punch-hole camera. Without
        // SHORT_EDGES the system letterboxes an immersive window away
        // from the cutout — a black bar on the camera side in
        // landscape (Sir's POCO F5 report: right edge flush, left
        // side cut out). The manifest declares the same mode on this
        // activity; setting it here too keeps the player edge-to-edge
        // regardless of theme/manifest resolution. Restored to
        // DEFAULT when leaving fullscreen. API 28+ only (the mode
        // does not exist below, and pre-cutout devices need nothing).
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (fs)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = lp
        }
        if (fs) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }


    // =================================================================
    // Touch + gestures (§2.5 + Controls tuner §2.7.4)
    // =================================================================
    private fun onRootTouch(ev: MotionEvent): Boolean {
        if (errorShown) return false
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                downTime = System.currentTimeMillis()
                longPressFired = false
                gestureMode = GestureMode.NONE
                touchOnSubtitle = subtitleView.visibility == View.VISIBLE &&
                    ev.y > subtitleView.top - dp(30) && cues.isNotEmpty()
                subDragBaseMargin = PSet.subBottomMarginDp
                subDragBaseOffset = subSessionOffsetMs
                if (!locked && PSet.gLongPressFF && prepared) {
                    handler.postDelayed(longPressRunnable, 550)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount == 1 && !scaleDetector.isInProgress) handleDrag(ev)
                if (ev.pointerCount == 2 && PSet.gTwoFingerSpeed) handleTwoFingerSpeed(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (ffActive) {
                    endSpeedFF()
                }
                when (gestureMode) {
                    GestureMode.SEEK -> {
                        seekTo(scrubTargetMs)
                        seekPreview.visibility = View.GONE
                        if (scrubWasPlaying) startPlayback()
                    }
                    GestureMode.BRIGHTNESS -> {
                        // §S5: OSD bars self-hide 1,000 ms after release.
                        handler.removeCallbacks(hideBrightBarRunnable)
                        handler.postDelayed(hideBrightBarRunnable, 1000)
                    }
                    GestureMode.VOLUME -> {
                        handler.removeCallbacks(hideSoundBarRunnable)
                        handler.postDelayed(hideSoundBarRunnable, 1000)
                    }
                    else -> Unit
                }
                gestureMode = GestureMode.NONE
                touchOnSubtitle = false
            }
        }
        return true
    }

    private val hideBrightBarRunnable = Runnable {
        if (::brightBar.isInitialized) brightBar.visibility = View.GONE
    }
    private val hideSoundBarRunnable = Runnable {
        if (::soundBar.isInitialized) soundBar.visibility = View.GONE
    }

    private val longPressRunnable = Runnable {
        if (gestureMode == GestureMode.NONE && !locked && isPlaying()) {
            longPressFired = true
            ffActive = true
            speedBeforeFF = currentSpeed
            setSpeed(2.0f, persist = false)
            ffIndicator.visibility = View.VISIBLE
        }
    }

    private fun endSpeedFF() {
        ffActive = false
        ffIndicator.visibility = View.GONE
        setSpeed(speedBeforeFF, persist = false)
    }

    private fun handleDrag(ev: MotionEvent) {
        if (locked || !prepared) return
        val dx = ev.x - downX
        val dy = ev.y - downY
        val adx = abs(dx)
        val ady = abs(dy)

        if (gestureMode == GestureMode.NONE) {
            if (max(adx, ady) < dp(24)) return
            gestureMode = when {
                touchOnSubtitle && ady > adx && PSet.gSubUpDown -> GestureMode.SUB_MOVE
                touchOnSubtitle && adx >= ady && PSet.gSubScroll -> GestureMode.SUB_SYNC
                adx > ady && PSet.gSeek -> {
                    scrubWasPlaying = isPlaying()
                    if (scrubWasPlaying) pausePlayback()
                    gestureBasePosMs = currentPos()
                    GestureMode.SEEK
                }
                downX < root.width / 2 && PSet.gBrightness -> {
                    val winB = window.attributes.screenBrightness
                        .takeIf { it >= 0 } ?: 0.5f
                    // Inverse of BrightnessMap so the drag starts in
                    // fraction space with no jump on the first move.
                    gestureBaseValue = ((winB - BrightnessMap.MIN_FRACTION) /
                        (1f - BrightnessMap.MIN_FRACTION)).coerceIn(0f, 1f)
                        .pow(1f / BrightnessMap.FACTOR)
                    GestureMode.BRIGHTNESS
                }
                downX >= root.width / 2 && PSet.gVolume -> {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    gestureBaseValue =
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                    GestureMode.VOLUME
                }
                else -> GestureMode.NONE
            }
        }

        when (gestureMode) {
            GestureMode.BRIGHTNESS -> {
                // §P6: gesture fraction -> window brightness through
                // the decoded 1.8-factor mapping (min 0.05, max 255);
                // the bar itself shows the raw fraction as "%d%%".
                val frac = (gestureBaseValue - dy / (root.height * 0.7f)).coerceIn(0f, 1f)
                setWindowBrightness(BrightnessMap.toWindowFraction(frac))
                brightBar.fraction = frac
                handler.removeCallbacks(hideBrightBarRunnable)
                brightBar.visibility = View.VISIBLE
            }
            GestureMode.VOLUME -> {
                val frac = (gestureBaseValue - dy / (root.height * 0.7f)).coerceIn(0f, 1f)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (PSet.systemVolume) {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC, (frac * maxVol).toInt(), 0
                    )
                }
                soundBar.fraction = frac
                handler.removeCallbacks(hideSoundBarRunnable)
                soundBar.visibility = View.VISIBLE
            }
            GestureMode.SEEK -> {
                // MX "Seek Speed" (§2.7.5): full-width swipe ≈ 1/3 of
                // the duration at speed 1.0, scaled by the setting.
                val span = durationMs * PSet.seekSpeed / 3.0
                val delta = (dx / root.width * span).toLong()
                scrubTargetMs = (gestureBasePosMs + delta).coerceIn(0L, durationMs.toLong())
                if (PSet.showPosWhileSeeking) {
                    val sign = if (delta >= 0) "+" else "−"
                    seekPreviewText.text =
                        "${Fmt.ms(scrubTargetMs)} / ${Fmt.ms(durationMs.toLong())} " +
                            "($sign${Fmt.ms(abs(delta))})"
                    seekPreview.visibility = View.VISIBLE
                    if (PSet.previewWhileSeeking) showSeekPreviewFrame(scrubTargetMs)
                }
            }
            GestureMode.SUB_MOVE -> {
                val deltaDp = (-dy / resources.displayMetrics.density).toInt()
                val newMargin = (subDragBaseMargin + deltaDp).coerceIn(0, 240)
                PSet.subBottomMarginDp = newMargin
                applySubtitleStyle()
                showIndicator("Subtitle position: ${newMargin}dp", 500)
            }
            GestureMode.SUB_SYNC -> {
                val delta = (dx / root.width * 10_000).toLong() // full width = 10s
                subSessionOffsetMs = (subDragBaseOffset + delta).coerceIn(-60_000L, 60_000L)
                showIndicator("Subtitle sync ${fmtSignedMs(subSessionOffsetMs)}", 500)
                updateSubtitle()
            }
            else -> Unit
        }
    }

    private fun handleTwoFingerSpeed(ev: MotionEvent) {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return
        if (gestureMode != GestureMode.NONE) return
        // Vertical two-finger swipe adjusts speed in 0.25 steps.
        val y = (ev.getY(0) + ev.getY(1)) / 2f
        if (abs(y - downY) > dp(60)) {
            val dir = if (y < downY) 0.25f else -0.25f
            setSpeed((currentSpeed + dir).coerceIn(0.25f, 4.0f), persist = false)
            showIndicator("Speed ${trimSpeed(currentSpeed)}×")
            downY = y
        }
    }

    private var previewRetriever: MediaMetadataRetriever? = null
    private fun showSeekPreviewFrame(targetMs: Long) {
        try {
            if (previewRetriever == null) {
                previewRetriever = MediaMetadataRetriever().apply { setDataSource(path) }
            }
            val frame = previewRetriever?.getFrameAtTime(
                targetMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            if (frame != null) {
                seekPreviewImage.setImageBitmap(frame)
                seekPreviewImage.visibility = View.VISIBLE
            }
        } catch (t: Throwable) { /* preview is best-effort */ }
    }

    private fun fmtSignedMs(ms: Long): String =
        (if (ms >= 0) "+" else "−") + Fmt.ms(abs(ms))

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (locked) {
                pokeUnlock()
                return true
            }
            // v0.5.2 (Sir's order): a single tap ONLY shows/hides
            // the interface — it never pauses or resumes. Play/pause
            // lives on the centre double tap below, exactly like MX.
            if (controlsVisible) hideControls() else showControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked) return true
            val third = root.width / 3f
            when {
                PSet.gDblTapSeek && e.x < third -> {
                    val amt = PSet.moveIntervalSec * 1000L
                    seekTo((currentPos() - amt).coerceIn(0L, durationMs.toLong()))
                    flashTapSeek(tapSeekLeft, "◂◂ ${PSet.moveIntervalSec}s")
                }
                PSet.gDblTapSeek && e.x >= third * 2 -> {
                    val amt = PSet.moveIntervalSec * 1000L
                    seekTo((currentPos() + amt).coerceIn(0L, durationMs.toLong()))
                    flashTapSeek(tapSeekRight, "${PSet.moveIntervalSec}s ▸▸")
                }
                // v0.5.2: centre double tap = play/pause (Sir's
                // order; default on). The old centre double-tap-zoom
                // branch is removed — pinch zoom remains.
                PSet.gDblTapPlayPause -> togglePlayPause()
            }
            return true
        }
    }

    private val hideTapSeekLeftRunnable = Runnable {
        if (::tapSeekLeft.isInitialized) tapSeekLeft.visibility = View.GONE
    }
    private val hideTapSeekRightRunnable = Runnable {
        if (::tapSeekRight.isInitialized) tapSeekRight.visibility = View.GONE
    }

    private fun flashTapSeek(view: TextView, label: String) {
        // §P6/S5: TapSeekLayout self-hides after 1,000 ms.
        view.text = label
        view.visibility = View.VISIBLE
        val hide = if (view === tapSeekLeft) hideTapSeekLeftRunnable else hideTapSeekRightRunnable
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 1000)
    }

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var baseScale = 1f
        private var basePanX = 0f
        private var basePanY = 0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (locked || !PSet.gZoomPan) return false
            baseScale = zoomScale
            basePanX = panX
            basePanY = panY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (PSet.gVideoZoom) {
                zoomMode = "custom"
                zoomScale = ZoomMath.clampScale(baseScale * detector.scaleFactor)
            }
            if (PSet.gVideoPan && zoomScale > 1f) {
                panX = basePanX + (detector.focusX - detector.currentSpanX / 2f) * 0f +
                    (detector.focusX - downX) * 0.5f
                panY = basePanY + (detector.focusY - downY) * 0.5f
            }
            applyVideoTransform()
            return true
        }
    }

    // =================================================================
    // Screen lock (§2.3 Tools ▸ Lock; lock targets Input / Rotation)
    // =================================================================
    private fun lockControls() {
        locked = true
        hideControls()
        closeMenu()
        if (PSet.lockTarget == "rotation") {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        pokeUnlock()
        toast("Screen locked — ${LockGesturePolicy.hint(PSet.lockMode)}")
    }

    private fun unlockControls() {
        locked = false
        unlockBtn.visibility = View.GONE
        if (PSet.lockTarget == "rotation") applyOrientation()
        showControls()
    }

    private fun pokeUnlock() {
        if (!PSet.showLockedUi && locked) {
            // "Show interface when locked screen is touched" OFF:
            // still flash the unlock button briefly — otherwise the
            // user can never get out. MX keeps an unlock affordance.
        }
        unlockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(unlockHideRunnable)
        handler.postDelayed(unlockHideRunnable, 2500)
    }

    // =================================================================
    // Quick Shortcuts strip (§2.2 item 6 — 15 shortcuts,
    // customisable). v0.3: rebuildRail() now rebuilds the MX
    // circle strip (collapsed + expanded faces) — same store,
    // same actions, new surface (frame spec §1.3).
    // =================================================================
    private fun rebuildRail() {
        rebuildStrip()
    }

    private fun shortcutActive(id: String): Boolean = when (id) {
        "loop" -> loopOne || loopAll
        "mute" -> muted
        "shuffle" -> shuffle
        "night_mode" -> nightMode
        "mirror" -> mirror
        "vertical_flip" -> vflip
        "repeat_ab" -> ABRepeat.active
        else -> true
    }

    private fun shortcutFor(id: String): Pair<Int, () -> Unit>? = when (id) {
        // v0.3 glyphs follow the recording (frame spec §1.3):
        // rotation = tilted phone, Background Play = headphones,
        // Sleep Timer = stopwatch, Audio Effect = waveform,
        // Customise Items = pencil. "sticky" is MX's Background
        // Play action (background audio), NOT PiP — v0.2 mis-wired
        // it to PiP; the strip now does what its label says (PiP
        // lives on the transport row).
        "screen_rotation" -> MxIcon.ROTATE_PHONE to { cycleOrientation() }
        "playback_speed" -> MxIcon.SPEED to { showSpeedDialog() }
        "sticky" -> MxIcon.HEADPHONES to {
            // MX's Background Play: drop to the home screen while
            // playback continues (the service + the backgrounded
            // flag keep onPause/onStop from stopping it).
            startBackgroundPlay()
            moveTaskToBack(true)
        }
        "loop" -> MxIcon.LOOP to { cycleLoopMode() }
        "mute" -> MxIcon.VOLUME_MUTE to { toggleMute() }
        "shuffle" -> MxIcon.SHUFFLE to {
            shuffle = !shuffle
            showIndicator("Shuffle ${if (shuffle) "on" else "off"}")
            rebuildRail()
        }
        "equalizer" -> MxIcon.EQUALIZER to { openEqualizer() }
        "audioEffect" -> MxIcon.WAVEFORM to { showAudioPanel() }
        "sleep_timer" -> MxIcon.STOPWATCH to { showSleepDialog() }
        "repeat_ab" -> MxIcon.REPEAT_AB to { showABPanel() }
        "night_mode" -> MxIcon.NIGHT to {
            nightMode = !nightMode
            nightOverlay.visibility = if (nightMode) View.VISIBLE else View.GONE
            rebuildRail()
        }
        "customise_items" -> MxIcon.PENCIL to { showRailEditor() }
        "screenshot" -> MxIcon.SCREENSHOT to { takeScreenshot() }
        "mirror" -> MxIcon.MIRROR to {
            mirror = !mirror
            applyVideoTransform()
            rebuildRail()
        }
        "vertical_flip" -> MxIcon.FLIP to {
            vflip = !vflip
            applyVideoTransform()
            rebuildRail()
        }
        else -> null
    }

    private fun cycleOrientation() {
        val modes = arrayOf("auto_land", "land", "rev_land", "auto", "system", "video")
        val labels = arrayOf(
            "Auto rotation (landscape)", "Landscape", "Reverse landscape",
            "Auto rotation", "Use system default", "Use video orientation"
        )
        val idx = modes.indexOf(PSet.orientationMode)
        val next = (idx + 1) % modes.size
        PSet.orientationMode = modes[next]
        applyOrientation()
        showIndicator(labels[next])
    }

    private fun cycleLoopMode() {
        when {
            loopOne -> { loopOne = false; loopAll = true; showIndicator("Loop All") }
            loopAll -> { loopAll = false; showIndicator("Loop off") }
            else -> { loopOne = true; showIndicator("Loop one") }
        }
        rebuildRail()
    }

    private fun toggleMute() {
        val player = mp ?: return
        muted = !muted
        try {
            if (muted) {
                lastVolume = 1f
                player.setVolume(0f, 0f)
            } else {
                player.setVolume(if (engineActive) 0f else 1f, if (engineActive) 0f else 1f)
            }
        } catch (t: Throwable) { /* ignore */ }
        showIndicator(if (muted) "Mute" else "Sound on")
        rebuildRail()
    }

    private fun setSpeed(speed: Float, persist: Boolean) {
        currentSpeed = speed.coerceIn(0.25f, 4.0f)
        val player = mp ?: return
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                val wasPlaying = player.isPlaying
                player.playbackParams = PlaybackParams().setSpeed(currentSpeed)
                // Device quirk (v0.1): setting params while paused can
                // auto-start playback — re-pause in that case.
                if (!wasPlaying && !playWhenReady && player.isPlaying) player.pause()
            }
        } catch (t: Throwable) { /* speed unsupported on this path */ }
        if (engineActive) EngineBridge.setSpeed(engineHandle, currentSpeed)
        if (persist) PSet.defaultSpeedPct = (currentSpeed * 100).toInt()
        // The strip's speed circle renders the rate as text ("1X").
        rebuildRail()
    }

    private fun trimSpeed(s: Float): String =
        if (s == s.toLong().toFloat()) s.toLong().toString()
        else ("%.2f".format(s)).trimEnd('0')

    // =================================================================
    // Subtitles: discovery, loading, rendering (§2.4)
    // =================================================================
    private fun discoverSubtitles(player: MediaPlayer) {
        val sources = mutableListOf(SubSource("None", 0))
        try {
            val infos = player.trackInfo ?: emptyArray()
            infos.forEachIndexed { i, info ->
                if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    val lang = info.getLanguage()?.takeIf { it.isNotEmpty() && it != "und" }
                    sources.add(
                        SubSource(
                            "Embedded" + (lang?.let { ": $it" } ?: " track"),
                            1, trackIndex = i
                        )
                    )
                }
            }
        } catch (t: Throwable) { /* no track info */ }
        // Sidecar files: MX matching via Shadow One's FileOps helper,
        // searched in the video's folder + the configured subtitle
        // folder (§2.8.1).
        val videoFile = File(path)
        val dirs = listOfNotNull(
            videoFile.parentFile,
            PSet.subFolder.takeIf { it.isNotEmpty() }?.let { File(it) }
        )
        val seen = HashSet<String>()
        for (dir in dirs) {
            val names = dir.list()?.toList() ?: continue
            for (name in FileOps.sidecarSubtitles(names, videoFile.name)) {
                val f = File(dir, name)
                if (seen.add(f.absolutePath)) {
                    sources.add(SubSource(name, 2, filePath = f.absolutePath))
                }
            }
        }
        subSources = sources

        var chosen: SubSource? = null
        if (PSet.rememberSelections) {
            val mem = PSet.fileSubtitle(path)
            chosen = sources.firstOrNull { src ->
                (mem == "none" && src.kind == 0) ||
                    (mem.startsWith("embedded:") && src.kind == 1 &&
                        src.trackIndex == mem.removePrefix("embedded:").toIntOrNull()) ||
                    (mem.startsWith("file:") && src.kind == 2 &&
                        src.filePath == mem.removePrefix("file:"))
            }
        }
        if (chosen == null) {
            val lang = PSet.prefSubLang
            chosen = (if (lang.isNotEmpty()) {
                sources.firstOrNull { it.kind == 1 && it.label.contains(lang, true) }
            } else null)
                ?: sources.firstOrNull { it.kind == 2 }
                ?: sources.firstOrNull { it.kind == 1 }
                ?: sources[0]
            // MX: with the HW decoder, embedded subtitles need
            // "Show subtitles with HW decoder" (§2.8.4) to auto-load.
            if (chosen.kind == 1 && decoderMode == 1 && !PSet.subHw) {
                chosen = sources.firstOrNull { it.kind == 2 } ?: sources[0]
            }
        }
        applySubtitleSource(chosen, player)
        applySubtitleStyle()
    }

    private fun applySubtitleSource(src: SubSource, player: MediaPlayer) {
        subCurrent = src
        cues = emptyList()
        subFileBytes = null
        subFileName = null
        try {
            val infos = player.trackInfo ?: emptyArray()
            infos.forEachIndexed { i, info ->
                if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    runCatching { player.deselectTrack(i) }
                }
            }
        } catch (t: Throwable) { /* ignore */ }
        when (src.kind) {
            1 -> runCatching { player.selectTrack(src.trackIndex) }
            2 -> loadSubtitleFile(src.filePath)
        }
        if (PSet.rememberSelections) {
            PSet.setFileSubtitle(
                path, when (src.kind) {
                    0 -> "none"
                    1 -> "embedded:${src.trackIndex}"
                    else -> "file:${src.filePath}"
                }
            )
        }
        subtitleView.visibility = View.GONE
        updateSubtitle()
    }

    private fun loadSubtitleFile(filePath: String) {
        try {
            subFileBytes = File(filePath).readBytes()
            subFileName = File(filePath).name
            decodeAndParseSubtitles()
        } catch (t: Throwable) {
            toast("Could not read the subtitle file.")
        }
    }

    private fun decodeAndParseSubtitles() {
        val bytes = subFileBytes ?: return
        val text = SubtitleFormats.decode(bytes, PSet.subEncoding)
        cues = SubtitleFormats.parseAuto(subFileName ?: "", text)
        updateSubtitle()
    }

    private fun updateSubtitle() {
        if (subCurrent.kind != 2) return
        val pos = currentPos()
        val baseSync = if (decoderMode == 1) PSet.subHwSyncMs else PSet.subDefaultSyncMs
        val t = PlayerLogic.subLookupMs(pos, PSet.subSpeed, baseSync + subSessionOffsetMs)
        val cue = cues.firstOrNull { t >= it.startMs && t < it.endMs }
        if (cue == null) {
            if (subtitleView.visibility == View.VISIBLE) subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = styleCueText(cue.text)
            subtitleView.typeface = currentSubTypeface(
                PSet.subItalicEffect && cue.text.trimStart().startsWith("/")
            )
            if (subtitleView.visibility != View.VISIBLE) subtitleView.visibility = View.VISIBLE
        }
    }

    private fun styleCueText(raw: String): CharSequence {
        var t = raw
        if (PSet.subItalicEffect && t.trimStart().startsWith("/")) {
            t = t.trimStart().removePrefix("/").trimStart()
        }
        // Force LTR (§2.8): wrap with a left-to-right mark so RTL
        // scripts don't reorder Latin cue text.
        return if (PSet.subForceLtr) "\u200E" + t else t
    }

    private var cachedTypeface: Typeface? = null
    private var cachedTypefaceKey: String? = null

    private fun currentSubTypeface(italic: Boolean): Typeface {
        val key = PSet.subFont + "|" + PSet.subFontFolder
        if (cachedTypefaceKey != key) {
            cachedTypefaceKey = key
            cachedTypeface = loadSubTypeface()
        }
        val base = cachedTypeface ?: Typeface.DEFAULT
        var style = if (PSet.subBold) Typeface.BOLD else Typeface.NORMAL
        if (italic) style = style or Typeface.ITALIC
        return Typeface.create(base, style)
    }

    private fun loadSubTypeface(): Typeface? {
        val name = PSet.subFont
        if (name.isEmpty() || name == "Default") return null
        val candidates = listOf(
            File(name),
            File(PSet.subFontFolder, name),
            File(PSet.subFolder, name)
        )
        for (f in candidates) {
            if (f.isFile) {
                try {
                    return Typeface.createFromFile(f)
                } catch (t: Throwable) { /* try next */ }
            }
        }
        return null
    }

    private fun applySubtitleStyle() {
        if (!::subtitleView.isInitialized) return
        subtitleView.textSize = Store.subtitleTextSizeSp * PSet.subScalePct / 100f
        subtitleView.setTextColor(PSet.subColor)
        subtitleView.typeface = currentSubTypeface(false)
        when {
            PSet.subImproveStroke -> subtitleView.setShadowLayer(3.5f, 0f, 0f, Color.BLACK)
            PSet.subBorder && PSet.subShadow ->
                subtitleView.setShadowLayer(2f, 1f, 1f, Color.BLACK)
            PSet.subBorder -> subtitleView.setShadowLayer(1.5f, 0f, 0f, Color.BLACK)
            PSet.subShadow -> subtitleView.setShadowLayer(2f, 1f, 1f, Color.BLACK)
            else -> subtitleView.setShadowLayer(0.1f, 0f, 0f, Color.TRANSPARENT)
        }
        if (PSet.subBg) {
            subtitleView.setBackgroundColor(PSet.subBgColor)
            subtitleView.setPadding(dp(10), dp(3), dp(10), dp(3))
        } else {
            subtitleView.setBackgroundColor(Color.TRANSPARENT)
            subtitleView.setPadding(0, 0, 0, 0)
        }
        subtitleView.textAlignment = when (PSet.subAlign) {
            "left" -> View.TEXT_ALIGNMENT_TEXT_START
            "right" -> View.TEXT_ALIGNMENT_TEXT_END
            else -> View.TEXT_ALIGNMENT_CENTER
        }
        val lp = subtitleView.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            // v0.4 chrome: progress panel (~34dp) + decoded transport
            // (12 + 46 + 15 = 73dp) float over the bottom edge.
            lp.bottomMargin = dp(PSet.subBottomMarginDp) + dp(110)
            subtitleView.layoutParams = lp
        }
    }

    // =================================================================
    // Track choosers
    // =================================================================
    private var selectedAudioTrack = -1

    private fun currentAudioTrackLabel(): String {
        val player = mp ?: return ""
        return try {
            val infos = player.trackInfo ?: return ""
            val info = infos.getOrNull(selectedAudioTrack) ?: return ""
            val lang = info.getLanguage()?.takeIf { it.isNotEmpty() && it != "und" }
            "Track" + (lang?.let { " ($it)" } ?: "")
        } catch (t: Throwable) {
            ""
        }
    }

    /** Top-bar audio button opens the MX Audio Track sheet
     *  (frame spec §3.2). */
    private fun showAudioTrackChooser() = showAudioSheet()

    /** Language display name for a track label ("<name> - <lang>"). */
    private fun langName(code: String?): String? {
        val c = code?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        val name = try {
            java.util.Locale(c).displayLanguage
        } catch (t: Throwable) {
            null
        }
        return name?.takeIf { it.isNotEmpty() } ?: c
    }

    /** Audio Track sheet (frame spec §3.2): radio list (NOT boxed),
     *  a "Disable" radio, the "Use SW audio decoder" checkbox —
     *  which in this build is exactly the HW+ engine-audio mode —
     *  a divider, then Open / Stereo mode / Synchronization rows
     *  with MX's availability dimming (the last two are unavailable
     *  under the HW decoder, per the mined spec §2.3). */
    private fun showAudioSheet() {
        if (engineActive) {
            showEngineAudioSheet()
            return
        }
        val player = mp ?: return
        val infos = try {
            player.trackInfo ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
        val audioIdx = infos.indices.filter {
            infos[it].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
        }
        if (audioIdx.isEmpty()) {
            toast("No audio tracks in this file.")
            return
        }
        openSheet {
            sheetBox.addView(sheetHeader("Audio Track"))
            val effective = if (selectedAudioTrack in audioIdx) selectedAudioTrack
                else audioIdx[0]
            for (i in audioIdx) {
                val lang = langName(infos[i].getLanguage())
                val label = "Track ${audioIdx.indexOf(i) + 1}" +
                    (lang?.let { " - $it" } ?: "")
                sheetBox.addView(
                    sheetRadioRow(label, checked = !audioDisabled && i == effective) {
                        runCatching { player.selectTrack(i) }
                        selectedAudioTrack = i
                        audioDisabled = false
                        if (PSet.rememberSelections) PSet.setFileAudioTrack(path, i)
                        closeSheet()
                    }
                )
            }
            sheetBox.addView(sheetRadioRow("Disable", checked = audioDisabled) {
                audioDisabled = true
                applyAudioDisabled()
                closeSheet()
            })
            addAudioSheetFooter()
        }
    }

    /** Engine-mode audio sheet (§4.4 #85): the rows are built from
     *  the engine's own probe — entries are FFmpeg stream indices,
     *  with a "Default" entry for the engine's best-stream pick —
     *  and switching goes through SwAudioSession.switchAudioStream
     *  (close/reopen inside the session, position/gain/speed/delay
     *  preserved). A failed switch closes the session by contract
     *  → HW fallback. */
    private fun showEngineAudioSheet() {
        val handle = engineHandle
        val filePath = path
        openSheet(tag = "audio") {
            sheetBox.addView(sheetHeader("Audio Track"))
            sheetBox.addView(sheetTextRow("Loading…", enabled = false) {})
        }
        Thread {
            val info = try {
                FFmpegEngine.probe(filePath)
            } catch (t: Throwable) {
                null
            }
            val audioStreams = info?.streams?.filter { it.type == "audio" }
                ?: emptyList()
            handler.post {
                // The session (or sheet) may have changed while probing.
                if (!engineActive || engineHandle != handle ||
                    !sheetVisible || sheetTag != "audio"
                ) {
                    return@post
                }
                if (audioStreams.isEmpty()) {
                    closeSheet()
                    toast("No audio tracks in this file.")
                    return@post
                }
                sheetBox.removeAllViews()
                sheetBox.addView(sheetHeader("Audio Track"))
                val current = EngineBridge.currentStream(handle)
                val streamIndexes = listOf(-1) + audioStreams.map { it.index }
                val labels = listOf("Default") + audioStreams.map { s ->
                    buildString {
                        val t = s.title?.takeIf { it.isNotEmpty() }
                        append(t ?: s.codec)
                        val lang = langName(s.language)
                        if (lang != null) append(" - ").append(lang)
                        if (t != null) append(" (").append(s.codec).append(")")
                    }
                }
                for (k in streamIndexes.indices) {
                    val streamIndex = streamIndexes[k]
                    sheetBox.addView(
                        sheetRadioRow(
                            labels[k],
                            checked = !audioDisabled && streamIndex == current
                        ) {
                            closeSheet()
                            if (streamIndex == EngineBridge.currentStream(handle) &&
                                !audioDisabled
                            ) {
                                return@sheetRadioRow
                            }
                            val pos = currentPos()
                            val wasPlaying = isPlaying()
                            val wasDisabled = audioDisabled
                            if (EngineBridge.switchAudio(handle, streamIndex)) {
                                audioDisabled = false
                                if (wasDisabled) {
                                    // The session kept the zeroed
                                    // "Disable" gain across the switch.
                                    EngineBridge.setGain(handle, boostPct / 100f)
                                }
                                if (PSet.rememberSelections) {
                                    PSet.setFileAudioStream(filePath, streamIndex)
                                }
                                showIndicator(labels[k])
                            } else {
                                fallbackToHwAfterEngineLoss(pos, wasPlaying)
                            }
                        }
                    )
                }
                sheetBox.addView(sheetRadioRow("Disable", checked = audioDisabled) {
                    audioDisabled = true
                    applyAudioDisabled()
                    closeSheet()
                })
                addAudioSheetFooter()
            }
        }.start()
    }

    /** Shared sheet footer: SW-audio checkbox, divider, and the
     *  Open / Stereo mode / Synchronization rows (frame spec §3.2). */
    private fun addAudioSheetFooter() {
        sheetBox.addView(
            sheetCheckRow(
                "Use SW audio decoder",
                checked = decoderMode != 1,
                enabled = EngineBridge.isAvailable()
            ) {
                // Our SW audio decoder IS the FFmpeg engine: checked
                // ⇔ the HW+ mode (engine audio + hardware video).
                closeSheet()
                setDecoderMode(if (decoderMode != 1) 1 else 4)
            }
        )
        sheetBox.addView(sheetDivider())
        sheetBox.addView(sheetTextRow("Open") {
            closeSheet()
            pickExternalAudio()
        })
        val hw = decoderMode == 1
        sheetBox.addView(sheetTextRow("Stereo mode", enabled = !hw) {
            closeSheet()
            showStereoDialog()
        })
        sheetBox.addView(sheetTextRow("Synchronization", enabled = !hw) {
            closeSheet()
            showAudioSyncPanel()
        })
    }

    /** The sheet's "Disable" radio made real: HW path deselects
     *  the audio track; the engine path zeroes the session gain
     *  (restored to the boost level when a track is re-picked). */
    private fun applyAudioDisabled() {
        if (engineActive) {
            EngineBridge.setGain(
                engineHandle, if (audioDisabled) 0f else boostPct / 100f
            )
            return
        }
        val player = mp ?: return
        // The effective track: the explicit choice, else the first
        // audio track (MediaPlayer's implicit default).
        val track = if (selectedAudioTrack >= 0) selectedAudioTrack else try {
            val infos = player.trackInfo ?: emptyArray()
            infos.indices.firstOrNull {
                infos[it].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
            } ?: -1
        } catch (t: Throwable) {
            -1
        }
        if (track < 0) return
        runCatching {
            if (audioDisabled) player.deselectTrack(track) else player.selectTrack(track)
        }
    }

    /** The engine session died (failed track switch): per the
     *  engine contract the only honest recovery is the HW path,
     *  reloaded at the position playback had reached. */
    private fun fallbackToHwAfterEngineLoss(pos: Long, wasPlaying: Boolean) {
        engineHandle = 0L
        decoderMode = 1
        updateDecoderBadge()
        toast("Audio track switch failed — fell back to HW decoder.")
        prepared = false
        loadCurrent(startPosOverride = pos, autoplayOverride = wasPlaying)
    }

    /** Top-bar subtitle button AND Subtitle » Panel both open the
     *  MX sheet now (frame spec §3.1). */
    private fun showSubtitleChooser() = showSubtitleSheet()

    private fun showSubtitlePanel() = showSubtitlePanelBar()

    /** Subtitle sheet (frame spec §3.1): header, "Open" row, then
     *  the track list inside a thin bordered box that runs to the
     *  bottom edge and scrolls. Rows carry MX checkboxes; our
     *  renderer drives ONE active track, so the box behaves as a
     *  single-select with checkbox visuals — tapping the active
     *  track unchecks it back to None. */
    private fun showSubtitleSheet() {
        openSheet { populateSubtitleSheet() }
    }

    private fun populateSubtitleSheet() {
        sheetBox.removeAllViews()
        sheetBox.addView(sheetHeader("Subtitle"))
        val openRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                closeSheet()
                pickSubtitleFile()
            }
        }
        openRow.addView(android.widget.ImageView(this).apply {
            setImageDrawable(MxIcon(MxIcon.FOLDER))
            isClickable = false
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        openRow.addView(TextView(this).apply {
            text = "Open"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), 0, 0, 0)
        })
        sheetBox.addView(openRow, LinearLayout.LayoutParams(MATCH, dp(45)))
        // Bordered track box: 1dp white-35% border, 8dp radius,
        // black-25% fill, extends past the bottom edge (weight 1).
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(64, 0, 0, 0))
                setStroke(dp(1), Color.argb(89, 255, 255, 255))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (src in subSources) {
            rows.addView(sheetCheckRow(src.label, checked = src == subCurrent) {
                val player = mp ?: return@sheetCheckRow
                if (src == subCurrent) applySubtitleSource(subSources[0], player)
                else applySubtitleSource(src, player)
                populateSubtitleSheet()
            })
        }
        scroll.addView(rows)
        box.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        sheetBox.addView(
            box,
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(10) }
        )
    }

    // =================================================================
    // File pickers (subtitle / external audio)
    // =================================================================
    private val REQ_SUBTITLE = 4101
    private val REQ_AUDIO = 4102

    private fun pickSubtitleFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/plain", "application/x-subrip", "text/vtt", "*/*")
            )
        }
        try {
            startActivityForResult(intent, REQ_SUBTITLE)
        } catch (t: Throwable) {
            toast("No file picker available.")
        }
    }

    private fun pickExternalAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            startActivityForResult(intent, REQ_AUDIO)
        } catch (t: Throwable) {
            toast("No file picker available.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_SUBTITLE -> {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("empty")
                    subFileBytes = bytes
                    subFileName = queryDisplayName(uri) ?: "subtitle"
                    val src = SubSource(subFileName ?: "Subtitle", 2, filePath = "")
                    subCurrent = src
                    // Register it so the Subtitle sheet lists the
                    // track the user just opened (v0.3 sheet).
                    if (subSources.none { it.kind == 2 && it.label == src.label }) {
                        subSources = subSources + src
                    }
                    cues = emptyList()
                    decodeAndParseSubtitles()
                    toast("Subtitle loaded: ${cues.size} cues")
                } catch (t: Throwable) {
                    toast("Could not load that subtitle file.")
                }
            }
            REQ_AUDIO -> startExternalAudio(uri)
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ---- External audio (Audio » Open): a second player slaved to
    // the video clock, MX-style. Play/pause state and drift are
    // reconciled in updateProgress. ----
    private var extAudio: MediaPlayer? = null
    private var extAudioActive = false

    private fun startExternalAudio(uri: android.net.Uri) {
        releaseExternalAudio()
        try {
            val ap = MediaPlayer()
            ap.setDataSource(this, uri)
            ap.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            ap.prepare()
            extAudio = ap
            extAudioActive = true
            // Video's own audio goes silent; the external track rules.
            if (!engineActive) runCatching { mp?.setVolume(0f, 0f) }
            if (isPlaying()) ap.start()
            toast("External audio track loaded")
        } catch (t: Throwable) {
            toast("Could not load that audio file.")
        }
    }

    private fun syncExternalAudio() {
        val ap = extAudio ?: return
        if (!extAudioActive || !prepared) return
        try {
            if (playWhenReady && !ap.isPlaying) ap.start()
            if (!playWhenReady && ap.isPlaying) ap.pause()
            val videoPos = currentPos()
            val drift = ap.currentPosition - videoPos
            if (abs(drift) > 300) ap.seekTo(videoPos.toInt())
        } catch (t: Throwable) { /* mid-transition */ }
    }

    private fun releaseExternalAudio() {
        extAudioActive = false
        try {
            extAudio?.stop()
        } catch (t: Throwable) { /* ignore */ }
        extAudio?.release()
        extAudio = null
    }

    // =================================================================
    // Panels: subtitle sync/speed/settings, audio, display, rail
    // =================================================================
    private fun stepPanelDialog(
        title: String,
        valueText: () -> String,
        steps: List<Pair<String, () -> Unit>>
    ) {
        val value = TextView(this).apply {
            text = valueText()
            gravity = Gravity.CENTER
            setTextColor(TITLE_C)
            textSize = 17f
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        for ((label, action) in steps) {
            val b = android.widget.Button(this).apply {
                text = label
                setOnClickListener {
                    action()
                    value.text = valueText()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(value)
            addView(row)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showSubtitleSyncPanel() {
        stepPanelDialog(
            "Subtitle synchronization",
            { "Offset ${fmtSignedMs(effectiveSubSyncMs())}  (this session)" },
            listOf(
                "−1s" to { subSessionOffsetMs -= 1000; updateSubtitle() },
                "−0.5s" to { subSessionOffsetMs -= 500; updateSubtitle() },
                "Reset" to { subSessionOffsetMs = 0; updateSubtitle() },
                "+0.5s" to { subSessionOffsetMs += 500; updateSubtitle() },
                "+1s" to { subSessionOffsetMs += 1000; updateSubtitle() }
            )
        )
    }

    private fun effectiveSubSyncMs(): Long =
        (if (decoderMode == 1) PSet.subHwSyncMs else PSet.subDefaultSyncMs) + subSessionOffsetMs

    private fun showSubtitleSpeedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { "${trimSpeed(it)}×" }.toTypedArray()
        val current = speeds.indexOfFirst { it == PSet.subSpeed }.coerceAtLeast(2)
        AlertDialog.Builder(this)
            .setTitle("Subtitle speed")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PSet.subSpeed = speeds[which]
                updateSubtitle()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleSettingsPanel() {
        val items = arrayOf(
            "Encoding: ${PSet.subEncoding}",
            "Text size: ${Store.subtitleTextSizeSp.toInt()}sp",
            "Scale: ${PSet.subScalePct}%",
            "Text colour",
            "Bold: ${if (PSet.subBold) "on" else "off"}",
            "Background: ${if (PSet.subBg) "on" else "off"}",
            "Alignment: ${PSet.subAlign}",
            "Font: ${PSet.subFont}"
        )
        AlertDialog.Builder(this)
            .setTitle("Subtitle Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEncodingChooser()
                    1 -> showSubSizeDialog()
                    2 -> showSubScaleDialog()
                    3 -> showSubColorDialog()
                    4 -> {
                        PSet.subBold = !PSet.subBold
                        applySubtitleStyle()
                    }
                    5 -> {
                        PSet.subBg = !PSet.subBg
                        applySubtitleStyle()
                    }
                    6 -> showSubAlignDialog()
                    7 -> showSubFontDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEncodingChooser() {
        val labels = SubtitleFormats.ENCODINGS.toTypedArray()
        val current = SubtitleFormats.ENCODINGS.indexOf(PSet.subEncoding).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Encoding")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PSet.subEncoding = SubtitleFormats.ENCODINGS[which]
                decodeAndParseSubtitles()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubSizeDialog() {
        val seek = SeekBar(this).apply {
            max = 22 // 10..32sp
            progress = (Store.subtitleTextSizeSp - 10).toInt().coerceIn(0, 22)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitle text size")
            .setView(seek)
            .setPositiveButton("OK") { _, _ ->
                Store.subtitleTextSizeSp = (seek.progress + 10).toFloat()
                applySubtitleStyle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubScaleDialog() {
        val seek = SeekBar(this).apply {
            max = 150 // 50..200%
            progress = (PSet.subScalePct - 50).coerceIn(0, 150)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitle scale")
            .setView(seek)
            .setPositiveButton("OK") { _, _ ->
                PSet.subScalePct = seek.progress + 50
                applySubtitleStyle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubColorDialog() {
        val labels = Settings.COLOR_CHOICES.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Subtitle text colour")
            .setItems(labels) { _, which ->
                PSet.subColor = Settings.COLOR_CHOICES[which].second
                applySubtitleStyle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubAlignDialog() {
        val labels = arrayOf("Left", "Centre", "Right")
        val values = arrayOf("left", "center", "right")
        AlertDialog.Builder(this)
            .setTitle("Subtitle alignment")
            .setSingleChoiceItems(labels, values.indexOf(PSet.subAlign).coerceAtLeast(1)) { d, w ->
                PSet.subAlign = values[w]
                applySubtitleStyle()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubFontDialog() {
        val folder = PSet.subFontFolder
        val fonts = if (folder.isNotEmpty()) {
            File(folder).listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in listOf("ttf", "otf") }
                ?.map { it.name } ?: emptyList()
        } else {
            emptyList()
        }
        val labels = (listOf("Default") + fonts).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Subtitle font")
            .setItems(labels) { _, which ->
                PSet.subFont = labels[which]
                cachedTypefaceKey = null
                applySubtitleStyle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAudioPanel() {
        val boostState = when {
            !PSet.volumeBoostGate -> "off (enable in Settings » Audio)"
            !engineActive -> "needs HW+/SW decoder"
            else -> "$boostPct%"
        }
        val items = arrayOf(
            "Volume boost: $boostState",
            "Synchronization…",
            "Stereo mode: ${stereoLabel()}",
            "Equalizer"
        )
        AlertDialog.Builder(this)
            .setTitle("Audio Effect")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showBoostDialog()
                    1 -> showAudioSyncPanel()
                    2 -> if (decoderMode != 1) showStereoDialog()
                        else toast("Stereo mode is not available with the HW decoder.")
                    3 -> openEqualizer()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBoostDialog() {
        if (!PSet.volumeBoostGate) {
            toast("Enable \"Volume boost\" in Settings » Audio first.")
            return
        }
        if (!engineActive) {
            toast("Volume boost over 100% needs the HW+/SW engine decoder.")
            return
        }
        val seek = SeekBar(this).apply {
            max = 100 // 100..200%
            progress = boostPct - 100
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val label = TextView(this).apply {
            text = "$boostPct%"
            gravity = Gravity.CENTER
            setTextColor(TITLE_C)
            textSize = 16f
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "${p + 100}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(seek)
        }
        AlertDialog.Builder(this)
            .setTitle("Volume boost")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                boostPct = seek.progress + 100
                EngineBridge.setGain(engineHandle, boostPct / 100f)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAudioSyncPanel() {
        stepPanelDialog(
            "Audio synchronization",
            {
                val total = PSet.audioDelayMs +
                    (if (isBluetoothAudio()) PSet.btDelayMs else 0L)
                "Delay ${fmtSignedMs(total)}" +
                    (if (isBluetoothAudio()) " (incl. Bluetooth)" else "")
            },
            listOf(
                "−0.5s" to { adjustAudioDelay(-500) },
                "−0.1s" to { adjustAudioDelay(-100) },
                "Reset" to {
                    PSet.audioDelayMs = 0
                    applyAudioDelay()
                },
                "+0.1s" to { adjustAudioDelay(100) },
                "+0.5s" to { adjustAudioDelay(500) }
            )
        )
    }

    private fun adjustAudioDelay(deltaMs: Long) {
        PSet.audioDelayMs = (PSet.audioDelayMs + deltaMs).coerceIn(-5_000L, 5_000L)
        applyAudioDelay()
    }

    private fun applyAudioDelay() {
        if (engineActive) {
            val total = PSet.audioDelayMs +
                (if (isBluetoothAudio()) PSet.btDelayMs else 0L)
            EngineBridge.setAudioDelay(engineHandle, total)
        }
    }

    private fun openEqualizer() {
        if (!PSet.equalizerGate) {
            toast("Enable \"Audio equalizer\" in Settings » Audio first.")
            return
        }
        val player = mp ?: return
        val intent = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .apply {
                putExtra(
                    android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION,
                    player.audioSessionId
                )
                putExtra(
                    android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME,
                    packageName
                )
                putExtra(
                    android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE,
                    android.media.audiofx.AudioEffect.CONTENT_TYPE_MOVIE
                )
            }
        try {
            startActivityForResult(intent, 0)
        } catch (t: Throwable) {
            toast("No system equalizer is available on this device.")
        }
    }

    private fun showDisplaySettingsPanel() {
        val hideLabels = arrayOf("2s", "3.5s", "5s", "8s")
        val hideValues = longArrayOf(2000, 3500, 5000, 8000)
        // v0.3: the "Battery & clock" row is gone — the frame spec
        // (§8.1) removes battery/clock from the player chrome, so a
        // player-side toggle for it would switch nothing.
        val items = arrayOf(
            "Elapsed time: ${if (PSet.elapsedTime) "on" else "off"}",
            "Show remaining time: ${if (PSet.showRemaining) "on" else "off"}",
            "Keep screen on: ${if (PSet.keepScreenOn) "on" else "off"}",
            "Rotation button: ${if (PSet.rotationButton) "on" else "off"}",
            "Auto-hide interface: ${hideLabels[hideValues.indexOf(PSet.autoHideMs).coerceAtLeast(1)]}",
            "Full screen: ${PSet.fullScreen}",
            "Orientation…"
        )
        AlertDialog.Builder(this)
            .setTitle("Display Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { PSet.elapsedTime = !PSet.elapsedTime; updateProgress() }
                    1 -> { PSet.showRemaining = !PSet.showRemaining; updateProgress() }
                    2 -> { PSet.keepScreenOn = !PSet.keepScreenOn; keepScreenOnIfNeeded() }
                    3 -> {
                        PSet.rotationButton = !PSet.rotationButton
                        syncRotationShortcut()
                    }
                    4 -> {
                        val idx = hideValues.indexOf(PSet.autoHideMs)
                        PSet.autoHideMs = hideValues[(idx + 1) % hideValues.size]
                        keepControls()
                    }
                    5 -> {
                        PSet.fullScreen = when (PSet.fullScreen) {
                            "auto" -> "on"
                            "on" -> "off"
                            else -> "auto"
                        }
                        applyFullscreen()
                    }
                    6 -> cycleOrientation()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRailEditor() {
        val all = Settings.SHORTCUTS
        val enabled = PSet.railShortcuts.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val checked = BooleanArray(all.size) { enabled.contains(all[it].first) }
        AlertDialog.Builder(this)
            .setTitle("Customise Items")
            .setMultiChoiceItems(
                all.map { it.second }.toTypedArray(), checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                val ids = all.filterIndexed { i, _ -> checked[i] }.map { it.first }
                PSet.railShortcuts = ids.joinToString(",")
                rebuildRail()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =================================================================
    // Properties (§2.9) — groups from Shadow One's FileOps, plus the
    // FFmpeg probe as the Media group when the engine is present.
    // =================================================================
    private fun currentVideoItem(): VideoItem = VideoItem(
        id = 0,
        title = title,
        path = path,
        durationMs = durationMs.toLong(),
        sizeBytes = File(path).length(),
        folder = File(path).parent ?: "",
        dateAddedSec = 0L,
        width = videoW,
        height = videoH
    )

    private fun showProperties() {
        val item = currentVideoItem()
        val groups = FileOps.propertiesGroups(this, item).toMutableList()
        fun render() {
            val body = StringBuilder()
            for ((groupName, rows) in groups) {
                body.append(groupName).append('\n')
                for ((k, v) in rows) body.append("  ").append(k).append(": ").append(v).append('\n')
                body.append('\n')
            }
            body.append("Playback\n")
            body.append("  Decoder: ").append(PlayerLogic.decoderLabel(decoderMode)).append('\n')
            body.append("  Resume position: ").append(Fmt.ms(Store.resumePos(path))).append('\n')
            body.append("  Finished: ").append(if (Store.isFinished(path)) "yes" else "no").append('\n')
            val last = PSet.fileLastPlayedAt(path)
            if (last > 0) {
                body.append("  Last played: ")
                    .append(Fmt.clock(last)).append('\n')
            }
            val scroll = ScrollView(this).apply {
                addView(TextView(this@PlayerActivity).apply {
                    text = body.toString()
                    setTextColor(TITLE_C)
                    textSize = 13.5f
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    setTextIsSelectable(true)
                })
            }
            AlertDialog.Builder(this)
                .setTitle("Properties")
                .setView(scroll)
                .setPositiveButton("OK", null)
                .show()
        }
        if (EngineBridge.isAvailable()) {
            Thread {
                val info = try {
                    FFmpegEngine.probe(path)
                } catch (t: Throwable) {
                    null
                }
                if (info != null) {
                    val rows = mutableListOf<Pair<String, String>>()
                    rows.add("Format" to info.format)
                    if (info.durationMs > 0) rows.add("Duration" to Fmt.ms(info.durationMs))
                    if (info.bitrate > 0) rows.add("Bitrate" to "${info.bitrate / 1000} kbps")
                    for (s in info.streams) {
                        val label = when (s.type) {
                            "video" -> "Video"
                            "audio" -> "Audio"
                            "subtitle" -> "Subtitle"
                            else -> s.type
                        }
                        val detail = StringBuilder(s.codec)
                        if (s.width > 0) detail.append(" ${s.width}×${s.height}")
                        if (s.sampleRate > 0) detail.append(" ${s.sampleRate} Hz, ${s.channels} ch")
                        if (s.bitrate > 0) detail.append(", ${s.bitrate / 1000} kbps")
                        s.language?.let { detail.append(" [$it]") }
                        rows.add(label to detail.toString())
                    }
                    groups.add("Media (FFmpeg)" to rows)
                }
                runOnUiThread { render() }
            }.start()
        } else {
            render()
        }
    }

    // =================================================================
    // PiP + background play (§2.7.20–23)
    // =================================================================
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Picture-in-Picture needs Android 8 or newer.")
            return
        }
        try {
            val ratio = if (videoW > 0 && videoH > 0) {
                android.util.Rational(videoW, videoH)
            } else {
                android.util.Rational(16, 9)
            }
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .build()
            if (!enterPictureInPictureMode(params)) {
                toast("Picture-in-Picture is not available.")
            }
        } catch (t: Throwable) {
            // The manifest flag is Shadow One's surface; without it
            // the system refuses — say so, don't crash.
            toast("Picture-in-Picture is not available in this build.")
        }
    }

    private val NOTIF_ID = 77
    private val NOTIF_CHANNEL = "gbp_background"

    private fun startBackgroundPlay() {
        backgrounded = true
        if (PlaybackService.isDeclared(this)) {
            // Real path (MX PlayService model): the foreground
            // service keeps the process alive and owns the
            // notification + MediaSession; this activity keeps
            // owning playback and registers the transport it drives.
            if (Build.VERSION.SDK_INT >= 33) {
                // FGS notifications are hidden on API 33+ without the
                // runtime grant; if the permission is not in the
                // manifest yet this request simply comes back denied
                // and the service still runs (task-manager surface).
                runCatching {
                    requestPermissions(
                        arrayOf("android.permission.POST_NOTIFICATIONS"), 77
                    )
                }
            }
            PlaybackService.start(this)
            showIndicator("Background play")
            return
        }
        // Fallback while the manifest entry is pending (see
        // PLAYER-SERVICE-NEEDS.md): in-activity continuation + the
        // plain notification, exactly as v0.2 shipped. Process death
        // still ends playback in this path — stated, not hidden.
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        NOTIF_CHANNEL, "Background playback",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val open = Intent(this, PlayerActivity::class.java).apply {
                putExtra("path", path)
                putExtra("title", title)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = android.app.PendingIntent.getActivity(
                this, 0, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(this, NOTIF_CHANNEL)
            } else {
                android.app.Notification.Builder(this)
            }
            val notif = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText("Playing in background")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        } catch (t: Throwable) { /* notification is best-effort */ }
        showIndicator("Background play")
    }

    private fun cancelNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (t: Throwable) { /* ignore */ }
    }

    // =================================================================
    // Screenshot (§2.2 rail)
    // =================================================================
    private fun takeScreenshot() {
        // §S1 note: this path NEVER used TextureView.getBitmap — it
        // re-decodes the frame at the current position with
        // MediaMetadataRetriever, so it is surface-independent and
        // works unchanged on every path (HW / HW+ / SW) after the
        // SurfaceView swap. No PixelCopy gate needed.
        val pos = currentPos()
        Thread {
            var saved: String? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val frame = retriever.getFrameAtTime(
                    pos * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                retriever.release()
                if (frame != null) {
                    val name = "GBP_${System.currentTimeMillis()}.png"
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/GhostBladePlayer"
                            )
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                        )
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            }
                            saved = "Pictures/GhostBladePlayer/$name"
                        }
                    } else {
                        val dir = File(
                            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                            "GhostBladePlayer"
                        ).apply { mkdirs() }
                        val out = File(dir, name)
                        out.outputStream().use {
                            frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        saved = out.absolutePath
                    }
                }
            } catch (t: Throwable) { /* reported below */ }
            val result = saved
            runOnUiThread {
                if (result != null) toast("Screenshot saved: $result")
                else toast("Screenshot failed.")
            }
        }.start()
    }

    // =================================================================
    // Tools: share / delete (recycle bin) / rename / add to playlist
    // =================================================================
    private fun shareCurrent() {
        try {
            val uri = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA}=?",
                arrayOf(path), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                    )
                } else {
                    null
                }
            }
            if (uri == null) {
                toast("Could not share this file.")
                return
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share"))
        } catch (t: Throwable) {
            toast("Could not share this file.")
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Move \"${File(path).name}\" to the recycle bin?")
            .setPositiveButton("Delete") { _, _ ->
                val entry = try {
                    FileOps.moveToRecycle(
                        this, currentVideoItem(),
                        Store.boolSetting(Settings.DELETE_SUBTITLE_TOGETHER)
                    )
                } catch (t: Throwable) {
                    null
                }
                if (entry == null) {
                    toast("Delete failed.")
                } else {
                    toast("Moved to recycle bin")
                    val remaining = playlist.filter { it != path }
                    if (remaining.isEmpty()) {
                        releaseAll()
                        finish()
                    } else {
                        playlist = ArrayList(remaining)
                        index = index.coerceIn(0, playlist.size - 1)
                        path = playlist[index]
                        title = File(path).name
                        decoderMode = resolveDecoder(-1)
                        updateDecoderBadge()
                        prepared = false
                        loadCurrent()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog() {
        if (!Store.boolSetting(Settings.ALLOW_EDITS)) {
            toast("Editing is disabled in Settings » General.")
            return
        }
        val input = android.widget.EditText(this).apply {
            setText(File(path).nameWithoutExtension)
            setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val err = FileOps.renameError(newName)
                if (err != null) {
                    toast(err)
                    return@setPositiveButton
                }
                val pos = currentPos()
                val wasPlaying = isPlaying()
                val newPath = try {
                    FileOps.renameVideo(this, currentVideoItem(), newName)
                } catch (t: Throwable) {
                    null
                }
                if (newPath == null) {
                    toast("Rename failed.")
                } else {
                    val oldPath = path
                    path = newPath
                    title = File(newPath).name
                    val idxInList = playlist.indexOf(oldPath)
                    if (idxInList >= 0) playlist[idxInList] = newPath
                    titleView.text = title
                    prepared = false
                    loadCurrent(startPosOverride = pos, autoplayOverride = wasPlaying)
                    toast("Renamed")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** PSet serialisation: lines of "name \t path1 | path2 | …". */
    private fun parseCustomPlaylists(): MutableList<Pair<String, MutableList<String>>> {
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in PSet.customPlaylists.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("\t", limit = 2)
            val name = parts[0]
            val paths = if (parts.size > 1 && parts[1].isNotBlank()) {
                parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            } else {
                mutableListOf()
            }
            out.add(name to paths)
        }
        return out
    }

    private fun saveCustomPlaylists(lists: List<Pair<String, List<String>>>) {
        PSet.customPlaylists = lists.joinToString("\n") { (name, paths) ->
            name + "\t" + paths.joinToString(" | ")
        }
    }

    private fun showAddToPlaylistDialog() {
        val lists = parseCustomPlaylists()
        val labels = (lists.map { it.first } + "New playlist…").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to Playlist")
            .setItems(labels) { _, which ->
                if (which == lists.size) {
                    showNewPlaylistDialog()
                } else {
                    val (name, paths) = lists[which]
                    val added = if (path in paths) 0 else {
                        paths.add(path)
                        1
                    }
                    saveCustomPlaylists(lists)
                    toast("$added video(s) added to the playlist '$name'")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewPlaylistDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Playlist name"
            setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val lists = parseCustomPlaylists()
                if (lists.any { it.first.equals(name, ignoreCase = true) }) {
                    toast("A playlist with that name already exists.")
                    return@setPositiveButton
                }
                lists.add(name to mutableListOf(path))
                saveCustomPlaylists(lists)
                toast("1 video(s) added to the playlist '$name'")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =================================================================
    // Playlist drawer (§2.10)
    // =================================================================
    private fun togglePlaylistDrawer() {
        if (drawerPanel.visibility == View.VISIBLE) {
            drawerPanel.visibility = View.GONE
        } else {
            populatePlaylistDrawer()
            drawerPanel.visibility = View.VISIBLE
            keepControls()
        }
    }

    private fun populatePlaylistDrawer() {
        drawerList.removeAllViews()
        playlist.forEachIndexed { i, p ->
            val row = TextView(this).apply {
                text = (if (i == index) "▶  " else "     ") + File(p).name
                setTextColor(if (i == index) ACCENT else TITLE_C)
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(16), dp(11), dp(12), dp(11))
                setOnClickListener {
                    drawerPanel.visibility = View.GONE
                    if (i != index) playAt(i)
                }
            }
            drawerList.addView(row)
        }
    }

    // ---- Headset unplug → pause (§2.8 "Pause on headset
    // disconnected"), via the becoming-noisy broadcast. ----
    private var noisyReceiver: BroadcastReceiver? = null

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                    PSet.pauseHeadsetOff && isPlaying()
                ) {
                    pausePlayback()
                }
            }
        }
        noisyReceiver = receiver
        registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun unregisterNoisyReceiver() {
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        noisyReceiver = null
    }
}
