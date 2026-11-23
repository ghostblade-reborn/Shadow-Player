package com.ghostblade.player

import java.nio.charset.Charset

/**
 * Plain JVM tests for SubtitleFormats (SubtitleFormats.kt).
 * Same style as tests/SubtitleTest.kt: main() runs assertions, prints a
 * summary, and throws (non-zero exit) on any failure.
 */

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
        println("PASS: $name")
    } else {
        failed++
        val msg = if (detail.isEmpty()) name else "$name -- $detail"
        failures.add(msg)
        println("FAIL: $msg")
    }
}

private fun <T> assertEq(name: String, expected: T, actual: T) {
    check(name, expected == actual, "expected=<$expected> actual=<$actual>")
}

fun main() {
    // ===============================================================
    // SRT (delegated through SubtitleFormats)
    // ===============================================================
    val srtText = "1\n00:00:01,000 --> 00:00:02,500\nHello SRT\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n"
    assertEq("srt: detect by extension", SubtitleFormat.SRT, SubtitleFormats.detect("movie.srt", srtText))
    assertEq("srt: detect by content", SubtitleFormat.SRT, SubtitleFormats.detect(null, srtText))
    val srtCues = SubtitleFormats.parse(SubtitleFormat.SRT, srtText)
    assertEq("srt: cue count", 2, srtCues.size)
    if (srtCues.size == 2) assertEq("srt: cue1", Cue(1000L, 2500L, "Hello SRT"), srtCues[0])
    assertEq("srt: parseAuto", srtCues, SubtitleFormats.parseAuto("movie.srt", srtText))
    assertEq("srt: malformed tolerated", 0, SubtitleFormats.parse(SubtitleFormat.SRT, "not a subtitle").size)

    // ===============================================================
    // ASS / SSA
    // ===============================================================
    val assText = """
        [Script Info]
        Title: Test
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,Hello{\i1}World{\i0}
        Dialogue: 0,0:00:04.00,0:00:06.00,Default,,0,0,0,,Line one\NLine two, with comma
        Comment: 0,0:00:07.00,0:00:08.00,Default,,0,0,0,,Should be skipped
        Dialogue: garbage,line,here
    """.trimIndent()
    assertEq("ass: detect by extension .ass", SubtitleFormat.ASS, SubtitleFormats.detect("movie.ass", assText))
    assertEq("ass: detect by extension .ssa", SubtitleFormat.ASS, SubtitleFormats.detect("movie.ssa", "whatever"))
    assertEq("ass: detect by content", SubtitleFormat.ASS, SubtitleFormats.detect(null, assText))
    val assCues = SubtitleFormats.parse(SubtitleFormat.ASS, assText)
    assertEq("ass: cue count (Comment + garbage skipped)", 2, assCues.size)
    if (assCues.size == 2) {
        assertEq("ass: cue1 start (centiseconds)", 1000L, assCues[0].startMs)
        assertEq("ass: cue1 end (centiseconds)", 3500L, assCues[0].endMs)
        assertEq("ass: cue1 override blocks stripped", "HelloWorld", assCues[0].text)
        assertEq("ass: cue2 \\N newline + comma kept", "Line one\nLine two, with comma", assCues[1].text)
    }
    // Custom Format order honoured
    val assCustom = """
        [Events]
        Format: Start, End, Text
        Dialogue: 0:00:10.00,0:00:12.25,Custom order
    """.trimIndent()
    val assCustomCues = SubtitleFormats.parse(SubtitleFormat.ASS, assCustom)
    assertEq("ass: custom Format count", 1, assCustomCues.size)
    if (assCustomCues.size == 1) assertEq("ass: custom Format cue", Cue(10000L, 12250L, "Custom order"), assCustomCues[0])
    // \h -> space
    val assHard = "[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,A\\hB\n"
    val assHardCues = SubtitleFormats.parse(SubtitleFormat.ASS, assHard)
    assertEq("ass: \\h count", 1, assHardCues.size)
    if (assHardCues.size == 1) assertEq("ass: \\h becomes space", "A B", assHardCues[0].text)
    assertEq("ass: malformed tolerated", 0, SubtitleFormats.parse(SubtitleFormat.ASS, "random\ntext\n").size)

    // ===============================================================
    // SAMI
    // ===============================================================
    val samiText = """
        <SAMI><HEAD><TITLE>Test</TITLE></HEAD><BODY>
        <SYNC Start=1000><P Class=ENCC>Hello &amp; welcome
        <SYNC Start=3000><P Class=ENCC>&nbsp;
        <SYNC Start=4000><P Class=ENCC>Second &lt;cue&gt;
        </BODY></SAMI>
    """.trimIndent()
    assertEq("sami: detect by extension .smi", SubtitleFormat.SAMI, SubtitleFormats.detect("movie.smi", samiText))
    assertEq("sami: detect by extension .sami", SubtitleFormat.SAMI, SubtitleFormats.detect("movie.sami", "x"))
    assertEq("sami: detect by content", SubtitleFormat.SAMI, SubtitleFormats.detect(null, samiText))
    val samiCues = SubtitleFormats.parse(SubtitleFormat.SAMI, samiText)
    assertEq("sami: cue count (&nbsp; clear skipped)", 2, samiCues.size)
    if (samiCues.size == 2) {
        assertEq("sami: cue1 times (end = next SYNC)", Cue(1000L, 3000L, "Hello & welcome"), samiCues[0])
        assertEq("sami: cue2 last end = start+4000, entities decoded", Cue(4000L, 8000L, "Second <cue>"), samiCues[1])
    }
    assertEq("sami: malformed tolerated", 0, SubtitleFormats.parse(SubtitleFormat.SAMI, "<SYNC Start=abc>bad").size)
    assertEq("sami: garbage tolerated", 0, SubtitleFormats.parse(SubtitleFormat.SAMI, "hello").size)

    // ===============================================================
    // WebVTT
    // ===============================================================
    val vttText = """
        WEBVTT

        NOTE this block is ignored

        00:01.500 --> 00:03.000
        Hello <b>world</b>

        cue-identifier-2
        01:00:04.250 --> 01:00:06.000 align:start position:0%
        Second line

        STYLE
        ::cue { color: lime }

        block without timing
        just text
    """.trimIndent()
    assertEq("vtt: detect by extension", SubtitleFormat.VTT, SubtitleFormats.detect("movie.vtt", vttText))
    assertEq("vtt: detect by content", SubtitleFormat.VTT, SubtitleFormats.detect(null, vttText))
    val vttCues = SubtitleFormats.parse(SubtitleFormat.VTT, vttText)
    assertEq("vtt: cue count (NOTE/STYLE/bad skipped)", 2, vttCues.size)
    if (vttCues.size == 2) {
        assertEq("vtt: cue1 MM:SS.mmm + tags stripped", Cue(1500L, 3000L, "Hello world"), vttCues[0])
        assertEq("vtt: cue2 HH:MM:SS.mmm + identifier + settings ignored", Cue(3604250L, 3606000L, "Second line"), vttCues[1])
    }
    assertEq("vtt: malformed tolerated", 0, SubtitleFormats.parse(SubtitleFormat.VTT, "WEBVTT\n\nno timing here\n").size)

    // ===============================================================
    // MicroDVD
    // ===============================================================
    val microText = "{25}{75}Hello|World\n{100}{125}{Y:i}Styled text\nnot a cue line\n{bad}{line}nope\n"
    assertEq("microdvd: detect by extension+content .sub", SubtitleFormat.MICRODVD, SubtitleFormats.detect("movie.sub", microText))
    assertEq("microdvd: detect by content", SubtitleFormat.MICRODVD, SubtitleFormats.detect(null, microText))
    val microCues = SubtitleFormats.parse(SubtitleFormat.MICRODVD, microText, fps = 25.0)
    assertEq("microdvd: cue count (bad lines skipped)", 2, microCues.size)
    if (microCues.size == 2) {
        assertEq("microdvd: cue1 frames@25fps + | newline", Cue(1000L, 3000L, "Hello\nWorld"), microCues[0])
        assertEq("microdvd: cue2 style code stripped", Cue(4000L, 5000L, "Styled text"), microCues[1])
    }
    val microDefault = SubtitleFormats.parse(SubtitleFormat.MICRODVD, "{24}{48}Default fps")
    assertEq("microdvd: default fps count", 1, microDefault.size)
    if (microDefault.size == 1) {
        assertEq("microdvd: default fps 23.976 start", 1001L, microDefault[0].startMs)
        assertEq("microdvd: default fps 23.976 end", 2002L, microDefault[0].endMs)
    }

    // ===============================================================
    // MPL2
    // ===============================================================
    val mplText = "[10][35]Hello|World\n[40][50]/Italic line\nbad line\n[xx][yy]nope\n"
    assertEq("mpl2: detect by extension .mpl", SubtitleFormat.MPL2, SubtitleFormats.detect("movie.mpl", mplText))
    assertEq("mpl2: detect by content", SubtitleFormat.MPL2, SubtitleFormats.detect(null, mplText))
    assertEq("mpl2: detect .txt by content", SubtitleFormat.MPL2, SubtitleFormats.detect("movie.txt", mplText))
    val mplCues = SubtitleFormats.parse(SubtitleFormat.MPL2, mplText)
    assertEq("mpl2: cue count (bad lines skipped)", 2, mplCues.size)
    if (mplCues.size == 2) {
        assertEq("mpl2: cue1 deciseconds + | newline", Cue(1000L, 3500L, "Hello\nWorld"), mplCues[0])
        assertEq("mpl2: cue2 leading / italic stripped", Cue(4000L, 5000L, "Italic line"), mplCues[1])
    }

    // ===============================================================
    // PJS (Phoenix)
    // ===============================================================
    val pjsText = "25,75,\"Hello, world\"\n100,125,\"Second cue\"\nbad,line,here\n1,2\n"
    assertEq("pjs: detect by extension", SubtitleFormat.PJS, SubtitleFormats.detect("movie.pjs", pjsText))
    assertEq("pjs: detect by content", SubtitleFormat.PJS, SubtitleFormats.detect(null, pjsText))
    val pjsCues = SubtitleFormats.parse(SubtitleFormat.PJS, pjsText, fps = 25.0)
    assertEq("pjs: cue count (bad lines skipped)", 2, pjsCues.size)
    if (pjsCues.size == 2) {
        assertEq("pjs: cue1 frames@25fps + comma inside quotes", Cue(1000L, 3000L, "Hello, world"), pjsCues[0])
        assertEq("pjs: cue2", Cue(4000L, 5000L, "Second cue"), pjsCues[1])
    }
    assertEq("pjs: malformed tolerated", 0, SubtitleFormats.parse(SubtitleFormat.PJS, "hello world").size)

    // ===============================================================
    // PowerDivX
    // ===============================================================
    val psbText = "{00:00:01}First caption\ncontinues here\n{00:00:05}Second caption\n"
    assertEq("powerdivx: detect by extension .psb", SubtitleFormat.POWERDIVX, SubtitleFormats.detect("movie.psb", psbText))
    assertEq("powerdivx: detect by content", SubtitleFormat.POWERDIVX, SubtitleFormats.detect(null, psbText))
    val psbCues = SubtitleFormats.parse(SubtitleFormat.POWERDIVX, psbText)
    assertEq("powerdivx: cue count", 2, psbCues.size)
    if (psbCues.size == 2) {
        assertEq("powerdivx: cue1 end = next stamp, multiline joined", Cue(1000L, 5000L, "First caption\ncontinues here"), psbCues[0])
        assertEq("powerdivx: cue2 last end = start+4000", Cue(5000L, 9000L, "Second caption"), psbCues[1])
    }
    val psbDouble = SubtitleFormats.parse(SubtitleFormat.POWERDIVX, "{00:00:10}{00:00:12}Double stamp\n")
    assertEq("powerdivx: double-stamp count", 1, psbDouble.size)
    if (psbDouble.size == 1) assertEq("powerdivx: double-stamp cue", Cue(10000L, 12000L, "Double stamp"), psbDouble[0])
    assertEq("powerdivx: malformed tolerated", 0, SubtitleFormats.parse(SubtitleFormat.POWERDIVX, "no stamps here").size)

    // ===============================================================
    // SubViewer
    // ===============================================================
    val subViewerText = """
        [INFORMATION]
        [TITLE]Test Movie
        [END INFORMATION]
        [SUBTITLE]
        00:00:01.00,00:00:03.50
        Hello[br]World

        00:00:04.00,00:00:06.00
        Second cue
    """.trimIndent()
    assertEq("subviewer: detect by extension+content .sub", SubtitleFormat.SUBVIEWER, SubtitleFormats.detect("movie.sub", subViewerText))
    assertEq("subviewer: detect by content [INFORMATION]", SubtitleFormat.SUBVIEWER, SubtitleFormats.detect(null, subViewerText))
    val svCues = SubtitleFormats.parse(SubtitleFormat.SUBVIEWER, subViewerText)
    assertEq("subviewer: cue count (headers skipped)", 2, svCues.size)
    if (svCues.size == 2) {
        assertEq("subviewer: cue1 centiseconds + [br] newline", Cue(1000L, 3500L, "Hello\nWorld"), svCues[0])
        assertEq("subviewer: cue2", Cue(4000L, 6000L, "Second cue"), svCues[1])
    }
    assertEq("subviewer: malformed tolerated", 0, SubtitleFormats.parse(SubtitleFormat.SUBVIEWER, "[INFORMATION]\nno timings\n").size)
    // Detection by timing lines alone (no header)
    assertEq(
        "subviewer: detect by timing line only",
        SubtitleFormat.SUBVIEWER,
        SubtitleFormats.detect(null, "00:00:01.00,00:00:02.00\nText\n")
    )

    // ===============================================================
    // UNKNOWN + parseAuto dispatch
    // ===============================================================
    assertEq("unknown: detect random text", SubtitleFormat.UNKNOWN, SubtitleFormats.detect("movie.xyz", "just some random words"))
    assertEq("unknown: detect null name + garbage", SubtitleFormat.UNKNOWN, SubtitleFormats.detect(null, "hello world"))
    assertEq("unknown: parse yields empty", 0, SubtitleFormats.parse(SubtitleFormat.UNKNOWN, srtText).size)
    assertEq("unknown: parseAuto yields empty", 0, SubtitleFormats.parseAuto("movie.xyz", "random words here").size)
    assertEq("parseAuto: ass dispatch", assCues, SubtitleFormats.parseAuto("movie.ass", assText))
    assertEq("parseAuto: vtt dispatch by content", vttCues, SubtitleFormats.parseAuto(null, vttText))
    assertEq("parseAuto: microdvd .sub dispatch", microCues, SubtitleFormats.parseAuto("movie.sub", microText, fps = 25.0))
    // Empty / blank input never throws for any format
    for (f in SubtitleFormat.values()) {
        assertEq("empty input tolerated: $f", 0, SubtitleFormats.parse(f, "").size)
    }

    // ===============================================================
    // decode()
    // ===============================================================
    // UTF-8 BOM sniffed and stripped under Auto
    val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        "Hello BOM".toByteArray(Charsets.UTF_8)
    assertEq("decode: UTF-8 BOM (Auto)", "Hello BOM", SubtitleFormats.decode(utf8BomBytes, "Auto"))
    assertEq("decode: UTF-8 BOM (null = Auto)", "Hello BOM", SubtitleFormats.decode(utf8BomBytes, null))
    // UTF-16LE BOM
    val utf16Bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
        "Hi 16".toByteArray(Charset.forName("UTF-16LE"))
    assertEq("decode: UTF-16LE BOM (Auto)", "Hi 16", SubtitleFormats.decode(utf16Bytes, "Auto"))
    // Plain UTF-8 with non-ASCII survives Auto strict decode
    val cafeBytes = "café — naïve".toByteArray(Charsets.UTF_8)
    assertEq("decode: plain UTF-8 (Auto)", "café — naïve", SubtitleFormats.decode(cafeBytes, null))
    // windows-1251 named charset
    val cyrillic = "Привет"
    val win1251Bytes = cyrillic.toByteArray(Charset.forName("windows-1251"))
    assertEq("decode: windows-1251 named", cyrillic, SubtitleFormats.decode(win1251Bytes, "windows-1251"))
    // Auto fallback: windows-1251 bytes are invalid UTF-8 -> ISO-8859-1 fallback
    assertEq(
        "decode: Auto falls back to ISO-8859-1 on broken UTF-8",
        String(win1251Bytes, Charsets.ISO_8859_1),
        SubtitleFormats.decode(win1251Bytes, "Auto")
    )
    check("decode: Auto fallback is not the UTF-8 replacement-char mess",
        SubtitleFormats.decode(win1251Bytes, "Auto") != cyrillic)
    // Unknown named charset falls back to UTF-8 instead of throwing
    assertEq(
        "decode: unknown charset falls back to UTF-8",
        "plain ascii",
        SubtitleFormats.decode("plain ascii".toByteArray(Charsets.UTF_8), "no-such-charset-xyz")
    )
    // Empty bytes
    assertEq("decode: empty bytes", "", SubtitleFormats.decode(ByteArray(0), "Auto"))

    // ===============================================================
    // ENCODINGS sanity
    // ===============================================================
    assertEq("encodings: Auto first", "Auto", SubtitleFormats.ENCODINGS.first())
    check("encodings: contains UTF-8", SubtitleFormats.ENCODINGS.contains("UTF-8"))
    check("encodings: contains UTF-16", SubtitleFormats.ENCODINGS.contains("UTF-16"))
    check("encodings: contains windows-1251", SubtitleFormats.ENCODINGS.contains("windows-1251"))
    check("encodings: contains Shift_JIS", SubtitleFormats.ENCODINGS.contains("Shift_JIS"))
    check("encodings: contains Big5", SubtitleFormats.ENCODINGS.contains("Big5"))
    check("encodings: no duplicates", SubtitleFormats.ENCODINGS.size == SubtitleFormats.ENCODINGS.distinct().size)
    check(
        "encodings: all non-Auto entries supported",
        SubtitleFormats.ENCODINGS.drop(1).all { name ->
            try {
                Charset.isSupported(name)
            } catch (e: Exception) {
                false
            }
        }
    )
    check("encodings: reasonably complete list", SubtitleFormats.ENCODINGS.size >= 20,
        "size=${SubtitleFormats.ENCODINGS.size}")

    // ---------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------
    val total = passed + failed
    println()
    println("==================================")
    println("SubtitleFormatsTest: $passed/$total assertions passed, $failed failed")
    if (failures.isNotEmpty()) {
        println("Failures:")
        failures.forEach { println("  - $it") }
    }
    println("==================================")
    if (failed > 0) {
        throw AssertionError("$failed of $total assertions failed")
    }
    println("ALL TESTS PASSED")
}
