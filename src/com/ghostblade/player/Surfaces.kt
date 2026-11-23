package com.ghostblade.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** Named playlists on top of [PSet.customPlaylists] — the store
 *  Shadow Two declared for spec §2.9 "Add to playlist"
 *  (serialised as lines: `name \t path1 | path2 | …`). The library
 *  and the player therefore see the same playlists. [Store] keeps
 *  an equivalent tested API; the UI uses this one. */
object PlaylistStore {

    private fun load(): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        val raw = PSet.customPlaylists
        if (raw.isBlank()) return out
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val name = line.substring(0, tab)
            val rest = line.substring(tab + 1)
            out[name] = if (rest.isBlank()) mutableListOf()
            else rest.split("|").filter { it.isNotBlank() }.toMutableList()
        }
        return out
    }

    private fun save(map: LinkedHashMap<String, MutableList<String>>) {
        PSet.customPlaylists = map.entries.joinToString("\n") { (name, paths) ->
            name + "\t" + paths.joinToString("|")
        }
    }

    fun names(): List<String> = load().keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun paths(name: String): List<String> = load()[name]?.toList() ?: emptyList()

    fun create(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val map = load()
        if (map.containsKey(n)) return false
        map[n] = mutableListOf()
        save(map)
        return true
    }

    fun delete(name: String) {
        val map = load()
        map.remove(name)
        save(map)
    }

    fun rename(old: String, new: String): Boolean {
        val n = new.trim()
        if (n.isEmpty()) return false
        val map = load()
        val items = map.remove(old) ?: return false
        if (map.containsKey(n)) {
            map[old] = items
            return false
        }
        map[n] = items
        save(map)
        return true
    }

    /** Adds [paths] to [name] (created on demand), skipping
     *  duplicates. Returns how many were actually added — the
     *  caller toasts MX's "%d video(s) added to the playlist '%s'". */
    fun add(name: String, paths: List<String>): Int {
        val map = load()
        val list = map.getOrPut(name) { mutableListOf() }
        var added = 0
        for (p in paths) {
            if (!list.contains(p)) {
                list.add(p)
                added++
            }
        }
        save(map)
        return added
    }

    fun remove(name: String, path: String) {
        val map = load()
        map[name]?.remove(path)
        save(map)
    }

    fun setPaths(name: String, paths: List<String>) {
        val map = load()
        map[name] = paths.toMutableList()
        save(map)
    }
}

/** Content URI for sharing [file] through [ShareProvider]
 *  (framework-only replacement for AndroidX FileProvider). The
 *  token is the Base64-url of the absolute path; access is only
 *  ever granted per-intent by the caller. */
fun shareUri(ctx: android.content.Context, file: File): Uri {
    val token = android.util.Base64.encodeToString(
        file.absolutePath.toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
            android.util.Base64.NO_PADDING)
    return Uri.parse("content://${ctx.packageName}.fileprovider/$token")
}

/** Minimal file-sharing ContentProvider (declared in the
 *  manifest, exported with grantUriPermissions). Serves exactly
 *  the file encoded in the URI token, read-only. */
class ShareProvider : android.content.ContentProvider() {

    private fun fileFor(uri: Uri): File? {
        val token = uri.lastPathSegment ?: return null
        return try {
            val path = String(android.util.Base64.decode(token,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                    android.util.Base64.NO_PADDING), Charsets.UTF_8)
            File(path)
        } catch (t: Throwable) {
            null
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val file = fileFor(uri) ?: return null
        if (!file.exists()) return null
        return android.os.ParcelFileDescriptor.open(file,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val file = fileFor(uri) ?: return "application/octet-stream"
        val ext = file.extension.lowercase()
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?):
        android.database.Cursor {
        val cursor = android.database.MatrixCursor(
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE))
        val file = fileFor(uri)
        if (file != null && file.exists()) {
            cursor.addRow(arrayOf(file.name, file.length()))
        }
        return cursor
    }

    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: android.content.ContentValues?,
                        selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** Launches the player with MX's intent contract (PlayerActivity
 *  header): "path", "title", "playlist" (ArrayList<String>),
 *  "index", optional "decoder" (MX values 0 Default / 1 HW /
 *  4 HW+ / 2 SW). Also records the play in history. */
fun launchPlayer(activity: Activity, paths: List<String>, index: Int,
                 titles: Map<String, String> = emptyMap(), decoder: Int = -1,
                 resumeExact: Boolean = false) {
    if (paths.isEmpty()) return
    val i = index.coerceIn(0, paths.size - 1)
    val path = paths[i]
    Store.recordPlayed(path)
    val intent = Intent(activity, PlayerActivity::class.java).apply {
        putExtra("path", path)
        putExtra("title", titles[path] ?: File(path).name)
        putStringArrayListExtra("playlist", ArrayList(paths))
        putExtra("index", i)
        if (decoder >= 0) putExtra("decoder", decoder)
        // v0.5.3: the play-last (resume) button — the player seeks
        // straight to the saved position, no Ask prompt.
        if (resumeExact) putExtra("resume_exact", true)
    }
    activity.startActivity(intent)
}

/** Shared chrome for the secondary surfaces: MX-blue / themed bar
 *  with back arrow + title, and a vertical content container.
 *  Returns (root, content). */
internal fun surfaceChrome(activity: Activity, title: String,
                           theme: Settings.ThemeColors): Pair<LinearLayout, LinearLayout> {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.pageBg)
    }
    val bar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(theme.chrome)
        setPadding(dp(8), 0, dp(8), 0)
    }
    bar.addView(TextView(activity).apply {
        text = "←"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(8), dp(10), dp(12), dp(10))
        setOnClickListener { activity.finish() }
    })
    bar.addView(TextView(activity).apply {
        text = title; textSize = 18f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(0xFFFFFFFF.toInt())
    })
    root.addView(bar, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(activity).apply { addView(content) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    return root to content
}

internal fun surfaceRow(activity: Activity, theme: Settings.ThemeColors,
                        title: String, summary: String): LinearLayout {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val row = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
    }
    row.addView(TextView(activity).apply {
        text = title; textSize = 16f; setTextColor(theme.title)
    })
    if (summary.isNotEmpty()) {
        row.addView(TextView(activity).apply {
            text = summary; textSize = 13f; setTextColor(theme.tertiary)
            setPadding(0, dp(2), 0, 0)
        })
    }
    return row
}

internal fun Activity.toast(msg: String) =
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/* ==================== Recycle Bin (spec §2.10) ==================== */

/** Recycle Bin surface. Menu (spec order): "View Menu", "Help",
 *  "Select", "Delete All". Tapping an item restores it to its
 *  original folder. */
class RecycleBinActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private lateinit var content: LinearLayout
    private var selecting = false
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        val (root, c) = surfaceChrome(this, "Recycle Bin", theme)
        content = c
        // Menu row (spec order): View Menu · Help · Select · Delete All
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.card)
        }
        for (label in listOf("View Menu", "Help", "Select", "Delete All")) {
            menu.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(theme.accent)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                setOnClickListener { onMenu(label) }
            })
        }
        root.addView(menu, 1)
        setContentView(root)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun onMenu(label: String) {
        when (label) {
            "View Menu" -> AlertDialog.Builder(this)
                .setTitle("View Menu")
                .setMessage("Tap an item to restore it to its original folder. " +
                    "Use Select to restore or delete several items at once.")
                .setPositiveButton("OK", null)
                .show()
            "Help" -> startActivity(Intent(this, HelpActivity::class.java)
                .putExtra("topic", "faq"))
            "Select" -> {
                selecting = !selecting
                if (!selecting) selected.clear()
                render()
            }
            "Delete All" -> {
                if (Store.recycleEntries().isEmpty()) {
                    toast("Recycle bin is empty")
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle("Delete All")
                    .setMessage("Permanently delete everything in the recycle bin?")
                    .setPositiveButton("Delete") { _, _ ->
                        FileOps.emptyRecycle(this)
                        selected.clear()
                        render()
                        toast("Recycle bin emptied")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        val entries = Store.recycleEntries()
            .sortedByDescending { it.deletedAtMs }
        if (selecting && selected.isNotEmpty()) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF000000.toInt())
            }
            for (label in listOf("Restore", "Delete", "Cancel")) {
                bar.addView(TextView(this).apply {
                    text = label; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    setOnClickListener { onSelectionAction(label) }
                })
            }
            content.addView(bar)
        }
        if (entries.isEmpty()) {
            content.addView(surfaceRow(this, theme, "Recycle bin is empty",
                "Deleted videos are kept here until you restore or delete them"))
            return
        }
        for (entry in entries) {
            val mark = if (selecting && selected.contains(entry.id)) "☑ " else
                if (selecting) "☐ " else ""
            val row = surfaceRow(this, theme, mark + entry.title,
                "${LibFmt.size(entry.sizeBytes)} · Deleted ${LibFmt.dateTime(entry.deletedAtMs)}\n${entry.origPath}")
            row.setOnClickListener {
                if (selecting) {
                    if (!selected.add(entry.id)) selected.remove(entry.id)
                    render()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Restore")
                        .setMessage("Restore '${entry.title}' to its original folder?")
                        .setPositiveButton("Restore") { _, _ ->
                            val path = FileOps.restoreFromRecycle(this, entry)
                            if (path != null) {
                                toast("Restored")
                                render()
                            } else toast("Restore failed")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            row.setOnLongClickListener {
                selecting = true
                selected.add(entry.id)
                render()
                true
            }
            content.addView(row)
        }
    }

    private fun onSelectionAction(label: String) {
        val entries = Store.recycleEntries().filter { selected.contains(it.id) }
        when (label) {
            "Restore" -> {
                var ok = 0
                for (e in entries) if (FileOps.restoreFromRecycle(this, e) != null) ok++
                toast("Restored $ok item(s)")
            }
            "Delete" -> {
                for (e in entries) FileOps.deleteRecycleEntry(this, e)
                toast("Deleted ${entries.size} item(s)")
            }
            "Cancel" -> Unit
        }
        selecting = false
        selected.clear()
        render()
    }
}

/* ==================== Private Folder (spec §2.11) ==================== */

/** Private Folder surface: PIN-locked (hash in [Store]). Toolbar
 *  (spec order): "List view" toggle, "Refresh", "Change your PIN",
 *  "Select". Selection action mode carries only SELECT ALL +
 *  Play / Unlock / Delete. */
class PrivateFolderActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private lateinit var content: LinearLayout
    private var unlocked = false
    private var gridMode = false
    private var selecting = false
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        val (root, c) = surfaceChrome(this, "Private Folder", theme)
        content = c
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.card)
        }
        for (label in listOf("List view", "Refresh", "Change your PIN", "Select")) {
            bar.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(theme.accent)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                setOnClickListener { onToolbar(label, this) }
            })
        }
        root.addView(bar, 1)
        setContentView(root)
        if (Store.privatePinHash().isEmpty()) showSetPin() else showVerifyPin()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pinInput(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setPadding(dp(20), dp(12), dp(20), dp(4))
    }

    private fun showSetPin() {
        val first = pinInput("New PIN")
        val second = pinInput("Confirm PIN")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(first); addView(second)
        }
        AlertDialog.Builder(this)
            .setTitle("Set your PIN")
            .setMessage("Choose a PIN to lock the private folder.")
            .setView(box)
            .setPositiveButton("Set PIN") { _, _ ->
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    a.length < 4 -> { toast("PIN must be at least 4 digits"); showSetPin() }
                    a != b -> { toast("PINs do not match"); showSetPin() }
                    else -> {
                        Store.setPrivatePinHash(FileOps.pinHash(a))
                        unlocked = true
                        render()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showVerifyPin() {
        val input = pinInput("PIN")
        AlertDialog.Builder(this)
            .setTitle("Private Folder")
            .setMessage("Enter your PIN to unlock.")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (FileOps.pinHash(input.text.toString()) == Store.privatePinHash()) {
                    unlocked = true
                    render()
                } else {
                    toast("Wrong PIN")
                    showVerifyPin()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun onToolbar(label: String, view: TextView) {
        if (!unlocked) return
        when (label) {
            "List view" -> {
                gridMode = !gridMode
                view.text = if (gridMode) "Grid view" else "List view"
                render()
            }
            "Refresh" -> render()
            "Change your PIN" -> {
                val current = pinInput("Current PIN")
                val next = pinInput("New PIN")
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(current); addView(next)
                }
                AlertDialog.Builder(this)
                    .setTitle("Change your PIN")
                    .setView(box)
                    .setPositiveButton("Change") { _, _ ->
                        if (FileOps.pinHash(current.text.toString()) != Store.privatePinHash()) {
                            toast("Current PIN is wrong")
                        } else if (next.text.toString().length < 4) {
                            toast("PIN must be at least 4 digits")
                        } else {
                            Store.setPrivatePinHash(FileOps.pinHash(next.text.toString()))
                            toast("PIN changed")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            "Select" -> {
                selecting = !selecting
                if (!selecting) selected.clear()
                render()
            }
        }
    }

    private fun storedPath(entry: PrivateEntry): String =
        File(FileOps.privateDir(this), entry.storedName).absolutePath

    private fun render() {
        if (!unlocked) return
        content.removeAllViews()
        val entries = Store.privateEntries().sortedByDescending { it.addedAtMs }
        if (selecting) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF000000.toInt())
            }
            for (label in listOf("SELECT ALL", "Play", "Unlock", "Delete")) {
                bar.addView(TextView(this).apply {
                    text = label; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    setOnClickListener { onSelectionAction(label) }
                })
            }
            content.addView(bar)
        }
        if (entries.isEmpty()) {
            content.addView(surfaceRow(this, theme, "Private folder is empty",
                "Lock videos here from the library: long-press → Lock in Private Folder"))
            return
        }
        if (gridMode) {
            var i = 0
            while (i < entries.size) {
                val rowPair = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (j in 0 until 2) {
                    val idx = i + j
                    if (idx < entries.size) {
                        val cell = surfaceRow(this, theme, entries[idx].title,
                            LibFmt.size(entries[idx].sizeBytes))
                        cell.layoutParams = LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        wireEntry(cell, entries[idx])
                        rowPair.addView(cell)
                    } else {
                        rowPair.addView(View(this), LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }
                content.addView(rowPair)
                i += 2
            }
        } else {
            for (entry in entries) {
                val mark = if (selecting && selected.contains(entry.storedName)) "☑ " else
                    if (selecting) "☐ " else ""
                val row = surfaceRow(this, theme, mark + entry.title,
                    "${LibFmt.size(entry.sizeBytes)} · Added ${LibFmt.dateTime(entry.addedAtMs)}")
                wireEntry(row, entry)
                content.addView(row)
            }
        }
    }

    private fun wireEntry(view: View, entry: PrivateEntry) {
        view.setOnClickListener {
            if (selecting) {
                if (!selected.add(entry.storedName)) selected.remove(entry.storedName)
                render()
            } else {
                val entries = Store.privateEntries().sortedByDescending { it.addedAtMs }
                val paths = entries.map { storedPath(it) }
                val titles = entries.associate { storedPath(it) to it.title }
                launchPlayer(this, paths, paths.indexOf(storedPath(entry)), titles)
            }
        }
        view.setOnLongClickListener {
            selecting = true
            selected.add(entry.storedName)
            render()
            true
        }
    }

    private fun onSelectionAction(label: String) {
        val entries = Store.privateEntries()
        when (label) {
            "SELECT ALL" -> {
                selected.addAll(entries.map { it.storedName })
                render()
                return
            }
            "Play" -> {
                val chosen = entries.filter { selected.contains(it.storedName) }
                if (chosen.isNotEmpty()) {
                    launchPlayer(this, chosen.map { storedPath(it) }, 0,
                        chosen.associate { storedPath(it) to it.title })
                }
            }
            "Unlock" -> {
                var ok = 0
                for (e in entries.filter { selected.contains(it.storedName) }) {
                    if (FileOps.moveOutOfPrivate(this, e) != null) ok++
                }
                toast("Unlocked $ok item(s)")
            }
            "Delete" -> {
                val chosen = entries.filter { selected.contains(it.storedName) }
                for (e in chosen) FileOps.deletePrivateEntry(this, e)
                toast("Deleted ${chosen.size} item(s)")
            }
        }
        selecting = false
        selected.clear()
        render()
    }
}

/* ==================== Playback History (spec §4.6 #109) ==================== */

/** History surface: every video with a resume position, finished
 *  mark or play record, newest first. Tap plays. */
class HistoryActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        val (root, c) = surfaceChrome(this, "History", theme)
        content = c
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.card)
        }
        bar.addView(TextView(this).apply {
            text = "Clear history"; textSize = 13f; setTextColor(theme.accent)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("Clear history")
                    .setMessage("Delete all playback history, resume positions and finished marks?")
                    .setPositiveButton("Clear") { _, _ ->
                        Store.clearHistoryData()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        root.addView(bar, 1)
        setContentView(root)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        content.removeAllViews()
        val records = Store.historyRecords()
        if (records.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No playback history yet",
                "Videos you play will appear here"))
            return
        }
        val byPath = Library.scan(this).associateBy { it.path }
        val titles = records.associate { r ->
            r.path to (byPath[r.path]?.title ?: File(r.path).name)
        }
        for (record in records) {
            val state = when {
                record.finished -> "Finished"
                record.lastPosMs > 0L -> "Resume ${LibFmt.duration(record.lastPosMs)}"
                else -> "Played"
            }
            val row = surfaceRow(this, theme, titles[record.path] ?: record.path,
                "$state · ${LibFmt.dateTime(record.lastPlayedAtMs)}")
            row.setOnClickListener {
                launchPlayer(this, records.map { it.path },
                    records.indexOf(record), titles)
            }
            content.addView(row)
        }
    }
}

/* ==================== Music Library (spec §4.6 #105) ==================== */

/** Music surface: Tracks / Playlists / Albums / Artists / Folders
 *  over MediaStore.Audio, plus the two MX surfaces that were
 *  missing (v0.4 Stage pass): Favourites (MusicFavouriteActivity —
 *  the shared [Store] favourite set, same one videos use) and
 *  Recently Played (RecentlyPlayedActivity — [Store] playback
 *  history joined to the audio scan). MX's tab bar is 38dp
 *  (activity_local_music_list.xml MagicIndicator dp38); our tab
 *  row matches at ~38dp of padding. Tracks play in the player
 *  (audio mode). Long-press a track for Lyrics / Favourite. */
class MusicActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private lateinit var content: LinearLayout
    private var tab = "Tracks"
    private var groupFilter: Pair<String, String>? = null // (tab, value)
    private var tracks: List<AudioItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        val (root, c) = surfaceChrome(this, "Music", theme)
        content = c
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.card)
        }
        for (label in listOf("Tracks", "Playlists", "Albums", "Artists", "Folders",
            "Favourites", "Recent")) {
            tabs.addView(TextView(this).apply {
                text = label; textSize = 12f; setTextColor(theme.accent)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                setOnClickListener {
                    tab = label
                    groupFilter = null
                    render()
                }
            })
        }
        root.addView(tabs, 1)
        setContentView(root)
        tracks = Library.scanAudio(this)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        content.removeAllViews()
        when {
            groupFilter != null -> renderTracks(tracks.filter { matches(it, groupFilter!!) })
            tab == "Tracks" -> renderTracks(tracks)
            tab == "Playlists" -> renderPlaylists()
            tab == "Favourites" -> renderTracks(
                tracks.filter { Store.isFavourite(it.path) })
            tab == "Recent" -> {
                val byPath = tracks.associateBy { it.path }
                renderTracks(Store.historyRecords()
                    .mapNotNull { byPath[it.path] })
            }
            else -> renderGroups(tab)
        }
    }

    private fun matches(item: AudioItem, filter: Pair<String, String>): Boolean =
        when (filter.first) {
            "Albums" -> item.album == filter.second
            "Artists" -> item.artist == filter.second
            else -> item.folder == filter.second
        }

    private fun groupKey(item: AudioItem, tabName: String): String = when (tabName) {
        "Albums" -> item.album.ifBlank { "Unknown album" }
        "Artists" -> item.artist.ifBlank { "Unknown artist" }
        else -> item.folder.ifBlank { "Unknown folder" }
    }

    private fun renderGroups(tabName: String) {
        val groups = tracks.groupBy { groupKey(it, tabName) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        if (groups.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No music found",
                "Audio files on this device will appear here"))
            return
        }
        for ((name, items) in groups) {
            val row = surfaceRow(this, theme, name, "${items.size} tracks")
            row.setOnClickListener {
                groupFilter = tabName to name
                render()
            }
            content.addView(row)
        }
    }

    private fun renderPlaylists() {
        val newRow = surfaceRow(this, theme, "＋ New playlist", "")
        newRow.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Playlist name"
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
            AlertDialog.Builder(this)
                .setTitle("New playlist")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    if (PlaylistStore.create(input.text.toString())) render()
                    else toast("A playlist with that name already exists")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        content.addView(newRow)
        for (name in PlaylistStore.names()) {
            val row = surfaceRow(this, theme, name,
                "${PlaylistStore.paths(name).size} items")
            row.setOnClickListener {
                startActivity(Intent(this, PlaylistDetailActivity::class.java)
                    .putExtra("name", name))
            }
            content.addView(row)
        }
    }

    private fun renderTracks(list: List<AudioItem>) {
        if (groupFilter != null) {
            val back = surfaceRow(this, theme, "← ${groupFilter!!.second}", "Back to $tab")
            back.setOnClickListener {
                groupFilter = null
                render()
            }
            content.addView(back)
        }
        if (list.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No tracks", ""))
            return
        }
        val titles = list.associate { it.path to it.title }
        for (item in list) {
            val summary = listOf(item.artist, item.album)
                .filter { it.isNotBlank() }.joinToString(" · ") +
                " · ${LibFmt.duration(item.durationMs)}"
            val row = surfaceRow(this, theme, item.title, summary)
            row.setOnClickListener {
                // Record the music-widget state (spec #106) so the
                // home-screen widgets show this track and their
                // play/prev/next buttons target this queue.
                Store.setStringSetting(Settings.LAST_MUSIC_PATH, item.path)
                Store.setStringSetting(Settings.LAST_MUSIC_TITLE, item.title)
                Store.setStringSetting(Settings.LAST_MUSIC_ARTIST, item.artist)
                Store.setStringListSetting(Settings.LAST_MUSIC_QUEUE,
                    list.map { it.path })
                MusicWidgets.refresh(this)
                launchPlayer(this, list.map { it.path }, list.indexOf(item), titles)
            }
            row.setOnLongClickListener {
                showTrackMenu(item)
                true
            }
            content.addView(row)
        }
    }

    private fun showTrackMenu(item: AudioItem) {
        val favLabel = if (Store.isFavourite(item.path)) "Remove favourite"
            else "Add favourite"
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("Lyrics", favLabel)) { _, which ->
                when (which) {
                    0 -> showLyrics(item)
                    1 -> {
                        Store.setFavourite(item.path, !Store.isFavourite(item.path))
                        toast(if (Store.isFavourite(item.path)) "Added to favourites"
                            else "Removed from favourites")
                        if (tab == "Favourites") render()
                    }
                }
            }
            .show()
    }

    /** Lyrics view: the .lrc sidecar parsed by
     *  [SubtitleFormats.parseLrc] and shown as clean timed lines
     *  (timestamps stripped) in a scrollable view — MX's
     *  LyricsActivity is a WebView fed by its online lyrics
     *  service (CUT: internet); the local sidecar is our honest
     *  equivalent. Unparseable files fall back to their raw text. */
    private fun showLyrics(item: AudioItem) {
        val file = File(item.path)
        val base = file.name.substringBeforeLast('.', file.name)
        val lrc = File(file.parentFile, "$base.lrc")
        val body = TextView(this).apply {
            textSize = 15f
            setTextColor(theme.title)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setLineSpacing(0f, 1.3f)
        }
        body.text = when {
            !lrc.exists() ->
                "No lyrics found.\n\nPlace a .lrc file with the same name next to the track."
            else -> try {
                val raw = lrc.readText()
                val cues = SubtitleFormats.parseLrc(raw)
                if (cues.isNotEmpty()) cues.joinToString("\n") { it.text } else raw
            } catch (t: Throwable) {
                "Could not read the lyrics file."
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Lyrics — ${item.title}")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && tab == "Playlists") render()
    }
}

/* ==================== Playlist Detail (spec §2.9) ==================== */

/** One playlist's contents. Bar: "Sort by" + "More" (Rename /
 *  Delete playlist). Row long-press menu per spec §2.9: Play next,
 *  Play later, Add to playlist, Remove, Share, Delete,
 *  Add/Remove favourite, Add to Home Screen. */
class PlaylistDetailActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private lateinit var content: LinearLayout
    private var playlistName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        playlistName = intent.getStringExtra("name") ?: ""
        theme = Settings.currentThemeColors(this)
        val (root, c) = surfaceChrome(this, playlistName, theme)
        content = c
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.card)
        }
        for (label in listOf("Sort by", "More")) {
            bar.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(theme.accent)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                setOnClickListener { if (label == "Sort by") showSort() else showMore() }
            })
        }
        root.addView(bar, 1)
        setContentView(root)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun items(): List<VideoItem> {
        val byPath = Library.scan(this).associateBy { it.path }
        return PlaylistStore.paths(playlistName).mapNotNull { byPath[it] }
    }

    private fun render() {
        content.removeAllViews()
        val videos = items()
        if (videos.isEmpty()) {
            content.addView(surfaceRow(this, theme, "Playlist is empty",
                "Add videos from the library: item menu → Add to Playlist"))
            return
        }
        val titles = videos.associate { it.path to it.title }
        for (video in videos) {
            val row = surfaceRow(this, theme, video.title,
                "${LibFmt.duration(video.durationMs)} · ${LibFmt.size(video.sizeBytes)}")
            row.setOnClickListener {
                launchPlayer(this, videos.map { it.path }, videos.indexOf(video), titles)
            }
            row.setOnLongClickListener {
                showItemMenu(video)
                true
            }
            content.addView(row)
        }
    }

    private fun showSort() {
        val fields = listOf("Title", "Date", "Length", "Size")
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setItems(fields.toTypedArray()) { _, which ->
                val mode = listOf(SortMode.NAME, SortMode.DATE,
                    SortMode.DURATION, SortMode.SIZE)[which]
                val sorted = Library.sortVideos(items(), mode, true)
                PlaylistStore.setPaths(playlistName, sorted.map { it.path })
                render()
            }
            .show()
    }

    private fun showMore() {
        AlertDialog.Builder(this)
            .setTitle(playlistName)
            .setItems(arrayOf("Rename playlist", "Delete playlist")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply {
                            setText(playlistName)
                            setPadding(dp(20), dp(12), dp(20), dp(4))
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Rename playlist")
                            .setView(input)
                            .setPositiveButton("Rename") { _, _ ->
                                if (PlaylistStore.rename(playlistName, input.text.toString())) {
                                    playlistName = input.text.toString().trim()
                                    toast("Renamed")
                                    recreate()
                                } else toast("Rename failed")
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete playlist")
                            .setMessage("Delete the playlist '$playlistName'? The videos stay on your device.")
                            .setPositiveButton("Delete") { _, _ ->
                                PlaylistStore.delete(playlistName)
                                finish()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showItemMenu(video: VideoItem) {
        val favLabel = if (Store.isFavourite(video.path)) "Remove favourite" else "Add favourite"
        val labels = arrayOf("Play next", "Play later", "Add to playlist…",
            "Remove from playlist", "Share", "Delete", favLabel, "Add to Home Screen")
        AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> { Store.enqueueNext(video.path); toast("Will play next") }
                    1 -> { Store.enqueueLater(video.path); toast("Added to play queue") }
                    2 -> showAddToPlaylist(video)
                    3 -> {
                        PlaylistStore.remove(playlistName, video.path)
                        render()
                    }
                    4 -> shareVideo(video)
                    5 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete")
                            .setMessage("Move '${video.title}' to the recycle bin?")
                            .setPositiveButton("Delete") { _, _ ->
                                val entry = FileOps.moveToRecycle(this, video,
                                    Store.boolSetting(Settings.DELETE_SUBTITLE_TOGETHER))
                                if (entry != null) {
                                    PlaylistStore.remove(playlistName, video.path)
                                    render()
                                } else toast("Delete failed")
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    6 -> {
                        Store.setFavourite(video.path, !Store.isFavourite(video.path))
                        toast(if (Store.isFavourite(video.path)) "Added to favourites"
                        else "Removed from favourites")
                    }
                    7 -> addToHomeScreen(video)
                }
            }
            .show()
    }

    private fun showAddToPlaylist(video: VideoItem) {
        val names = PlaylistStore.names()
        val labels = (names + "New playlist…").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
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
                            val added = PlaylistStore.add(name, listOf(video.path))
                            toast(String.format("%d video(s) added to the playlist '%s'",
                                added, name))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    val added = PlaylistStore.add(names[which], listOf(video.path))
                    toast(String.format("%d video(s) added to the playlist '%s'",
                        added, names[which]))
                }
            }
            .show()
    }

    private fun shareVideo(video: VideoItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = video.mimeType.ifBlank { "video/*" }
            putExtra(Intent.EXTRA_STREAM, shareUri(this@PlaylistDetailActivity, File(video.path)))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun addToHomeScreen(video: VideoItem) {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Home screen shortcuts need Android 8+")
            return
        }
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Home screen shortcuts are not supported by this launcher")
            return
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("path", video.path)
            putExtra("title", video.title)
            putStringArrayListExtra("playlist", arrayListOf(video.path))
            putExtra("index", 0)
        }
        val info = ShortcutInfo.Builder(this, "play-${video.id}")
            .setShortLabel(video.title.take(25))
            .setLongLabel(video.title)
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher_fg))
            .setIntent(intent)
            .build()
        try {
            sm.requestPinShortcut(info, null)
            toast("Shortcut requested")
        } catch (t: Throwable) {
            toast("Could not add the shortcut")
        }
    }
}

/* ==================== USB media list (spec §4.1 #31) ==================== */

/** USB / removable-storage media list. The surface's menu carries
 *  exactly one item — "Refresh" (spec §2: MX's list_usb.xml has a
 *  single @id/media_scan "Refresh").
 *
 *  Volumes come from [StorageManager]. A mounted volume whose root
 *  directory resolves is browsed by walking it for video files:
 *  API 30+ exposes StorageVolume.directory; below that the root is
 *  derived from the app-specific external-files dirs (the volumes
 *  the system granted this app). A volume that cannot be resolved
 *  to files offers the system folder picker; the picked tree is
 *  browsed only when its document id maps to a real path
 *  (ExternalStorageProvider "primary:"/"<uuid>:"/"raw:" ids) —
 *  otherwise the surface says so honestly instead of faking a
 *  listing. Playback uses the normal path-based player contract. */
class UsbActivity : Activity() {

    private data class VolumeRow(
        val description: String,
        val state: String,
        val root: File?
    )

    private lateinit var theme: Settings.ThemeColors
    private lateinit var content: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var browsing: List<VideoItem>? = null
    private var browsingName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        val (root, c) = surfaceChrome(this, "USB", theme)
        content = c
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.card)
        }
        // The USB list's only menu item (spec): Refresh.
        bar.addView(TextView(this).apply {
            text = "Refresh"; textSize = 13f; setTextColor(theme.accent)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener {
                browsing = null
                render()
                toast("Refreshed")
            }
        })
        root.addView(bar, 1)
        setContentView(root)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun volumeRows(): List<VolumeRow> {
        val sm = getSystemService(StorageManager::class.java) ?: return emptyList()
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val extRoots = getExternalFilesDirs(null)?.filterNotNull()
            ?.mapNotNull { Library.volumeRootOf(it.absolutePath) }
            ?.distinct() ?: emptyList()
        val usedRoots = mutableSetOf<String>()
        val rows = mutableListOf<VolumeRow>()
        for (volume in sm.storageVolumes) {
            if (!volume.isRemovable) continue
            var root: File? = null
            if (Build.VERSION.SDK_INT >= 30) root = volume.directory
            if (root == null && volume.isPrimary) root = File(primaryRoot)
            if (root == null) {
                // API 24–29: pair the volume with an external-files
                // root that is not the primary volume's and is not
                // already paired with another volume.
                val candidate = extRoots.firstOrNull {
                    it != primaryRoot && !usedRoots.contains(it)
                }
                if (candidate != null) root = File(candidate)
            }
            if (root != null) usedRoots.add(root.absolutePath)
            rows.add(VolumeRow(volume.getDescription(this),
                volume.state ?: "", root))
        }
        return rows
    }

    private fun render() {
        content.removeAllViews()
        val files = browsing
        if (files != null) {
            val back = surfaceRow(this, theme, "← Volumes", browsingName)
            back.setOnClickListener {
                browsing = null
                render()
            }
            content.addView(back)
            if (files.isEmpty()) {
                content.addView(surfaceRow(this, theme, "No videos found",
                    "No video files were found on this volume"))
                return
            }
            val titles = files.associate { it.path to it.title }
            for (item in files) {
                val summary = LibFmt.size(item.sizeBytes) +
                    if (item.durationMs > 0L) " · ${LibFmt.duration(item.durationMs)}" else ""
                val row = surfaceRow(this, theme, item.title, summary)
                row.setOnClickListener {
                    launchPlayer(this, files.map { it.path },
                        files.indexOf(item), titles)
                }
                content.addView(row)
            }
            return
        }
        val rows = volumeRows()
        if (rows.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No USB storage connected",
                "Connect a USB drive with an OTG adapter, then tap Refresh"))
        }
        for (row in rows) {
            val mounted = row.state == Environment.MEDIA_MOUNTED
            val summary = when {
                !mounted -> "Not mounted (state: ${row.state})"
                row.root != null -> "Mounted · ${row.root.absolutePath}"
                else -> "Mounted · tap to open with the system picker"
            }
            val view = surfaceRow(this, theme, row.description, summary)
            view.setOnClickListener { onVolumeClick(row, mounted) }
            content.addView(view)
        }
        val picker = surfaceRow(this, theme, "Open a folder with the system picker…",
            "For drives the volume list cannot read directly")
        picker.setOnClickListener { pickTree() }
        content.addView(picker)
    }

    private fun onVolumeClick(row: VolumeRow, mounted: Boolean) {
        if (!mounted) {
            toast("Volume is not mounted")
            return
        }
        val root = row.root
        if (root != null && root.canRead()) {
            toast("Scanning ${row.description}…")
            scanVolume(root, row.description)
        } else {
            AlertDialog.Builder(this)
                .setTitle(row.description)
                .setMessage("This volume can't be read as files directly. " +
                    "Open it with the system folder picker instead?")
                .setPositiveButton("Open picker") { _, _ -> pickTree() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun pickTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_USB_TREE)
        } catch (t: Throwable) {
            toast("No folder picker available on this device")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_USB_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) { /* best effort */ }
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (t: Throwable) {
            null
        }
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val path = docId?.let { Library.pathFromTreeDocumentId(it, primaryRoot) }
        val dir = path?.let { File(it) }
        if (dir != null && dir.isDirectory && dir.canRead()) {
            toast("Scanning…")
            scanVolume(dir, dir.name)
        } else {
            toast("That location can't be browsed as files — its provider " +
                "doesn't expose real paths")
        }
    }

    /** Walks [root] (bounded depth/count) for video files and
     *  switches the surface to the file list. Durations are probed
     *  for the first [MAX_PROBED] files only, to bound scan time. */
    private fun scanVolume(root: File, label: String) {
        io.execute {
            val exts = Store.stringSetSetting(Settings.SCAN_EXTENSIONS)
                .ifEmpty { Settings.VIDEO_EXTENSIONS.toSet() }
            val found = mutableListOf<File>()
            fun walk(dir: File, depth: Int) {
                if (found.size >= MAX_FILES) return
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (found.size >= MAX_FILES) return
                    if (child.isDirectory) {
                        if (depth < MAX_DEPTH && !child.name.startsWith(".")) {
                            walk(child, depth + 1)
                        }
                    } else if (Library.isVideoFileName(child.name, exts)) {
                        found.add(child)
                    }
                }
            }
            walk(root, 0)
            var probed = 0
            val items = found
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map { f ->
                    val duration = if (probed < MAX_PROBED) {
                        probed++
                        probeDuration(f)
                    } else 0L
                    VideoItem(
                        id = f.absolutePath.hashCode().toLong(),
                        title = f.name.substringBeforeLast('.', f.name),
                        path = f.absolutePath,
                        durationMs = duration,
                        sizeBytes = f.length(),
                        folder = f.parentFile?.name ?: "",
                        dateAddedSec = f.lastModified() / 1000L,
                        width = 0, height = 0)
                }
            mainHandler.post {
                browsing = items
                browsingName = label
                render()
            }
        }
    }

    private fun probeDuration(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            try { r.release() } catch (t: Throwable) { /* ignore */ }
        }
    }

    companion object {
        private const val REQ_USB_TREE = 61
        private const val MAX_FILES = 2000
        private const val MAX_DEPTH = 8
        private const val MAX_PROBED = 300
    }
}

/* ==================== Media Cleaner (spec §4.6 #108) ==================== */

/** Media manager / cleaner: duplicate videos (same name + size),
 *  large files, zero-byte junk entries and empty folders, computed
 *  by [Cleaner] over a MediaStore scan. Deleting media moves it to
 *  the recycle bin through FileOps (the library's delete path,
 *  honouring the same "Allow file edits" gate); empty folders are
 *  removed with File.delete() only after being re-verified empty —
 *  an empty folder holds nothing the bin could restore. */
class MediaCleanerActivity : Activity() {

    private lateinit var theme: Settings.ThemeColors
    private lateinit var content: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var items: List<VideoItem> = emptyList()
    private var emptyDirs: List<String> = emptyList()
    private val selected = mutableSetOf<String>()
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        PSet.init(this)
        theme = Settings.currentThemeColors(this)
        val (root, c) = surfaceChrome(this, "Media Cleaner", theme)
        content = c
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.card)
        }
        for (label in listOf("Rescan", "Delete selected")) {
            bar.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(theme.accent)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                setOnClickListener {
                    if (label == "Rescan") load() else deleteSelected()
                }
            })
        }
        root.addView(bar, 1)
        setContentView(root)
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        loaded = false
        selected.clear()
        render()
        io.execute {
            val scanned = Library.scan(this,
                Store.boolSetting(Settings.RESPECT_NOMEDIA))
            // Candidate empty folders: subfolders of the media
            // folders (and the media folders themselves) that are
            // empty on disk; Cleaner.emptyFolders cross-checks that
            // no scanned item lives under them.
            val parents = scanned.map { Library.dirOfPath(it.path) }
                .distinct().filter { it.isNotEmpty() }
            val candidates = mutableSetOf<String>()
            for (parent in parents) {
                val dir = File(parent)
                if (dir.listFiles()?.isEmpty() == true) candidates.add(parent)
                dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                    if (sub.listFiles()?.isEmpty() == true) {
                        candidates.add(sub.absolutePath)
                    }
                }
            }
            val empties = Cleaner.emptyFolders(candidates.toList(), scanned)
            mainHandler.post {
                items = scanned
                emptyDirs = empties
                loaded = true
                render()
            }
        }
    }

    private fun header(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.accent)
            setPadding(dp(16), dp(20), dp(16), dp(6))
        })
    }

    private fun selectableRow(item: VideoItem, summary: String) {
        val mark = if (selected.contains(item.path)) "☑ " else "☐ "
        val row = surfaceRow(this, theme, mark + item.title, summary)
        row.setOnClickListener {
            if (!selected.add(item.path)) selected.remove(item.path)
            render()
        }
        content.addView(row)
    }

    private fun render() {
        content.removeAllViews()
        if (!loaded) {
            content.addView(surfaceRow(this, theme, "Scanning…",
                "Looking for duplicates, large files and junk"))
            return
        }
        val dupGroups = Cleaner.duplicateGroups(items)
        val wasted = dupGroups.sumOf { Cleaner.wastedBytes(it) }
        content.addView(surfaceRow(this, theme,
            "${items.size} videos · ${LibFmt.size(items.sumOf { it.sizeBytes })}",
            if (wasted > 0L) "${LibFmt.size(wasted)} recoverable from duplicates"
            else "No duplicates found"))
        if (selected.isNotEmpty()) {
            val size = items.filter { selected.contains(it.path) }
                .sumOf { it.sizeBytes }
            val row = surfaceRow(this, theme,
                "Delete selected (${selected.size}) · ${LibFmt.size(size)}",
                "Moves the files to the recycle bin")
            row.setOnClickListener { deleteSelected() }
            content.addView(row)
        }
        // Duplicates
        header("Duplicates (${dupGroups.size} groups)")
        if (dupGroups.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No duplicate videos",
                "Duplicates share the same file name and size"))
        }
        for (group in dupGroups) {
            val name = group.first().path.substringAfterLast('/')
            val head = surfaceRow(this, theme,
                "$name · ${group.size} copies",
                "${LibFmt.size(group.first().sizeBytes)} each · " +
                    "${LibFmt.size(Cleaner.wastedBytes(group))} wasted")
            head.setOnClickListener {
                // Select every copy except the first (the keeper).
                for (item in group.drop(1)) selected.add(item.path)
                render()
            }
            content.addView(head)
            for (item in group) {
                selectableRow(item, item.path)
            }
        }
        // Large files
        val large = Cleaner.largeFiles(items)
        header("Large files (${large.size}, ≥ " +
            LibFmt.size(Cleaner.LARGE_FILE_THRESHOLD_BYTES) + ")")
        if (large.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No large files", ""))
        }
        for (item in large) {
            selectableRow(item, "${LibFmt.size(item.sizeBytes)} · ${item.path}")
        }
        // Empty files (junk)
        val empties = Cleaner.emptyFiles(items)
        header("Empty files (${empties.size})")
        if (empties.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No empty files", ""))
        }
        for (item in empties) {
            selectableRow(item, item.path)
        }
        // Empty folders
        header("Empty folders (${emptyDirs.size})")
        if (emptyDirs.isEmpty()) {
            content.addView(surfaceRow(this, theme, "No empty folders", ""))
        } else {
            for (dir in emptyDirs) {
                content.addView(surfaceRow(this, theme, dir, "Empty folder"))
            }
            val del = surfaceRow(this, theme, "Delete all empty folders",
                "Folders are re-checked before anything is removed")
            del.setOnClickListener { deleteEmptyFolders() }
            content.addView(del)
        }
    }

    private fun deleteSelected() {
        val chosen = items.filter { selected.contains(it.path) }
        if (chosen.isEmpty()) {
            toast("Nothing selected — tap items to select them")
            return
        }
        if (!Store.boolSetting(Settings.ALLOW_EDITS)) {
            toast("File edits are disabled in Settings → General")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete selected")
            .setMessage("Move ${chosen.size} file(s) to the recycle bin?")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    var ok = 0
                    val together =
                        Store.boolSetting(Settings.DELETE_SUBTITLE_TOGETHER)
                    for (item in chosen) {
                        if (FileOps.moveToRecycle(this, item, together) != null) ok++
                    }
                    mainHandler.post {
                        toast("$ok file(s) moved to the recycle bin")
                        load()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteEmptyFolders() {
        AlertDialog.Builder(this)
            .setTitle("Delete empty folders")
            .setMessage("Delete ${emptyDirs.size} empty folder(s)? " +
                "Only folders that are still empty are removed.")
            .setPositiveButton("Delete") { _, _ ->
                var ok = 0
                for (path in emptyDirs) {
                    val dir = File(path)
                    if (dir.isDirectory && dir.listFiles()?.isEmpty() == true &&
                        dir.delete()) ok++
                }
                toast("Deleted $ok empty folder(s)")
                load()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
