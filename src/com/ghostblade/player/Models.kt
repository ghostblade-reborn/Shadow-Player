package com.ghostblade.player

/** One video from the device MediaStore. The first nine fields are
 *  the v0.1 SPEC contract shared by Shadow One (library) and Shadow
 *  Two (player) — unchanged. v0.2 appends defaulted fields, so all
 *  existing constructions keep compiling.
 *
 *  [folder] is the display name of the containing folder
 *  (MediaStore BUCKET_DISPLAY_NAME, e.g. "Movies", "Camera").
 *  [dateAddedSec] is seconds since epoch (MediaStore DATE_ADDED).
 *  [width]/[height] are pixels; 0 when the store has no value.
 *  [dateModifiedSec] is MediaStore DATE_MODIFIED seconds (drives
 *  the "NEW" tag). [frameRate] is frames/second when known
 *  (enriched lazily; 0 = unknown). [mimeType] may be blank. */
data class VideoItem(
    val id: Long,
    val title: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val dateModifiedSec: Long = 0L,
    val frameRate: Float = 0f,
    val mimeType: String = ""
)

/** One audio track from MediaStore (music library surface). */
data class AudioItem(
    val id: Long,
    val title: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val artist: String,
    val album: String,
    val folder: String,
    val dateAddedSec: Long
)

/** One row of playback history (§2.9 "Playback history" group):
 *  resume position, last playback time, finished state. */
data class HistoryRecord(
    val path: String,
    val lastPosMs: Long,
    val lastPlayedAtMs: Long,
    val finished: Boolean
)

/** A file moved into the private folder. The bytes live in the
 *  app-private `private/` dir under [storedName]; [origPath] is
 *  where "Unlock" moves it back to. */
data class PrivateEntry(
    val origPath: String,
    val storedName: String,
    val title: String,
    val sizeBytes: Long,
    val addedAtMs: Long
)

/** A file in the recycle bin. The bytes live in the app-private
 *  `recycle/` dir under [storedName]; [origPath] is the restore
 *  target. */
data class RecycleEntry(
    val id: String,
    val origPath: String,
    val storedName: String,
    val title: String,
    val sizeBytes: Long,
    val deletedAtMs: Long
)
