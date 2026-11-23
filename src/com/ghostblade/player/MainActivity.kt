package com.ghostblade.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** GhostBlade Player library (v0.4, MX carbon copy — MASTER-SCENE-SPEC L1).
 *
 *  Chrome: 56dp app bar (?actionBarSize; title 18sp bold per decoded
 *  toolbar.xml), Folders · All folders · Files views, list and grid
 *  layouts rebuilt to the DECODED MX layouts (list_row_media.xml:
 *  96×56dp thumb card, 14sp semibold title; list_grid_media.xml:
 *  card ratio 1:0.564; list_row/grid_listable.xml: 62dp folder icon,
 *  15sp/12sp text), MX sort orders with MX direction labels, title
 *  search with history, multi-select action mode, per-item menu,
 *  recycle bin / private folder / playlists integration, first-run
 *  guide, and a framework pull-to-refresh equivalent of MX's
 *  SwipeRefresher host (activity_media_list.xml).
 *
 *  Playback launches go through [launchPlayer] (PlayerActivity's
 *  intent contract); resume prompting is the player's job per
 *  Settings → Player → Resume (PSet-bridged). */
class MainActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private lateinit var rootLayout: LinearLayout
    private lateinit var normalBar: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionTitle: TextView
    private lateinit var titleView: TextView
    private lateinit var backBtn: ImageButton
    private lateinit var viewToggleBtn: ImageButton
    private lateinit var searchInput: EditText
    private lateinit var listView: ListView
    private lateinit var gridView: GridView
    private lateinit var emptyView: TextView
    private lateinit var fab: FrameLayout
    private lateinit var pullIndicator: FrameLayout
    private lateinit var pullIcon: ImageView
    private lateinit var statusBar: LinearLayout
    private lateinit var statusText: TextView

    private var rawScan: List<VideoItem> = emptyList()
    private var videos: List<VideoItem> = emptyList()       // filtered + sorted
    private var displayVideos: List<VideoItem> = emptyList() // current page of videos
    private var folderGroups: List<FolderGroup> = emptyList()
    private var currentFolder: String? = null
    private var searching = false
    private var searchQuery = ""
    private var selectionMode = false
    private val selected = linkedSetOf<String>()
    private var adapter: BaseAdapter? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    /** v0.5.7: single-threaded display-model worker. Filtering,
     *  grouping, sorting and every per-row derived string are
     *  computed HERE, never on the UI thread — v0.5.6 built the
     *  whole display model inside refreshDisplay() on the main
     *  thread (and re-derived strings per row during flings),
     *  which is the library lag Sir reported. Serial execution +
     *  a generation counter keep results in order. */
    private val displayIo = Executors.newSingleThreadExecutor()
    @Volatile private var displayGen = 0

    /** Per-row data precomputed on [displayIo] for the CURRENT
     *  display model, keyed by video path. The adapter reads these
     *  instead of calling Store / formatting strings while the
     *  list is scrolling. */
    private data class RowMeta(
        val fields: String,
        val status: String,
        val lastPlayedText: String?,
        val watchedFrac: Float,
        val favourite: Boolean,
        val isNew: Boolean,
        val finished: Boolean
    )
    @Volatile private var rowMeta: Map<String, RowMeta> = emptyMap()
    /** Folder new-counts precomputed on [displayIo], keyed by
     *  folder name (folderRow used to re-count a whole folder's
     *  videos on every bind). */
    @Volatile private var folderNewCounts: Map<String, Int> = emptyMap()

    private val thumbCache = object : LruCache<Long, Bitmap>(96) {}

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* ---- L1 decoded-truth helpers (MASTER-SCENE-SPEC Step 4) ----
     * Colours are MX's decoded dark/light skin values (colors.xml):
     * grid primary #DADDE4 / #35344C, secondary #99DADDE4 / #96A2BA.
     * theme.title already equals MX's primary in both skins; the
     * secondary here overrides our older theme.secondary, which did
     * not match MX's decoded values. */
    private fun mxPrimary(): Int = theme.title
    private fun mxSecondary(): Int =
        if (theme.dark) 0x99DADDE4.toInt() else 0xFF96A2BA.toInt()
    private fun mxMoreColor(): Int =
        if (theme.dark) 0xFFDADDE4.toInt() else 0xFF96A2BA.toInt()
    private fun mxSelectedBg(): Int =
        if (theme.dark) 0x26FFFFFF else 0xFFF1F1F1.toInt()
    private fun mxBoxBg(): Int =
        if (theme.dark) 0x4DDADDE4 else 0xFFF2F6FA.toInt()
    private fun mxBoxText(): Int =
        if (theme.dark) 0xFFDADDE4.toInt() else 0xFF96A2BA.toInt()

    /** round_box_small.xml: rectangle, 2dp corners, 4dp horizontal
     *  padding (the TextView supplies the padding); the solid colour
     *  is the caller's tint (tag_red / duration_background / tag_dark).
     *  v0.5.7: instances are cached per colour — rows used to mint a
     *  fresh GradientDrawable on every bind. */
    private val tagBgCache = HashMap<Int, GradientDrawable>()
    private fun tagBg(color: Int): GradientDrawable = tagBgCache.getOrPut(color) {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(color)
        }
    }

    /** v0.5.7: row press feedback — a subtle pressed tint over the
     *  row's normal colour, MX-style. One StateListDrawable per row
     *  (drawables carry per-view state; the colour drawables inside
     *  are shared). */
    private fun pressBackground(normalColor: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),
                ColorDrawable(mxSelectedBg()))
            addState(intArrayOf(), ColorDrawable(normalColor))
        }

    /** ic_more_option / ic_grid_more, catalogue-measured 18×18dp
     *  glyph: three dots. Redrawn, never MX's raster. */
    private fun dotsDrawable(color: Int, horizontal: Boolean): Drawable =
        object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.FILL
            }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val r = minOf(b.width(), b.height()) * 0.11f
                val cx = b.exactCenterX(); val cy = b.exactCenterY()
                val step = minOf(b.width(), b.height()) * 0.33f
                for (i in -1..1) {
                    if (horizontal) canvas.drawCircle(cx + i * step, cy, r, paint)
                    else canvas.drawCircle(cx, cy + i * step, r, paint)
                }
            }
            override fun setAlpha(a: Int) { paint.alpha = a }
            override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

    /** ic_file_selected, catalogue-measured 24×24dp: MX-blue disc
     *  with a white check (the raster is white-tinted in MX's
     *  layouts; the disc is the selection affordance colour). */
    private fun selectedDrawable(): Drawable = object : Drawable() {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3C8CF0.toInt(); style = Paint.Style.FILL
        }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE
            strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            tick.strokeWidth = b.width() * 0.09f
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, disc)
            val p = Path().apply {
                moveTo(b.left + b.width() * 0.28f, b.top + b.height() * 0.52f)
                lineTo(b.left + b.width() * 0.44f, b.top + b.height() * 0.68f)
                lineTo(b.left + b.width() * 0.74f, b.top + b.height() * 0.34f)
            }
            canvas.drawPath(p, tick)
        }
        override fun setAlpha(a: Int) { disc.alpha = a; tick.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Mini folder silhouette for the 12dp rl_info slot
     *  (ic_folder_internal, catalogue ~9.7×7.3dp) and the 24dp
     *  status-bar slot (ic_statusbar_folder). v0.5: proportions
     *  corrected to the measured MX aspect (body = 80% x 60% of
     *  the box, centred) and grey hues matching MX's device look. */
    private fun folderMiniDrawable(dark: Boolean): Drawable = object : Drawable() {
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // v0.5.2: slate hues on dark themes (MX's dark folder),
            // the measured greys on light.
            color = if (dark) 0xFF333E4A.toInt() else 0xFF9E9E9E.toInt(); style = Paint.Style.FILL
        }
        private val front = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF414F5E.toInt() else 0xFFCCCCCC.toInt(); style = Paint.Style.FILL
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            val body = Path().apply {
                moveTo(b.left + w * 0.10f, b.top + h * 0.80f)
                lineTo(b.left + w * 0.10f, b.top + h * 0.20f)
                lineTo(b.left + w * 0.44f, b.top + h * 0.20f)
                lineTo(b.left + w * 0.54f, b.top + h * 0.34f)
                lineTo(b.left + w * 0.90f, b.top + h * 0.34f)
                lineTo(b.left + w * 0.90f, b.top + h * 0.80f)
                close()
            }
            canvas.drawPath(body, back)
            canvas.drawRect(b.left + w * 0.10f, b.top + h * 0.44f,
                b.left + w * 0.90f, b.top + h * 0.80f, front)
        }
        override fun setAlpha(a: Int) { back.alpha = a; front.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** v0.5 drawn glyph set for the library chrome (app bar, quick
     *  row, bottom nav, thumb overlays) — 24-unit grid like the
     *  player's MxIcon, geometry from the v0.5 measurement pass
     *  (MX raster ink boxes, device screenshots). */
    private class GlyphDrawable(private val kind: Int, private val tint: Int) : Drawable() {
        companion object {
            const val BACK = 1; const val CLOSE = 2; const val PLAY_LAST = 3
            const val SEARCH = 4; const val GRID = 5; const val LIST = 6
            const val NOTE = 8; const val PLAYLIST = 9; const val CLEANER = 10
            const val LOCK = 11; const val FOLDER = 12; const val PERSON = 13
            const val PLAY = 14; const val HEART = 15; const val DELETE = 16
            const val SHARE = 17; const val HIDE = 18; const val REBUILD = 19
            const val PROPERTIES = 20; const val REFRESH = 21; const val PLAY_USING = 22
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint; style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            val u = minOf(w, h) / 24f
            stroke.strokeWidth = 1.6f * u
            when (kind) {
                BACK -> {
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
                PLAY_LAST -> {
                    // v0.5.4: redrawn to MX's measured ink — on Sir's
                    // MX screenshot this glyph's ink spans 22.4dp of
                    // the 48dp button. With the bar button's 30dp
                    // drawable box (u = 1.25dp) that is a 17.9u span;
                    // no scale hacks (v0.5.3's x1.35 never landed).
                    stroke.strokeWidth = 2.4f * u
                    canvas.drawRoundRect(RectF(3.05f * u, 4.6f * u, 20.95f * u, 19.4f * u), 2f * u, 2f * u, stroke)
                    val p = Path()
                    p.moveTo(10f * u, 8.2f * u); p.lineTo(16.9f * u, 12f * u); p.lineTo(10f * u, 15.8f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                SEARCH -> {
                    // v0.5.4: MX's search ink measures 17.7dp wide =
                    // a 14.2u span in the 30dp box (4.6u..18.8u).
                    stroke.strokeWidth = 2.4f * u
                    canvas.drawCircle(10.2f * u, 10.2f * u, 5.6f * u, stroke)
                    stroke.strokeWidth = 2.6f * u
                    canvas.drawLine(14.3f * u, 14.3f * u, 18.8f * u, 18.8f * u, stroke)
                }
                GRID -> {
                    // v0.5.4: MX's grid ink measures 20dp wide = a
                    // 16u span in the 30dp box (4u..20u).
                    canvas.drawRoundRect(RectF(4f * u, 4f * u, 11.3f * u, 11.3f * u), 1.4f * u, 1.4f * u, fill)
                    canvas.drawRoundRect(RectF(12.7f * u, 4f * u, 20f * u, 11.3f * u), 1.4f * u, 1.4f * u, fill)
                    canvas.drawRoundRect(RectF(4f * u, 12.7f * u, 11.3f * u, 20f * u), 1.4f * u, 1.4f * u, fill)
                    canvas.drawRoundRect(RectF(12.7f * u, 12.7f * u, 20f * u, 20f * u), 1.4f * u, 1.4f * u, fill)
                }
                LIST -> {
                    // v0.5.4: same 16u span discipline as GRID.
                    for (cy in floatArrayOf(6f, 12f, 18f)) {
                        canvas.drawCircle(5.5f * u, cy * u, 1.8f * u, fill)
                        canvas.drawLine(9.5f * u, cy * u, 20f * u, cy * u, stroke.apply { strokeWidth = 2.2f * u })
                    }
                }
                NOTE -> {
                    canvas.drawOval(RectF(4f * u, 14.5f * u, 10.5f * u, 21f * u), fill)
                    canvas.drawRect(RectF(9.6f * u, 3f * u, 11.2f * u, 16.5f * u), fill)
                    val p = Path()
                    p.moveTo(9.6f * u, 3f * u); p.quadTo(14.5f * u, 3.5f * u, 17f * u, 5.5f * u)
                    p.lineTo(17f * u, 11f * u); p.quadTo(14f * u, 8f * u, 9.6f * u, 5.5f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                PLAYLIST -> {
                    canvas.drawRect(RectF(2f * u, 7f * u, 13f * u, 8.8f * u), fill)
                    canvas.drawRect(RectF(2f * u, 15.2f * u, 13f * u, 17f * u), fill)
                    val p = Path()
                    p.moveTo(15f * u, 5.5f * u); p.lineTo(22.3f * u, 12.5f * u); p.lineTo(15f * u, 19.5f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                CLEANER -> { // broom: stick + swept head
                    stroke.strokeWidth = 2f * u
                    canvas.drawLine(10f * u, 3f * u, 13.5f * u, 12.5f * u, stroke)
                    val p = Path()
                    p.moveTo(8.5f * u, 12.5f * u); p.lineTo(15.5f * u, 12.5f * u)
                    p.lineTo(17.5f * u, 20.5f * u); p.lineTo(6.5f * u, 20.5f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                LOCK -> {
                    val p = Path()
                    p.moveTo(5.5f * u, 11f * u); p.lineTo(5.5f * u, 7f * u)
                    p.arcTo(RectF(5.5f * u, 1f * u, 18.5f * u, 11.5f * u), 180f, 180f)
                    p.lineTo(18.5f * u, 11f * u)
                    canvas.drawPath(p, stroke)
                    canvas.drawRoundRect(RectF(2.5f * u, 10f * u, 21.5f * u, 22f * u), 1.5f * u, 1.5f * u, fill)
                }
                FOLDER -> {
                    val p = Path()
                    p.moveTo(2.5f * u, 20f * u); p.lineTo(2.5f * u, 5.5f * u); p.lineTo(9f * u, 5.5f * u)
                    p.lineTo(11.5f * u, 8f * u); p.lineTo(21.5f * u, 8f * u); p.lineTo(21.5f * u, 20f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                PERSON -> {
                    canvas.drawCircle(12f * u, 8.2f * u, 3.4f * u, fill)
                    val p = Path()
                    p.moveTo(4.8f * u, 20.5f * u); p.lineTo(6.2f * u, 15.6f * u)
                    p.quadTo(12f * u, 13.6f * u, 17.8f * u, 15.6f * u); p.lineTo(19.2f * u, 20.5f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                PLAY -> {
                    val p = Path()
                    p.moveTo(5f * u, 3.5f * u); p.lineTo(20.5f * u, 12f * u); p.lineTo(5f * u, 20.5f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                HEART -> {
                    val p = Path()
                    p.moveTo(12f * u, 20.2f * u)
                    p.cubicTo(5.8f * u, 15.6f * u, 3.4f * u, 12.2f * u, 3.4f * u, 8.9f * u)
                    p.cubicTo(3.4f * u, 6.1f * u, 5.5f * u, 4.1f * u, 8f * u, 4.1f * u)
                    p.cubicTo(9.7f * u, 4.1f * u, 11.2f * u, 5f * u, 12f * u, 6.3f * u)
                    p.cubicTo(12.8f * u, 5f * u, 14.3f * u, 4.1f * u, 16f * u, 4.1f * u)
                    p.cubicTo(18.5f * u, 4.1f * u, 20.6f * u, 6.1f * u, 20.6f * u, 8.9f * u)
                    p.cubicTo(20.6f * u, 12.2f * u, 18.2f * u, 15.6f * u, 12f * u, 20.2f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                DELETE -> {
                    // v0.5.4: trash can for the selection bar's new
                    // Delete button (Sir: holding a video showed no
                    // deletion button at all).
                    stroke.strokeWidth = 2.1f * u
                    canvas.drawLine(4f * u, 6.2f * u, 20f * u, 6.2f * u, stroke)
                    canvas.drawRoundRect(RectF(9.6f * u, 3.4f * u, 14.4f * u, 6.2f * u), 1f * u, 1f * u, stroke)
                    val p = Path()
                    p.moveTo(6.2f * u, 6.2f * u); p.lineTo(17.8f * u, 6.2f * u)
                    p.lineTo(16.8f * u, 20f * u); p.lineTo(7.2f * u, 20f * u)
                    p.close(); canvas.drawPath(p, stroke)
                    canvas.drawLine(10.2f * u, 9.6f * u, 10.2f * u, 16.8f * u, stroke)
                    canvas.drawLine(13.8f * u, 9.6f * u, 13.8f * u, 16.8f * u, stroke)
                }
                SHARE -> {
                    stroke.strokeWidth = 1.9f * u
                    canvas.drawLine(8.1f * u, 10.9f * u, 15.4f * u, 6.6f * u, stroke)
                    canvas.drawLine(8.1f * u, 13.1f * u, 15.4f * u, 17.4f * u, stroke)
                    canvas.drawCircle(5.8f * u, 12f * u, 2.5f * u, fill)
                    canvas.drawCircle(17.6f * u, 5.4f * u, 2.5f * u, fill)
                    canvas.drawCircle(17.6f * u, 18.6f * u, 2.5f * u, fill)
                }
                HIDE -> {
                    // eye outline + pupil + slash
                    stroke.strokeWidth = 2f * u
                    val p = Path()
                    p.moveTo(3f * u, 12f * u)
                    p.quadTo(12f * u, 4.6f * u, 21f * u, 12f * u)
                    p.quadTo(12f * u, 19.4f * u, 3f * u, 12f * u)
                    p.close(); canvas.drawPath(p, stroke)
                    canvas.drawCircle(12f * u, 12f * u, 2.6f * u, fill)
                    stroke.strokeWidth = 2.2f * u
                    canvas.drawLine(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u, stroke)
                }
                REBUILD -> {
                    // picture glyph: frame + sun + mountains
                    stroke.strokeWidth = 2f * u
                    canvas.drawRoundRect(RectF(3.5f * u, 5f * u, 20.5f * u, 19f * u), 1.5f * u, 1.5f * u, stroke)
                    canvas.drawCircle(16.6f * u, 9f * u, 1.6f * u, fill)
                    val p = Path()
                    p.moveTo(5.5f * u, 17f * u); p.lineTo(10.5f * u, 11.5f * u)
                    p.lineTo(13.8f * u, 14.8f * u); p.lineTo(16f * u, 12.8f * u)
                    p.lineTo(18.8f * u, 17f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                PROPERTIES -> {
                    stroke.strokeWidth = 2.1f * u
                    canvas.drawCircle(12f * u, 12f * u, 8.6f * u, stroke)
                    canvas.drawCircle(12f * u, 7.7f * u, 1.35f * u, fill)
                    stroke.strokeWidth = 2.4f * u
                    canvas.drawLine(12f * u, 11.2f * u, 12f * u, 16.4f * u, stroke)
                }
                REFRESH -> {
                    // MX's circular-arrow refresh (ic_action_refresh):
                    // a near-full arc, gap at top-right, arrowhead at
                    // the arc's start pointing along the rotation.
                    stroke.strokeWidth = 2.3f * u
                    canvas.drawArc(RectF(4.6f * u, 4.6f * u, 19.4f * u, 19.4f * u),
                        -32f, 288f, false, stroke)
                    val p = Path()
                    p.moveTo(20.9f * u, 3.2f * u); p.lineTo(20.5f * u, 9.3f * u)
                    p.lineTo(14.9f * u, 6.9f * u)
                    p.close(); canvas.drawPath(p, fill)
                }
                PLAY_USING -> {
                    // play triangle + options dots (Play using…)
                    val p = Path()
                    p.moveTo(4.5f * u, 6.5f * u); p.lineTo(13.5f * u, 12f * u); p.lineTo(4.5f * u, 17.5f * u)
                    p.close(); canvas.drawPath(p, fill)
                    for (cy in floatArrayOf(6.5f, 12f, 17.5f)) {
                        canvas.drawCircle(18.6f * u, cy * u, 1.5f * u, fill)
                    }
                }
            }
            canvas.restore()
        }
        override fun setAlpha(a: Int) { fill.alpha = a; stroke.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; stroke.colorFilter = cf }
        override fun getIntrinsicWidth(): Int = 24
        override fun getIntrinsicHeight(): Int = 24
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** v0.5: MX app-bar action = fixed 48x48dp button with a 24dp
     *  glyph (AppCompat action-button minimums, decoded menu icons).
     *  Replaces the Unicode text-glyph buttons, whose widths varied
     *  per glyph and made the bar's spacing uneven. */
    private fun barIconButton(drawable: Drawable, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageDrawable(drawable)
            setBackgroundColor(0x00000000)
            // v0.5.4: padding 12dp -> 9dp (30dp drawable box) and
            // FIT_XY so the glyph's bounds are exactly that box —
            // the old FIT_CENTER + 24px intrinsic size left the
            // rendered ink far smaller than MX's bar icons.
            setPadding(dp(9), dp(9), dp(9), dp(9))
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun toggleLayout() {
        val grid = Store.stringSetting(Settings.LIST_LAYOUT) == "grid"
        Store.setStringSetting(Settings.LIST_LAYOUT, if (grid) "list" else "grid")
        refreshDisplay()
    }

    /** v0.5: MX Folders-screen quick-access row (Sir's device
     *  screenshots): 44dp dark circles with 24dp glyphs + 11sp
     *  labels, ~65dp pitch. File Transfer and Status Saver are
     *  absent — network / external-app features, cut by rule. */
    
    /** v0.5: MX bottom navigation (device screenshots): Local /
     *  Music / Me at 24dp glyph + 11sp label, active tab MX blue.
     *  MX's fourth tab, Transfer, is a network share — cut. */
    
    /* ================= lifecycle ================= */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Store.asyncWrites = true
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        buildUi()
        if (hasPermission()) reload() else requestPermission()
        showGuideIfFirstRun()
    }

    override fun onResume() {
        super.onResume()
        // Settings (theme etc.) or file state may have changed.
        val newTheme = Settings.currentThemeColors(this)
        if (newTheme != theme) {
            theme = newTheme
            buildUi()
        }
        if (hasPermission()) reload()
    }

    /* ================= UI construction ================= */

    private fun barTextButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.pageBg)
        }
        // --- normal app bar ---
        normalBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.chrome)
            setPadding(dp(8), 0, dp(4), 0)
        }
        backBtn = barIconButton(GlyphDrawable(GlyphDrawable.BACK, 0xFFFFFFFF.toInt())) { onBackPressed() }
        backBtn.visibility = View.GONE
        titleView = TextView(this).apply {
            text = "Shadow Player"
            // toolbar.xml: tv_title 18sp, font_muli_bold, maxLines 1,
            // ellipsize end. Framework bold stands in for Mulish Bold.
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        normalBar.addView(backBtn)
        normalBar.addView(titleView, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        normalBar.addView(barIconButton(GlyphDrawable(GlyphDrawable.PLAY_LAST, 0xFFFFFFFF.toInt())) { playLast() })
        normalBar.addView(barIconButton(GlyphDrawable(GlyphDrawable.SEARCH, 0xFFFFFFFF.toInt())) { enterSearch() })
        viewToggleBtn = barIconButton(GlyphDrawable(GlyphDrawable.GRID, 0xFFFFFFFF.toInt())) { toggleLayout() }
        normalBar.addView(viewToggleBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        val overflowBtn = barIconButton(dotsDrawable(0xFFFFFFFF.toInt(), false)) {}
        overflowBtn.setOnClickListener { showOverflow(overflowBtn) }
        normalBar.addView(overflowBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        rootLayout.addView(normalBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        // --- search bar ---
        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.chrome)
            setPadding(dp(4), 0, dp(4), 0)
            visibility = View.GONE
        }
        searchBar.addView(barIconButton(GlyphDrawable(GlyphDrawable.BACK, 0xFFFFFFFF.toInt())) { exitSearch() })
        searchInput = EditText(this).apply {
            hint = "Search videos"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xB3FFFFFF.toInt())
            setBackgroundColor(0x00000000)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString() ?: ""
                    refreshDisplay()
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    if (searchQuery.isNotBlank()) Store.addSearchQuery(searchQuery)
                    true
                } else false
            }
        }
        searchBar.addView(searchInput, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchBar.addView(barIconButton(GlyphDrawable(GlyphDrawable.CLOSE, 0xFFFFFFFF.toInt())) {
            searchInput.text.clear()
        })
        rootLayout.addView(searchBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        // --- selection action bar (black, spec §2.4) ---
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        selectionTitle = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), 0, dp(6), 0)
        }
        selectionBar.addView(selectionTitle)
        val actionsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // v0.5.4: the selection actions are now proper icon+label
        // BUTTONS like MX's action mode — in v0.5.3 they were small
        // 13sp text labels, and there was NO Delete at all: Sir held
        // a video and found no deletion button. Delete now sits
        // right after Share, wired to the same recycle-bin flow as
        // the per-item menu.
        fun selectionActionButton(label: String, kind: Int): View {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener { onSelectionAction(label) }
            }
            cell.addView(ImageView(this).apply {
                setImageDrawable(GlyphDrawable(kind, 0xFFFFFFFF.toInt()))
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            cell.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return cell
        }
        val actionKinds = mapOf(
            "Play selected items" to GlyphDrawable.PLAY,
            "Play using…" to GlyphDrawable.PLAY_USING,
            "Share" to GlyphDrawable.SHARE,
            "Delete" to GlyphDrawable.DELETE,
            "Lock in Private Folder" to GlyphDrawable.LOCK,
            "Hide" to GlyphDrawable.HIDE,
            "Rebuild thumbnails" to GlyphDrawable.REBUILD,
            "Properties" to GlyphDrawable.PROPERTIES)
        for (label in SELECTION_ACTIONS) {
            actionsRow.addView(selectionActionButton(label,
                actionKinds[label] ?: GlyphDrawable.PLAY))
        }
        actionsScroll.addView(actionsRow)
        selectionBar.addView(actionsScroll, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(barIconButton(GlyphDrawable(GlyphDrawable.CLOSE, 0xFFFFFFFF.toInt())) { exitSelection() })
        rootLayout.addView(selectionBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        // --- content (SwipeRefresher equivalent, see pullWrap below) ---
        val contentFrame = FrameLayout(this)
        listView = ListView(this).apply {
            divider = null
            setBackgroundColor(theme.pageBg)
        }
        gridView = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(160)
            verticalSpacing = dp(16)
            horizontalSpacing = dp(16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(theme.pageBg)
            visibility = View.GONE
        }
        // v0.5.7: rows cascade in (fade + short rise) whenever a
        // fresh display model lands — see refreshDisplay's apply
        // phase, which schedules the layout animation.
        val cascade = android.view.animation.AnimationUtils.loadLayoutAnimation(
            this, R.anim.list_layout)
        listView.layoutAnimation = cascade
        gridView.layoutAnimation = cascade
        emptyView = TextView(this).apply {
            // list_layout_recyclerview.xml rl_empty: 13sp, colour
            // mxskin__list_more (dark #DADDE4 / light #96A2BA),
            // padding 12sp sides, 24sp top, centred horizontally.
            textSize = 13f
            setTextColor(if (theme.dark) 0xFFDADDE4.toInt() else 0xFF96A2BA.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(24), dp(12), 0)
            visibility = View.GONE
        }
        contentFrame.addView(listView)
        contentFrame.addView(gridView)
        contentFrame.addView(emptyView)
        // --- status bar (activity_media_list.xml rl_status) ---
        // 24dp folder icon (marginLeft 18dp) + secondary 12sp text,
        // padding L4/T12/R18/B12, pinned to the bottom of the list
        // area, gone unless a folder is open.
        statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.card)
            visibility = View.GONE
        }
        val statusIcon = ImageView(this).apply {
            setImageDrawable(folderMiniDrawable(theme.dark))
        }
        statusBar.addView(statusIcon, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            setMargins(dp(18), 0, 0, 0)
        })
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(mxSecondary())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(12), dp(18), dp(12))
        }
        statusBar.addView(statusText, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        contentFrame.addView(statusBar, statusParams)
        // Floating play-last button (List setting, spec §2.1)
        fab = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3C8CF0.toInt())
            }
            // v0.5: drawn 24dp play glyph centred in the 48dp FAB
            // (was a "▶" font glyph).
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(GlyphDrawable(GlyphDrawable.PLAY, 0xFFFFFFFF.toInt()))
                isClickable = false
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            visibility = View.GONE
            setOnClickListener { playLast() }
        }
        // list_layout_recyclerview.xml play_last: 48×48dp FAB,
        // marginEnd 16dp, marginBottom 16dp.
        val fabParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(16), dp(16))
        }
        contentFrame.addView(fab, fabParams)
        // --- pull-to-refresh wrap (SwipeRefresher equivalent) ---
        // MX hosts @id/list inside ActivityList$SwipeRefresher; the
        // framework equivalent is a drag indicator that tracks the
        // finger while the list is scrolled to its top and triggers
        // a rescan on release past the threshold (installPullToRefresh).
        val pullWrap = FrameLayout(this)
        pullWrap.addView(contentFrame)
        // v0.5.4: MX's refresh affordance is its circular-arrow
        // ICON with a rotation animation — ours was a text label
        // ("Pull down to refresh"), which Sir flagged as not MX.
        // The icon now turns with the drag and spins while the
        // rescan runs (MX's decoded loading_rotate: 0->359deg,
        // 1000 ms, linear, infinite).
        pullIndicator = FrameLayout(this).apply {
            setBackgroundColor(theme.chrome)
            translationY = -dp(48).toFloat()
        }
        pullIcon = ImageView(this).apply {
            setImageDrawable(GlyphDrawable(GlyphDrawable.REFRESH, 0xFFFFFFFF.toInt()))
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        pullIndicator.addView(pullIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        pullWrap.addView(pullIndicator, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            gravity = Gravity.TOP
        })
        // v0.5.2: the quick-access row and the bottom nav are
        // REMOVED (Sir's order, marked on his screenshot). Everything
        // they opened — Music, Playlists, Private Folder, Media
        // Cleaner, Settings — stays reachable from the overflow menu.
        rootLayout.addView(pullWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootLayout)

        adapter = LibraryAdapter()
        listView.adapter = adapter
        gridView.adapter = adapter
        val clickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
            onItemClick(pos)
        }
        listView.onItemClickListener = clickListener
        gridView.onItemClickListener = clickListener
        val longListener = android.widget.AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            onItemLongClick(pos)
        }
        listView.onItemLongClickListener = longListener
        gridView.onItemLongClickListener = longListener
        installPullToRefresh(listView)
        installPullToRefresh(gridView)
        updateChrome()
    }

    /** Framework equivalent of MX's SwipeRefresher host
     *  (activity_media_list.xml @id/swipeRefresher): while the list
     *  is scrolled to its top, a downward drag pulls the indicator
     *  in with 0.5× resistance; releasing past 56dp of indicator
     *  travel triggers a rescan, below it the indicator retracts.
     *  The listener never consumes events, so list scrolling,
     *  clicks and long-press selection are untouched. Only installed
     *  when the Refresh-methods setting includes "swipe" (as before);
     *  the overflow-menu Refresh button remains the other affordance. */
    private fun installPullToRefresh(view: AbsListView) {
        if (!Store.stringSetSetting(Settings.REFRESH_METHODS).contains("swipe")) return
        var downY = 0f
        var pullPx = 0f
        var tracking = false
        view.setOnTouchListener { _, event ->
            val atTop = view.firstVisiblePosition == 0 &&
                (view.getChildAt(0)?.top ?: 0) >= 0
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    pullPx = 0f
                    tracking = atTop
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking && atTop) {
                        val dy = event.y - downY
                        pullPx = if (dy > 0) (dy * 0.5f).coerceAtMost(dp(48).toFloat())
                        else 0f
                        pullIndicator.translationY = -dp(48) + pullPx
                        // v0.5.4: MX's refresh icon turns with the
                        // drag (up to 270 degrees at full pull).
                        pullIcon.rotation = (pullPx / dp(48)) * 270f
                    } else if (!atTop) {
                        pullPx = 0f
                        pullIndicator.translationY = -dp(48).toFloat()
                        pullIcon.rotation = 0f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracking && pullPx >= dp(48)) {
                        pullIndicator.translationY = 0f
                        // v0.5.4: spin while rescanning — MX's
                        // decoded loading_rotate (0->359deg, 1000 ms,
                        // linear, infinite).
                        pullIcon.startAnimation(
                            android.view.animation.RotateAnimation(
                                0f, 359f,
                                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                            ).apply {
                                duration = 1000
                                interpolator = android.view.animation.LinearInterpolator()
                                repeatCount = android.view.animation.Animation.INFINITE
                            })
                        reload()
                        mainHandler.postDelayed({
                            pullIndicator.translationY = -dp(48).toFloat()
                            pullIcon.clearAnimation()
                            pullIcon.rotation = 0f
                        }, 700)
                    } else {
                        pullIndicator.translationY = -dp(48).toFloat()
                        pullIcon.rotation = 0f
                    }
                    tracking = false
                    pullPx = 0f
                }
            }
            false
        }
    }

    /* ================= data ================= */

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        @Suppress("DEPRECATION")
        requestPermissions(perms.toTypedArray(), REQ_PERMISSION)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (hasPermission()) reload() else refreshDisplay()
        }
    }

    private fun reload() {
        io.execute {
            val respectNomedia = Store.boolSetting(Settings.RESPECT_NOMEDIA)
            val scanned = Library.scan(this, respectNomedia)
            // v0.5.7: filtering joins the scan off the UI thread —
            // v0.5.6 posted back to main and filtered there.
            displayIo.execute {
                val filtered = filterScan(scanned)
                mainHandler.post {
                    rawScan = scanned
                    videos = filtered
                    restoreLastFolder()
                    refreshDisplay()
                }
            }
        }
    }

    /** Scan-settings filtering (v0.5.7: runs on [displayIo]). */
    private fun filterScan(source: List<VideoItem>): List<VideoItem> {
        val showHidden = Store.boolSetting(Settings.SHOW_HIDDEN)
        var list = Library.filterByScanSettings(
            source,
            Store.stringSetSetting(Settings.SCAN_FOLDERS),
            Store.stringSetSetting(Settings.SCAN_EXTENSIONS),
            showHidden)
        list = Library.visibleItems(list, Store.hiddenPaths(),
            Store.privateEntries().map { it.origPath }.toSet(), showHidden)
        return list
    }

    /** Restore the folder the user was browsing (v0.1 lastFolder).
     *  Main thread: reads/writes view state. */
    private fun restoreLastFolder() {
        val last = Store.lastFolder
        if (currentFolder == null && last != null &&
            videos.any { it.folder == last }) {
            currentFolder = last
        }
    }

    private fun applyFilters() {
        val source = rawScan
        displayIo.execute {
            val filtered = filterScan(source)
            mainHandler.post {
                videos = filtered
                restoreLastFolder()
                refreshDisplay()
            }
        }
    }

    private fun sortedVideos(source: List<VideoItem>): List<VideoItem> {
        val field = Store.stringSetting(Settings.LIST_SORT_FIELD)
        val mode = Library.sortModeForField(field)
        val finished = videos.filter { Store.isFinished(it.path) }.map { it.path }.toSet()
        return Library.sortVideos(source, mode,
            Store.boolSetting(Settings.LIST_SORT_ASCENDING), finished)
    }

    /** Sort with pre-captured settings — the displayIo half of
     *  [sortedVideos], so display prep never re-reads settings. */
    private fun sortForDisplay(
        source: List<VideoItem>, field: String, ascending: Boolean,
        finished: Set<String>
    ): List<VideoItem> = Library.sortVideos(
        source, Library.sortModeForField(field), ascending, finished)

    /** The adapter's fields line, computed from a pre-captured
     *  field set (v0.5.7: computed on displayIo into [RowMeta]). */
    private fun fieldsLineFor(video: VideoItem, fields: Set<String>): String {
        val parts = mutableListOf<String>()
        if (fields.contains("duration")) parts.add(LibFmt.duration(video.durationMs))
        if (fields.contains("size")) parts.add(LibFmt.size(video.sizeBytes))
        if (fields.contains("resolution") && video.width > 0) {
            parts.add(LibFmt.resolution(video.width, video.height))
        }
        if (fields.contains("date")) parts.add(LibFmt.date(video.dateAddedSec))
        if (fields.contains("framerate") && video.frameRate > 0f) {
            parts.add("%.0f fps".format(video.frameRate))
        }
        if (fields.contains("path")) parts.add(video.path)
        return parts.joinToString(" · ")
    }

    private fun statusLineFor(video: VideoItem): String = when {
        Store.isFinished(video.path) -> "✓ Finished"
        Store.resumePos(video.path) > 0L ->
            LibFmt.remaining(video.durationMs, Store.resumePos(video.path))
        else -> ""
    }

    /** v0.5.7: refreshDisplay is now a two-phase pipeline.
     *  Phase 1 (here, caller thread): capture every input.
     *  Phase 2 ([displayIo]): group, sort, count, format — all
     *  the work v0.5.6 did synchronously on the UI thread, plus
     *  the FAB's File.exists() probe and every per-row derived
     *  string for the adapter. Phase 3 (main): apply the finished
     *  model to the views. A generation counter drops stale
     *  results when refreshes race (e.g. fast search typing). */
    private fun refreshDisplay() {
        val gen = ++displayGen
        val allVideos = videos
        val folder = currentFolder
        val isSearching = searching
        val query = searchQuery
        val viewMode = Store.stringSetting(Settings.LIST_VIEW_MODE)
        val grid = Store.stringSetting(Settings.LIST_LAYOUT) == "grid"
        val sortField = Store.stringSetting(Settings.LIST_SORT_FIELD)
        val sortAsc = Store.boolSetting(Settings.LIST_SORT_ASCENDING)
        val finishedSet = allVideos.filter { Store.isFinished(it.path) }
            .map { it.path }.toSet()
        val showGroups = !isSearching && viewMode == "folders" && folder == null
        val lastPath = Store.lastPlayedPath()
        val fabSetting = Store.boolSetting(Settings.LIST_FLOATING_BUTTON)
        val scrollSetting = Store.boolSetting(Settings.SCROLL_TO_LAST_MEDIA)
        val hasPerm = hasPermission()
        displayIo.execute {
            val groups = Library.groupByFolder(allVideos)
            val display = when {
                isSearching -> sortForDisplay(
                    Library.filterByTitle(allVideos, query), sortField, sortAsc, finishedSet)
                viewMode == "folders" && folder != null -> sortForDisplay(
                    allVideos.filter { it.folder == folder }, sortField, sortAsc, finishedSet)
                viewMode == "files" -> sortForDisplay(allVideos, sortField, sortAsc, finishedSet)
                viewMode == "all" -> sortForDisplay(allVideos, sortField, sortAsc, finishedSet)
                else -> emptyList() // folders root: groups are the rows
            }
            val rowCount = when {
                isSearching && query.isBlank() -> Store.searchHistory().size
                showGroups -> groups.size
                else -> display.size
            }
            val emptyText = when {
                !hasPerm ->
                    "Video access is not granted.\nGrant access in system Settings, then pull to refresh."
                isSearching && query.isNotBlank() -> "No videos match \"$query\""
                isSearching -> "Type to search ${allVideos.size} videos"
                allVideos.isEmpty() -> "No videos found on this device"
                else -> "Nothing to show here"
            }
            // Status bar (rl_status): shown while a folder is open,
            // with the folder's on-disk path as the status text.
            val statusTextValue = if (folder != null && display.isNotEmpty()) {
                Library.dirOfPath(display.first().path)
            } else null
            // Floating play-last button gate (v0.5.4 semantics,
            // unchanged): setting ON + a recorded path that still
            // exists on disk (or a content URI). The exists() probe
            // is storage I/O — it now runs here, not on the UI
            // thread.
            val fabVisible = fabSetting && lastPath.isNotEmpty() &&
                (lastPath.startsWith("content://") || java.io.File(lastPath).exists())
            // "Scroll down to last played media" (List setting):
            // the row holding the last-played video — or its
            // folder, at the folders root.
            val scrollIndex = if (!isSearching && scrollSetting && lastPath.isNotEmpty()) {
                if (showGroups) {
                    groups.indexOfFirst { g -> g.videos.any { it.path == lastPath } }
                } else {
                    display.indexOfFirst { it.path == lastPath }
                }
            } else -1
            // Per-row derived data for the adapter (see RowMeta).
            val nowSec = System.currentTimeMillis() / 1000L
            val newPeriod = Store.intSetting(Settings.NEW_TAGGED_PERIOD)
            val fieldsSet = Store.stringSetSetting(Settings.LIST_FIELDS)
            val meta = HashMap<String, RowMeta>(display.size * 2 + 1)
            for (v in display) {
                val lastAt = Store.lastPlayedAt(v.path)
                meta[v.path] = RowMeta(
                    fields = fieldsLineFor(v, fieldsSet),
                    status = statusLineFor(v),
                    lastPlayedText = if (lastAt > 0L) {
                        "Last played ${LibFmt.dateTime(lastAt)}"
                    } else null,
                    watchedFrac = Library.watchedFraction(
                        v.durationMs, Store.resumePos(v.path)),
                    favourite = Store.isFavourite(v.path),
                    isNew = Library.isNewTag(v.dateModifiedSec, newPeriod, nowSec),
                    finished = Store.isFinished(v.path)
                )
            }
            val newCounts = HashMap<String, Int>(groups.size * 2 + 1)
            for (g in groups) {
                newCounts[g.name] = g.videos.count {
                    Library.isNewTag(it.dateModifiedSec, newPeriod, nowSec)
                }
            }
            mainHandler.post {
                if (gen != displayGen) return@post // a newer refresh superseded this one
                folderGroups = groups
                displayVideos = display
                rowMeta = meta
                folderNewCounts = newCounts
                listView.visibility = if (grid) View.GONE else View.VISIBLE
                gridView.visibility = if (grid) View.VISIBLE else View.GONE
                emptyView.visibility = if (rowCount == 0) View.VISIBLE else View.GONE
                if (rowCount == 0) emptyView.text = emptyText
                if (statusTextValue != null) {
                    statusBar.visibility = View.VISIBLE
                    statusText.text = statusTextValue
                } else {
                    statusBar.visibility = View.GONE
                }
                fab.visibility = if (fabVisible) View.VISIBLE else View.GONE
                adapter?.notifyDataSetChanged()
                if (scrollIndex >= 0) {
                    listView.setSelection(scrollIndex)
                    gridView.setSelection(scrollIndex)
                }
                // v0.5.7: gentle cascade when a fresh model lands.
                listView.scheduleLayoutAnimation()
                gridView.scheduleLayoutAnimation()
                updateChrome()
            }
        }
    }

    private fun updateChrome() {
        val viewMode = Store.stringSetting(Settings.LIST_VIEW_MODE)
        backBtn.visibility = if (currentFolder != null || searching) View.VISIBLE else View.GONE
        titleView.text = when {
            selectionMode -> "${selected.size} selected"
            searching -> "Search"
            currentFolder != null -> currentFolder
            viewMode == "all" -> "All folders"
            viewMode == "files" -> "Files"
            else -> "Folders" // v0.5: MX's root title (device evidence)
        }
        // v0.5: view-toggle glyph shows the layout you switch TO.
        if (::viewToggleBtn.isInitialized) {
            val gridNow = Store.stringSetting(Settings.LIST_LAYOUT) == "grid"
            viewToggleBtn.setImageDrawable(GlyphDrawable(
                if (gridNow) GlyphDrawable.LIST else GlyphDrawable.GRID,
                0xFFFFFFFF.toInt()))
        }
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        normalBar.visibility = if (searching) View.GONE else View.VISIBLE
        searchBar.visibility = if (searching) View.VISIBLE else View.GONE
        if (selectionMode) selectionTitle.text = "${selected.size} selected"
    }

    /* ================= navigation / actions ================= */

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selectionMode -> exitSelection()
            searching -> exitSearch()
            currentFolder != null -> {
                currentFolder = null
                Store.lastFolder = null
                refreshDisplay()
            }
            else -> super.onBackPressed()
        }
    }

    private fun enterSearch() {
        searching = true
        searchQuery = ""
        searchInput.text.clear()
        updateChrome()
        refreshDisplay()
        searchInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun exitSearch() {
        searching = false
        searchQuery = ""
        updateChrome()
        refreshDisplay()
    }

    private fun isGroupRow(position: Int): Boolean {
        val viewMode = Store.stringSetting(Settings.LIST_VIEW_MODE)
        return !searching && viewMode == "folders" && currentFolder == null &&
            position < folderGroups.size
    }

    private fun onItemClick(position: Int) {
        if (searching && searchQuery.isBlank()) {
            val history = Store.searchHistory()
            if (position < history.size) {
                searchInput.setText(history[position])
                searchInput.setSelection(history[position].length)
            }
            return
        }
        if (isGroupRow(position)) {
            if (selectionMode) return
            val group = folderGroups[position]
            currentFolder = group.name
            Store.lastFolder = group.name
            refreshDisplay()
            return
        }
        val video = displayVideos.getOrNull(position) ?: return
        if (selectionMode) {
            toggleSelection(video.path)
            return
        }
        if (Store.boolSetting(Settings.SELECTION_MODE)) {
            // "Selection mode" (List setting): with it on, tapping a
            // thumbnail selects the item instead of playing it.
            enterSelection(video.path)
            return
        }
        playVideos(displayVideos, displayVideos.indexOf(video))
    }

    private fun onItemLongClick(position: Int): Boolean {
        if (isGroupRow(position)) return false
        val video = displayVideos.getOrNull(position) ?: return false
        if (!selectionMode) enterSelection(video.path) else toggleSelection(video.path)
        return true
    }

    private fun playVideos(list: List<VideoItem>, index: Int, decoder: Int = -1,
                           resumeExact: Boolean = false) {
        val paths = list.map { it.path }.toMutableList()
        val titles = list.associate { it.path to it.title }.toMutableMap()
        // Insert the play queue (Play next / Play later) after the
        // tapped item, MX-style, then clear it.
        val queue = Store.playQueue()
        if (queue.isNotEmpty() && index >= 0) {
            val byPath = rawScan.associateBy { it.path }
            for ((offset, q) in queue.withIndex()) {
                if (!paths.contains(q)) {
                    paths.add(index + 1 + offset, q)
                    byPath[q]?.let { titles[q] = it.title }
                }
            }
            Store.clearPlayQueue()
        }
        launchPlayer(this, paths, index, titles, decoder, resumeExact)
    }

    private fun playLast() {
        val lastPath = Store.lastPlayedPath()
        val item = rawScan.firstOrNull { it.path == lastPath }
        if (item == null) {
            toast("No last played video yet")
            return
        }
        val siblings = sortedVideos(videos.filter { it.folder == item.folder })
        val list = if (siblings.isEmpty()) listOf(item) else siblings
        // v0.5.3 (Sir: "the most important button for me"): one tap
        // reopens this exact video at the exact second he left it —
        // the player skips its Ask prompt for this launch.
        playVideos(list, list.indexOf(item), resumeExact = true)
    }

    /* ================= selection mode ================= */

    private fun enterSelection(path: String) {
        selectionMode = true
        selected.clear()
        selected.add(path)
        updateChrome()
        adapter?.notifyDataSetChanged()
    }

    private fun toggleSelection(path: String) {
        if (!selected.add(path)) selected.remove(path)
        if (selected.isEmpty()) selectionMode = false
        updateChrome()
        adapter?.notifyDataSetChanged()
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        updateChrome()
        adapter?.notifyDataSetChanged()
    }

    private fun selectedVideos(): List<VideoItem> =
        displayVideos.filter { selected.contains(it.path) } +
            videos.filter { selected.contains(it.path) && !displayVideos.contains(it) }

    private fun onSelectionAction(label: String) {
        val items = selectedVideos()
        when (label) {
            "Play selected items" -> {
                if (items.isNotEmpty()) {
                    exitSelection()
                    playVideos(items, 0)
                }
            }
            "Play using…" -> {
                if (items.isEmpty()) return
                AlertDialog.Builder(this)
                    .setTitle("Play using…")
                    .setItems(arrayOf("HW decoder", "HW+ decoder", "SW decoder")) { _, which ->
                        val decoder = listOf(1, 4, 2)[which]
                        val first = items.first()
                        exitSelection()
                        playVideos(listOf(first), 0, decoder)
                    }
                    .show()
            }
            "Share" -> shareVideos(items)
            "Delete" -> showDelete(items)
            "Lock in Private Folder" -> lockInPrivate(items)
            "Hide" -> {
                for (item in items) Store.setHidden(item.path, true)
                toast("${items.size} item(s) hidden")
                exitSelection()
                applyFilters()
            }
            "Rebuild thumbnails" -> {
                thumbCache.evictAll()
                adapter?.notifyDataSetChanged()
                toast("Thumbnails rebuilt")
            }
            "Properties" -> {
                if (items.size == 1) showProperties(items.first())
                else if (items.isNotEmpty()) {
                    val totalSize = items.sumOf { it.sizeBytes }
                    val totalDur = items.sumOf { it.durationMs }
                    AlertDialog.Builder(this)
                        .setTitle("Properties")
                        .setMessage("${items.size} items\n" +
                            "Total size: ${LibFmt.size(totalSize)}\n" +
                            "Total length: ${LibFmt.duration(totalDur)}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun lockInPrivate(items: List<VideoItem>) {
        if (Store.privatePinHash().isEmpty()) {
            toast("Set a private folder PIN first")
            startActivity(Intent(this, PrivateFolderActivity::class.java))
            return
        }
        io.execute {
            var ok = 0
            for (item in items) if (FileOps.moveToPrivate(this, item) != null) ok++
            mainHandler.post {
                toast("$ok item(s) locked in the private folder")
                exitSelection()
                reload()
            }
        }
    }

    private fun shareVideos(items: List<VideoItem>) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            val item = items.first()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank { "video/*" }
                putExtra(Intent.EXTRA_STREAM, shareUri(this@MainActivity, File(item.path)))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        } else {
            val uris = ArrayList(items.map { shareUri(this, File(it.path)) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        }
    }

    /* ================= per-item menu ================= */

    private fun showItemMenu(video: VideoItem) {
        val favLabel = if (Store.isFavourite(video.path)) "Remove favourite" else "Add favourite"
        val hideLabel = if (Store.isHidden(video.path)) "Unhide" else "Hide"
        val labels = arrayOf("Play", "Play using…", "Play next", "Play later",
            "Add to Playlist", favLabel, "Mark as…", "Properties", "Rename",
            "Delete", hideLabel, "Share", "Create clip")
        AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> playVideos(displayVideos, displayVideos.indexOf(video))
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Play using…")
                            .setItems(arrayOf("HW decoder", "HW+ decoder", "SW decoder")) { _, w ->
                                playVideos(listOf(video), 0, listOf(1, 4, 2)[w])
                            }
                            .show()
                    }
                    2 -> { Store.enqueueNext(video.path); toast("Will play next") }
                    3 -> { Store.enqueueLater(video.path); toast("Added to play queue") }
                    4 -> showAddToPlaylist(listOf(video))
                    5 -> {
                        Store.setFavourite(video.path, !Store.isFavourite(video.path))
                        toast(if (Store.isFavourite(video.path)) "Added to favourites"
                        else "Removed from favourites")
                    }
                    6 -> showMarkAs(video)
                    7 -> showProperties(video)
                    8 -> showRename(video)
                    9 -> showDelete(listOf(video))
                    10 -> {
                        Store.setHidden(video.path, !Store.isHidden(video.path))
                        applyFilters()
                    }
                    11 -> shareVideos(listOf(video))
                    12 -> startActivity(ClipEditorActivity.intent(this, video.path))
                }
            }
            .show()
    }

    private fun showMarkAs(video: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("Mark as…")
            .setItems(arrayOf("Last played", "NEW", "Finished", "None")) { _, which ->
                when (which) {
                    0 -> Store.recordPlayed(video.path)
                    1, 3 -> {
                        // Clear finished + resume: a 1ms save un-marks
                        // finished, then a 0 save clears the position.
                        Store.saveResumePos(video.path, 1L)
                        Store.saveResumePos(video.path, 0L)
                    }
                    2 -> Store.markFinished(video.path)
                }
                adapter?.notifyDataSetChanged()
            }
            .show()
    }

    private fun showAddToPlaylist(items: List<VideoItem>) {
        val names = PlaylistStore.names()
        val labels = (names + "New playlist…").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to Playlist")
            .setItems(labels) { _, which ->
                if (which == names.size) {
                    val input = EditText(this).apply {
                        hint = "Playlist name"
                        setPadding(dp(20), dp(12), dp(20), dp(4))
                    }
                    AlertDialog.Builder(this)
                        .setTitle("New playlist")
                        .setView(input)
                        .setPositiveButton("Create & add") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isEmpty()) return@setPositiveButton
                            val added = PlaylistStore.add(name, items.map { it.path })
                            toast(String.format("%d video(s) added to the playlist '%s'",
                                added, name))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    val added = PlaylistStore.add(names[which], items.map { it.path })
                    toast(String.format("%d video(s) added to the playlist '%s'",
                        added, names[which]))
                }
            }
            .show()
    }

    private fun showRename(video: VideoItem) {
        if (!Store.boolSetting(Settings.ALLOW_EDITS)) {
            toast("File edits are disabled in Settings → General")
            return
        }
        val input = EditText(this).apply {
            setText(File(video.path).name)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename to")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString()
                val error = FileOps.renameError(newName)
                if (error != null) {
                    toast(error)
                    return@setPositiveButton
                }
                io.execute {
                    val newPath = FileOps.renameVideo(this, video, newName)
                    mainHandler.post {
                        if (newPath != null) {
                            toast("Renamed")
                            reload()
                        } else toast("Rename failed (name in use or file missing)")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDelete(items: List<VideoItem>) {
        if (!Store.boolSetting(Settings.ALLOW_EDITS)) {
            toast("File edits are disabled in Settings → General")
            return
        }
        val message = if (items.size == 1)
            "Move '${items.first().title}' to the recycle bin?"
        else "Move ${items.size} items to the recycle bin?"
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    var ok = 0
                    val together = Store.boolSetting(Settings.DELETE_SUBTITLE_TOGETHER)
                    for (item in items) {
                        if (FileOps.moveToRecycle(this, item, together) != null) ok++
                    }
                    mainHandler.post {
                        toast("$ok item(s) moved to the recycle bin")
                        if (selectionMode) exitSelection()
                        reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProperties(video: VideoItem) {
        io.execute {
            val groups = FileOps.propertiesGroups(this, video)
            mainHandler.post {
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(12), dp(20), dp(4))
                }
                for ((group, rows) in groups) {
                    container.addView(TextView(this).apply {
                        text = group
                        textSize = 13f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(0xFF3C8CF0.toInt())
                        setPadding(0, dp(10), 0, dp(4))
                    })
                    for ((label, value) in rows) {
                        container.addView(TextView(this).apply {
                            text = "$label: $value"
                            textSize = 14f
                            setTextColor(theme.title)
                            setPadding(0, dp(2), 0, dp(2))
                        })
                    }
                }
                val scroll = android.widget.ScrollView(this).apply { addView(container) }
                AlertDialog.Builder(this)
                    .setTitle("Properties")
                    .setView(scroll)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /* ================= overflow menu ================= */

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        val methods = Store.stringSetSetting(Settings.REFRESH_METHODS)
        if (methods.contains("button")) {
            menu.add(0, M_REFRESH, 0, "Refresh")
        }
        val grid = Store.stringSetting(Settings.LIST_LAYOUT) == "grid"
        menu.add(0, M_LAYOUT, 1, if (grid) "List view" else "Grid view")
        menu.add(0, M_VIEW_MENU, 2, "View Menu")
        menu.add(0, M_EQUALIZER, 3, "Equalizer")
        val viewSub = menu.addSubMenu(0, M_VIEW, 4, "View")
        val viewMode = Store.stringSetting(Settings.LIST_VIEW_MODE)
        val allItem = viewSub.add(1, M_VIEW_ALL, 0, "All folders")
        val foldersItem = viewSub.add(1, M_VIEW_FOLDERS, 1, "Folders")
        val filesItem = viewSub.add(1, M_VIEW_FILES, 2, "Files")
        viewSub.setGroupCheckable(1, true, true)
        when (viewMode) {
            "all" -> allItem.isChecked = true
            "files" -> filesItem.isChecked = true
            else -> foldersItem.isChecked = true
        }
        viewSub.add(0, M_SORT, 3, "Sort by…")
        viewSub.add(0, M_FIELDS, 4, "Fields")
        menu.add(0, M_SELECT, 5, "Select")
        menu.add(0, M_PLAYLISTS, 6, "Playlists")
        menu.add(0, M_PRIVATE, 7, "Private Folder")
        menu.add(0, M_RECYCLE, 8, "Recycle Bin")
        menu.add(0, M_HISTORY, 9, "History")
        menu.add(0, M_MUSIC, 10, "Music")
        menu.add(0, M_USB, 14, "USB")
        menu.add(0, M_CLEANER, 15, "Media Cleaner")
        menu.add(0, M_SETTINGS, 11, "Settings")
        val helpSub = menu.addSubMenu(0, M_HELP, 12, "Help")
        helpSub.add(0, M_HELP_NEW, 0, "What's New")
        helpSub.add(0, M_HELP_FEATURES, 1, "Features")
        helpSub.add(0, M_HELP_FAQ, 2, "FAQ")
        helpSub.add(0, M_HELP_ABOUT, 3, "About")
        // "Quit Button" (General setting): MX shows the Quit entry
        // only when the user enables it.
        if (Store.boolSetting(Settings.QUIT_BUTTON)) {
            menu.add(0, M_QUIT, 13, "Quit")
        }
        popup.setOnMenuItemClickListener { item ->
            onOverflowItem(item.itemId)
            true
        }
        popup.show()
    }

    private fun onOverflowItem(id: Int) {
        when (id) {
            M_REFRESH -> reload()
            M_LAYOUT -> {
                val grid = Store.stringSetting(Settings.LIST_LAYOUT) == "grid"
                Store.setStringSetting(Settings.LIST_LAYOUT, if (grid) "list" else "grid")
                refreshDisplay()
            }
            M_VIEW_MENU -> showViewMenu()
            M_EQUALIZER -> showEqualizer()
            M_VIEW_ALL -> setViewMode("all")
            M_VIEW_FOLDERS -> setViewMode("folders")
            M_VIEW_FILES -> setViewMode("files")
            M_SORT -> showSortDialog()
            M_FIELDS -> showFieldsDialog()
            M_SELECT -> {
                if (displayVideos.isNotEmpty()) {
                    selectionMode = true
                    selected.clear()
                    updateChrome()
                    adapter?.notifyDataSetChanged()
                    toast("Tap items to select them")
                } else toast("Nothing to select")
            }
            M_PLAYLISTS -> showPlaylistsDialog()
            M_PRIVATE -> startActivity(Intent(this, PrivateFolderActivity::class.java))
            M_RECYCLE -> startActivity(Intent(this, RecycleBinActivity::class.java))
            M_HISTORY -> startActivity(Intent(this, HistoryActivity::class.java))
            M_MUSIC -> startActivity(Intent(this, MusicActivity::class.java))
            M_USB -> startActivity(Intent(this, UsbActivity::class.java))
            M_CLEANER -> startActivity(Intent(this, MediaCleanerActivity::class.java))
            M_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            M_HELP_NEW -> openHelp("whatsnew")
            M_HELP_FEATURES -> openHelp("features")
            M_HELP_FAQ -> openHelp("faq")
            M_HELP_ABOUT -> openHelp("about")
            M_QUIT -> finishAffinity()
        }
    }

    private fun setViewMode(mode: String) {
        Store.setStringSetting(Settings.LIST_VIEW_MODE, mode)
        if (mode != "folders") {
            currentFolder = null
            Store.lastFolder = null
        }
        refreshDisplay()
    }

    private fun openHelp(topic: String) {
        startActivity(Intent(this, HelpActivity::class.java).putExtra("topic", topic))
    }

    private fun showViewMenu() {
        val grid = Store.stringSetting(Settings.LIST_LAYOUT) == "grid"
        val viewMode = Store.stringSetting(Settings.LIST_VIEW_MODE)
        val viewLabel = when (viewMode) {
            "all" -> "All folders"
            "files" -> "Files"
            else -> "Folders"
        }
        val labels = arrayOf(
            "Layout: ${if (grid) "Grid" else "List"}",
            "View: $viewLabel",
            "Sort by…",
            "Fields")
        AlertDialog.Builder(this)
            .setTitle("View Menu")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        Store.setStringSetting(Settings.LIST_LAYOUT,
                            if (grid) "list" else "grid")
                        refreshDisplay()
                    }
                    1 -> {
                        val next = when (viewMode) {
                            "folders" -> "all"
                            "all" -> "files"
                            else -> "folders"
                        }
                        setViewMode(next)
                    }
                    2 -> showSortDialog()
                    3 -> showFieldsDialog()
                }
            }
            .show()
    }

    private fun showSortDialog() {
        val fields = listOf(
            "default" to "Sort by Default",
            "title" to "Title",
            "date" to "Date",
            "length" to "Length",
            "size" to "Size",
            "resolution" to "Resolution",
            "framerate" to "Frame rate",
            "status" to "Status",
            "type" to "Type",
            "location" to "Location")
        val current = Store.stringSetting(Settings.LIST_SORT_FIELD)
        val checked = fields.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(fields.map { it.second }.toTypedArray(), checked) { d, which ->
                val field = fields[which].first
                d.dismiss()
                if (field == "default") {
                    Store.setStringSetting(Settings.LIST_SORT_FIELD, "default")
                    refreshDisplay()
                } else {
                    showSortDirectionDialog(field, fields[which].second)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSortDirectionDialog(field: String, label: String) {
        val directions = when (field) {
            "title" -> listOf("A to Z" to true, "Z to A" to false)
            "date" -> listOf("Oldest" to true, "Newest" to false)
            "length" -> listOf("Shortest" to true, "Longest" to false)
            "size" -> listOf("Smallest" to true, "Largest" to false)
            "resolution", "framerate" -> listOf("Lowest" to true, "Highest" to false)
            else -> listOf("Ascending" to true, "Descending" to false)
        }
        AlertDialog.Builder(this)
            .setTitle("$label — direction")
            .setItems(directions.map { it.first }.toTypedArray()) { _, which ->
                Store.setStringSetting(Settings.LIST_SORT_FIELD, field)
                Store.setBoolSetting(Settings.LIST_SORT_ASCENDING, directions[which].second)
                refreshDisplay()
            }
            .show()
    }

    private fun showFieldsDialog() {
        val ids = Settings.LIST_FIELD_IDS
        val current = Store.stringSetSetting(Settings.LIST_FIELDS)
        val checked = BooleanArray(ids.size) { current.contains(ids[it].first) }
        AlertDialog.Builder(this)
            .setTitle("Fields")
            .setMultiChoiceItems(ids.map { it.second }.toTypedArray(), checked) { _, which, on ->
                checked[which] = on
            }
            .setPositiveButton("OK") { _, _ ->
                val chosen = ids.filterIndexed { i, _ -> checked[i] }.map { it.first }
                Store.setStringSetSetting(Settings.LIST_FIELDS, chosen.toSet())
                adapter?.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPlaylistsDialog() {
        val names = PlaylistStore.names()
        val labels = (names + "New playlist…").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Playlists")
            .setItems(labels) { _, which ->
                if (which == names.size) {
                    val input = EditText(this).apply {
                        hint = "Playlist name"
                        setPadding(dp(20), dp(12), dp(20), dp(4))
                    }
                    AlertDialog.Builder(this)
                        .setTitle("New playlist")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isEmpty()) return@setPositiveButton
                            if (PlaylistStore.create(name)) {
                                startActivity(Intent(this, PlaylistDetailActivity::class.java)
                                    .putExtra("name", name))
                            } else toast("A playlist with that name already exists")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    startActivity(Intent(this, PlaylistDetailActivity::class.java)
                        .putExtra("name", names[which]))
                }
            }
            .show()
    }

    private fun showEqualizer() {
        if (!Store.boolSetting(Settings.AUDIO_EFFECTS_ENABLED)) {
            toast("Enable Audio effects in Settings → Audio first")
            return
        }
        var eq: Equalizer? = null
        try {
            eq = Equalizer(0, 0)
        } catch (t: Throwable) {
            toast("Equalizer is not available on this device")
            return
        }
        val bands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange // [min, max] in millibels
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        for (b in 0 until bands) {
            val freqHz = eq.getCenterFreq(b.toShort()) / 1000
            container.addView(TextView(this).apply {
                text = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"
                textSize = 13f
                setTextColor(theme.secondary)
            })
            val bar = android.widget.SeekBar(this).apply {
                max = range[1] - range[0]
                progress = eq.getBandLevel(b.toShort()) - range[0]
            }
            val bandIndex = b
            val equalizer = eq
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        try {
                            equalizer.setBandLevel(bandIndex.toShort(),
                                (p + range[0]).toShort())
                        } catch (t: Throwable) { /* band rejected */ }
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            container.addView(bar)
        }
        eq.enabled = true
        AlertDialog.Builder(this)
            .setTitle("Equalizer")
            .setView(container)
            .setPositiveButton("OK") { _, _ -> eq.release() }
            .setOnCancelListener { eq.release() }
            .show()
    }

    /* ================= guide ================= */

    private fun showGuideIfFirstRun() {
        if (Store.boolSetting(Settings.GUIDE_SHOWN)) return
        AlertDialog.Builder(this)
            .setTitle("Welcome to Shadow Player")
            .setMessage(
                "In the player:\n\n" +
                    "• Swipe horizontally to seek\n" +
                    "• Swipe up/down on the left for brightness, on the right for volume\n" +
                    "• Double tap the sides to seek back/forward\n" +
                    "• Pinch to zoom the video\n\n" +
                    "In the library, long-press a video to select it, " +
                    "and tap the ⋮ button on a row for more options.")
            .setPositiveButton("GOT IT") { _, _ ->
                Store.setBoolSetting(Settings.GUIDE_SHOWN, true)
            }
            .setCancelable(false)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /* ================= adapter ================= */

    private inner class LibraryAdapter : BaseAdapter() {

        override fun getCount(): Int {
            if (searching && searchQuery.isBlank()) return Store.searchHistory().size
            return if (isGroupRowMode()) folderGroups.size else displayVideos.size
        }

        override fun getItem(position: Int): Any =
            if (isGroupRowMode()) folderGroups[position] else displayVideos[position]

        override fun getItemId(position: Int): Long = position.toLong()

        private fun isGroupRowMode(): Boolean {
            val viewMode = Store.stringSetting(Settings.LIST_VIEW_MODE)
            return !searching && viewMode == "folders" && currentFolder == null
        }

        private fun isGrid(): Boolean = Store.stringSetting(Settings.LIST_LAYOUT) == "grid"

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (searching && searchQuery.isBlank()) {
                val q = Store.searchHistory()[position]
                return TextView(this@MainActivity).apply {
                    text = "⌕  $q"
                    textSize = 15f
                    setTextColor(theme.title)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
            }
            if (isGroupRowMode()) {
                val group = folderGroups[position]
                return if (isGrid()) folderGridCell(group) else folderRow(group)
            }
            val video = displayVideos[position]
            return if (isGrid()) gridCell(video) else videoRow(video)
        }

        /** list_row_listable.xml: 62dp folder icon (@dimen/
         *  directory_width, marginLeft 16dp), text block 16dp from
         *  the icon and 16dp from the end — title 15sp primary
         *  (NewText.Primary, maxLines 2, lineSpacingExtra 4dp,
         *  marginBottom 2dp), count 12sp secondary with the total
         *  size as a round-box info_opposite tag beside it (10sp
         *  semibold, gridBox colours), info_normal 12sp secondary
         *  single line below (marginTop 2dp). iv_new: 10dp red dot
         *  when the folder holds a NEW-tagged file. */
        private fun folderRow(group: FolderGroup): View {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // v0.5.3: the NEW badge straddles the folder's
                // top-right corner (7dp of it lives outside the
                // icon slot) — without this the row clipped it to
                // a half-badge, exactly what Sir photographed.
                clipChildren = false
                clipToPadding = false
                background = pressBackground(Color.TRANSPARENT)
            }
            val iconWrap = FrameLayout(this@MainActivity).apply {
                clipChildren = false
                clipToPadding = false
            }
            iconWrap.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_folder)
                // v0.5: device-measured folder glyph is ~70x53dp;
                // the redrawn vector insets it to 92%x70% of the
                // view, so a 76dp slot renders it at MX's size.
                layoutParams = FrameLayout.LayoutParams(dp(76), dp(76))
            })
            // v0.5.7: precomputed on displayIo (see folderNewCounts);
            // the fallback keeps the row correct if a bind ever
            // races the model build.
            val newCount = folderNewCounts[group.name] ?: run {
                val nowSec = System.currentTimeMillis() / 1000L
                group.videos.count {
                    Library.isNewTag(it.dateModifiedSec,
                        Store.intSetting(Settings.NEW_TAGGED_PERIOD), nowSec)
                }
            }
            if (newCount > 0) {
                // v0.5: MX's numbered red_badge (decoded: 20dp tall,
                // 11sp, straddling the icon's top-right corner;
                // device confirms ~20dp, centre on the glyph edge).
                iconWrap.addView(TextView(this@MainActivity).apply {
                    text = newCount.toString()
                    textSize = 11f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = badgeBg
                    setPadding(dp(5), 0, dp(5), 0)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        // The glyph's true top-right corner in the
                        // 76dp slot is (73.0, 9.8) from the vector's
                        // path bounds; END-anchored with a -7dp right
                        // margin, the badge centre lands on it and
                        // 2-digit counts grow leftward, like MX.
                        setMargins(0, 0, dp(-7), 0)
                        minimumWidth = dp(20)
                    }
                })
            }
            row.addView(iconWrap, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), 0, 0, 0)
            })
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
            }
            texts.addView(TextView(this@MainActivity).apply {
                text = group.name
                textSize = 15f
                setTextColor(mxPrimary())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, 0, 0, dp(2))
            })
            val countRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            countRow.addView(TextView(this@MainActivity).apply {
                text = if (group.videos.size == 1) "1 video" else "${group.videos.size} videos"
                textSize = 12f
                setTextColor(mxSecondary())
            })
            countRow.addView(TextView(this@MainActivity).apply {
                text = LibFmt.size(group.totalSizeBytes)
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(mxBoxText())
                background = tagBg(mxBoxBg())
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), 0, 0, 0)
                }
            })
            texts.addView(countRow)
            // v0.5: no third line on folder rows — MX's device rows
            // show title + count only (the path/last-played line was
            // ours alone).
            row.addView(texts, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        /** list_grid_listable.xml: 62dp folder icon centred
         *  (8dp side margins), title centred 15sp primary
         *  (maxLines 2, 8dp side margins), count centred 12sp
         *  secondary (marginTop 2dp, marginBottom 8dp). */
        private fun folderGridCell(group: FolderGroup): View {
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = pressBackground(Color.TRANSPARENT)
            }
            cell.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_folder)
                layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).apply {
                    setMargins(dp(8), 0, dp(8), 0)
                }
            })
            cell.addView(TextView(this@MainActivity).apply {
                text = group.name
                textSize = 15f
                setTextColor(mxPrimary())
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
            })
            cell.addView(TextView(this@MainActivity).apply {
                text = if (group.videos.size == 1) "1 video" else "${group.videos.size} videos"
                textSize = 12f
                setTextColor(mxSecondary())
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                setPadding(dp(8), dp(2), dp(8), dp(8))
            })
            return cell
        }

        // v0.5.7: both lines come from the displayIo-precomputed
        // RowMeta; the live computation remains as a fallback for
        // binds that race the model build.
        private fun fieldsLine(video: VideoItem): String =
            rowMeta[video.path]?.fields
                ?: fieldsLineFor(video, Store.stringSetSetting(Settings.LIST_FIELDS))

        private fun statusLine(video: VideoItem): String =
            rowMeta[video.path]?.status ?: statusLineFor(video)

        /** CardView with MX's decoded 1:0.564 ratio
         *  (list_grid_media.xml layout_constraintDimensionRatio). */
        private inner class RatioFrameLayout : FrameLayout(this@MainActivity) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                val w = MeasureSpec.getSize(wSpec)
                super.onMeasure(wSpec,
                    MeasureSpec.makeMeasureSpec((w * 0.564f).toInt(),
                        MeasureSpec.EXACTLY))
            }
        }

        /** Thumbnail card shared by list_row_media.xml (fixed
         *  96×56dp frame) and list_grid_media.xml (ratio frame).
         *  Inside icon_frame: centred type icon (32dp list / 40dp
         *  grid) under the centre-crop thumb, NEW tag top-start
         *  (4dp margins, tag_red #F2405D, 10sp semibold white,
         *  round_box_small), favourite heart top-end 16dp when
         *  favourited (#F2405D), selected mark 24dp top-end
         *  (list: offset 68dp from start = 4dp from the end of the
         *  96dp frame; grid: 4dp margins), duration tag bottom-end
         *  (4dp margins, 10sp semibold white; list tint
         *  duration_background #80171717, grid tint tag_dark
         *  #CC000000). MX's subtitle tag (bottom, left of duration)
         *  stays unpopulated: our model has no embedded-subtitle
         *  flag (FileOps' hasEmbeddedSubtitle is always false).
         *  The 2dp resume progress bar is our retained feature —
         *  MX's layouts carry no equivalent element. */
        // v0.5.7: the folder NEW badge background — also identical
        // for every row, shared like the thumbnail card's.
        private val badgeBg: GradientDrawable by lazy {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xFFF2405D.toInt())
            }
        }

        // v0.5.7: the thumbnail card background is identical for
        // every row — one shared instance instead of a fresh
        // GradientDrawable per bind.
        private val thumbFrameBg: GradientDrawable by lazy {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat() // cardCornerRadius 4dp
                setColor(0xFF000000.toInt())
            }
        }

        private fun thumbFrame(video: VideoItem, grid: Boolean): FrameLayout {
            val frame = (if (grid) RatioFrameLayout() else FrameLayout(this@MainActivity)).apply {
                background = thumbFrameBg
                clipToOutline = true
            }
            frame.addView(ImageView(this@MainActivity).apply {
                // v0.5: drawn play glyph (MX's 32/40dp type-icon box)
                // instead of the "▶" font glyph (~11x13dp optical).
                setImageDrawable(GlyphDrawable(GlyphDrawable.PLAY, 0xB3FFFFFF.toInt()))
                layoutParams = FrameLayout.LayoutParams(
                    if (grid) dp(40) else dp(32), if (grid) dp(40) else dp(32)).apply {
                    gravity = Gravity.CENTER
                }
            })
            val img = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            frame.addView(img)
            loadThumbnail(video, img)
            // v0.5.7: NEW / favourite / watched state from RowMeta
            // (displayIo), live reads only as a racing fallback.
            val meta = rowMeta[video.path]
            if (meta?.isNew ?: Library.isNewTag(video.dateModifiedSec,
                    Store.intSetting(Settings.NEW_TAGGED_PERIOD),
                    System.currentTimeMillis() / 1000L)) {
                frame.addView(TextView(this@MainActivity).apply {
                    text = "NEW"
                    textSize = 10f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(0xFFFFFFFF.toInt())
                    background = tagBg(0xFFF2405D.toInt())
                    setPadding(dp(4), 0, dp(4), 0)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setMargins(dp(4), dp(4), 0, 0)
                    }
                })
            }
            if (meta?.favourite ?: Store.isFavourite(video.path)) {
                frame.addView(ImageView(this@MainActivity).apply {
                    // v0.5: drawn heart path in the 16/24dp slot
                    // instead of the "♥" font glyph.
                    setImageDrawable(GlyphDrawable(GlyphDrawable.HEART, 0xFFF2405D.toInt()))
                    layoutParams = FrameLayout.LayoutParams(
                        if (grid) dp(24) else dp(16), if (grid) dp(24) else dp(16)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, dp(2), dp(2), 0)
                    }
                })
            }
            frame.addView(TextView(this@MainActivity).apply {
                text = LibFmt.duration(video.durationMs)
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFFFFFFFF.toInt())
                background = tagBg(if (grid) 0xCC000000.toInt() else 0x80171717.toInt())
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, dp(4), dp(4))
                }
            })
            val frac = meta?.watchedFrac
                ?: Library.watchedFraction(video.durationMs, Store.resumePos(video.path))
            if (frac > 0f && !(meta?.finished ?: Store.isFinished(video.path))) {
                val bar = ProgressBar(this@MainActivity, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (frac * 1000).toInt()
                    progressDrawable?.setTint(0xFF3C8CF0.toInt())
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
                        gravity = Gravity.BOTTOM
                    }
                }
                frame.addView(bar)
            }
            if (selectionMode && selected.contains(video.path)) {
                frame.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(selectedDrawable())
                    layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, dp(4), dp(4), 0)
                    }
                })
            }
            return frame
        }

        /** list_row_media.xml: container padding L14/T8/B8 (no
         *  right padding — the more button carries its own), thumb
         *  card 96×56dp, text block marginStart 8dp. Title 14sp
         *  semibold primary (NewText.Medium.Primary, maxLines 3,
         *  lineSpacingExtra 4dp, marginBottom 2dp); tv_last_played
         *  12sp secondary, 1 line; rl_info (marginBottom 4dp) =
         *  12dp folder-type icon + fields text 12sp secondary
         *  (marginStart 2dp, maxLines 2); the status line stands in
         *  for MX's file_info flexbox row. More: 40×38dp view,
         *  padding T10/R22/B10, 18dp vertical-dots glyph tinted
         *  listMoreColor, vertically centred. Selected rows take
         *  MX's checked background (bg_item selector:
         *  grid_selected_background #26FFFFFF / #F1F1F1). */
        private fun videoRow(video: VideoItem): View {
            val isSel = selectionMode && selected.contains(video.path)
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), 0, dp(8))
                // v0.5.7: press feedback on every row; the checked
                // colour stays the resting background in selection.
                background = pressBackground(
                    if (isSel) mxSelectedBg() else Color.TRANSPARENT)
            }
            row.addView(thumbFrame(video, grid = false),
                LinearLayout.LayoutParams(dp(96), dp(56)))
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            texts.addView(TextView(this@MainActivity).apply {
                text = video.title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(mxPrimary())
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, 0, 0, dp(2))
            })
            // v0.5.7: precomputed "Last played" text (RowMeta);
            // live read only as a racing fallback.
            val lastPlayedText = rowMeta[video.path]?.lastPlayedText ?: run {
                val lastAt = Store.lastPlayedAt(video.path)
                if (lastAt > 0L) "Last played ${LibFmt.dateTime(lastAt)}" else null
            }
            if (lastPlayedText != null) {
                texts.addView(TextView(this@MainActivity).apply {
                    text = lastPlayedText
                    textSize = 12f
                    setTextColor(mxSecondary())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            val fields = fieldsLine(video)
            if (fields.isNotEmpty()) {
                val infoRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(4))
                }
                infoRow.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(folderMiniDrawable(theme.dark))
                    layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
                })
                infoRow.addView(TextView(this@MainActivity).apply {
                    text = fields
                    textSize = 12f
                    setTextColor(mxSecondary())
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), 0, 0, 0)
                })
                texts.addView(infoRow)
            }
            val status = statusLine(video)
            if (status.isNotEmpty()) {
                texts.addView(TextView(this@MainActivity).apply {
                    text = status
                    textSize = 12f
                    setTextColor(theme.accent)
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
            val more = ImageView(this@MainActivity).apply {
                setImageDrawable(dotsDrawable(mxMoreColor(), horizontal = false))
                setPadding(0, dp(10), dp(22), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(38))
                isClickable = true
                setOnClickListener { showItemMenu(video) }
            }
            row.addView(more)
            return row
        }

        /** list_grid_media.xml: item root padding T4/B4; card spans
         *  the cell width at ratio 1:0.564 (RatioFrameLayout);
         *  title below the card — 14sp semibold primary, marginTop
         *  4dp, marginEnd 18dp, maxLines 2, minLines 2,
         *  lineSpacingExtra 4dp; more 38×28dp at the title's top
         *  end, padding L20/B10, 18dp dots glyph. MX's grid item
         *  has no fields line, so none is drawn (fields remain in
         *  list view and Properties). */
        private fun gridCell(video: VideoItem): View {
            val isSel = selectionMode && selected.contains(video.path)
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                background = pressBackground(
                    if (isSel) mxSelectedBg() else Color.TRANSPARENT)
            }
            val thumb = thumbFrame(video, grid = true)
            cell.addView(thumb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            titleRow.addView(TextView(this@MainActivity).apply {
                text = video.title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(mxPrimary())
                maxLines = 2
                minLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(4).toFloat(), 1f)
            }, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, dp(4), dp(18), 0)
            })
            titleRow.addView(ImageView(this@MainActivity).apply {
                setImageDrawable(dotsDrawable(mxMoreColor(), horizontal = true))
                setPadding(dp(20), 0, 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(28)).apply {
                    setMargins(0, dp(2), 0, 0)
                }
                isClickable = true
                setOnClickListener { showItemMenu(video) }
            })
            cell.addView(titleRow)
            return cell
        }

        private fun loadThumbnail(video: VideoItem, img: ImageView) {
            img.tag = video.id
            val cached = thumbCache.get(video.id)
            if (cached != null) {
                img.setImageBitmap(cached)
                return
            }
            img.setImageBitmap(null)
            io.execute {
                val bmp = try {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        contentResolver, video.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null)
                } catch (t: Throwable) {
                    null
                }
                if (bmp != null && Store.boolSetting(Settings.CACHE_THUMBNAIL)) {
                    thumbCache.put(video.id, bmp)
                }
                mainHandler.post {
                    if (img.tag == video.id && bmp != null) img.setImageBitmap(bmp)
                }
            }
        }
    }

    companion object {
        private const val REQ_PERMISSION = 7
        private const val M_REFRESH = 1
        private const val M_LAYOUT = 2
        private const val M_VIEW_MENU = 3
        private const val M_EQUALIZER = 4
        private const val M_VIEW = 5
        private const val M_VIEW_ALL = 6
        private const val M_VIEW_FOLDERS = 7
        private const val M_VIEW_FILES = 8
        private const val M_SORT = 9
        private const val M_FIELDS = 10
        private const val M_SELECT = 11
        private const val M_PLAYLISTS = 12
        private const val M_PRIVATE = 13
        private const val M_RECYCLE = 14
        private const val M_HISTORY = 15
        private const val M_MUSIC = 16
        private const val M_SETTINGS = 17
        private const val M_HELP = 18
        private const val M_HELP_NEW = 19
        private const val M_HELP_FEATURES = 20
        private const val M_HELP_FAQ = 21
        private const val M_HELP_ABOUT = 22
        private const val M_QUIT = 23
        private const val M_USB = 24
        private const val M_CLEANER = 25
        private val SELECTION_ACTIONS = listOf(
            "Play selected items", "Play using…", "Share", "Delete",
            "Lock in Private Folder", "Hide", "Rebuild thumbnails", "Properties")
    }
}
