package com.ghostblade.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Video clip editor (spec #107) — MX Player has a separate editor
 * surface for trimming a video and saving the selection as a new
 * file. This is ours: framework-only, no re-encoding.
 *
 * Two halves:
 *  - [ClipLogic] — pure, JVM-tested (tests/ClipEditorTest.kt):
 *    range clamping, the container/codec support matrix, output
 *    naming, duration verification and time formatting.
 *  - [ClipTrimmer] — the Android engine: MediaExtractor →
 *    MediaMuxer stream copy, then verify-before-success.
 *
 * Honest limits of a stream copy (the UI states these too):
 *  - Cuts land on keyframes: the saved clip starts at the nearest
 *    keyframe at or before the requested start, so it can begin a
 *    little earlier than the handle position. The end is the last
 *    sample at or before the requested end.
 *  - Only containers Android's MediaMuxer can write are offered
 *    (MP4, WebM). A file whose codecs fit neither is refused with
 *    the reason — we never re-encode silently and never write a
 *    corrupt file.
 *  - Timed-text / subtitle tracks are not copied into the clip.
 */

/* ================= pure logic ================= */

object ClipLogic {

    /** Shortest clip we will save. MX's trimmer also refuses a
     *  zero-length selection; 1 s is our documented floor. */
    const val MIN_CLIP_MS = 1_000L

    /** What the trimmer should write, decided from the source's
     *  track codecs before any byte is copied. */
    enum class Plan { MP4, WEBM, UNSUPPORTED }

    data class PlanDecision(
        val plan: Plan,
        /** Output file extension without the dot ("mp4" / "webm"). */
        val extension: String,
        /** User-visible reason when [plan] is UNSUPPORTED. */
        val reason: String?
    )

    /**
     * Clamp a [startMs, endMs) selection into [0, durationMs] and
     * enforce [MIN_CLIP_MS]: an inverted or too-short range is
     * repaired by moving the end (or, at the tail of the file, the
     * start) — the result always satisfies
     * 0 <= start < end <= duration and end - start >= MIN_CLIP_MS,
     * unless the file itself is shorter than MIN_CLIP_MS, in which
     * case the whole file is the selection.
     */
    fun clampRange(startMs: Long, endMs: Long, durationMs: Long): Pair<Long, Long> {
        val dur = maxOf(durationMs, 0L)
        if (dur <= MIN_CLIP_MS) return 0L to dur
        var s = startMs.coerceIn(0L, dur - MIN_CLIP_MS)
        var e = endMs.coerceIn(s + MIN_CLIP_MS, dur)
        if (e - s < MIN_CLIP_MS) {
            e = minOf(dur, s + MIN_CLIP_MS)
            if (e - s < MIN_CLIP_MS) s = e - MIN_CLIP_MS
        }
        return s to e
    }

    /** Dragging the start handle: it may not cross
     *  end - MIN_CLIP_MS and may not leave [0, duration]. */
    fun moveStart(newStartMs: Long, endMs: Long, durationMs: Long): Long =
        newStartMs.coerceIn(0L, maxOf(0L, minOf(endMs, durationMs) - MIN_CLIP_MS))

    /** Dragging the end handle: it may not cross
     *  start + MIN_CLIP_MS and may not leave [0, duration]. */
    fun moveEnd(startMs: Long, newEndMs: Long, durationMs: Long): Long =
        newEndMs.coerceIn(minOf(durationMs, startMs + MIN_CLIP_MS), maxOf(0L, durationMs))

    /* ---------- container / codec matrix ---------- */

    private val MP4_VIDEO = setOf("video/avc", "video/mp4v-es", "video/3gpp")
    private val MP4_VIDEO_HEVC_MIN_API = 26
    private val MP4_AUDIO = setOf("audio/mp4a-latm")
    private val WEBM_VIDEO = setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9")
    private val WEBM_AUDIO = setOf("audio/vorbis")
    private val WEBM_AUDIO_OPUS_MIN_API = 29

    fun videoFitsMp4(mime: String, apiLevel: Int): Boolean =
        mime in MP4_VIDEO || (mime == "video/hevc" && apiLevel >= MP4_VIDEO_HEVC_MIN_API)

    fun audioFitsMp4(mime: String): Boolean = mime in MP4_AUDIO

    fun videoFitsWebm(mime: String): Boolean = mime in WEBM_VIDEO

    fun audioFitsWebm(mime: String, apiLevel: Int): Boolean =
        mime in WEBM_AUDIO || (mime == "audio/opus" && apiLevel >= WEBM_AUDIO_OPUS_MIN_API)

    /**
     * Decide the output container for a source with [videoMime]
     * (null = no video track) and zero or more [audioMimes].
     * MP4 is preferred when everything fits (widest compatibility);
     * WebM is the fallback for VPx/Vorbis/Opus sources. Anything
     * else is UNSUPPORTED with a reason naming the codec — the
     * caller must refuse, not improvise.
     */
    fun choosePlan(videoMime: String?, audioMimes: List<String>, apiLevel: Int): PlanDecision {
        if (videoMime == null) {
            return PlanDecision(Plan.UNSUPPORTED, "",
                "This file has no video track to clip.")
        }
        val badMp4Audio = audioMimes.firstOrNull { !audioFitsMp4(it) }
        if (videoFitsMp4(videoMime, apiLevel) && badMp4Audio == null) {
            return PlanDecision(Plan.MP4, "mp4", null)
        }
        val badWebmAudio = audioMimes.firstOrNull { !audioFitsWebm(it, apiLevel) }
        if (videoFitsWebm(videoMime) && badWebmAudio == null) {
            return PlanDecision(Plan.WEBM, "webm", null)
        }
        val culprit = when {
            !videoFitsMp4(videoMime, apiLevel) && !videoFitsWebm(videoMime) -> videoMime
            badMp4Audio != null && badWebmAudio != null -> badMp4Audio
            else -> videoMime
        }
        return PlanDecision(Plan.UNSUPPORTED, "",
            "Clipping without re-encoding isn't possible for this file: " +
                "$culprit can't be copied into an MP4 or WebM file. " +
                "This build doesn't re-encode video.")
    }

    /* ---------- output naming ---------- */

    /**
     * Output file name for a clip of [sourceName]: MX-style derived
     * name — "<base>_clip.<ext>". The extension is the OUTPUT
     * container's, which may differ from the source's.
     */
    fun outputFileName(sourceName: String, extension: String): String {
        val base = sourceName.substringBeforeLast('.', sourceName)
            .ifBlank { "video" }
        return "${base}_clip.$extension"
    }

    /** First non-colliding name in [dir]: name, "name (1)", … —
     *  java.io.File only, so this runs on the JVM in tests. */
    fun uniqueFile(dir: File, fileName: String): File {
        val direct = File(dir, fileName)
        if (!direct.exists()) return direct
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /* ---------- verification + formatting ---------- */

    /**
     * A stream copy starts at the keyframe at/before the requested
     * start, so the real duration is expected to be the requested
     * length or a bit LONGER, never much shorter. Accept when the
     * measured duration is within 500 ms under the request and at
     * most max(2 s, 10%) over it.
     */
    fun verifyDurationOk(expectedMs: Long, actualMs: Long): Boolean {
        if (actualMs <= 0L) return false
        val over = maxOf(2_000L, expectedMs / 10)
        return actualMs >= expectedMs - 500L && actualMs <= expectedMs + over
    }

    /** "MM:SS.mmm" (hours shown when >= 1 h: "H:MM:SS.mmm"). */
    fun formatTimeMs(ms: Long): String {
        val t = maxOf(ms, 0L)
        val h = t / 3_600_000L
        val m = (t % 3_600_000L) / 60_000L
        val s = (t % 60_000L) / 1_000L
        val milli = t % 1_000L
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, milli)
        else "%02d:%02d.%03d".format(m, s, milli)
    }
}

/* ================= trim engine (Android) ================= */

/** Where a clip is written: a plain file (app-writable folder) or a
 *  MediaStore entry (shared storage on API 29+). */
sealed class ClipTarget {
    abstract val displayName: String
    data class FileTarget(val file: File) : ClipTarget() {
        override val displayName: String get() = file.absolutePath
    }
    data class UriTarget(val uri: Uri, override val displayName: String) : ClipTarget()
}

data class ClipProbe(
    val durationMs: Long,
    val videoMime: String?,
    val audioMimes: List<String>,
    val rotationDeg: Int,
    val decision: ClipLogic.PlanDecision
)

data class TrimResult(
    val success: Boolean,
    val message: String,
    /** Where the verified clip lives on success. */
    val target: ClipTarget?
)

object ClipTrimmer {

    /** Read the source's tracks and decide the plan. Blocking —
     *  call off the UI thread. */
    fun probe(path: String, apiLevel: Int): ClipProbe {
        val extractor = MediaExtractor()
        var durationMs = 0L
        var videoMime: String? = null
        val audioMimes = mutableListOf<String>()
        try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = maxOf(durationMs,
                        format.getLong(MediaFormat.KEY_DURATION) / 1_000L)
                }
                when {
                    mime.startsWith("video/") -> if (videoMime == null) videoMime = mime
                    mime.startsWith("audio/") -> audioMimes.add(mime)
                }
            }
        } finally {
            extractor.release()
        }
        if (durationMs <= 0L) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (t: Throwable) {
                /* duration stays 0: the UI refuses to edit */
            } finally {
                retriever.release()
            }
        }
        return ClipProbe(durationMs, videoMime, audioMimes, rotationOf(path),
            ClipLogic.choosePlan(videoMime, audioMimes, apiLevel))
    }

    private fun rotationOf(path: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * Stream-copy [startMs, endMs) of [srcPath] into [target].
     * Blocking — call off the UI thread. [onProgress] receives
     * 0f..1f on the calling thread. The output is verified before
     * success is reported; on any failure the partial output is
     * deleted and the result says why.
     */
    fun trim(ctx: Context, srcPath: String, startMs: Long, endMs: Long,
             decision: ClipLogic.PlanDecision, target: ClipTarget,
             onProgress: (Float) -> Unit): TrimResult {
        val format = when (decision.plan) {
            ClipLogic.Plan.MP4 -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ClipLogic.Plan.WEBM -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            ClipLogic.Plan.UNSUPPORTED ->
                return TrimResult(false, decision.reason ?: "Unsupported file.", null)
        }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var outFd: android.os.ParcelFileDescriptor? = null
        try {
            extractor.setDataSource(srcPath)
            muxer = when (target) {
                is ClipTarget.FileTarget -> MediaMuxer(target.file.absolutePath, format)
                is ClipTarget.UriTarget -> {
                    // Held in outFd until the finally block: the
                    // muxer writes through this descriptor.
                    outFd = ctx.contentResolver.openFileDescriptor(target.uri, "w")
                        ?: return fail(ctx, target, "Couldn't open the output file.")
                    MediaMuxer(outFd.fileDescriptor, format)
                }
            }
            val rotation = rotationOf(srcPath)
            if (rotation != 0) muxer.setOrientationHint(rotation)

            // Map extractor tracks -> muxer tracks (video + audio
            // only; timed-text/subtitle tracks are not copied).
            val trackMap = HashMap<Int, Int>()
            var maxInputSize = 0
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                val muxerIndex = muxer.addTrack(trackFormat)
                trackMap[i] = muxerIndex
                extractor.selectTrack(i)
                if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize,
                        trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (trackMap.isEmpty()) {
                return fail(ctx, target, "No video or audio track could be copied.")
            }
            if (maxInputSize <= 0) maxInputSize = 1_048_576

            val startUs = startMs * 1_000L
            val endUs = endMs * 1_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = android.media.MediaCodec.BufferInfo()
            muxer.start()
            var baseUs = -1L
            var lastProgress = -1f
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break
                if (baseUs < 0L) baseUs = sampleTimeUs
                val muxerTrack = trackMap[extractor.sampleTrackIndex]
                if (muxerTrack == null) {
                    // A track we chose not to copy (shouldn't happen:
                    // unselected tracks yield no samples) — skip it.
                    extractor.advance()
                    continue
                }
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = maxOf(0L, sampleTimeUs - baseUs)
                info.flags = extractor.sampleFlags and
                    (android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME or
                        android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                muxer.writeSampleData(muxerTrack, buffer, info)
                val progress = ((sampleTimeUs - startUs).toFloat() /
                    (endUs - startUs).toFloat()).coerceIn(0f, 1f)
                if (progress - lastProgress >= 0.02f) {
                    lastProgress = progress
                    onProgress(progress)
                }
                extractor.advance()
            }
            muxer.stop()
            onProgress(1f)
        } catch (t: Throwable) {
            return fail(ctx, target,
                "Clipping failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            try { muxer?.release() } catch (t: Throwable) { /* already stopped */ }
            try { outFd?.close() } catch (t: Throwable) { /* nothing to do */ }
            extractor.release()
        }

        // Verify before success: the file must exist, be non-empty
        // and measure within tolerance of the requested range.
        val actualMs = durationOf(ctx, target)
        val expectedMs = endMs - startMs
        if (actualMs <= 0L || !ClipLogic.verifyDurationOk(expectedMs, actualMs)) {
            return fail(ctx, target,
                "The saved clip didn't verify (expected about " +
                    "${ClipLogic.formatTimeMs(expectedMs)}, got " +
                    "${ClipLogic.formatTimeMs(actualMs)}). Nothing was saved.")
        }
        return TrimResult(true, "Clip saved.", target)
    }

    private fun durationOf(ctx: Context, target: ClipTarget): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            when (target) {
                is ClipTarget.FileTarget -> {
                    if (!target.file.exists() || target.file.length() <= 0L) return 0L
                    retriever.setDataSource(target.file.absolutePath)
                }
                is ClipTarget.UriTarget -> retriever.setDataSource(ctx, target.uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            retriever.release()
        }
    }

    /** Delete a (partial) output and report failure. */
    private fun fail(ctx: Context, target: ClipTarget, message: String): TrimResult {
        deleteTarget(ctx, target)
        return TrimResult(false, message, null)
    }

    fun deleteTarget(ctx: Context, target: ClipTarget) {
        try {
            when (target) {
                is ClipTarget.FileTarget -> target.file.delete()
                is ClipTarget.UriTarget -> ctx.contentResolver.delete(target.uri, null, null)
            }
        } catch (t: Throwable) {
            /* best effort: a leftover partial is still better than a crash */
        }
    }

}
