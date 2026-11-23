package com.ghostblade.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One settings node. Screens are pure data (see
 *  [SettingsActivity.nodesFor]); the activity renders any screen
 *  from its node list, so every MX node exists exactly once with
 *  MX's label, default and options (spec §2.6–§2.8). */
sealed class SNode {
    data class Category(val title: String) : SNode()
    data class ScreenLink(val title: String, val summary: String, val target: String) : SNode()
    data class Check(val key: String, val title: String, val summary: String = "") : SNode()
    data class ListPref(
        val key: String, val title: String,
        val entries: List<String>, val values: List<String>
    ) : SNode()
    data class MultiPref(
        val key: String, val title: String,
        val entries: List<String>, val values: List<String>,
        val allWhenEmpty: Boolean = false
    ) : SNode()
    data class IntPref(val key: String, val title: String, val unit: String = "") : SNode()
    data class FloatPref(val key: String, val title: String, val unit: String = "sec") : SNode()
    data class PercentPref(val key: String, val title: String) : SNode()
    data class SeekPref(
        val key: String, val title: String, val min: Int, val max: Int, val unit: String
    ) : SNode()
    data class ColorPref(val key: String, val title: String) : SNode()
    data class LocalePref(val key: String, val title: String) : SNode()
    data class FolderPref(val key: String, val title: String) : SNode()
    data class Action(val id: String, val title: String, val summary: String = "") : SNode()
}

/** All six MX settings screens (List / Player / Decoder / Audio /
 *  Subtitle / General) plus their sub-screens, rendered from data.
 *
 *  Values persist in [Store] under MX's preference keys. Keys the
 *  player consumes are ALSO bridged to Shadow Two's [PSet]
 *  (SharedPreferences) — see [bridge] — so a change here genuinely
 *  changes playback; the bridge table is documented in
 *  SETTINGS-KEYS.md. */
class SettingsActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private var screenStack = mutableListOf("root")
    private lateinit var titleView: TextView
    private lateinit var listContainer: LinearLayout
    private var scanDirs: List<String> = emptyList()
    private var pendingFolderKey: String? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        val start = intent.getStringExtra("screen") ?: "root"
        screenStack = mutableListOf(start)
        buildChrome()
        showScreen(start)
    }

    /* ================= value access + PSet bridge ================= */

    /** Reads the effective value for [key]: PSet for bridged
     *  (player-consumed) keys, Store otherwise. */
    private fun getValue(key: String): String {
        if (key == KEY_SUB_SIZE_SP) return Store.subtitleTextSizeSp.toInt().toString()
        if (key.startsWith(SCAN_FOLDER_PREFIX)) {
            val dir = key.removePrefix(SCAN_FOLDER_PREFIX)
            val set = Store.stringSetSetting(Settings.SCAN_FOLDERS)
            return (set.isEmpty() || set.contains(dir)).toString()
        }
        bridge[key]?.let { return it.first() }
        return Store.rawSetting(key)
    }

    /** Writes [value] to Store (MX key) and, for bridged keys, to
     *  PSet so the player honours it immediately. */
    private fun setValue(key: String, value: String) {
        if (key == KEY_SUB_SIZE_SP) {
            Store.subtitleTextSizeSp = value.toFloatOrNull() ?: 18f
            return
        }
        if (key.startsWith(SCAN_FOLDER_PREFIX)) {
            setScanFolder(key.removePrefix(SCAN_FOLDER_PREFIX), value == "true")
            return
        }
        Store.setRawSetting(key, value)
        bridge[key]?.second?.invoke(value)
    }

    private fun setScanFolder(dir: String, included: Boolean) {
        var set = Store.stringSetSetting(Settings.SCAN_FOLDERS)
        if (set.isEmpty()) set = scanDirs.toSet() // empty means "all"
        set = if (included) set + dir else set - dir
        Store.setStringSetSetting(
            Settings.SCAN_FOLDERS,
            if (set == scanDirs.toSet()) emptySet() else set
        )
    }

    /** MX key → (PSet read, PSet write). Types are converted at the
     *  boundary (sec↔ms, %↔fraction, int↔string enums). */
    private val bridge: Map<String, Pair<() -> String, (String) -> Unit>> by lazy {
        fun bool(get: () -> Boolean, set: (Boolean) -> Unit) =
            Pair<() -> String, (String) -> Unit>({ get().toString() }, { set(it == "true") })
        fun int(get: () -> Int, set: (Int) -> Unit) =
            Pair<() -> String, (String) -> Unit>({ get().toString() }, { set(it.toIntOrNull() ?: 0) })
        fun str(get: () -> String, set: (String) -> Unit) =
            Pair<() -> String, (String) -> Unit>({ get() }, { set(it) })
        fun secToMs(get: () -> Long, set: (Long) -> Unit) =
            Pair<() -> String, (String) -> Unit>(
                { (get() / 1000.0).toString() },
                { set(((it.toFloatOrNull() ?: 0f) * 1000).toLong()) })
        fun enumInt(values: List<Int>, get: () -> Int, set: (Int) -> Unit,
                    storeValues: List<String>) =
            Pair<() -> String, (String) -> Unit>(
                { storeValues.getOrElse(values.indexOf(get())) { storeValues[0] } },
                { v -> set(values.getOrElse(storeValues.indexOf(v)) { values[0] }) })
        mapOf(
            Settings.RESUME to str({ PSet.resumeMode }, { PSet.resumeMode = it }),
            Settings.RESUME_ONLY_FIRST to bool({ PSet.resumeFirstOnly }, { PSet.resumeFirstOnly = it }),
            Settings.DEFAULT_PLAYBACK_SPEED to int({ PSet.defaultSpeedPct }, { PSet.defaultSpeedPct = it }),
            Settings.REMEMBER_SELECTIONS to bool({ PSet.rememberSelections }, { PSet.rememberSelections = it }),
            Settings.BACK_TO_LIST to bool({ PSet.backToList }, { PSet.backToList = it }),
            Settings.FAST_SEEK to bool({ PSet.fastSeeking }, { PSet.fastSeeking = it }),
            Settings.SUPPRESS_ERROR_MESSAGE to bool({ PSet.suppressErrors }, { PSet.suppressErrors = it }),
            Settings.DOUBLE_TAP_BACK to bool({ PSet.doubleTapBackExit }, { PSet.doubleTapBackExit = it }),
            Settings.QUICK_ZOOM to bool({ PSet.quickZoom }, { PSet.quickZoom = it }),
            Settings.SMART_PREV to bool({ PSet.smartPrev }, { PSet.smartPrev = it }),
            Settings.MEDIA_BUTTONS to bool({ PSet.mediaButtons }, { PSet.mediaButtons = it }),
            Settings.AUDIO_FOCUS to bool({ PSet.playAlone }, { PSet.playAlone = it }),
            Settings.SEEK_PREVIEWS to bool({ PSet.previewWhileSeeking }, { PSet.previewWhileSeeking = it }),
            Settings.LOADING_CIRCLE_ANIMATION to bool({ PSet.loadingAnimation }, { PSet.loadingAnimation = it }),
            Settings.STICKY_VIDEO to str({ PSet.bgMode }, { PSet.bgMode = it }),
            Settings.STICKY_AUDIO to bool({ PSet.bgAudio }, { PSet.bgAudio = it }),
            Settings.PLAYBACK_THEME to str({ PSet.playbackTheme }, { PSet.playbackTheme = it }),
            Settings.SCREEN_ORIENTATION to enumInt(
                listOf(0, 1, 2, 3, 4, 5),
                { ORIENTATION_PSET.indexOf(PSet.orientationMode).coerceAtLeast(0) },
                { PSet.orientationMode = ORIENTATION_PSET.getOrElse(it) { "auto_land" } },
                listOf("0", "1", "2", "3", "4", "5")),
            Settings.SCREEN_FULLSCREEN to enumInt(
                listOf(0, 1, 2),
                { FULLSCREEN_PSET.indexOf(PSet.fullScreen).coerceAtLeast(0) },
                { PSet.fullScreen = FULLSCREEN_PSET.getOrElse(it) { "auto" } },
                listOf("0", "1", "2")),
            Settings.SCREEN_ELAPSED_TIME to bool({ PSet.elapsedTime }, { PSet.elapsedTime = it }),
            Settings.SCREEN_BATTERY_CLOCK to bool({ PSet.batteryClock }, { PSet.batteryClock = it }),
            Settings.SCREEN_SHOW_REMAINING_TIME to bool({ PSet.showRemaining }, { PSet.showRemaining = it }),
            Settings.SCREEN_KEEP_SCREEN_ON to bool({ PSet.keepScreenOn }, { PSet.keepScreenOn = it }),
            Settings.SCREEN_ROTATION_BUTTON to bool({ PSet.rotationButton }, { PSet.rotationButton = it }),
            Settings.CONTROLS_INTERFACE_AUTO_HIDE_DELAY to
                Pair<() -> String, (String) -> Unit>(
                    { (PSet.autoHideMs / 1000).toString() },
                    { PSet.autoHideMs = (it.toLongOrNull() ?: 3L) * 1000L }),
            Settings.GESTURE_SEEK_POSITION to bool({ PSet.gSeek }, { PSet.gSeek = it }),
            Settings.GESTURE_ZOOM_AND_PAN to bool({ PSet.gZoomPan }, { PSet.gZoomPan = it }),
            Settings.GESTURE_VIDEO_ZOOM to bool({ PSet.gVideoZoom }, { PSet.gVideoZoom = it }),
            Settings.GESTURE_VIDEO_PAN to bool({ PSet.gVideoPan }, { PSet.gVideoPan = it }),
            Settings.GESTURE_VOLUME to bool({ PSet.gVolume }, { PSet.gVolume = it }),
            Settings.GESTURE_BRIGHTNESS to bool({ PSet.gBrightness }, { PSet.gBrightness = it }),
            Settings.GESTURE_DOUBLE_TAP_PLAY_PAUSE to bool({ PSet.gDblTapPlayPause }, { PSet.gDblTapPlayPause = it }),
            Settings.GESTURE_DOUBLE_TAP_FFRW to bool({ PSet.gDblTapSeek }, { PSet.gDblTapSeek = it }),
            Settings.GESTURE_LONG_PRESS_SPEED_FF to bool({ PSet.gLongPressFF }, { PSet.gLongPressFF = it }),
            Settings.GESTURE_PLAYBACK_SPEED to bool({ PSet.gTwoFingerSpeed }, { PSet.gTwoFingerSpeed = it }),
            Settings.GESTURE_SUBTITLE_SCROLL to bool({ PSet.gSubScroll }, { PSet.gSubScroll = it }),
            Settings.GESTURE_SUBTITLE_UP_DOWN to bool({ PSet.gSubUpDown }, { PSet.gSubUpDown = it }),
            Settings.GESTURE_SUBTITLE_ZOOM to bool({ PSet.gSubZoom }, { PSet.gSubZoom = it }),
            Settings.SCREEN_LOCK_MODE to int({ PSet.lockMode }, { PSet.lockMode = it }),
            Settings.CONTROLS_LOCK_MODE to int({ PSet.lockMode }, { PSet.lockMode = it }),
            Settings.CONTROLS_SHOW_INTERFACE_WHEN_LOCKED to bool({ PSet.showLockedUi }, { PSet.showLockedUi = it }),
            Settings.CONTROLS_LOCK_TARGETS to Pair<() -> String, (String) -> Unit>(
                { if (PSet.lockTarget == "rotation") "[\"Screen Rotation\"]" else "[\"Input\"]" },
                { v ->
                    val set = Store.stringSetSetting(Settings.CONTROLS_LOCK_TARGETS)
                    PSet.lockTarget =
                        if (set.contains("Screen Rotation") && !set.contains("Input")) "rotation" else "input"
                }),
            Settings.NAV_SEEK_SPEED to Pair<() -> String, (String) -> Unit>(
                { (PSet.seekSpeed * 100).toInt().toString() },
                { PSet.seekSpeed = (it.toFloatOrNull() ?: 100f) / 100f }),
            Settings.NAV_FORWARD_BACKWARD_BUTTON to bool({ PSet.fwdBackButtons }, { PSet.fwdBackButtons = it }),
            Settings.NAV_MOVE_INTERVAL_SEC to int({ PSet.moveIntervalSec }, { PSet.moveIntervalSec = it }),
            Settings.NAV_PREV_NEXT_BUTTON to bool({ PSet.prevNextButtons }, { PSet.prevNextButtons = it }),
            Settings.NAV_DISPLAY_CURRENT_POSITION to bool({ PSet.showPosWhileSeeking }, { PSet.showPosWhileSeeking = it }),
            Settings.VOLUME_BOOST to bool({ PSet.volumeBoostGate }, { PSet.volumeBoostGate = it }),
            Settings.SYNC_SYSTEM_VOLUME to bool({ PSet.systemVolume }, { PSet.systemVolume = it }),
            Settings.AUDIO_DELAY to secToMs({ PSet.audioDelayMs }, { PSet.audioDelayMs = it }),
            Settings.BLUETOOTH_AUDIO_DELAY to secToMs({ PSet.btDelayMs }, { PSet.btDelayMs = it }),
            Settings.AUDIO_LANGUAGE to str({ PSet.prefAudioLang }, { PSet.prefAudioLang = it }),
            Settings.PREFER_AUDIO_PASSTHROUGH to bool({ PSet.passthrough }, { PSet.passthrough = it }),
            Settings.AUDIO_EFFECTS_ENABLED to bool({ PSet.equalizerGate }, { PSet.equalizerGate = it }),
            Settings.AUDIO_FADE_IN_ON_START to bool({ PSet.fadeOnStart }, { PSet.fadeOnStart = it }),
            Settings.AUDIO_FADE_IN_ON_SEEK to bool({ PSet.fadeOnSeek }, { PSet.fadeOnSeek = it }),
            Settings.PAUSE_ON_HEADSET_DISCONNECTED to bool({ PSet.pauseHeadsetOff }, { PSet.pauseHeadsetOff = it }),
            Settings.DECODER_MODE to enumInt(
                listOf(0, 1, 4, 2),
                { PSet.decoderDefault },
                { PSet.decoderDefault = it },
                listOf("default", "hw", "hwplus", "sw")),
            Settings.USE_SPEEDUP_TRICKS to bool({ PSet.speedupTricks }, { PSet.speedupTricks = it }),
            Settings.DEFAULT_DEINTERLACER to int({ PSet.deinterlace }, { PSet.deinterlace = it }),
            Settings.CALIBRATE_HW_PLAY_POSITION to secToMs({ PSet.calibratePosMs }, { PSet.calibratePosMs = it }),
            Settings.SUBTITLE_CHARSET to Pair<() -> String, (String) -> Unit>(
                { if (PSet.subEncoding == "Auto") "" else PSet.subEncoding },
                { PSet.subEncoding = if (it.isEmpty()) "Auto" else it }),
            Settings.SUBTITLE_LANGUAGE to str({ PSet.prefSubLang }, { PSet.prefSubLang = it }),
            Settings.SUBTITLE_DEFAULT_SYNC to secToMs({ PSet.subDefaultSyncMs }, { PSet.subDefaultSyncMs = it }),
            Settings.SUBTITLE_SHOW_HW to bool({ PSet.subHw }, { PSet.subHw = it }),
            Settings.SUBTITLE_TYPEFACE to Pair<() -> String, (String) -> Unit>(
                { if (PSet.subFont == "Default") "" else PSet.subFont },
                { PSet.subFont = if (it.isEmpty()) "Default" else it }),
            Settings.TYPEFACE_DIR to str({ PSet.subFontFolder }, { PSet.subFontFolder = it }),
            Settings.SUBTITLE_SCALE to Pair<() -> String, (String) -> Unit>(
                { PSet.subScalePct.toString() },
                { v ->
                    val pct = v.toIntOrNull() ?: 100
                    PSet.subScalePct = pct
                    Store.subtitleTextSizeSp = 18f * pct / 100f
                }),
            Settings.SUBTITLE_TEXT_COLOR to int({ PSet.subColor }, { PSet.subColor = it }),
            Settings.SUBTITLE_SHADOW to bool({ PSet.subShadow }, { PSet.subShadow = it }),
            Settings.SUBTITLE_BOLD to bool({ PSet.subBold }, { PSet.subBold = it }),
            Settings.SUBTITLE_IMPROVE_STROKE to bool({ PSet.subImproveStroke }, { PSet.subImproveStroke = it }),
            Settings.SUBTITLE_ALIGNMENT to enumInt(
                listOf(0, 1, 2),
                { ALIGN_PSET.indexOf(PSet.subAlign).coerceAtLeast(0) },
                { PSet.subAlign = ALIGN_PSET.getOrElse(it) { "center" } },
                listOf("0", "1", "2")),
            Settings.SUBTITLE_BOTTOM_PADDING to int({ PSet.subBottomMarginDp }, { PSet.subBottomMarginDp = it }),
            Settings.SUBTITLE_BG_ENABLED to bool({ PSet.subBg }, { PSet.subBg = it }),
            Settings.SUBTITLE_BG_COLOR to int({ PSet.subBgColor }, { PSet.subBgColor = it }),
            Settings.ITALIC_TAG to bool({ PSet.subItalicEffect }, { PSet.subItalicEffect = it }),
            Settings.FORCE_LTR to bool({ PSet.subForceLtr }, { PSet.subForceLtr = it }),
            Settings.SUBTITLE_FOLDER to str({ PSet.subFolder }, { PSet.subFolder = it }),
            Settings.CONTROLS_QUICK_SHORTCUTS to Pair<() -> String, (String) -> Unit>(
                {
                    val ids = PSet.railShortcuts.split(",").filter { it.isNotBlank() }
                    ids.joinToString(",", "[", "]") { "\"$it\"" }
                },
                {
                    val ids = Store.stringListSetting(Settings.CONTROLS_QUICK_SHORTCUTS)
                    PSet.railShortcuts = ids.joinToString(",")
                })
        )
    }

    /* ================= honest gating (Monitoring v0.2 P2-3) ================= */

    /** Why a settings node cannot take effect in this build, or
     *  null when the node is live. Engine limits come from
     *  [FFmpegEngine.gateReason] (the engine team's single source
     *  of truth); the rest are build limits recorded here. A gated
     *  node still renders (MX has the option), but tapping it opens
     *  an explanation instead of silently persisting a value
     *  nothing reads. */
    private fun gateReasonFor(key: String): String? {
        FFmpegEngine.gateReason(key)?.let { return it }
        return LOCAL_GATE_REASONS[key]
    }

    /** Shows the gate dialog; returns true when [key] is gated
     *  (the caller must NOT open its editor / persist a value). */
    private fun gated(key: String, title: String): Boolean {
        val reason = gateReasonFor(key) ?: return false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(reason + "\n\nThis option exists in MX Player and is shown " +
                "for completeness, but it does not change anything in this build.")
            .setPositiveButton("OK", null)
            .show()
        return true
    }

    private fun nodeKey(node: SNode): String? = when (node) {
        is SNode.Check -> node.key
        is SNode.ListPref -> node.key
        is SNode.MultiPref -> node.key
        is SNode.IntPref -> node.key
        is SNode.FloatPref -> node.key
        is SNode.PercentPref -> node.key
        is SNode.SeekPref -> node.key
        is SNode.ColorPref -> node.key
        is SNode.LocalePref -> node.key
        is SNode.FolderPref -> node.key
        else -> null
    }

    /* ================= chrome + rendering ================= */

    private fun buildChrome() {
        theme = Settings.currentThemeColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.pageBg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.chrome)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(if (theme.dark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(10), dp(12), dp(10))
            setOnClickListener { onBackPressed() }
        }
        titleView = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }
        bar.addView(back)
        bar.addView(titleView)
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showScreen(id: String) {
        theme = Settings.currentThemeColors(this)
        val (title, nodes) = nodesFor(id)
        titleView.text = title
        listContainer.removeAllViews()
        listContainer.setBackgroundColor(theme.pageBg)
        for (node in nodes) {
            val row = renderNode(node)
            // Gated nodes (nothing in this build reads them) render
            // dimmed; tapping one explains instead of persisting.
            val key = nodeKey(node)
            if (key != null && gateReasonFor(key) != null) row.alpha = 0.55f
            listContainer.addView(row)
            // Decoded presentation (v0.4 Stage pass): MX's preference
            // rows are separated by hairline dividers
            // (divider_preference.xml carries a 1dip divider above
            // each category; rows sit on ?android:listPreferredItemHeight).
            if (node !is SNode.Category) listContainer.addView(divider())
        }
    }

    private fun refresh() = showScreen(screenStack.last())

    private fun pushScreen(id: String) {
        screenStack.add(id)
        showScreen(id)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screenStack.size > 1) {
            screenStack.removeAt(screenStack.lastIndex)
            showScreen(screenStack.last())
        } else {
            super.onBackPressed()
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(theme.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun rowShell(title: String, summary: String): LinearLayout {
        // Decoded geometry (preference.xml): text block marginStart
        // 15dip / marginEnd 6dip, 6dip vertical margins, row
        // minHeight ?android:listPreferredItemHeight (72dip on the
        // decoded target). Title 16sp / summary 13sp are the MX skin's
        // setting_title/setting_summary sizes as rendered on device.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            isClickable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(theme.title)
        })
        if (summary.isNotEmpty()) {
            texts.addView(TextView(this).apply {
                text = summary
                textSize = 13f
                setTextColor(theme.tertiary)
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun renderNode(node: SNode): View = when (node) {
        is SNode.Category -> TextView(this).apply {
            // divider_preference.xml verbatim: 14sp bold, accent
            // colour, 12dip padding on all sides.
            text = node.title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        is SNode.ScreenLink -> rowShell(node.title, node.summary).apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "›"; textSize = 20f; setTextColor(theme.tertiary)
            })
            setOnClickListener { pushScreen(node.target) }
        }
        is SNode.Check -> {
            val row = rowShell(node.title, node.summary)
            val box = CheckBox(this).apply {
                isChecked = getValue(node.key) == "true"
                isClickable = false
                buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            }
            row.addView(box)
            row.setOnClickListener {
                if (!gated(node.key, node.title)) {
                    val now = getValue(node.key) != "true"
                    setValue(node.key, now.toString())
                    box.isChecked = now
                    if (node.key == Settings.LIST_THEME) rebuildTheme()
                }
            }
            row
        }
        is SNode.ListPref -> rowShell(node.title, currentEntry(node)).apply {
            setOnClickListener { if (!gated(node.key, node.title)) showListDialog(node) }
        }
        is SNode.MultiPref -> rowShell(node.title, multiSummary(node)).apply {
            setOnClickListener { if (!gated(node.key, node.title)) showMultiDialog(node) }
        }
        is SNode.IntPref -> rowShell(node.title, getValue(node.key) + unitSuffix(node.unit)).apply {
            setOnClickListener {
                if (!gated(node.key, node.title)) showNumberDialog(node.key, node.title, false)
            }
        }
        is SNode.FloatPref -> rowShell(node.title, getValue(node.key) + unitSuffix(node.unit)).apply {
            setOnClickListener {
                if (!gated(node.key, node.title)) showNumberDialog(node.key, node.title, true)
            }
        }
        is SNode.PercentPref -> rowShell(node.title, getValue(node.key) + "%").apply {
            setOnClickListener {
                if (!gated(node.key, node.title)) {
                    showSeekDialog(node.key, node.title, 25, 400, "%", true)
                }
            }
        }
        is SNode.SeekPref -> rowShell(node.title, getValue(node.key) + unitSuffix(node.unit)).apply {
            setOnClickListener {
                if (!gated(node.key, node.title)) {
                    showSeekDialog(node.key, node.title, node.min, node.max, node.unit, false)
                }
            }
        }
        is SNode.ColorPref -> {
            val row = rowShell(node.title, "")
            val swatch = View(this).apply {
                val c = getValue(node.key).toIntOrNull() ?: 0
                setBackgroundColor(c)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            }
            row.addView(swatch)
            row.setOnClickListener {
                if (!gated(node.key, node.title)) showColorDialog(node)
            }
            row
        }
        is SNode.LocalePref -> rowShell(node.title, localeSummary(node.key)).apply {
            setOnClickListener {
                if (!gated(node.key, node.title)) showLocaleDialog(node)
            }
        }
        is SNode.FolderPref -> rowShell(node.title,
            getValue(node.key).ifEmpty { "Not set" }).apply {
            setOnClickListener { if (!gated(node.key, node.title)) pickFolder(node.key) }
        }
        is SNode.Action -> rowShell(node.title,
            if (node.id == "subtitle_font") subFontSummary() else node.summary).apply {
            setOnClickListener { runAction(node.id) }
        }
    }

    private fun unitSuffix(unit: String): String = if (unit.isEmpty()) "" else " $unit"

    private fun rebuildTheme() {
        buildChrome()
        refresh()
    }

    private fun currentEntry(node: SNode.ListPref): String {
        val idx = node.values.indexOf(getValue(node.key))
        return node.entries.getOrElse(idx) { node.entries.firstOrNull() ?: "" }
    }

    private fun multiSummary(node: SNode.MultiPref): String {
        var set = Store.stringSetSetting(node.key)
        if (node.allWhenEmpty && set.isEmpty()) set = node.values.toSet()
        val labels = node.values.filter { set.contains(it) }
            .map { v -> node.entries[node.values.indexOf(v)] }
        return if (labels.isEmpty()) "None" else labels.joinToString(", ")
    }

    private fun localeSummary(key: String): String {
        val v = getValue(key)
        if (v.isEmpty()) return "System default"
        val loc = Locale.forLanguageTag(v)
        return if (loc.displayName.isNotEmpty()) loc.displayName else v
    }

    private fun subFontSummary(): String {
        val v = getValue(Settings.SUBTITLE_TYPEFACE)
        return if (v.isEmpty()) "Default" else v
    }

    /* ================= dialogs ================= */

    private fun showListDialog(node: SNode.ListPref) {
        val checked = node.values.indexOf(getValue(node.key)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(node.title)
            .setSingleChoiceItems(node.entries.toTypedArray(), checked) { d, which ->
                setValue(node.key, node.values[which])
                d.dismiss()
                if (node.key == Settings.LIST_THEME || node.key == Settings.PLAYBACK_THEME) {
                    rebuildTheme()
                } else refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMultiDialog(node: SNode.MultiPref) {
        var set = Store.stringSetSetting(node.key)
        if (node.allWhenEmpty && set.isEmpty()) set = node.values.toSet()
        val checked = BooleanArray(node.values.size) { set.contains(node.values[it]) }
        AlertDialog.Builder(this)
            .setTitle(node.title)
            .setMultiChoiceItems(node.entries.toTypedArray(), checked) { _, which, on ->
                checked[which] = on
            }
            .setPositiveButton("OK") { _, _ ->
                val chosen = node.values.filterIndexed { i, _ -> checked[i] }
                if (node.allWhenEmpty && chosen.size == node.values.size) {
                    Store.setStringSetSetting(node.key, emptySet())
                    bridge[node.key]?.second?.invoke("[]")
                } else {
                    Store.setStringSetSetting(node.key, chosen.toSet())
                    bridge[node.key]?.second?.invoke(
                        chosen.joinToString(",", "[", "]") { "\"$it\"" })
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNumberDialog(key: String, title: String, isFloat: Boolean) {
        val input = EditText(this).apply {
            setText(getValue(key))
            inputType = if (isFloat)
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val raw = input.text.toString().trim()
                val ok = if (isFloat) raw.toFloatOrNull() != null else raw.toIntOrNull() != null
                if (ok) {
                    setValue(key, raw)
                    refresh()
                } else toast("Invalid number")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSeekDialog(key: String, title: String, min: Int, max: Int,
                               unit: String, withReset: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val label = TextView(this).apply {
            textSize = 16f
            setTextColor(theme.title)
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = ((getValue(key).toIntOrNull() ?: min) - min).coerceIn(0, max - min)
        }
        fun updateLabel() { label.text = "${bar.progress + min}${unitSuffix(unit)}" }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = updateLabel()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        updateLabel()
        container.addView(label)
        container.addView(bar)
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                setValue(key, (bar.progress + min).toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
        if (withReset) {
            builder.setNeutralButton("Reset") { _, _ ->
                setValue(key, "100")
                refresh()
            }
        }
        builder.show()
    }

    private fun showColorDialog(node: SNode.ColorPref) {
        val names = Settings.COLOR_CHOICES.map { it.first }.toTypedArray()
        val current = getValue(node.key).toIntOrNull() ?: 0
        val checked = Settings.COLOR_CHOICES.indexOfFirst { it.second == current }
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(node.title)
            .setSingleChoiceItems(names, checked) { d, which ->
                setValue(node.key, Settings.COLOR_CHOICES[which].second.toString())
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLocaleDialog(node: SNode.LocalePref) {
        val locales = Locale.getAvailableLocales()
            .filter { it.displayName.isNotEmpty() }
            .distinctBy { it.toLanguageTag() }
            .sortedBy { it.displayName }
        val entries = listOf("System default") + locales.map { it.displayName }
        val values = listOf("") + locales.map { it.toLanguageTag() }
        val checked = values.indexOf(getValue(node.key)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(node.title)
            .setSingleChoiceItems(entries.toTypedArray(), checked) { d, which ->
                setValue(node.key, values[which])
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickFolder(key: String) {
        pendingFolderKey = key
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_FOLDER)
        } catch (t: Throwable) {
            toast("No folder picker available on this device")
        }
    }

    /* ================= actions (General screen) ================= */

    private fun runAction(id: String) {
        when (id) {
            "subtitle_font" -> showFontDialog()
            "custom_codec" -> AlertDialog.Builder(this)
                .setTitle("Custom codec")
                .setMessage("MX Player can load a custom codec package from a " +
                    "file. This build instead bundles its own FFmpeg engine " +
                    "(built from source), which is always used — loading a " +
                    "custom codec from a file is not available in this build.")
                .setPositiveButton("OK", null)
                .show()
            "custom_codec_version" -> AlertDialog.Builder(this)
                .setTitle("Custom codec version")
                .setMessage("Bundled engine: " + FFmpegEngine.version())
                .setPositiveButton("OK", null)
                .show()
            "ext_decoder_config" -> AlertDialog.Builder(this)
                .setTitle("Per-extension decoder")
                .setMessage("Assigning a decoder per file extension is not " +
                    "implemented in this build. The Default decoder " +
                    "(Settings → Decoder) applies to every file.")
                .setPositiveButton("OK", null)
                .show()
            "clear_history" -> confirm("Clear history",
                "Delete all playback history, resume positions and finished marks?") {
                Store.clearHistoryData()
                toast("History cleared")
            }
            "clear_thumb_cache" -> {
                var count = 0
                cacheDir.listFiles()?.forEach { if (it.deleteRecursively()) count++ }
                toast("Thumbnail cache cleared ($count items)")
            }
            "clear_font_cache" -> {
                val dir = File(filesDir, "fonts")
                val existed = dir.exists()
                if (existed) dir.deleteRecursively()
                toast(if (existed) "Font cache cleared" else "Font cache is already empty")
            }
            "reset_settings" -> confirm("Reset settings",
                "Reset all settings to their defaults? Playback history and playlists are kept.") {
                Store.resetSettings()
                // Re-apply defaults through the bridge so the player
                // (PSet) resets too.
                for ((key, pair) in bridge) pair.second(Settings.defaultOf(key))
                Store.subtitleTextSizeSp = 18f
                rebuildTheme()
                toast("Settings reset")
            }
            "export_settings" -> exportJson(REQ_EXPORT_SETTINGS, settingsJson())
            "import_settings" -> importJson(REQ_IMPORT_SETTINGS, ::applySettingsJson)
            "export_materials" -> exportJson(REQ_EXPORT_MATERIALS, materialsJson())
            "import_materials" -> importJson(REQ_IMPORT_MATERIALS, ::applyMaterialsJson)
            else -> toast("Not available")
        }
    }

    private fun showFontDialog() {
        val dirPath = getValue(Settings.TYPEFACE_DIR)
        val fonts = mutableListOf("Default")
        if (dirPath.isNotEmpty()) {
            val dir = File(Uri.parse(dirPath).path ?: dirPath)
            dir.listFiles()?.filter { it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true) }
                ?.forEach { fonts.add(it.name) }
        }
        AlertDialog.Builder(this)
            .setTitle("Font")
            .setItems(fonts.toTypedArray()) { _, which ->
                setValue(Settings.SUBTITLE_TYPEFACE, if (which == 0) "" else fonts[which])
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Settings export (spec #100): the EFFECTIVE settings values
     *  (bridged keys read back from PSet, so a value last changed
     *  inside the player exports correctly — Monitoring v0.2 §8),
     *  plus the activity records (history / resume / playlists)
     *  and the UI playlist store. */
    private fun settingsJson(): String {
        val settings = JSONObject()
        for (key in Settings.DEFAULTS.keys) settings.put(key, getValue(key))
        val obj = JSONObject()
        obj.put("settings", settings)
        obj.put("activity", JSONObject(Store.activityJson()))
        obj.put("uiPlaylists", PSet.customPlaylists)
        return obj.toString(2)
    }

    private fun applySettingsJson(text: String) {
        try {
            val obj = JSONObject(text)
            var applied = 0
            var records = false
            if (obj.has("settings")) {
                val s = obj.getJSONObject("settings")
                val keys = s.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (Settings.DEFAULTS.containsKey(k)) {
                        setValue(k, s.getString(k))
                        applied++
                    }
                }
                if (obj.has("activity")) {
                    records = Store.applyActivityJson(
                        obj.getJSONObject("activity").toString())
                }
                if (obj.has("uiPlaylists")) {
                    PSet.customPlaylists = obj.getString("uiPlaylists")
                }
            } else {
                // Legacy v0.2 flat export: the whole object is settings.
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (Settings.DEFAULTS.containsKey(k)) {
                        setValue(k, obj.getString(k))
                        applied++
                    }
                }
            }
            rebuildTheme()
            toast("Imported $applied settings" +
                if (records) " + activity records" else "")
        } catch (t: Throwable) {
            toast("Import failed: not a settings file")
        }
    }

    private fun materialsJson(): String {
        val obj = JSONObject()
        for (key in MATERIAL_KEYS) obj.put(key, getValue(key))
        return obj.toString(2)
    }

    private fun applyMaterialsJson(text: String) {
        try {
            val obj = JSONObject(text)
            for (key in MATERIAL_KEYS) {
                if (obj.has(key)) setValue(key, obj.getString(key))
            }
            refresh()
            toast("Materials imported")
        } catch (t: Throwable) {
            toast("Import failed: not a materials file")
        }
    }

    private fun exportJson(request: Int, json: String) {
        pendingExport = json
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE,
                if (request == REQ_EXPORT_SETTINGS) "ghostblade-settings.json"
                else "ghostblade-materials.json")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (t: Throwable) {
            toast("No file picker available on this device")
        }
    }

    private fun importJson(request: Int, applier: (String) -> Unit) {
        pendingImport = applier
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (t: Throwable) {
            toast("No file picker available on this device")
        }
    }

    private var pendingExport: String? = null
    private var pendingImport: ((String) -> Unit)? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_FOLDER -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (t: Throwable) { /* best effort */ }
                pendingFolderKey?.let { setValue(it, uri.toString()) }
                pendingFolderKey = null
                refresh()
            }
            REQ_EXPORT_SETTINGS, REQ_EXPORT_MATERIALS -> {
                val json = pendingExport ?: return
                pendingExport = null
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    toast("Exported")
                } catch (t: Throwable) {
                    toast("Export failed")
                }
            }
            REQ_IMPORT_SETTINGS, REQ_IMPORT_MATERIALS -> {
                val applier = pendingImport ?: return
                pendingImport = null
                try {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    applier(text)
                } catch (t: Throwable) {
                    toast("Import failed")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /* ================= screen data ================= */

    private fun themeLabels(values: List<String>): List<String> = values.map { themeLabel(it) }

    private fun themeLabel(value: String): String = when (value) {
        "system" -> "System default"
        "white" -> "White"
        "black" -> "Black"
        "dark_gray" -> "Dark Gray"
        "dark_gray2" -> "Dark Gray 2"
        "dark_navy" -> "Dark Navy"
        "dark_navy2" -> "Dark Navy 2"
        "fl_pink" -> "Pink"
        else -> value.split("_").joinToString(" / ") { part ->
            when {
                part.endsWith("Accent") ->
                    part.removeSuffix("Accent").replaceFirstChar { it.uppercase() } + " accent"
                part == "fl" -> "Pink"
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }
    }

    private fun nodesFor(id: String): Pair<String, List<SNode>> = when (id) {
        "root" -> "Settings" to listOf(
            SNode.ScreenLink("List", "", "list"),
            SNode.ScreenLink("Player", "", "player"),
            SNode.ScreenLink("Decoder", "", "decoder"),
            SNode.ScreenLink("Audio", "", "audio"),
            SNode.ScreenLink("Subtitle", "", "subtitle"),
            SNode.ScreenLink("General", "", "general")
        )
        "list" -> "List" to listScreen()
        "player" -> "Player" to playerScreen()
        "tuner" -> "Tuner" to listOf(
            SNode.Category("Playback screen"),
            SNode.ScreenLink("Screen", "", "tuner_screen"),
            SNode.ScreenLink("Controls", "", "tuner_controls"),
            SNode.ScreenLink("Navigation", "", "tuner_navigation"),
            SNode.ScreenLink("Style", "", "tuner_style"),
            SNode.Category("Subtitle"),
            SNode.ScreenLink("Text", "", "tuner_subtitle_text"),
            SNode.ScreenLink("Layout", "", "tuner_subtitle_layout")
        )
        "tuner_screen" -> "Screen" to tunerScreen()
        "tuner_controls" -> "Controls" to tunerControls()
        "tuner_navigation" -> "Navigation" to listOf(
            SNode.SeekPref(Settings.NAV_SEEK_SPEED, "Seek speed", 10, 400, "%"),
            SNode.Check(Settings.NAV_FORWARD_BACKWARD_BUTTON, "Forward/Backward buttons"),
            SNode.IntPref(Settings.NAV_MOVE_INTERVAL_SEC, "Move interval", "sec"),
            SNode.Check(Settings.NAV_PREV_NEXT_BUTTON, "Previous/Next buttons"),
            SNode.Check(Settings.NAV_DISPLAY_CURRENT_POSITION,
                "Display current position while seeking")
        )
        "tuner_style" -> "Style" to listOf(
            SNode.ListPref(Settings.PLAYBACK_THEME, "Playback screen theme",
                themeLabels(Settings.PLAYBACK_THEMES), Settings.PLAYBACK_THEMES),
            SNode.ListPref(Settings.PLAYER_STYLE, "Player style",
                listOf("Default", "Alternative"), listOf("0", "1")),
            SNode.ListPref(Settings.PROGRESS_BAR_STYLE, "Progress bar style",
                listOf("Flat", "Material"), listOf("0", "1"))
        )
        "tuner_subtitle_text" -> "Subtitle Text" to subtitleTextNodes()
        "tuner_subtitle_layout" -> "Subtitle Layout" to listOf(
            SNode.ListPref(Settings.SUBTITLE_ALIGNMENT, "Alignment",
                listOf("Left", "Center", "Right"), listOf("0", "1", "2")),
            SNode.SeekPref(Settings.SUBTITLE_BOTTOM_PADDING, "Bottom padding", 0, 100, "dp"),
            SNode.Check(Settings.SUBTITLE_BG_ENABLED, "Background"),
            SNode.ColorPref(Settings.SUBTITLE_BG_COLOR, "Background colour")
        )
        "quick_shortcuts" -> "Quick Shortcuts" to listOf(
            SNode.MultiPref(Settings.CONTROLS_QUICK_SHORTCUTS, "Shortcuts",
                Settings.SHORTCUTS.map { it.second }, Settings.SHORTCUTS.map { it.first })
        )
        "decoder" -> "Decoder" to decoderScreen()
        "omx_video_codecs" -> "HW+ Video Codecs" to listOf(
            SNode.MultiPref(Settings.OMX_VIDEO_CODECS, "Video codecs",
                Settings.OMX_VIDEO_CODEC_LIST, Settings.OMX_VIDEO_CODEC_LIST)
        )
        "omx_audio_codecs" -> "HW+ Audio Codecs" to listOf(
            SNode.MultiPref(Settings.OMX_AUDIO_CODECS, "Audio codecs",
                Settings.OMX_AUDIO_CODEC_LIST, Settings.OMX_AUDIO_CODEC_LIST)
        )
        "audio" -> "Audio" to audioScreen()
        "subtitle" -> "Subtitle" to subtitleScreen()
        "general" -> "General" to generalScreen()
        "scan_folders" -> "Media Folders" to scanFolderNodes()
        "scan_extensions" -> "Video Extensions" to listOf(
            SNode.MultiPref(Settings.SCAN_EXTENSIONS, "Extensions",
                Settings.VIDEO_EXTENSIONS, Settings.VIDEO_EXTENSIONS, allWhenEmpty = true),
            SNode.Action("ext_decoder_config", "Per-extension decoder",
                "Not available in this build")
        )
        else -> "Settings" to emptyList()
    }

    private fun listScreen(): List<SNode> = listOf(
        SNode.Category("Appearance"),
        SNode.ListPref(Settings.LIST_THEME, "Theme",
            themeLabels(Settings.LIST_THEMES), Settings.LIST_THEMES),
        SNode.Check(Settings.MARK_LAST_PLAYED, "Mark last played media for each folder"),
        SNode.Check(Settings.SCROLL_TO_LAST_MEDIA, "Scroll down to last played media"),
        SNode.Check(Settings.SELECTION_MODE, "Selection mode",
            "Tap a thumbnail to select the item"),
        SNode.Check(Settings.LIST_FLOATING_BUTTON, "Floating action button"),
        SNode.IntPref(Settings.NEW_TAGGED_PERIOD, "NEW tagged period", "days"),
        SNode.Category("Scan"),
        SNode.ScreenLink("Media folders", "", "scan_folders"),
        SNode.ScreenLink("Video extensions", "", "scan_extensions"),
        SNode.Check(Settings.RESPECT_NOMEDIA, "Respect .nomedia"),
        SNode.Check(Settings.SHOW_HIDDEN, "Show hidden files and folders"),
        SNode.Category("List"),
        SNode.MultiPref(Settings.REFRESH_METHODS, "Refresh methods",
            listOf("Refresh button", "Swipe to refresh"), listOf("button", "swipe")),
        SNode.MultiPref(Settings.LIST_FIELDS, "Fields",
            Settings.LIST_FIELD_IDS.map { it.second }, Settings.LIST_FIELD_IDS.map { it.first })
    )

    private fun scanFolderNodes(): List<SNode> {
        val videos = Library.scan(this, respectNomedia = false)
        scanDirs = videos.map { Library.dirOfPath(it.path) }.distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (scanDirs.isEmpty()) {
            return listOf(SNode.Action("none", "No media folders found",
                "Grant video access and pull to refresh the library"))
        }
        val counts = videos.groupingBy { Library.dirOfPath(it.path) }.eachCount()
        return scanDirs.map { dir ->
            SNode.Check(SCAN_FOLDER_PREFIX + dir,
                dir.substringAfterLast('/').ifEmpty { dir },
                "$dir · ${counts[dir] ?: 0} files")
        }
    }

    private fun playerScreen(): List<SNode> = listOf(
        SNode.Category("Playback"),
        SNode.ListPref(Settings.RESUME, "Resume",
            listOf("Resume", "Start over", "Ask at Startup"),
            listOf("resume", "startover", "ask")),
        SNode.Check(Settings.RESUME_ONLY_FIRST, "Resume only first video"),
        SNode.PercentPref(Settings.DEFAULT_PLAYBACK_SPEED, "Default playback speed"),
        SNode.Check(Settings.REMEMBER_SELECTIONS, "Remember selections"),
        SNode.Check(Settings.BACK_TO_LIST, "Back to list after playback"),
        SNode.Check(Settings.SEEK_PREVIEWS, "Seek previews"),
        SNode.ListPref(Settings.SEEK_PREVIEWS_NET, "Seek previews scope",
            listOf("Off", "Local files", "All files"), listOf("0", "1", "2")),
        SNode.Check(Settings.FAST_SEEK, "Fast seek"),
        SNode.Check(Settings.AUDIO_FOCUS, "Audio focus"),
        SNode.Check(Settings.MEDIA_BUTTONS, "Media buttons"),
        SNode.Check(Settings.HEADSET_MULTI_PRESS, "Honour headset hook multi-press"),
        SNode.Check(Settings.PREVNEXT_TO_REWFF, "Previous/Next buttons act as RW/FF"),
        SNode.Check(Settings.TOGGLE_ON_MEDIA_PLAY_BUTTON, "Toggle on media play button"),
        SNode.Check(Settings.SMART_PREV, "Smart previous"),
        SNode.Check(Settings.SUPPRESS_ERROR_MESSAGE, "Suppress error message"),
        SNode.Check(Settings.USE_CUSTOM_POPWINDOW, "Use custom popup window"),
        SNode.Category("Background Play"),
        SNode.ListPref(Settings.STICKY_VIDEO, "Background play (video)",
            listOf("Stop", "Background play", "Picture-in-picture"),
            listOf("stop", "background", "pip")),
        SNode.Check(Settings.STICKY_AUDIO, "Background play (audio)"),
        SNode.Check(Settings.ALBUM_ART, "Album art"),
        SNode.Check(Settings.STICKY_SMOOTH_SWITCH, "Smooth switch"),
        SNode.Category("Tuner"),
        SNode.ScreenLink("Tuner…", "Playback screen & subtitle appearance", "tuner"),
        SNode.Category("Miscellaneous"),
        SNode.IntPref(Settings.VIDEO_ZOOM_DELAY, "Video zoom delay", "ms"),
        SNode.Check(Settings.VIDEO_ZOOM_LIMITED, "Limit video zoom"),
        SNode.Check(Settings.BUTTON_BACKLIGHT_OFF, "Turn off button backlight"),
        SNode.Check(Settings.LOADING_CIRCLE_ANIMATION, "Loading circle animation"),
        SNode.Check(Settings.SOFT_MAIN_KEYS, "Soft main keys"),
        SNode.ListPref(Settings.SCREEN_LOCK_MODE, "Screen lock mode",
            listOf("Mode #1", "Mode #2", "Mode #3"), listOf("0", "1", "2")),
        SNode.Check(Settings.ANDROID_40_COMPAT, "Android 4.0 compatible mode"),
        SNode.Check(Settings.DOUBLE_TAP_BACK, "Double tap Back to exit"),
        SNode.Check(Settings.QUICK_ZOOM, "Quick zoom")
    )

    private fun tunerScreen(): List<SNode> = listOf(
        SNode.ListPref(Settings.SCREEN_ORIENTATION, "Screen orientation",
            listOf("Auto (Landscape)", "Auto", "Landscape", "Reverse Landscape",
                "System", "Video size"),
            listOf("0", "1", "2", "3", "4", "5")),
        SNode.ListPref(Settings.SCREEN_FULLSCREEN, "Full screen",
            listOf("Auto switch", "On", "Off"), listOf("0", "1", "2")),
        SNode.ListPref(Settings.SCREEN_SOFT_BUTTONS, "Soft buttons",
            listOf("Auto hide", "Show", "Hide"), listOf("0", "1", "2")),
        SNode.Check(Settings.SCREEN_BRIGHTNESS_ENABLED, "Brightness control"),
        SNode.SeekPref(Settings.SCREEN_BRIGHTNESS, "Brightness", 0, 100, "%"),
        SNode.Check(Settings.SCREEN_ELAPSED_TIME, "Elapsed time"),
        SNode.Check(Settings.SCREEN_BATTERY_CLOCK, "Battery & clock"),
        SNode.Check(Settings.SCREEN_BATTERY_CLOCK_TITLEBAR, "Battery & clock on title bar"),
        SNode.Check(Settings.SCREEN_SHOW_REMAINING_TIME, "Show remaining time"),
        SNode.Check(Settings.SCREEN_STATUS_BAR, "Status bar"),
        SNode.Check(Settings.SCREEN_AUTO_HIDE_SOFT_BUTTONS, "Auto hide soft buttons"),
        SNode.Check(Settings.SCREEN_KEEP_SCREEN_ON, "Keep screen on"),
        SNode.Check(Settings.SCREEN_PAUSE_ON_OBSTRUCTED, "Pause when obstructed"),
        SNode.Check(Settings.SCREEN_SHOW_INTERFACE_STARTUP, "Show interface on startup"),
        SNode.Check(Settings.SCREEN_PLACE_AT_BOTTOM, "Place controls at bottom"),
        SNode.Check(Settings.SCREEN_ROTATION_BUTTON, "Rotation button"),
        SNode.Check(Settings.SCREEN_HIDE_DOWNLOAD_BUTTON, "Hide download button"),
        SNode.Check(Settings.SCREEN_CORNER_OFFSET_ENABLED, "Corner offset"),
        SNode.SeekPref(Settings.SCREEN_CORNER_OFFSET, "Corner offset size", 0, 100, ""),
        SNode.ColorPref(Settings.SCREEN_OSD_TEXT_COLOR, "OSD text colour"),
        SNode.Check(Settings.SCREEN_OSD_BG_ENABLED, "OSD background"),
        SNode.ColorPref(Settings.SCREEN_OSD_BG_COLOR, "OSD background colour")
    )

    private fun tunerControls(): List<SNode> = listOf(
        SNode.ListPref(Settings.CONTROLS_KEY_UPDOWN_ACTION, "Key up/down action",
            listOf("Volume", "Seek", "Brightness"), listOf("0", "1", "2")),
        SNode.ListPref(Settings.CONTROLS_WHEEL_ACTION, "Mouse wheel action",
            listOf("Volume", "Seek", "Brightness"), listOf("0", "1", "2")),
        SNode.ListPref(Settings.CONTROLS_LOCK_MODE, "Lock mode",
            listOf("Mode #1", "Mode #2", "Mode #3"), listOf("0", "1", "2")),
        SNode.MultiPref(Settings.CONTROLS_LOCK_TARGETS, "Lock target",
            listOf("Input", "Screen Rotation"), listOf("Input", "Screen Rotation")),
        SNode.Category("Gestures"),
        SNode.Check(Settings.GESTURE_SEEK_POSITION, "Seek (horizontal gesture)"),
        SNode.Check(Settings.GESTURE_ZOOM_AND_PAN, "Zoom & pan"),
        SNode.Check(Settings.GESTURE_VIDEO_ZOOM, "Video zoom"),
        SNode.Check(Settings.GESTURE_VIDEO_PAN, "Video pan"),
        SNode.Check(Settings.GESTURE_VOLUME, "Volume"),
        SNode.Check(Settings.GESTURE_BRIGHTNESS, "Brightness"),
        SNode.Check(Settings.GESTURE_DOUBLE_TAP_PLAY_PAUSE, "Double tap: Play/Pause"),
        SNode.Check(Settings.GESTURE_DOUBLE_TAP_FFRW, "Double tap: FF/RW"),
        SNode.Check(Settings.GESTURE_LONG_PRESS_SPEED_FF, "Long press: Speed/FF"),
        SNode.Check(Settings.GESTURE_PLAYBACK_SPEED, "Two-finger playback speed"),
        SNode.Check(Settings.GESTURE_SUBTITLE_SCROLL, "Subtitle scroll"),
        SNode.Check(Settings.GESTURE_SUBTITLE_UP_DOWN, "Subtitle up/down"),
        SNode.Check(Settings.GESTURE_SUBTITLE_ZOOM, "Subtitle zoom"),
        SNode.Category("Interface"),
        SNode.Check(Settings.CONTROLS_INTERFACE_AUTO_HIDE, "Auto hide interface"),
        SNode.IntPref(Settings.CONTROLS_INTERFACE_AUTO_HIDE_DELAY, "Auto hide delay", "sec"),
        SNode.Check(Settings.CONTROLS_SHOW_INTERFACE_WHEN_LOCKED,
            "Show interface when locked"),
        SNode.ScreenLink("Quick Shortcuts", "", "quick_shortcuts")
    )

    private fun subtitleTextNodes(): List<SNode> = listOf(
        SNode.Action("subtitle_font", "Font"),
        SNode.FolderPref(Settings.TYPEFACE_DIR, "Font folder"),
        SNode.PercentPref(Settings.SUBTITLE_SCALE, "Size"),
        SNode.ColorPref(Settings.SUBTITLE_TEXT_COLOR, "Text colour"),
        SNode.ColorPref(Settings.SUBTITLE_BORDER_COLOR, "Border colour"),
        SNode.Check(Settings.SUBTITLE_SHADOW, "Shadow"),
        SNode.Check(Settings.SUBTITLE_BOLD, "Bold"),
        SNode.Check(Settings.SUBTITLE_IMPROVE_STROKE, "Improve stroke rendering")
    )

    private fun decoderScreen(): List<SNode> = listOf(
        SNode.Category("Decoder"),
        SNode.ListPref(Settings.DECODER_MODE, "Default decoder",
            listOf("Default", "HW decoder", "HW+ decoder", "SW decoder"),
            listOf("default", "hw", "hwplus", "sw")),
        SNode.Check(Settings.OMX_DECODER, "HW+ decoder"),
        SNode.Check(Settings.OMX_DECODER_LOCAL, "HW+ decoder for local files"),
        SNode.Check(Settings.OMX_DECODER_NET, "HW+ decoder for network files"),
        SNode.Check(Settings.TRY_HW_IF_OMX_FAILS, "Try HW decoder if HW+ fails"),
        SNode.Check(Settings.TRY_OMX_IF_HW_FAILS, "Try HW+ decoder if HW fails"),
        SNode.Check(Settings.SW_DECODER_LOCAL, "SW decoder for local files"),
        SNode.Check(Settings.SW_DECODER_NET, "SW decoder for network files"),
        SNode.Check(Settings.OMX_DECODER_ALT, "Alternative HW+ decoder"),
        SNode.Category("HW / SW"),
        SNode.Check(Settings.CORRECT_HW_ASPECT_RATIO, "Correct HW aspect ratio"),
        SNode.Check(Settings.HW_AUDIO_TRACK_SELECTABLE, "HW audio track selectable"),
        SNode.Check(Settings.OMX_AUDIO_WITH_SW_VIDEO, "HW+ audio with SW video"),
        SNode.Check(Settings.SW_AUDIO, "SW audio decoder"),
        SNode.Check(Settings.SW_AUDIO_LOCAL, "SW audio for local files"),
        SNode.Check(Settings.SW_AUDIO_NET, "SW audio for network files"),
        SNode.FloatPref(Settings.CALIBRATE_HW_PLAY_POSITION, "Calibrate HW play position"),
        SNode.IntPref(Settings.CORE_LIMIT, "Core limit (0 = default)"),
        SNode.ListPref(Settings.COLOR_FORMAT, "Colour format",
            Settings.COLOR_FORMATS.map { it.second }, Settings.COLOR_FORMATS.map { it.first }),
        SNode.Check(Settings.USE_SPEEDUP_TRICKS, "Use speedup tricks"),
        SNode.ListPref(Settings.DEFAULT_DEINTERLACER, "Deinterlacer",
            listOf("None", "Yadif", "W3fdif"), listOf("0", "1", "2")),
        SNode.ScreenLink("HW+ video codecs", "", "omx_video_codecs"),
        SNode.ScreenLink("HW+ audio codecs", "", "omx_audio_codecs"),
        SNode.Category("Custom Codec"),
        SNode.Action("custom_codec", "Custom codec",
            "FFmpeg 7.1.1 built from source, bundled with the app"),
        SNode.Action("custom_codec_version", "Custom codec version", "7.1.1 (bundled)")
    )

    private fun audioScreen(): List<SNode> = listOf(
        SNode.Check(Settings.AUDIO_PLAYER, "Audio player mode"),
        SNode.ListPref(Settings.AUDIO_DEVICE, "Audio device",
            Settings.AUDIO_DEVICES.map { it.second }, Settings.AUDIO_DEVICES.map { it.first }),
        SNode.Check(Settings.VOLUME_BOOST, "Volume boost"),
        SNode.Check(Settings.SYNC_SYSTEM_VOLUME, "Sync system volume"),
        SNode.Check(Settings.HEADSET_SHOW_SYSTEM_VOLUME_UI,
            "Show system volume UI for headset"),
        SNode.Check(Settings.PAUSE_ON_HEADSET_DISCONNECTED,
            "Pause on headset disconnected"),
        SNode.Check(Settings.AUDIO_FADE_IN_ON_START, "Fade in on start"),
        SNode.Check(Settings.AUDIO_FADE_IN_ON_SEEK, "Fade in on seek"),
        SNode.LocalePref(Settings.AUDIO_LANGUAGE, "Audio language"),
        SNode.FloatPref(Settings.AUDIO_DELAY, "Audio delay"),
        SNode.FloatPref(Settings.BLUETOOTH_AUDIO_DELAY, "Bluetooth audio delay"),
        SNode.Check(Settings.PREFER_AUDIO_PASSTHROUGH, "Prefer audio passthrough"),
        SNode.Check(Settings.AUDIO_EFFECTS_ENABLED, "Audio effects (equalizer)")
    )

    private fun subtitleScreen(): List<SNode> = listOf(
        SNode.Category("Subtitle"),
        SNode.FolderPref(Settings.SUBTITLE_FOLDER, "Subtitle folder"),
        SNode.ListPref(Settings.SUBTITLE_CHARSET, "Encoding",
            Settings.SUBTITLE_CHARSETS.map { it.second },
            Settings.SUBTITLE_CHARSETS.map { it.first }),
        SNode.LocalePref(Settings.SUBTITLE_LANGUAGE, "Subtitle language"),
        SNode.Check(Settings.SUBTITLE_SHOW_HW, "Show subtitles with HW decoder"),
        SNode.Check(Settings.SUBTITLE_HW_ACCEL, "Hardware accelerated subtitles"),
        SNode.FloatPref(Settings.SUBTITLE_DEFAULT_SYNC, "Default sync"),
        SNode.Check(Settings.ITALIC_TAG, "Italic tag effect"),
        SNode.Check(Settings.FORCE_LTR, "Force left-to-right"),
        SNode.SeekPref(KEY_SUB_SIZE_SP, "Text size", 10, 40, "sp"),
        SNode.Category("Tuner"),
        SNode.ScreenLink("Text", "", "tuner_subtitle_text"),
        SNode.ScreenLink("Layout", "", "tuner_subtitle_layout")
    )

    private fun generalScreen(): List<SNode> = listOf(
        SNode.Category("General"),
        SNode.LocalePref(Settings.USER_LOCALE, "Language"),
        SNode.Check(Settings.QUIT_BUTTON, "Quit button"),
        SNode.Check(Settings.TV_MODE, "TV mode"),
        SNode.Check(Settings.ALLOW_EDITS, "Allow file edits",
            "Rename and delete from the library"),
        SNode.Check(Settings.DELETE_SUBTITLE_TOGETHER, "Delete subtitle files together"),
        SNode.Check(Settings.CACHE_THUMBNAIL, "Cache thumbnails"),
        SNode.Check(Settings.SUBTITLE_HW_ACCEL, "Hardware accelerated subtitles"),
        SNode.Category("Data"),
        SNode.Action("clear_history", "Clear history"),
        SNode.Action("clear_thumb_cache", "Clear thumbnail cache"),
        SNode.Action("clear_font_cache", "Clear font cache"),
        SNode.Action("export_settings", "Export settings"),
        SNode.Action("import_settings", "Import settings"),
        SNode.Action("export_materials", "Export materials"),
        SNode.Action("import_materials", "Import materials"),
        SNode.Action("reset_settings", "Reset settings")
    )

    companion object {
        private const val REQ_FOLDER = 40
        private const val REQ_EXPORT_SETTINGS = 41
        private const val REQ_IMPORT_SETTINGS = 42
        private const val REQ_EXPORT_MATERIALS = 43
        private const val REQ_IMPORT_MATERIALS = 44
        private const val KEY_SUB_SIZE_SP = "__subtitle_text_size_sp"
        private const val SCAN_FOLDER_PREFIX = "scanfolder:"
        private val ORIENTATION_PSET =
            listOf("auto_land", "auto", "land", "rev_land", "system", "video")
        private val FULLSCREEN_PSET = listOf("auto", "on", "off")
        private val ALIGN_PSET = listOf("left", "center", "right")
        private val MATERIAL_KEYS = listOf(
            Settings.SUBTITLE_FOLDER, Settings.TYPEFACE_DIR,
            Settings.CUSTOM_CODEC, Settings.SCAN_FOLDERS
        )

        /** Shared reason for the Decoder screen's enable/fallback
         *  switches: the engine team wired the Default-decoder
         *  choice and the automatic HW fallback; the individual
         *  MX switches have no reader in this build. */
        private const val DECODER_GATE =
            "Decoder enable/fallback switches are not wired in this build. " +
            "The working control is \"Default decoder\" (HW / HW+ / SW); when the " +
            "engine cannot open a file the player falls back to HW automatically."

        /** Nodes that persist-but-nothing-reads, with the honest
         *  reason shown instead (Monitoring v0.2 P2-3 sweep,
         *  verified by grepping every consumer in the project).
         *  Engine-gated keys are NOT here — they come from
         *  [FFmpegEngine.gateReason]. */
        private val LOCAL_GATE_REASONS: Map<String, String> = mapOf(
            Settings.USER_LOCALE to
                "The app interface is not translated yet — this build is English " +
                "only, so a language choice would change nothing on screen.",
            Settings.TV_MODE to
                "This build has no TV (leanback) interface, so TV mode has " +
                "nothing to switch to.",
            Settings.SUBTITLE_HW_ACCEL to
                "Hardware-accelerated subtitle rendering is not implemented; " +
                "subtitles are drawn by the player's software renderer.",
            Settings.USE_CUSTOM_POPWINDOW to
                "The custom PiP popup is not implemented. Picture-in-picture " +
                "itself works (the system window); this switch only selected " +
                "MX's custom popup style.",
            Settings.PLAYER_STYLE to
                "The player implements one interface style in this build; the " +
                "alternative style is not built.",
            Settings.PROGRESS_BAR_STYLE to
                "The player implements one progress-bar style in this build.",
            Settings.CONTROLS_KEY_UPDOWN_ACTION to
                "The player does not remap the volume keys in this build.",
            Settings.CONTROLS_WHEEL_ACTION to
                "The player does not handle mouse-wheel actions in this build.",
            Settings.GESTURE_SUBTITLE_ZOOM to
                "The subtitle pinch-zoom gesture is not implemented; subtitle " +
                "size is set under Subtitle → Text size.",
            Settings.STICKY_SMOOTH_SWITCH to
                "Smooth background switching is not implemented in this build.",
            Settings.ALBUM_ART to
                "Album-art display for background audio is not implemented in " +
                "this build.",
            Settings.SEEK_PREVIEWS_NET to
                "Seek previews are generated locally only in this build; there " +
                "is no network preview scope to select.",
            Settings.AUDIO_PLAYER to
                "A separate audio-player mode is not implemented; music plays " +
                "in the standard player from the Music surface.",
            Settings.HEADSET_SHOW_SYSTEM_VOLUME_UI to
                "System volume panel behaviour is not customised in this build.",
            Settings.SUBTITLE_BORDER_COLOR to
                "The subtitle border colour is not adjustable in this build; " +
                "the border uses the player's fixed outline.",
            Settings.CONTROLS_INTERFACE_AUTO_HIDE to
                "Interface auto-hide cannot be switched off in this build; " +
                "the auto-hide delay below is the working control.",
            Settings.SCREEN_SOFT_BUTTONS to
                "Soft-button display mode is not customised in this build.",
            Settings.SCREEN_BRIGHTNESS_ENABLED to
                "The brightness gesture is always on in this build (swipe up/" +
                "down on the left half of the player).",
            Settings.SCREEN_BRIGHTNESS to
                "A fixed startup brightness is not applied in this build; the " +
                "player restores your last swipe-set brightness.",
            Settings.SCREEN_BATTERY_CLOCK_TITLEBAR to
                "Battery & clock placement is fixed in this build.",
            Settings.SCREEN_STATUS_BAR to
                "Status-bar visibility is not customised in this build.",
            Settings.SCREEN_AUTO_HIDE_SOFT_BUTTONS to
                "Soft-button auto-hide is not customised in this build.",
            Settings.SCREEN_PAUSE_ON_OBSTRUCTED to
                "Pause-when-obstructed is not implemented in this build.",
            Settings.SCREEN_SHOW_INTERFACE_STARTUP to
                "The interface always shows on startup in this build.",
            Settings.SCREEN_PLACE_AT_BOTTOM to
                "Control placement is fixed in this build.",
            Settings.SCREEN_HIDE_DOWNLOAD_BUTTON to
                "This offline build has no download button, so there is " +
                "nothing to hide.",
            Settings.SCREEN_CORNER_OFFSET_ENABLED to
                "Corner offset is not implemented in this build.",
            Settings.SCREEN_CORNER_OFFSET to
                "Corner offset is not implemented in this build.",
            Settings.SCREEN_OSD_TEXT_COLOR to
                "OSD text colour is fixed in this build.",
            Settings.SCREEN_OSD_BG_ENABLED to
                "OSD background is fixed in this build.",
            Settings.SCREEN_OSD_BG_COLOR to
                "OSD background colour is fixed in this build.",
            Settings.VIDEO_ZOOM_DELAY to
                "Video zoom delay is not applied in this build.",
            Settings.VIDEO_ZOOM_LIMITED to
                "Video zoom limiting is not implemented in this build.",
            Settings.BUTTON_BACKLIGHT_OFF to
                "Button backlight control is not available in this build.",
            Settings.SOFT_MAIN_KEYS to
                "Soft main keys are not customised in this build.",
            Settings.ANDROID_40_COMPAT to
                "Android 4.0 compatibility mode has no meaning on this build " +
                "(minimum API 24) and is not implemented.",
            Settings.OMX_DECODER to DECODER_GATE,
            Settings.OMX_DECODER_LOCAL to DECODER_GATE,
            Settings.OMX_DECODER_NET to DECODER_GATE,
            Settings.TRY_HW_IF_OMX_FAILS to DECODER_GATE,
            Settings.TRY_OMX_IF_HW_FAILS to DECODER_GATE,
            Settings.OMX_DECODER_ALT to DECODER_GATE,
            // v0.4 ungate re-check (Stage Team): SW video decoding
            // itself is REAL now (SwVideoSession in the engine), so
            // the old "SW not in this build" framing would be a lie.
            // What remains true: these per-scope switches have no
            // reader — SW is selected for every local file through
            // "Default decoder" → SW, and the network scope is cut
            // (this build is fully offline). Gate stays, reason fixed.
            Settings.SW_DECODER_LOCAL to
                "Software decoding works in this build — choose it via " +
                "\"Default decoder\" → SW decoder. This separate per-scope " +
                "switch has no reader, so toggling it alone changes nothing.",
            Settings.SW_DECODER_NET to
                "This build is fully offline (no network playback), so the " +
                "network scope has nothing to apply to. Software decoding " +
                "for local files works via \"Default decoder\" → SW decoder.",
            Settings.OMX_AUDIO_WITH_SW_VIDEO to
                "In SW mode the bundled engine decodes audio and video " +
                "together in one session; mixing HW+ audio with SW video " +
                "is not a selectable combination in this build.",
            Settings.SW_AUDIO to
                "SW audio is engaged through the HW+/SW decoder choice in " +
                "this build; separate SW-audio switches are not wired.",
            Settings.SW_AUDIO_LOCAL to
                "SW audio is engaged through the HW+/SW decoder choice in " +
                "this build; separate SW-audio switches are not wired.",
            Settings.SW_AUDIO_NET to
                "SW audio is engaged through the HW+/SW decoder choice in " +
                "this build; separate SW-audio switches are not wired.",
            Settings.CORRECT_HW_ASPECT_RATIO to
                "HW aspect-ratio correction is handled by the system render " +
                "path and cannot be switched in this build.",
            Settings.HW_AUDIO_TRACK_SELECTABLE to
                "Audio tracks are chosen from the player's Audio Track menu " +
                "in this build; this switch is not wired."
        )
    }
}

/** Local Help screens (spec §2.1 Help ▸): What's New, Features,
 *  FAQ, About — static text, fully offline. (MX's "Check for
 *  updates" and "Bug Report" are internet features: CUT.) */
class HelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val topic = intent.getStringExtra("topic") ?: "about"
        val (title, body) = when (topic) {
            "whatsnew" -> "What's New" to
                "Version 0.2\n\n" +
                "• MX-style library with folders, grid view, sort orders and search\n" +
                "• Multi-select with recycle bin, private folder and playlists\n" +
                "• Full settings: Player, Decoder, Audio, Subtitle, List, General\n" +
                "• HW+ playback via our own FFmpeg build (bundled, from source)\n" +
                "• Playback history and a music library\n\n" +
                "No internet, no ads, no tracking — everything stays on your phone."
            "features" -> "Features" to
                "• Plays every common video format with hardware decoding\n" +
                "• HW+ / SW decoding powered by a bundled FFmpeg build\n" +
                "• Subtitles: SRT, ASS/SSA, VTT and more, with full styling\n" +
                "• Gestures: brightness, volume, seek, zoom & pan\n" +
                "• Resume playback, playback speed up to 4x, A–B repeat\n" +
                "• Private folder with PIN lock, recycle bin, playlists\n" +
                "• Fully offline: no internet permission, no ads, no trackers"
            "faq" -> "FAQ" to
                "Q: A video has no sound (AC3/DTS)?\n" +
                "A: Open it with \"Play using… → HW+\" — the bundled FFmpeg " +
                "engine decodes those codecs.\n\n" +
                "Q: Where are my subtitles?\n" +
                "A: Put a subtitle file with the same name next to the video, " +
                "or set a subtitle folder in Settings → Subtitle.\n\n" +
                "Q: How do I hide a video?\n" +
                "A: Long-press it → Hide, or move it to the private folder.\n\n" +
                "Q: Does the app use the internet?\n" +
                "A: Never. The app has no internet permission at all."
            else -> "About" to
                "Shadow Player 0.5.7\n\n" +
                "A fully offline video player.\n\n" +
                "Decoding: Android media framework (HW) plus FFmpeg 7.1.1 " +
                "built from source and bundled with the app (HW+/SW).\n\n" +
                "No internet permission · No ads · No tracking\n" +
                "Your videos and data never leave this phone."
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF3C8CF0.toInt())
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(10), dp(12), dp(10))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = title; textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        val bodyView = TextView(this).apply {
            text = body
            textSize = 15f
            setTextColor(0xFF35344C.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setLineSpacing(0f, 1.25f)
        }
        root.addView(ScrollView(this).apply { addView(bodyView) })
        setContentView(root)
    }
}
