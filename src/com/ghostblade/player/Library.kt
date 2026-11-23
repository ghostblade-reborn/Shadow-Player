package com.ghostblade.player

import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Video sort orders. v0.1 shipped NAME/DATE/DURATION/SIZE; v0.2
 *  adds the remaining MX "Sort by" fields (spec §2.1): RESOLUTION,
 *  FRAMERATE, STATUS, TYPE, LOCATION, plus DEFAULT (the store's own
 *  order — MX's "Sort by Default"). Direction labels are MX's:
 *  Title "A to Z"/"Z to A", Date "Oldest"/"Newest", Length
 *  "Shortest"/"Longest", Size "Smallest"/"Largest", Resolution &
 *  Frame rate "Lowest"/"Highest", Status/Type/Location
 *  "Ascending"/"Descending". */
enum class SortMode { DEFAULT, NAME, DATE, DURATION, SIZE, RESOLUTION, FRAMERATE, STATUS, TYPE, LOCATION }

/** A named folder and its videos (MX-style Folders view). */
data class FolderGroup(val name: String, val videos: List<VideoItem>) {
    val totalDurationMs: Long get() = videos.sumOf { it.durationMs }
    val totalSizeBytes: Long get() = videos.sumOf { it.sizeBytes }
}

/** MediaStore scan + the pure library helpers.
 *
 *  [scan]/[scanAudio] are the only Android-bound parts.
 *  [groupByFolder], [sortVideos], [filterByTitle], [folderOfPath]
 *  and the v0.2 helpers are pure Kotlin and are what
 *  tests/TestMain.kt exercises on the JVM. */
object Library {

    /** All device videos from MediaStore. Returns an empty list when
     *  the query is denied or fails — the UI shows its empty /
     *  permission state from that, never a crash. Order is
     *  unspecified; callers sort with [sortVideos].
     *
     *  When [respectNomedia] is true (the List setting, default ON),
     *  files under a directory tree containing a `.nomedia` file are
     *  skipped. Scan-folder / extension / hidden filtering is NOT
     *  done here — callers apply [filterByScanSettings] so the raw
     *  scan stays reusable (e.g. playlist resolution). */
    fun scan(ctx: Context, respectNomedia: Boolean = true): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val nomediaCache = mutableMapOf<String, Boolean>()
        try {
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleC = c.getColumnIndex(MediaStore.Video.Media.TITLE)
                val nameC = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dataC = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val durC = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeC = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateC = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val modC = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val wC = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val hC = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val mimeC = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val bucketC = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val path = if (dataC >= 0) c.getString(dataC) ?: continue else continue
                    if (respectNomedia && isUnderNomedia(path, nomediaCache)) continue
                    var title = if (titleC >= 0) c.getString(titleC) else null
                    if (title.isNullOrBlank()) {
                        val dn = if (nameC >= 0) c.getString(nameC) else null
                        title = dn?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                            ?: path.substringAfterLast('/')
                    }
                    val bucket = if (bucketC >= 0) c.getString(bucketC) else null
                    out.add(
                        VideoItem(
                            id = c.getLong(idC),
                            title = title,
                            path = path,
                            durationMs = if (durC >= 0) c.getLong(durC) else 0L,
                            sizeBytes = if (sizeC >= 0) c.getLong(sizeC) else 0L,
                            folder = bucket?.takeIf { it.isNotBlank() } ?: folderOfPath(path),
                            dateAddedSec = if (dateC >= 0) c.getLong(dateC) else 0L,
                            width = if (wC >= 0) c.getInt(wC) else 0,
                            height = if (hC >= 0) c.getInt(hC) else 0,
                            dateModifiedSec = if (modC >= 0) c.getLong(modC) else 0L,
                            mimeType = if (mimeC >= 0) c.getString(mimeC) ?: "" else ""
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            // SecurityException (permission revoked mid-scan) or a
            // broken provider: honest empty result, UI handles it.
            return emptyList()
        }
        return out
    }

    /** True when any ancestor directory of [path] (including its own
     *  folder) holds a `.nomedia` file. Results cached per directory
     *  in [cache] for the duration of one scan. */
    private fun isUnderNomedia(path: String, cache: MutableMap<String, Boolean>): Boolean {
        var dir = dirOfPath(path)
        while (dir.isNotEmpty()) {
            val hit = cache.getOrPut(dir) { File(dir, ".nomedia").exists() }
            if (hit) return true
            val parent = dir.substringBeforeLast('/', "")
            if (parent == dir) break
            dir = parent
        }
        return false
    }

    /** All device audio tracks from MediaStore (music surface). */
    fun scanAudio(ctx: Context): List<AudioItem> {
        val out = mutableListOf<AudioItem>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME
        )
        try {
            ctx.contentResolver.query(
                uri, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
            )?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleC = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val nameC = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataC = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val durC = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeC = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val artistC = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumC = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val dateC = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val bucketC = c.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val path = if (dataC >= 0) c.getString(dataC) ?: continue else continue
                    var title = if (titleC >= 0) c.getString(titleC) else null
                    if (title.isNullOrBlank()) {
                        val dn = if (nameC >= 0) c.getString(nameC) else null
                        title = dn?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                            ?: path.substringAfterLast('/')
                    }
                    val bucket = if (bucketC >= 0) c.getString(bucketC) else null
                    out.add(
                        AudioItem(
                            id = c.getLong(idC),
                            title = title,
                            path = path,
                            durationMs = if (durC >= 0) c.getLong(durC) else 0L,
                            sizeBytes = if (sizeC >= 0) c.getLong(sizeC) else 0L,
                            artist = if (artistC >= 0) c.getString(artistC) ?: "" else "",
                            album = if (albumC >= 0) c.getString(albumC) ?: "" else "",
                            folder = bucket?.takeIf { it.isNotBlank() } ?: folderOfPath(path),
                            dateAddedSec = if (dateC >= 0) c.getLong(dateC) else 0L
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    /* ---------- pure helpers (JVM-tested) ---------- */

    /** Display name of the folder containing [path]:
     *  "/storage/emulated/0/Movies/a.mp4" -> "Movies".
     *  A file at a volume root yields "" (callers show a fallback). */
    fun folderOfPath(path: String): String {
        val parent = path.substringBeforeLast('/', "")
        return parent.substringAfterLast('/')
    }

    /** Full parent directory of [path] ("" when none). */
    fun dirOfPath(path: String): String = path.substringBeforeLast('/', "")

    /** Lowercase file extension without the dot ("" when none). */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).lowercase() else ""
    }

    /** Groups by folder name; groups sorted by name
     *  (case-insensitive), videos keep their incoming order. */
    fun groupByFolder(items: List<VideoItem>): List<FolderGroup> =
        items.groupBy { it.folder }
            .map { (name, vids) -> FolderGroup(name, vids) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** Sorts a copy of [items]. NAME is case-insensitive with a
     *  path tie-break so equal titles order deterministically.
     *  STATUS orders unfinished (0) before finished (1) using
     *  [finishedPaths]; DEFAULT keeps the incoming order. */
    fun sortVideos(
        items: List<VideoItem>, mode: SortMode, ascending: Boolean = true,
        finishedPaths: Set<String> = emptySet()
    ): List<VideoItem> {
        if (mode == SortMode.DEFAULT) return items.toList()
        val cmp: Comparator<VideoItem> = when (mode) {
            SortMode.NAME -> compareBy<VideoItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy { it.path }
            SortMode.DATE -> compareBy<VideoItem> { it.dateAddedSec }.thenBy { it.path }
            SortMode.DURATION -> compareBy<VideoItem> { it.durationMs }.thenBy { it.path }
            SortMode.SIZE -> compareBy<VideoItem> { it.sizeBytes }.thenBy { it.path }
            SortMode.RESOLUTION -> compareBy<VideoItem> { it.width.toLong() * it.height.toLong() }
                .thenBy { it.path }
            SortMode.FRAMERATE -> compareBy<VideoItem> { it.frameRate }.thenBy { it.path }
            SortMode.STATUS -> compareBy<VideoItem> { if (finishedPaths.contains(it.path)) 1 else 0 }
                .thenBy { it.path }
            SortMode.TYPE -> compareBy<VideoItem, String>(String.CASE_INSENSITIVE_ORDER) {
                extensionOf(it.path)
            }.thenBy { it.path }
            SortMode.LOCATION -> compareBy<VideoItem, String>(String.CASE_INSENSITIVE_ORDER) {
                it.path
            }
            SortMode.DEFAULT -> compareBy<VideoItem> { it.path } // unreachable
        }
        return if (ascending) items.sortedWith(cmp) else items.sortedWith(cmp.reversed())
    }

    /** Maps a List-settings sort field id to a [SortMode]. */
    fun sortModeForField(field: String): SortMode = when (field) {
        "title" -> SortMode.NAME
        "date" -> SortMode.DATE
        "length" -> SortMode.DURATION
        "size" -> SortMode.SIZE
        "resolution" -> SortMode.RESOLUTION
        "framerate" -> SortMode.FRAMERATE
        "status" -> SortMode.STATUS
        "type" -> SortMode.TYPE
        "location" -> SortMode.LOCATION
        else -> SortMode.DEFAULT
    }

    /** Case-insensitive title search; a blank query returns [items]
     *  unchanged. */
    fun filterByTitle(items: List<VideoItem>, query: String): List<VideoItem> {
        val q = query.trim()
        if (q.isEmpty()) return items
        return items.filter { it.title.contains(q, ignoreCase = true) }
    }

    /** Applies the List → Scan settings to a raw scan: folder
     *  inclusion ([scanFolders] empty = all folders), extension
     *  filter ([extensions] empty = all), dot-file visibility. */
    fun filterByScanSettings(
        items: List<VideoItem>,
        scanFolders: Set<String>,
        extensions: Set<String>,
        showHiddenFiles: Boolean
    ): List<VideoItem> = items.filter { v ->
        (scanFolders.isEmpty() || scanFolders.contains(dirOfPath(v.path))) &&
            (extensions.isEmpty() || extensions.contains(extensionOf(v.path))) &&
            (showHiddenFiles || !v.path.substringAfterLast('/').startsWith('.'))
    }

    /** Removes app-hidden and private-folder paths from the library
     *  listing. When [showHidden] is on, app-hidden items stay (the
     *  UI marks them); private items never show in the library. */
    fun visibleItems(
        items: List<VideoItem>,
        hiddenPaths: Set<String>,
        privatePaths: Set<String>,
        showHidden: Boolean
    ): List<VideoItem> = items.filter { v ->
        !privatePaths.contains(v.path) && (showHidden || !hiddenPaths.contains(v.path))
    }

    /** MX "NEW" tag: the file was modified within [periodDays]. */
    fun isNewTag(dateModifiedSec: Long, periodDays: Int, nowSec: Long): Boolean {
        if (dateModifiedSec <= 0L || periodDays <= 0) return false
        return nowSec - dateModifiedSec <= periodDays.toLong() * 86_400L
    }

    /** Watched fraction 0f..1f for the resume progress bar;
     *  0f when duration is unknown. */
    fun watchedFraction(durationMs: Long, posMs: Long): Float {
        if (durationMs <= 0L || posMs <= 0L) return 0f
        return (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /* ---------- USB / widget helpers (pure, JVM-tested) ---------- */

    /** Volume root of an app-specific external-files dir:
     *  "/storage/1234-5678/Android/data/<pkg>/files" ->
     *  "/storage/1234-5678". Null when the path has no
     *  "/Android/data/" segment (not an app-specific dir). Used to
     *  resolve removable-volume roots on API 24–29, where
     *  StorageVolume has no public directory accessor. */
    fun volumeRootOf(filesDirPath: String): String? {
        val marker = "/Android/data/"
        val idx = filesDirPath.indexOf(marker)
        if (idx <= 0) return null
        return filesDirPath.substring(0, idx)
    }

    /** Real path for an ExternalStorageProvider tree document id:
     *  "primary:Movies/a.mp4" -> "<primaryRoot>/Movies/a.mp4",
     *  "1234-5678:Movies" -> "/storage/1234-5678/Movies",
     *  "raw:/storage/…" -> the absolute path after "raw:".
     *  Null for ids from other providers / malformed ids — the USB
     *  surface then says honestly that the picked location cannot
     *  be browsed as files. */
    fun pathFromTreeDocumentId(docId: String, primaryRoot: String): String? {
        if (docId.startsWith("raw:")) {
            val p = docId.removePrefix("raw:")
            return if (p.startsWith("/")) p else null
        }
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1)
        val root = if (volume.equals("primary", ignoreCase = true)) primaryRoot
            else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /** True when [name]'s extension is in [extensions]
     *  (case-insensitive; empty set = nothing matches). */
    fun isVideoFileName(name: String, extensions: Set<String>): Boolean =
        extensions.contains(extensionOf(name))

    /** Index arithmetic for queue-style prev/next (music widget):
     *  wraps in both directions; 0 for an empty queue. */
    fun wrapIndex(current: Int, delta: Int, size: Int): Int {
        if (size <= 0) return 0
        val m = (current + delta) % size
        return if (m < 0) m + size else m
    }
}

/** Media cleaner scan logic (spec #108) — pure over [VideoItem]
 *  lists; the activity supplies the items (MediaStore scan) and
 *  the candidate folder list (filesystem), and performs the actual
 *  deletions through FileOps' recycle-bin path. */
object Cleaner {

    /** "Large file" threshold for the cleaner surface: 200 MiB.
     *  (MX does not publish its threshold; this is our documented
     *  choice, shown in the UI.) */
    const val LARGE_FILE_THRESHOLD_BYTES: Long = 200L * 1024L * 1024L

    /** Duplicate groups: same file NAME (case-insensitive) and
     *  same non-zero size, at least two copies. Groups are sorted
     *  by wasted bytes (descending), items inside a group by path
     *  so "the first copy" is a stable keeper. */
    fun duplicateGroups(items: List<VideoItem>): List<List<VideoItem>> {
        val groups = items
            .filter { it.sizeBytes > 0L }
            .groupBy { it.sizeBytes to it.path.substringAfterLast('/').lowercase() }
            .values
            .filter { it.size >= 2 }
            .map { g -> g.sortedBy { it.path } }
        return groups.sortedWith(
            compareByDescending<List<VideoItem>> { wastedBytes(it) }.thenBy { it.first().path })
    }

    /** Bytes recoverable by deleting all but one copy of [group]
     *  (0 for a group of < 2 or zero-size items). */
    fun wastedBytes(group: List<VideoItem>): Long {
        if (group.size < 2) return 0L
        val size = group.first().sizeBytes
        return if (size > 0L) size * (group.size - 1) else 0L
    }

    /** Files at or above [thresholdBytes], largest first. */
    fun largeFiles(items: List<VideoItem>, thresholdBytes: Long = LARGE_FILE_THRESHOLD_BYTES):
        List<VideoItem> =
        items.filter { it.sizeBytes >= thresholdBytes }
            .sortedWith(compareByDescending<VideoItem> { it.sizeBytes }.thenBy { it.path })

    /** Zero-byte entries (junk / corrupt records), by title. */
    fun emptyFiles(items: List<VideoItem>): List<VideoItem> =
        items.filter { it.sizeBytes == 0L }
            .sortedWith(compareBy<VideoItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title })

    /** The subset of [candidateDirs] under which no item lives —
     *  the folders the cleaner offers to remove. The activity only
     *  passes directories it has verified are empty on disk, so
     *  this is a consistency filter, not the emptiness check. */
    fun emptyFolders(candidateDirs: List<String>, items: List<VideoItem>): List<String> =
        candidateDirs.filter { dir ->
            items.none { it.path.startsWith("$dir/") }
        }.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

/** Display formatting — pure Kotlin/JVM (java.text only). */
object LibFmt {

    /** "12:34" under an hour, "1:23:45" at/above an hour.
     *  Negative input clamps to "0:00". */
    fun duration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) "%d:%02d:%02d".format(Locale.US, h, m, s)
        else "%d:%02d".format(Locale.US, m, s)
    }

    /** Resume badge text: "▶ 12:34 left". When nothing (or
     *  everything) remains, returns "▶ 0:00 left". */
    fun remaining(durationMs: Long, posMs: Long): String =
        "▶ " + duration(durationMs - posMs) + " left"

    /** "512 B", "1.5 KB", "23.4 MB", "1.2 GB" (1024-based). */
    fun size(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = -1
        while (v >= 1024.0 && u < units.size - 1) {
            v /= 1024.0
            u++
        }
        return "%.1f %s".format(Locale.US, v, units[u])
    }

    /** "1920 × 1080"; "—" when either dimension is unknown. */
    fun resolution(width: Int, height: Int): String =
        if (width > 0 && height > 0) "$width × $height" else "—"

    /** "21 Nov 2026" from MediaStore DATE_ADDED seconds. */
    fun date(dateAddedSec: Long): String {
        if (dateAddedSec <= 0L) return "—"
        return SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(dateAddedSec * 1000L))
    }

    /** "21 Nov 2026, 14:32" from epoch millis (history rows,
     *  Properties "Last playback time" / "Date"). */
    fun dateTime(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        return SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(epochMs))
    }
}
