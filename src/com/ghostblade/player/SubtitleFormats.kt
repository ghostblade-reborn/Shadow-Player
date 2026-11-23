package com.ghostblade.player

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Multi-format sidecar subtitle support for GhostBlade Player v0.2.
 *
 * MX Player accepts 8 sidecar subtitle formats; this file parses all of
 * them to the shared [Cue] model (see Subtitle.kt) with PLAIN TEXT output:
 * every styling / override tag is stripped, ASS `\N` becomes a newline and
 * `\h` becomes a space, MicroDVD / MPL2 `|` becomes a newline, SubViewer
 * `[br]` becomes a newline.
 *
 * Pure Kotlin / JVM — no Android dependencies — so it is unit-testable
 * (see tests/SubtitleFormatsTest.kt). Every parser is tolerant by design:
 * malformed input never throws; bad entries are skipped.
 */
enum class SubtitleFormat { SRT, ASS, SAMI, VTT, MICRODVD, MPL2, PJS, POWERDIVX, SUBVIEWER, UNKNOWN }

object SubtitleFormats {

    // ------------------------------------------------------------------
    // Encodings
    // ------------------------------------------------------------------

    /**
     * Charset names for the player's encoding chooser: "Auto" first, then
     * the common set, filtered to charsets the runtime actually supports.
     */
    val ENCODINGS: List<String> = run {
        val candidates = ArrayList<String>()
        candidates.add("UTF-8")
        candidates.add("UTF-16")
        for (i in 1250..1258) candidates.add("windows-$i")
        for (i in 1..16) candidates.add("ISO-8859-$i")
        candidates.add("Shift_JIS")
        candidates.add("EUC-JP")
        candidates.add("ISO-2022-JP")
        candidates.add("EUC-KR")
        candidates.add("GBK")
        candidates.add("GB2312")
        candidates.add("Big5")
        candidates.add("windows-874")
        candidates.add("KOI8-R")
        val supported = LinkedHashSet<String>()
        for (name in candidates) {
            val ok = try {
                Charset.isSupported(name)
            } catch (e: Exception) {
                false
            }
            if (ok) supported.add(name)
        }
        val out = ArrayList<String>()
        out.add("Auto")
        out.addAll(supported)
        out
    }

    /**
     * Decode raw subtitle bytes.
     *
     * [charsetName] null or "Auto": BOM sniff (UTF-8 / UTF-16LE / UTF-16BE),
     * otherwise a strict UTF-8 attempt with an ISO-8859-1 fallback when the
     * bytes are not valid UTF-8. A named charset is used directly, falling
     * back to UTF-8 when the name is unknown / unsupported.
     */
    fun decode(bytes: ByteArray, charsetName: String?): String {
        if (charsetName == null || charsetName.equals("Auto", ignoreCase = true)) {
            // BOM sniffing.
            if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            ) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
                    .removePrefix("\uFEFF")
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
                    .removePrefix("\uFEFF")
            }
            // Strict UTF-8 attempt; fall back to ISO-8859-1 (which maps every
            // byte) when the sequence is broken.
            return try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (e: CharacterCodingException) {
                String(bytes, Charsets.ISO_8859_1)
            } catch (e: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
        return try {
            String(bytes, Charset.forName(charsetName)).removePrefix("\uFEFF")
        } catch (e: Exception) {
            try {
                String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            } catch (e2: Exception) {
                ""
            }
        }
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    private val MICRODVD_LINE = Regex("""^\{\d+\}\{\d+\}""")
    private val POWERDIVX_LINE = Regex("""^\{\d{1,2}:\d{2}:\d{2}\}""")
    private val MPL2_LINE = Regex("""^\[\d+\]\[\d+\]""")
    private val PJS_LINE = Regex("""^\d+\s*,\s*\d+\s*,\s*"""")
    private val SUBVIEWER_TIMING = Regex(
        """^\d{1,2}:\d{2}:\d{2}[.,]\d{1,3},\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}$"""
    )

    /** Content-only sniffing; UNKNOWN when nothing recognisable is found. */
    private fun sniff(text: String): SubtitleFormat {
        val clean = text.removePrefix("\uFEFF")
        val trimmed = clean.trimStart()
        if (trimmed.startsWith("WEBVTT")) return SubtitleFormat.VTT
        if (clean.contains("[Script Info]", ignoreCase = true) ||
            clean.contains("[Events]", ignoreCase = true) ||
            clean.contains("\nDialogue:", ignoreCase = true) ||
            clean.startsWith("Dialogue:", ignoreCase = true)
        ) return SubtitleFormat.ASS
        if (clean.contains("<SAMI", ignoreCase = true) ||
            clean.contains("<SYNC", ignoreCase = true)
        ) return SubtitleFormat.SAMI
        if (clean.contains("[INFORMATION]", ignoreCase = true)) return SubtitleFormat.SUBVIEWER

        val lines = clean.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Line-pattern formats: look at every line (headers may precede cues).
        for (line in lines) {
            if (MICRODVD_LINE.containsMatchIn(line)) return SubtitleFormat.MICRODVD
            if (POWERDIVX_LINE.containsMatchIn(line)) return SubtitleFormat.POWERDIVX
            if (MPL2_LINE.containsMatchIn(line)) return SubtitleFormat.MPL2
            if (PJS_LINE.containsMatchIn(line)) return SubtitleFormat.PJS
            if (SUBVIEWER_TIMING.matches(line)) return SubtitleFormat.SUBVIEWER
        }
        if (clean.contains("-->")) return SubtitleFormat.SRT
        return SubtitleFormat.UNKNOWN
    }

    /**
     * Detect the format of a subtitle file: by file extension first, then
     * by content sniffing. `.sub` is ambiguous (MicroDVD vs SubViewer) and
     * `.txt` is generic, so both defer to the content sniff.
     */
    fun detect(fileName: String?, text: String): SubtitleFormat {
        val ext = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && fileName.contains('.') }
        when (ext) {
            "srt" -> return SubtitleFormat.SRT
            "ass", "ssa" -> return SubtitleFormat.ASS
            "smi", "sami" -> return SubtitleFormat.SAMI
            "vtt" -> return SubtitleFormat.VTT
            "mpl" -> return SubtitleFormat.MPL2
            "psb" -> return SubtitleFormat.POWERDIVX
            "pjs" -> return SubtitleFormat.PJS
            "sub" -> {
                val sniffed = sniff(text)
                return if (sniffed != SubtitleFormat.UNKNOWN) sniffed else SubtitleFormat.MICRODVD
            }
            "txt" -> {
                val sniffed = sniff(text)
                return if (sniffed != SubtitleFormat.UNKNOWN) sniffed else SubtitleFormat.MPL2
            }
        }
        return sniff(text)
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Parse [text] as [format]. [fps] is used ONLY by the frame-based
     * formats (MicroDVD, PJS). Never throws; UNKNOWN yields an empty list.
     */
    fun parse(format: SubtitleFormat, text: String, fps: Double = 23.976): List<Cue> {
        return try {
            when (format) {
                SubtitleFormat.SRT -> SrtParser.parse(text)
                SubtitleFormat.ASS -> parseAss(text)
                SubtitleFormat.SAMI -> parseSami(text)
                SubtitleFormat.VTT -> parseVtt(text)
                SubtitleFormat.MICRODVD -> parseMicroDvd(text, fps)
                SubtitleFormat.MPL2 -> parseMpl2(text)
                SubtitleFormat.PJS -> parsePjs(text, fps)
                SubtitleFormat.POWERDIVX -> parsePowerDivX(text)
                SubtitleFormat.SUBVIEWER -> parseSubViewer(text)
                SubtitleFormat.UNKNOWN -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Detect (extension + content) then parse. */
    fun parseAuto(fileName: String?, text: String, fps: Double = 23.976): List<Cue> {
        return parse(detect(fileName, text), text, fps)
    }

    // ------------------------------------------------------------------
    // LRC (music lyrics sidecars — NOT a video subtitle format; MX's
    // music section shows lyrics for local files, and .lrc is the
    // local sidecar form. Kept out of [SubtitleFormat] so the video
    // detection matrix is unchanged.)
    // ------------------------------------------------------------------

    private val LRC_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val LRC_META = Regex("""^\[(ti|ar|al|by|offset|re|ve):""", RegexOption.IGNORE_CASE)

    /**
     * Parse an .lrc lyrics file into timed [Cue]s. Tolerant:
     * metadata tags ([ti:]/[ar:]/…) are skipped, [offset:±ms] is
     * applied, a line with several time tags yields one cue per
     * tag, and each cue ends where the next begins (last cue +4 s).
     * Never throws; no timed lines → empty list.
     */
    fun parseLrc(text: String): List<Cue> {
        val clean = normalize(text)
        var offsetMs = 0L
        val raw = ArrayList<Pair<Long, String>>()
        for (line in clean.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[offset:", ignoreCase = true)) {
                offsetMs = trimmed.substringAfter(':').substringBefore(']')
                    .trim().toLongOrNull() ?: 0L
                continue
            }
            if (LRC_META.containsMatchIn(trimmed)) continue
            val tags = LRC_TAG.findAll(trimmed).toList()
            if (tags.isEmpty()) continue
            val body = trimmed.substring(tags.last().range.last + 1).trim()
            if (body.isEmpty()) continue
            for (tag in tags) {
                val min = tag.groupValues[1].toLongOrNull() ?: continue
                val sec = tag.groupValues[2].toLongOrNull() ?: continue
                val frac = tag.groupValues[3]
                val fracMs = if (frac.isEmpty()) 0L
                    else (frac + "000").substring(0, 3).toLong()
                raw.add((min * 60_000L + sec * 1_000L + fracMs + offsetMs) to body)
            }
        }
        raw.sortBy { it.first }
        return raw.mapIndexed { i, (start, body) ->
            val end = if (i + 1 < raw.size) raw[i + 1].first else start + 4_000L
            Cue(maxOf(start, 0L), maxOf(end, start + 1L), body)
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private val TAG = Regex("""<[^>]*>""")
    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    private fun normalize(text: String): String =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

    private fun stripTags(s: String): String = TAG.replace(s, "")

    /**
     * Parse "H:MM:SS.cc" / "HH:MM:SS.mmm" / "MM:SS.mmm" style times.
     * The fraction is right-padded / truncated to 3 digits, so centiseconds
     * (".50") and milliseconds (".500") both work. Null when malformed.
     */
    private fun parseColonTime(raw: String): Long? {
        val main = raw.trim()
        if (main.isEmpty()) return null
        val fracSplit = main.split('.', ',', limit = 2)
        val hms = fracSplit[0].split(':').map { it.trim() }
        if (hms.size < 2 || hms.size > 3) return null
        val nums = hms.map { it.toLongOrNull() ?: return null }
        val h: Long
        val m: Long
        val s: Long
        if (nums.size == 3) {
            h = nums[0]; m = nums[1]; s = nums[2]
            if (m !in 0..59 || s !in 0..59 || h < 0) return null
        } else {
            h = 0L; m = nums[0]; s = nums[1]
            if (m < 0 || s !in 0..59) return null
        }
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

    private fun framesToMs(frame: Long, fps: Double): Long {
        val safeFps = if (fps > 0.0) fps else 23.976
        return Math.round(frame * 1000.0 / safeFps)
    }

    /** Decode the common HTML entities used in SAMI / VTT captions. */
    private fun decodeEntities(s: String): String {
        var out = s
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { m ->
            try {
                m.groupValues[1].toInt(16).toChar().toString()
            } catch (e: Exception) {
                ""
            }
        }
        out = Regex("""&#(\d+);""").replace(out) { m ->
            try {
                m.groupValues[1].toInt().toChar().toString()
            } catch (e: Exception) {
                ""
            }
        }
        out = out.replace("&nbsp;", " ", ignoreCase = true)
        out = out.replace("&lt;", "<", ignoreCase = true)
        out = out.replace("&gt;", ">", ignoreCase = true)
        out = out.replace("&quot;", "\"", ignoreCase = true)
        out = out.replace("&#39;", "'")
        out = out.replace("&apos;", "'", ignoreCase = true)
        out = out.replace("&amp;", "&", ignoreCase = true)
        return out
    }

    // ------------------------------------------------------------------
    // ASS / SSA
    // ------------------------------------------------------------------

    private val ASS_OVERRIDE = Regex("""\{[^}]*\}""")

    private fun parseAss(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        // Default field order: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        for (rawLine in normalize(text).lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("Format:", ignoreCase = true) -> {
                    val fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                    val si = fields.indexOf("start")
                    val ei = fields.indexOf("end")
                    val ti = fields.indexOf("text")
                    if (si >= 0 && ei >= 0 && ti >= 0) {
                        startIdx = si; endIdx = ei; textIdx = ti
                    }
                }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val body = line.substringAfter(':').trimStart()
                    // Text is the last field and may itself contain commas:
                    // split only on the commas before it.
                    val parts = body.split(',', limit = textIdx + 1)
                    if (parts.size <= maxOf(startIdx, endIdx, textIdx)) continue
                    val start = parseColonTime(parts[startIdx]) ?: continue
                    val end = parseColonTime(parts[endIdx]) ?: continue
                    if (end < start) continue
                    var txt = ASS_OVERRIDE.replace(parts[textIdx], "")
                    txt = txt.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    txt = txt.trim()
                    if (txt.isEmpty()) continue
                    cues.add(Cue(start, end, txt))
                }
                else -> {
                    // [Script Info], [Events], Comment: lines, styles… ignored.
                }
            }
        }
        return cues
    }

    // ------------------------------------------------------------------
    // SAMI (.smi / .sami)
    // ------------------------------------------------------------------

    private val SYNC_TAG = Regex("""<SYNC\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SYNC_START = Regex("""Start\s*=\s*"?(\d+)""", RegexOption.IGNORE_CASE)

    private fun parseSami(text: String): List<Cue> {
        val clean = normalize(text)
        val tags = SYNC_TAG.findAll(clean).toList()
        if (tags.isEmpty()) return emptyList()
        data class Sync(val startMs: Long, val segment: String)
        val syncs = ArrayList<Sync>()
        for ((i, tag) in tags.withIndex()) {
            val start = SYNC_START.find(tag.value)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val segEnd = if (i + 1 < tags.size) tags[i + 1].range.first else clean.length
            syncs.add(Sync(start, clean.substring(tag.range.last + 1, segEnd)))
        }
        val cues = ArrayList<Cue>()
        for ((i, sync) in syncs.withIndex()) {
            val txt = cleanSamiText(sync.segment)
            if (txt.isEmpty()) continue // &nbsp; etc. = caption clear marker
            val end = if (i + 1 < syncs.size) syncs[i + 1].startMs else sync.startMs + 4000L
            if (end <= sync.startMs) continue
            cues.add(Cue(sync.startMs, end, txt))
        }
        return cues
    }

    private fun cleanSamiText(segment: String): String {
        // <br> marks a real line break; other source newlines are just
        // HTML formatting whitespace and collapse to spaces.
        var s = BR_TAG.replace(segment, "\u0001")
        s = Regex("""</p\s*>""", RegexOption.IGNORE_CASE).replace(s, "\u0001")
        s = stripTags(s)
        s = decodeEntities(s)
        val parts = s.split('\u0001').map { part ->
            part.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        }.filter { it.isNotEmpty() }
        return parts.joinToString("\n").trim()
    }

    // ------------------------------------------------------------------
    // WebVTT
    // ------------------------------------------------------------------

    private fun parseVtt(text: String): List<Cue> {
        val clean = normalize(text)
        val cues = ArrayList<Cue>()
        val blocks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in clean.split('\n')) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks.add(current); current = ArrayList()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) blocks.add(current)

        for (block in blocks) {
            val first = block[0].trim()
            if (first.startsWith("WEBVTT", ignoreCase = true) && block.none { it.contains("-->") }) {
                continue
            }
            val head = first.uppercase()
            if (head.startsWith("NOTE") || head.startsWith("STYLE") || head.startsWith("REGION")) {
                continue
            }
            val timingIdx = block.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue
            val timing = block[timingIdx].trim()
            val arrowParts = timing.split("-->")
            if (arrowParts.size < 2) continue
            val start = parseColonTime(arrowParts[0].trim()) ?: continue
            val endToken = arrowParts[1].trim().split(Regex("""\s+""")).firstOrNull() ?: continue
            val end = parseColonTime(endToken) ?: continue
            if (end < start) continue
            val body = block.drop(timingIdx + 1)
                .joinToString("\n") { decodeEntities(stripTags(it)).trim() }
                .trim()
            if (body.isEmpty()) continue
            cues.add(Cue(start, end, body))
        }
        return cues
    }

    // ------------------------------------------------------------------
    // MicroDVD (.sub, frame-based)
    // ------------------------------------------------------------------

    private val MICRODVD_FULL = Regex("""^\{(\d+)\}\{(\d+)\}(.*)$""")
    private val ANY_BRACE_GROUP = Regex("""\{[^}]*\}""")

    private fun parseMicroDvd(text: String, fps: Double): List<Cue> {
        val cues = ArrayList<Cue>()
        for (rawLine in normalize(text).lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val m = MICRODVD_FULL.matchEntire(line) ?: continue
            val startFrame = m.groupValues[1].toLongOrNull() ?: continue
            val endFrame = m.groupValues[2].toLongOrNull() ?: continue
            if (endFrame < startFrame) continue
            var txt = m.groupValues[3]
            txt = ANY_BRACE_GROUP.replace(txt, "") // {Y:i} style codes etc.
            txt = txt.replace("|", "\n").trim()
            if (txt.isEmpty()) continue
            cues.add(Cue(framesToMs(startFrame, fps), framesToMs(endFrame, fps), txt))
        }
        return cues
    }

    // ------------------------------------------------------------------
    // MPL2 (.mpl / .txt, deciseconds)
    // ------------------------------------------------------------------

    private val MPL2_FULL = Regex("""^\[(\d+)\]\[(\d+)\](.*)$""")

    private fun parseMpl2(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (rawLine in normalize(text).lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val m = MPL2_FULL.matchEntire(line) ?: continue
            val startDs = m.groupValues[1].toLongOrNull() ?: continue
            val endDs = m.groupValues[2].toLongOrNull() ?: continue
            if (endDs < startDs) continue
            val segments = m.groupValues[3].split('|').map { seg ->
                var s = seg.trim()
                if (s.startsWith("/")) s = s.substring(1).trim() // italic marker
                ANY_BRACE_GROUP.replace(s, "").trim()
            }.filter { it.isNotEmpty() }
            val txt = segments.joinToString("\n")
            if (txt.isEmpty()) continue
            cues.add(Cue(startDs * 100L, endDs * 100L, txt))
        }
        return cues
    }

    // ------------------------------------------------------------------
    // PJS (Phoenix, .pjs, frame-based)
    // ------------------------------------------------------------------

    private val PJS_QUOTED = Regex("""^\s*(\d+)\s*,\s*(\d+)\s*,\s*"(.*)"\s*$""")
    private val PJS_PLAIN = Regex("""^\s*(\d+)\s*,\s*(\d+)\s*,\s*(.+)$""")

    private fun parsePjs(text: String, fps: Double): List<Cue> {
        val cues = ArrayList<Cue>()
        for (rawLine in normalize(text).lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            var startFrame: Long? = null
            var endFrame: Long? = null
            var txt: String? = null
            val q = PJS_QUOTED.matchEntire(line)
            if (q != null) {
                startFrame = q.groupValues[1].toLongOrNull()
                endFrame = q.groupValues[2].toLongOrNull()
                txt = q.groupValues[3].replace("\"\"", "\"")
            } else {
                val p = PJS_PLAIN.matchEntire(line) ?: continue
                startFrame = p.groupValues[1].toLongOrNull()
                endFrame = p.groupValues[2].toLongOrNull()
                txt = p.groupValues[3].trim()
            }
            val sf = startFrame ?: continue
            val ef = endFrame ?: continue
            if (ef < sf) continue
            val body = txt?.trim().orEmpty()
            if (body.isEmpty()) continue
            cues.add(Cue(framesToMs(sf, fps), framesToMs(ef, fps), body))
        }
        return cues
    }

    // ------------------------------------------------------------------
    // PowerDivX (.psb, start stamps)
    // ------------------------------------------------------------------

    private val POWERDIVX_FULL = Regex(
        """^\{(\d{1,2}:\d{2}:\d{2})\}(?:\{(\d{1,2}:\d{2}:\d{2})\})?\s*(.*)$"""
    )

    private fun parsePowerDivX(text: String): List<Cue> {
        data class Entry(val startMs: Long, val explicitEndMs: Long?, val lines: MutableList<String>)
        val entries = ArrayList<Entry>()
        for (rawLine in normalize(text).lines()) {
            val line = rawLine.trim()
            val m = POWERDIVX_FULL.matchEntire(line)
            if (m != null) {
                val start = parseColonTime(m.groupValues[1])
                if (start == null) {
                    if (entries.isNotEmpty() && line.isNotEmpty()) entries.last().lines.add(line)
                    continue
                }
                val explicitEnd = m.groupValues[2].takeIf { it.isNotEmpty() }
                    ?.let { parseColonTime(it) }
                val entry = Entry(start, explicitEnd, ArrayList())
                val rest = m.groupValues[3].trim()
                if (rest.isNotEmpty()) entry.lines.add(rest)
                entries.add(entry)
            } else if (entries.isNotEmpty() && line.isNotEmpty()) {
                entries.last().lines.add(stripTags(line))
            }
        }
        val cues = ArrayList<Cue>()
        for ((i, entry) in entries.withIndex()) {
            val body = entry.lines.joinToString("\n").trim()
            if (body.isEmpty()) continue // empty stamp still ends the previous cue
            var end = entry.explicitEndMs
                ?: if (i + 1 < entries.size) entries[i + 1].startMs else entry.startMs + 4000L
            if (end <= entry.startMs) end = entry.startMs + 4000L
            cues.add(Cue(entry.startMs, end, body))
        }
        return cues
    }

    // ------------------------------------------------------------------
    // SubViewer (.sub)
    // ------------------------------------------------------------------

    private val SUBVIEWER_TIMING_FULL = Regex(
        """^(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3}),(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})$"""
    )
    private val SUBVIEWER_BR = Regex("""\[br\]""", RegexOption.IGNORE_CASE)

    private fun parseSubViewer(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        var curStart: Long? = null
        var curEnd: Long? = null
        val curLines = ArrayList<String>()

        fun flush() {
            val s = curStart
            val e = curEnd
            if (s != null && e != null && e >= s) {
                val body = curLines
                    .flatMap { SUBVIEWER_BR.replace(it, "\n").split('\n') }
                    .map { stripTags(it).trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                    .trim()
                if (body.isNotEmpty()) cues.add(Cue(s, e, body))
            }
            curStart = null; curEnd = null; curLines.clear()
        }

        for (rawLine in normalize(text).lines()) {
            val line = rawLine.trim()
            val tm = SUBVIEWER_TIMING_FULL.matchEntire(line)
            when {
                tm != null -> {
                    flush()
                    curStart = parseColonTime(tm.groupValues[1])
                    curEnd = parseColonTime(tm.groupValues[2])
                    if (curStart == null || curEnd == null) {
                        curStart = null; curEnd = null
                    }
                }
                line.isEmpty() -> flush()
                line.startsWith("[") && line.endsWith("]") -> {
                    // [INFORMATION] / [SUBTITLE] / metadata headers: skipped.
                    if (curStart == null) {
                        // header before any cue — nothing to flush
                    } else {
                        // a bracketed section after a cue ends that cue
                        flush()
                    }
                }
                curStart != null -> curLines.add(line)
                else -> {
                    // Header metadata lines ([AUTHOR] values etc.) — skipped.
                }
            }
        }
        flush()
        return cues
    }
}
