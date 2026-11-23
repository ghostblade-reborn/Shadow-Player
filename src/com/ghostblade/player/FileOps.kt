package com.ghostblade.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import java.io.File
import java.security.MessageDigest

/** File operations for the library (v0.2): rename, recycle bin,
 *  private folder, hide helpers, Properties data.
 *
 *  The name/validation/bin-record helpers are pure Kotlin and are
 *  JVM-tested; the `move*`/`restore*` functions are the Android
 *  boundary (they touch the file system + [Store] + MediaScanner).
 *
 *  Recycle-bin and private files live in app-private dirs
 *  (`filesDir/recycle`, `filesDir/private`) under a stored name;
 *  [Store] keeps the records (original path, title, size, time).
 *  Moving across volumes is copy + delete; within a volume it is a
 *  rename when possible. A failed move leaves the original in
 *  place and returns null — never a half state. */
object FileOps {

    val SUBTITLE_EXTENSIONS = setOf(
        "srt", "ass", "ssa", "smi", "sub", "txt", "vtt", "mpl", "pjs"
    )

    /* ---------- pure helpers (JVM-tested) ---------- */

    /** Validates a rename target. Returns an error message, or null
     *  when [newName] is acceptable. MX edits the full file name;
     *  path separators and blank names are rejected. */
    fun renameError(newName: String): String? {
        val n = newName.trim()
        return when {
            n.isEmpty() -> "Name is empty"
            n.contains('/') || n.contains('\\') -> "Name must not contain path separators"
            n == "." || n == ".." -> "Invalid name"
            else -> null
        }
    }

    /** The name a rename actually lands on: when the user dropped
     *  the extension, the original one is kept (MX behaviour). */
    fun resolvedRename(currentName: String, newName: String): String {
        val n = newName.trim()
        val curExt = currentName.substringAfterLast('.', "")
        val newExt = n.substringAfterLast('.', "")
        return if (curExt.isNotEmpty() && newExt.isEmpty() && !n.endsWith('.')) {
            "$n.$curExt"
        } else n
    }

    /** Subtitle sidecars of a video among [fileNames] (one directory
     *  listing): same base name + a subtitle extension. */
    fun sidecarSubtitles(fileNames: List<String>, videoFileName: String): List<String> {
        val base = videoFileName.substringBeforeLast('.', videoFileName)
        return fileNames.filter { name ->
            name != videoFileName &&
                name.substringBeforeLast('.', name).equals(base, ignoreCase = true) &&
                SUBTITLE_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())
        }
    }

    /** "a.mp4" -> "a (1).mp4" -> "a (2).mp4" until [name] is free
     *  in [existing]. */
    fun uniqueName(existing: Set<String>, name: String): String {
        if (!existing.contains(name)) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isEmpty() || ext == name) "$base ($i)" else "$base ($i).$ext"
            if (!existing.contains(candidate)) return candidate
            i++
        }
    }

    /** SHA-256 hex of a private-folder PIN. Pure (java.security). */
    fun pinHash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("ghostblade-private:$pin").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Stored file name inside the recycle/private dirs: unique,
     *  filesystem-safe, and still recognisable. */
    fun storedName(id: String, fileName: String): String {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${id}_$safe"
    }

    /* ---------- Android file operations ---------- */

    fun recycleDir(ctx: Context): File = File(ctx.filesDir, "recycle").apply { mkdirs() }
    fun privateDir(ctx: Context): File = File(ctx.filesDir, "private").apply { mkdirs() }

    /** Copies/moves [src] to [dst]; true on success. */
    private fun moveFile(src: File, dst: File): Boolean {
        if (!src.exists()) return false
        if (src.renameTo(dst)) return true
        return try {
            val expected = src.length()
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            if (dst.exists() && dst.length() == expected) {
                src.delete()
                true
            } else {
                dst.delete()
                false
            }
        } catch (t: Throwable) {
            dst.delete()
            false
        }
    }

    /** Moves a video into the recycle bin and records it in
     *  [Store]. When [deleteSubtitles] (General setting), its
     *  sidecar subtitles are deleted with it. Returns the entry,
     *  or null when the move failed (original untouched). */
    fun moveToRecycle(ctx: Context, video: VideoItem, deleteSubtitles: Boolean): RecycleEntry? {
        val src = File(video.path)
        if (!src.exists()) return null
        val id = System.currentTimeMillis().toString() + "-" + video.id
        val stored = storedName(id, src.name)
        val dst = File(recycleDir(ctx), stored)
        if (!moveFile(src, dst)) return null
        if (deleteSubtitles) {
            val names = src.parentFile?.list()?.toList() ?: emptyList()
            for (sub in sidecarSubtitles(names, src.name)) {
                File(src.parentFile, sub).delete()
            }
        }
        val entry = RecycleEntry(id, video.path, stored, video.title, video.sizeBytes,
            System.currentTimeMillis())
        Store.addRecycleEntry(entry)
        scanPaths(ctx, listOf(video.path))
        return entry
    }

    /** Restores a recycle entry to its original folder (unique name
     *  on collision). Returns the restored path, or null on
     *  failure (entry + file kept). */
    fun restoreFromRecycle(ctx: Context, entry: RecycleEntry): String? {
        val stored = File(recycleDir(ctx), entry.storedName)
        if (!stored.exists()) return null
        val dir = File(entry.origPath).parentFile ?: return null
        dir.mkdirs()
        val existing = dir.list()?.toSet() ?: emptySet()
        val targetName = uniqueName(existing, File(entry.origPath).name)
        val target = File(dir, targetName)
        if (!moveFile(stored, target)) return null
        Store.removeRecycleEntry(entry.id)
        scanPaths(ctx, listOf(target.absolutePath))
        return target.absolutePath
    }

    /** Permanently deletes a recycle entry's file + record. */
    fun deleteRecycleEntry(ctx: Context, entry: RecycleEntry) {
        File(recycleDir(ctx), entry.storedName).delete()
        Store.removeRecycleEntry(entry.id)
    }

    /** Permanently deletes every recycle entry. */
    fun emptyRecycle(ctx: Context) {
        for (entry in Store.recycleEntries()) {
            File(recycleDir(ctx), entry.storedName).delete()
        }
        Store.clearRecycleEntries()
    }

    /** Moves a video into the private folder (app-private dir) and
     *  records it. Returns the entry, or null on failure. */
    fun moveToPrivate(ctx: Context, video: VideoItem): PrivateEntry? {
        val src = File(video.path)
        if (!src.exists()) return null
        val id = System.currentTimeMillis().toString() + "-" + video.id
        val stored = storedName(id, src.name)
        val dst = File(privateDir(ctx), stored)
        if (!moveFile(src, dst)) return null
        val entry = PrivateEntry(video.path, stored, video.title, video.sizeBytes,
            System.currentTimeMillis())
        Store.addPrivateEntry(entry)
        scanPaths(ctx, listOf(video.path))
        return entry
    }

    /** "Unlock": moves a private entry back to its original folder
     *  (unique name on collision). Returns the new path or null. */
    fun moveOutOfPrivate(ctx: Context, entry: PrivateEntry): String? {
        val stored = File(privateDir(ctx), entry.storedName)
        if (!stored.exists()) return null
        val dir = File(entry.origPath).parentFile ?: return null
        dir.mkdirs()
        val existing = dir.list()?.toSet() ?: emptySet()
        val targetName = uniqueName(existing, File(entry.origPath).name)
        val target = File(dir, targetName)
        if (!moveFile(stored, target)) return null
        Store.removePrivateEntry(entry.storedName)
        scanPaths(ctx, listOf(target.absolutePath))
        return target.absolutePath
    }

    /** Permanently deletes a private entry's file + record. */
    fun deletePrivateEntry(ctx: Context, entry: PrivateEntry) {
        File(privateDir(ctx), entry.storedName).delete()
        Store.removePrivateEntry(entry.storedName)
    }

    /** Renames a video file in place (and its sidecar subtitles to
     *  the new base name). Returns the new path, or null on
     *  failure / name clash. */
    fun renameVideo(ctx: Context, video: VideoItem, newName: String): String? {
        val src = File(video.path)
        if (!src.exists()) return null
        val target = File(src.parentFile, resolvedRename(src.name, newName))
        if (target.exists()) return null
        if (!src.renameTo(target)) return null
        // Rename sidecars so subtitles keep following the video.
        val names = src.parentFile?.list()?.toList() ?: emptyList()
        val newBase = target.name.substringBeforeLast('.', target.name)
        for (sub in sidecarSubtitles(names, target.name)) {
            val subFile = File(src.parentFile, sub)
            val ext = sub.substringAfterLast('.', "")
            subFile.renameTo(File(src.parentFile, if (ext.isEmpty()) newBase else "$newBase.$ext"))
        }
        scanPaths(ctx, listOf(video.path, target.absolutePath))
        return target.absolutePath
    }

    /** Asks MediaStore to rescan [paths] (after rename/delete/move)
     *  so other apps — and our next scan — see the truth. */
    fun scanPaths(ctx: Context, paths: List<String>) {
        try {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        } catch (t: Throwable) {
            /* scanner unavailable: next full scan still works */
        }
    }

    /* ---------- Properties (§2.9 field groups) ---------- */

    /** Builds the Properties groups for [video]: "File", "Media",
     *  "Playback history" — MX's field order, "—" for values the
     *  device cannot provide. Retriever work happens here, so call
     *  it off the UI thread. */
    fun propertiesGroups(ctx: Context, video: VideoItem): List<Pair<String, List<Pair<String, String>>>> {
        var format = video.path.substringAfterLast('.', "").uppercase()
        var frameRate = video.frameRate
        var bitrate = 0L
        var rotation = 0
        var hasEmbeddedSubtitle = false
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(video.path)
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (!mime.isNullOrBlank()) format = mime
            if (frameRate <= 0f) {
                frameRate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull() ?: 0f
            }
            bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            r.release()
        } catch (t: Throwable) {
            /* retriever failed: fall back to MediaStore values */
        }
        val dir = File(video.path).parentFile
        val sidecars = sidecarSubtitles(dir?.list()?.toList() ?: emptyList(), File(video.path).name)
        val subtitleValue = when {
            sidecars.isNotEmpty() -> sidecars.joinToString(", ")
            hasEmbeddedSubtitle -> "Embedded subtitle"
            else -> "None"
        }
        val fileGroup = listOf(
            "File" to File(video.path).name,
            "Location" to video.folder,
            "Path" to video.path,
            "Size" to LibFmt.size(video.sizeBytes),
            "Date" to LibFmt.date(video.dateAddedSec),
            "Format" to format,
            "MIME type" to video.mimeType.ifBlank { "—" }
        )
        val mediaGroup = listOf(
            "Title" to video.title,
            "Length" to LibFmt.duration(video.durationMs),
            "Resolution" to LibFmt.resolution(video.width, video.height),
            "Frame rate" to if (frameRate > 0f) "%.2f fps".format(java.util.Locale.US, frameRate) else "—",
            "Bitrate" to if (bitrate > 0L) "${bitrate / 1000} kbps" else "—",
            "Rotation" to if (rotation != 0) "$rotation°" else "—",
            "Subtitle" to subtitleValue
        )
        val pos = Store.resumePos(video.path)
        val historyGroup = listOf(
            "Resume" to if (pos > 0L) LibFmt.duration(pos) else "—",
            "Last position" to if (pos > 0L) LibFmt.duration(pos) else "—",
            "Last playback time" to LibFmt.dateTime(Store.lastPlayedAt(video.path)),
            "State" to if (Store.isFinished(video.path)) "Finished" else "Not finished"
        )
        return listOf(
            "File" to fileGroup,
            "Media" to mediaGroup,
            "Playback history" to historyGroup
        )
    }
}
