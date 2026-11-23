package com.ghostblade.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/**
 * Clip editor surface (spec #107): pick a start/end range on a
 * dual-handle timeline over a live preview, then save the selection
 * as a new video file via [ClipTrimmer]'s stream copy.
 *
 * Launch contract: [intent] with the source file's path. Shadow
 * Two's player Tools menu and the library item menu can both wire
 * to it (see CLIP-EDITOR.md).
 *
 * Saving: on API 29+ the clip goes through MediaStore into the
 * source's own folder when it sits on shared storage (falling back
 * to Movies/GhostBladePlayer); before 29 it goes to the app's
 * Movies folder and is scanned into MediaStore. Either way the
 * result dialog says exactly where the clip landed.
 */
class ClipEditorActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"

        fun intent(ctx: Context, path: String): Intent =
            Intent(ctx, ClipEditorActivity::class.java).putExtra(EXTRA_PATH, path)
    }

    private lateinit var theme: Settings.ThemeColors
    private lateinit var videoView: VideoView
    private lateinit var slider: RangeSliderView
    private lateinit var rangeLabel: TextView
    private lateinit var posLabel: TextView
    private lateinit var noteLabel: TextView
    private lateinit var playButton: Button
    private lateinit var saveButton: Button
    private lateinit var progressBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView

    private var srcPath: String = ""
    private var probe: ClipProbe? = null
    private var startMs = 0L
    private var endMs = 0L
    private var saving = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val p = probe
            if (p != null && p.durationMs > 0) {
                val pos = try { videoView.currentPosition.toLong() } catch (t: Throwable) { 0L }
                slider.playheadMs = pos
                slider.invalidate()
                posLabel.text = "Position ${ClipLogic.formatTimeMs(pos)}"
                if (videoView.isPlaying && pos >= endMs - 60 && endMs > startMs) {
                    videoView.seekTo(startMs.toInt())
                }
            }
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        srcPath = intent.getStringExtra(EXTRA_PATH) ?: ""
        if (srcPath.isEmpty() || !File(srcPath).exists()) {
            Toast.makeText(this, "Video file not found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildUi()
        probeSource()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (::videoView.isInitialized && videoView.isPlaying) videoView.pause()
        super.onPause()
    }

    /* ---------------- UI ---------------- */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.pageBg)
        }
        // Chrome bar, same pattern as the other v0.2 surfaces.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.chrome)
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(10), dp(12), dp(10))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "Clip editor"; textSize = 18f
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
        }
        content.addView(TextView(this).apply {
            text = File(srcPath).name; textSize = 14f; setTextColor(theme.title)
            setPadding(0, 0, 0, dp(8))
        })

        // MX truth (view_edit_player_view.xml): the preview is a
        // SurfaceView filling a black, 8dp-radius card. VideoView
        // is the framework surface we have; adopted: black backing
        // + the decoded time-info pill below. NOT adopted: MX's
        // editor re-encodes frame-accurately — ours stays an
        // honest stream copy (keyframe disclosure in the note).
        videoView = VideoView(this)
        videoView.setBackgroundColor(0xFF000000.toInt())
        content.addView(videoView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply {
            setMargins(0, 0, 0, dp(10))
        })

        slider = RangeSliderView(this).apply {
            accentColor = theme.accent
            onRangeChanged = { s, e ->
                startMs = s
                endMs = e
                updateRangeLabel()
            }
        }
        content.addView(slider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        // Time-info pill after MX's card_time_info: 32dp high,
        // #80171717 background, 12sp white text ("pos / selected").
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x80171717.toInt())
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        rangeLabel = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        pill.addView(rangeLabel)
        posLabel = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            text = "Position 00:00.000"
        }
        pill.addView(posLabel)
        content.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        playButton = Button(this).apply {
            text = "▶ Preview"
            setOnClickListener { togglePreview() }
        }
        controls.addView(playButton, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Set start"
            setOnClickListener {
                val pos = currentPos()
                startMs = ClipLogic.moveStart(pos, endMs, probe?.durationMs ?: 0L)
                slider.startMs = startMs
                slider.invalidate()
                updateRangeLabel()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Set end"
            setOnClickListener {
                val pos = currentPos()
                endMs = ClipLogic.moveEnd(startMs, pos, probe?.durationMs ?: 0L)
                slider.endMs = endMs
                slider.invalidate()
                updateRangeLabel()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(controls)

        noteLabel = TextView(this).apply {
            textSize = 12f; setTextColor(theme.secondary)
            text = "Reading video…"
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(noteLabel)

        saveButton = Button(this).apply {
            text = "Save clip"
            isEnabled = false
            setOnClickListener { saveClip() }
        }
        content.addView(saveButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        progressBar = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        progressBox.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progressLabel = TextView(this).apply {
            textSize = 12f; setTextColor(theme.secondary)
        }
        progressBox.addView(progressLabel)
        content.addView(progressBox)

        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun currentPos(): Long =
        try { videoView.currentPosition.toLong() } catch (t: Throwable) { startMs }

    private fun updateRangeLabel() {
        rangeLabel.text = "Start ${ClipLogic.formatTimeMs(startMs)} · " +
            "End ${ClipLogic.formatTimeMs(endMs)} · " +
            "Length ${ClipLogic.formatTimeMs(endMs - startMs)}"
    }

    private fun togglePreview() {
        if (videoView.isPlaying) {
            videoView.pause()
            playButton.text = "▶ Preview"
        } else {
            val pos = currentPos()
            if (pos < startMs || pos >= endMs) videoView.seekTo(startMs.toInt())
            videoView.start()
            playButton.text = "⏸ Pause"
        }
    }

    /* ---------------- probe ---------------- */

    private fun probeSource() {
        Thread {
            val result = try {
                ClipTrimmer.probe(srcPath, Build.VERSION.SDK_INT)
            } catch (t: Throwable) {
                null
            }
            runOnUiThread { applyProbe(result) }
        }.start()
    }

    private fun applyProbe(result: ClipProbe?) {
        if (result == null || result.durationMs <= 0L) {
            noteLabel.text = "Couldn't read this video, so it can't be clipped here."
            return
        }
        probe = result
        val (s, e) = ClipLogic.clampRange(0L, result.durationMs, result.durationMs)
        startMs = s
        endMs = e
        slider.durationMs = result.durationMs
        slider.startMs = s
        slider.endMs = e
        slider.invalidate()
        updateRangeLabel()

        videoView.setVideoPath(srcPath)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            videoView.seekTo(startMs.toInt())
            videoView.start()
            playButton.text = "⏸ Pause"
        }
        videoView.setOnErrorListener { _, _, _ ->
            noteLabel.text = "Preview isn't available for this file, " +
                "but the range can still be set and saved."
            true
        }

        when (result.decision.plan) {
            ClipLogic.Plan.UNSUPPORTED -> {
                noteLabel.text = result.decision.reason
                saveButton.isEnabled = false
            }
            else -> {
                noteLabel.text = "Clips are copied without re-encoding — fast, " +
                    "with no quality loss. The saved clip starts at the nearest " +
                    "keyframe at or before your start point, so it may begin " +
                    "slightly earlier. Subtitle tracks are not copied."
                saveButton.isEnabled = true
            }
        }
    }

    /* ---------------- save ---------------- */

    private fun saveClip() {
        val p = probe ?: return
        if (saving) return
        if (videoView.isPlaying) {
            videoView.pause()
            playButton.text = "▶ Preview"
        }
        val outName = ClipLogic.outputFileName(File(srcPath).name, p.decision.extension)
        val target = createTarget(outName, p.decision)
        if (target == null) {
            AlertDialog.Builder(this)
                .setTitle("Clip editor")
                .setMessage("Couldn't create an output file for the clip.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        saving = true
        saveButton.isEnabled = false
        progressBox.visibility = View.VISIBLE
        progressBar.progress = 0
        progressLabel.text = "Saving clip…"
        val s = startMs
        val e = endMs
        val decision = p.decision
        Thread {
            val result = ClipTrimmer.trim(this, srcPath, s, e, decision, target) { fraction ->
                runOnUiThread {
                    progressBar.progress = (fraction * 100).toInt()
                    progressLabel.text = "Saving clip… ${(fraction * 100).toInt()}%"
                }
            }
            runOnUiThread { onTrimDone(result) }
        }.start()
    }

    private fun onTrimDone(result: TrimResult) {
        saving = false
        progressBox.visibility = View.GONE
        saveButton.isEnabled = true
        val target = result.target
        if (!result.success || target == null) {
            AlertDialog.Builder(this)
                .setTitle("Clip editor")
                .setMessage(result.message)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        var playPath: String? = null
        when (target) {
            is ClipTarget.FileTarget -> {
                FileOps.scanPaths(this, listOf(target.file.absolutePath))
                playPath = target.file.absolutePath
            }
            is ClipTarget.UriTarget -> {
                try {
                    contentResolver.update(target.uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                } catch (t: Throwable) { /* entry already visible */ }
                playPath = resolvePath(target.uri)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Clip saved")
            .setMessage("Saved to ${target.displayName}")
            .setPositiveButton("Done") { _, _ -> finish() }
        if (playPath != null) {
            val pp = playPath
            builder.setNeutralButton("Play") { _, _ ->
                launchPlayer(this, listOf(pp), 0,
                    mapOf(pp to File(pp).name))
                finish()
            }
        }
        builder.show()
    }

    /** Create the output target: MediaStore into the source's
     *  folder on API 29+ when possible; otherwise the app Movies
     *  folder (always writable, scanned into the library). */
    private fun createTarget(outName: String,
                             decision: ClipLogic.PlanDecision): ClipTarget? {
        if (Build.VERSION.SDK_INT >= 29) {
            val uriTarget = createMediaStoreTarget(outName, decision)
            if (uriTarget != null) return uriTarget
        }
        val dir = moviesDir() ?: return null
        val file = ClipLogic.uniqueFile(dir, outName)
        return ClipTarget.FileTarget(file)
    }

    private fun moviesDir(): File? {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(filesDir, "clips")
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    private fun createMediaStoreTarget(outName: String,
                                       decision: ClipLogic.PlanDecision):
        ClipTarget.UriTarget? {
        val relative = relativeDirOf(srcPath) ?: "Movies/GhostBladePlayer"
        val name = uniqueMediaStoreName(outName, relative)
        val mime = if (decision.plan == ClipLogic.Plan.WEBM) "video/webm" else "video/mp4"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, relative)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            ClipTarget.UriTarget(uri, "$relative/$name")
        } catch (t: Throwable) {
            null
        }
    }

    /** "Movies/Camera" style relative dir of the source file, or
     *  null when it isn't on primary shared storage. */
    private fun relativeDirOf(path: String): String? {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val parent = File(path).parent ?: return null
        if (parent != root && !parent.startsWith("$root/")) return null
        val rel = parent.removePrefix(root).trim('/')
        return rel.ifEmpty { null }
    }

    private fun uniqueMediaStoreName(name: String, relativePath: String): String {
        fun exists(candidate: String): Boolean {
            return try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.Video.Media.RELATIVE_PATH}=? AND " +
                        "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                    arrayOf("$relativePath/", candidate), null
                )?.use { it.count > 0 } ?: false
            } catch (t: Throwable) {
                false
            }
        }
        if (!exists(name)) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    private fun resolvePath(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * Dual-handle range selector: a track with the selected region in
 * the accent colour, two draggable handles and a thin playhead line
 * showing the preview position. All range math goes through
 * [ClipLogic], so the view itself holds no policy.
 */
class RangeSliderView(ctx: Context) : View(ctx) {

    var durationMs: Long = 0L
    var startMs: Long = 0L
    var endMs: Long = 0L
    var playheadMs: Long = -1L
    var accentColor: Int = 0xFF3C8CF0.toInt()
    var trackColor: Int = 0xFF3A4A5C.toInt()
    var onRangeChanged: ((startMs: Long, endMs: Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var draggingStart = true

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    init {
        setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
    }

    private fun xFor(ms: Long): Float {
        val w = (width - paddingLeft - paddingRight).toFloat()
        if (durationMs <= 0L || w <= 0f) return paddingLeft.toFloat()
        return paddingLeft + w * (ms.toFloat() / durationMs.toFloat())
    }

    private fun msFor(x: Float): Long {
        val w = (width - paddingLeft - paddingRight).toFloat()
        if (durationMs <= 0L || w <= 0f) return 0L
        val frac = ((x - paddingLeft) / w).coerceIn(0f, 1f)
        return (frac * durationMs).toLong()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val trackH = dp(4f)
        canvas.drawRect(left, cy - trackH / 2, right, cy + trackH / 2, trackPaint)
        val sx = xFor(startMs)
        val ex = xFor(endMs)
        canvas.drawRect(sx, cy - trackH / 2, ex, cy + trackH / 2, fillPaint)
        if (playheadMs in 0..maxOf(durationMs, 0L) && durationMs > 0L) {
            val px = xFor(playheadMs)
            canvas.drawRect(px - dp(1f), cy - dp(16f), px + dp(1f), cy + dp(16f),
                playheadPaint)
        }
        drawHandle(canvas, sx, cy)
        drawHandle(canvas, ex, cy)
    }

    private fun drawHandle(canvas: Canvas, x: Float, cy: Float) {
        canvas.drawCircle(x, cy, dp(11f), handlePaint)
        canvas.drawCircle(x, cy, dp(11f), handleRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0L) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val sx = xFor(startMs)
                val ex = xFor(endMs)
                draggingStart = kotlin.math.abs(event.x - sx) <= kotlin.math.abs(event.x - ex)
                applyTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                applyTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(x: Float) {
        val ms = msFor(x)
        if (draggingStart) {
            startMs = ClipLogic.moveStart(ms, endMs, durationMs)
        } else {
            endMs = ClipLogic.moveEnd(startMs, ms, durationMs)
        }
        invalidate()
        onRangeChanged?.invoke(startMs, endMs)
    }
}
