package com.ghostblade.player

/**
 * Pure-Kotlin SRT subtitle support for GhostBlade Player.
 *
 * No Android dependencies — this file is JVM-testable (see tests/SubtitleTest.kt).
 *
 * Contract (SPEC.md):
 *  - data class Cue(startMs, endMs, text)
 *  - SrtParser.parse(text): List<Cue>
 *  - SrtParser.cueAt(cues, posMs, offsetMs): String?
 *
 * Delay semantics for cueAt: the lookup time is (posMs + offsetMs). A POSITIVE
 * offset therefore makes a cue appear EARLIER (subtitles shifted forward);
 * a NEGATIVE offset makes cues appear later. The player UI labels this
 * "subtitle offset" (with "cues earlier / cues later" wording) and steps it
 * in 0.5s increments within -5s..+5s.
 */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * SubView geometry truth for the TEXT path, extracted from MX
 * 2.12.4's decoded values (mxplayer-analysis/decoded
 * RESOURCE-TRUTH.md) — for the Player Team, which owns the view
 * that consumes these (PlayerActivity's subtitleView):
 *
 *  - default bottom padding: `default_subtitle_bottom_padding_dp`
 *    = 8 (dimens.xml, verbatim integer)
 *  - default subtitle background: `default_subtitle_bkcolor`
 *    = #88000000
 *  - subtitle shadow colour (Exo sticker set MX ships):
 *    `exo_subtitle_shadow_color` = #A6000000
 *  - our text-size default stays 18sp (Store.subtitleTextSizeSp);
 *    no decoded MX dimen pins the SubView text size — MX scales it
 *    from the tuner PercentPref (100% default), so 18sp is ours,
 *    stated as ours.
 *  - STROKE: MX's SubView is a StrokeView subclass, but NO stroke
 *    width/colour constant for it exists in the decoded
 *    dimens/colors — not established, do not invent one. The
 *    player's current setShadowLayer radii (3.5f improved /
 *    1.5f border / 2f shadow, black) remain our approximation
 *    until a decompile round recovers StrokeView's defaults.
 */
object SubtitleStyleTruth {
    const val DEFAULT_BOTTOM_PADDING_DP = 8
    const val DEFAULT_BG_COLOR = 0x88000000.toInt()
    const val SHADOW_COLOR = 0xA6000000.toInt()
    const val DEFAULT_TEXT_SIZE_SP = 18f
}

object SrtParser {

    private val TIMING_SPLIT = Regex("""\s*-->\s*""")
    private val TAG = Regex("""<[^>]*>""")

    /**
     * Parse SRT text into cues. Tolerant by design:
     *  - accepts LF / CRLF / CR line endings and a leading UTF-8 BOM
     *  - blocks are separated by blank lines (a line that is empty after
     *    trimming — a separator line containing only spaces still separates);
     *    a block without a valid timing line is skipped (malformed input
     *    never aborts the whole parse)
     *  - the numeric index line is optional
     *  - timestamps accept ',' or '.' before milliseconds, the compact
     *    no-space form "00:00:01,000-->00:00:02,000", and trailing
     *    positioning settings after the end time are ignored
     *  - basic inline tags (<i>, <b>, <font ...>) are stripped from cue text
     * Cues are returned in file order (not re-sorted).
     */
    fun parse(text: String): List<Cue> {
        if (text.isBlank()) return emptyList()
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = ArrayList<Cue>()
        for (blockLines in splitBlocks(clean)) {
            val lines = blockLines.map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            // Find the timing line: first line containing "-->". Lines before it
            // (usually the numeric index) are ignored.
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue
            val cue = parseTimingLine(lines[timingIdx]) ?: continue
            val body = lines.drop(timingIdx + 1).joinToString("\n") { stripTags(it) }.trim()
            if (body.isEmpty()) continue
            cues.add(Cue(cue.first, cue.second, body))
        }
        return cues
    }

    /**
     * Group lines into blocks: any line that is empty after trimming is a
     * block separator (so a separator line containing only spaces or tabs
     * still separates two cues instead of merging them).
     */
    private fun splitBlocks(clean: String): List<List<String>> {
        val blocks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in clean.split('\n')) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks.add(current)
                    current = ArrayList()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) blocks.add(current)
        return blocks
    }

    /** Returns (startMs, endMs) or null when the line is not a valid timing line. */
    private fun parseTimingLine(line: String): Pair<Long, Long>? {
        val parts = TIMING_SPLIT.split(line.trim())
        if (parts.size < 2) return null
        val start = parseTimestamp(parts[0].trim()) ?: return null
        // End token may be followed by positioning settings ("X1:.. X2:..") — take first token.
        val endToken = parts[1].trim().split(Regex("""\s+""")).firstOrNull() ?: return null
        val end = parseTimestamp(endToken) ?: return null
        if (end < start) return null
        return start to end
    }

    /**
     * Parse "HH:MM:SS,mmm" (also "MM:SS,mmm" and '.' as the fraction separator).
     * Fraction is right-padded / truncated to exactly 3 digits.
     * Returns null on any malformed component, including values so large the
     * millisecond result would overflow Long.
     */
    fun parseTimestamp(ts: String): Long? {
        val main = ts.trim()
        if (main.isEmpty()) return null
        val fracSplit = main.split(',', '.', limit = 2)
        val hms = fracSplit[0].split(':')
        if (hms.size < 2 || hms.size > 3) return null
        val nums = hms.map { it.trim().toLongOrNull() ?: return null }
        val (h, m, s) = when (nums.size) {
            3 -> Triple(nums[0], nums[1], nums[2])
            else -> Triple(0L, nums[0], nums[1])
        }
        if (m !in 0..59 || s !in 0..59 || h < 0) return null
        var ms = 0L
        if (fracSplit.size == 2) {
            val frac = fracSplit[1].trim()
            if (frac.isEmpty() || !frac.all { it.isDigit() }) return null
            ms = (frac + "000").substring(0, 3).toLong()
        }
        return try {
            val totalSec = Math.addExact(
                Math.multiplyExact(Math.addExact(Math.multiplyExact(h, 60L), m), 60L), s
            )
            Math.addExact(Math.multiplyExact(totalSec, 1000L), ms)
        } catch (e: ArithmeticException) {
            null
        }
    }

    private fun stripTags(line: String): String = TAG.replace(line, "")

    /**
     * Text of the cue visible at playback position [posMs] with delay [offsetMs]
     * applied (lookup time = posMs + offsetMs). A cue is visible when
     * startMs <= lookup < endMs. Returns the first matching cue, or null.
     */
    fun cueAt(cues: List<Cue>, posMs: Long, offsetMs: Long): String? {
        val t = posMs + offsetMs
        if (t < 0) return null
        for (cue in cues) {
            if (t >= cue.startMs && t < cue.endMs) return cue.text
        }
        return null
    }
}
