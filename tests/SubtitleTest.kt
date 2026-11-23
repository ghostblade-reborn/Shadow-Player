package com.ghostblade.player

/**
 * Plain JVM tests for SrtParser / Cue (Subtitle.kt).
 * No test framework: main() runs assertions, prints a summary,
 * and throws (non-zero exit) on any failure.
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

private fun assertNull(name: String, actual: Any?) {
    check(name, actual == null, "expected null actual=<$actual>")
}

fun main() {
    // ---------------------------------------------------------------
    // 1. Basic parse: well-formed cues, exact values, multi-line text
    // ---------------------------------------------------------------
    val basic = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello world

        2
        00:00:03,000 --> 00:00:05,000
        Line one
        Line two

        3
        00:01:00,250 --> 00:01:04,750
        Third cue
    """.trimIndent()

    val basicCues = SrtParser.parse(basic)
    assertEq("basic: cue count", 3, basicCues.size)
    if (basicCues.size == 3) {
        assertEq("basic: cue1 startMs", 1000L, basicCues[0].startMs)
        assertEq("basic: cue1 endMs", 2500L, basicCues[0].endMs)
        assertEq("basic: cue1 text", "Hello world", basicCues[0].text)
        assertEq("basic: cue2 startMs", 3000L, basicCues[1].startMs)
        assertEq("basic: cue2 endMs", 5000L, basicCues[1].endMs)
        assertEq("basic: cue2 multi-line text joined with \\n", "Line one\nLine two", basicCues[1].text)
        assertEq("basic: cue3 startMs", 60250L, basicCues[2].startMs)
        assertEq("basic: cue3 endMs", 64750L, basicCues[2].endMs)
        assertEq("basic: cue3 text", "Third cue", basicCues[2].text)
        assertEq(
            "basic: cue1 whole object",
            Cue(1000L, 2500L, "Hello world"),
            basicCues[0]
        )
    }

    // ---------------------------------------------------------------
    // 2. Timing parse: parseTimestamp exact values
    // ---------------------------------------------------------------
    assertEq("timestamp: 01:02:03,456", 3723456L, SrtParser.parseTimestamp("01:02:03,456"))
    assertEq("timestamp: 02:03,500 (MM:SS)", 123500L, SrtParser.parseTimestamp("02:03,500"))
    assertEq("timestamp: 00:00:01.250 (dot separator)", 1250L, SrtParser.parseTimestamp("00:00:01.250"))
    assertEq("timestamp: 00:00:01,5 (fraction padded)", 1500L, SrtParser.parseTimestamp("00:00:01,5"))
    assertEq("timestamp: 00:00:01,05 (fraction padded to 050)", 1050L, SrtParser.parseTimestamp("00:00:01,05"))
    assertEq("timestamp: 00:00:01,1234 (fraction truncated)", 1123L, SrtParser.parseTimestamp("00:00:01,1234"))
    assertEq("timestamp: 00:00:00,000", 0L, SrtParser.parseTimestamp("00:00:00,000"))
    assertEq("timestamp: 00:00:10,000 (no fraction required check separate)", 10000L, SrtParser.parseTimestamp("00:00:10,000"))
    assertEq("timestamp: 10:00:00,000", 36000000L, SrtParser.parseTimestamp("10:00:00,000"))
    assertEq("timestamp: dot separator MM:SS 01:30.000", 90000L, SrtParser.parseTimestamp("01:30.000"))

    // ---------------------------------------------------------------
    // 3. Malformed input
    // ---------------------------------------------------------------
    // Block with no timing line skipped, valid neighbour still parsed
    val noTiming = """
        1
        This block has no timing line

        2
        00:00:01,000 --> 00:00:02,000
        Valid cue
    """.trimIndent()
    val noTimingCues = SrtParser.parse(noTiming)
    assertEq("malformed: block without timing skipped (count)", 1, noTimingCues.size)
    if (noTimingCues.size == 1) {
        assertEq("malformed: surviving cue text", "Valid cue", noTimingCues[0].text)
    }

    // Bad timestamp block skipped, valid neighbour still parsed
    val badTimestamp = """
        1
        00:99:01,000 --> 00:00:02,000
        Bad start

        2
        00:00:03,000 --> 00:00:04,000
        Good cue

        3
        not-a-time --> also-not-a-time
        Bad both
    """.trimIndent()
    val badTsCues = SrtParser.parse(badTimestamp)
    assertEq("malformed: bad timestamp blocks skipped (count)", 1, badTsCues.size)
    if (badTsCues.size == 1) {
        assertEq("malformed: bad-ts surviving cue text", "Good cue", badTsCues[0].text)
        assertEq("malformed: bad-ts surviving cue startMs", 3000L, badTsCues[0].startMs)
    }

    assertEq("malformed: empty string -> empty list", emptyList<Cue>(), SrtParser.parse(""))
    assertEq("malformed: whitespace only -> empty list", emptyList<Cue>(), SrtParser.parse("   \n\n  "))
    assertEq("malformed: garbage text -> empty list", emptyList<Cue>(), SrtParser.parse("hello world\n\nfoo bar baz"))
    assertEq("malformed: garbage single line -> empty list", emptyList<Cue>(), SrtParser.parse("garbage"))

    // Cue with end before start skipped
    val endBeforeStart = """
        1
        00:00:05,000 --> 00:00:01,000
        Backwards cue

        2
        00:00:06,000 --> 00:00:07,000
        Forward cue
    """.trimIndent()
    val ebsCues = SrtParser.parse(endBeforeStart)
    assertEq("malformed: end-before-start skipped (count)", 1, ebsCues.size)
    if (ebsCues.size == 1) {
        assertEq("malformed: end-before-start surviving text", "Forward cue", ebsCues[0].text)
    }

    // Empty-text block skipped
    val emptyText = """
        1
        00:00:01,000 --> 00:00:02,000

        2
        00:00:03,000 --> 00:00:04,000
        Has text
    """.trimIndent()
    val emptyTextCues = SrtParser.parse(emptyText)
    assertEq("malformed: empty-text block skipped (count)", 1, emptyTextCues.size)
    if (emptyTextCues.size == 1) {
        assertEq("malformed: empty-text surviving text", "Has text", emptyTextCues[0].text)
    }

    // parseTimestamp null cases
    assertNull("timestamp null: abc", SrtParser.parseTimestamp("abc"))
    assertNull("timestamp null: 00:75:00,000 (minutes > 59)", SrtParser.parseTimestamp("00:75:00,000"))
    assertNull("timestamp null: empty string", SrtParser.parseTimestamp(""))
    assertNull("timestamp null: 00:00:75,000 (seconds > 59)", SrtParser.parseTimestamp("00:00:75,000"))
    assertNull("timestamp null: 75:00,000 MM:SS minutes > 59", SrtParser.parseTimestamp("75:00,000"))
    assertNull("timestamp null: single component 123", SrtParser.parseTimestamp("123"))
    assertNull("timestamp null: non-numeric fraction 00:00:01,abc", SrtParser.parseTimestamp("00:00:01,abc"))
    assertNull("timestamp null: blank spaces", SrtParser.parseTimestamp("   "))

    // ---------------------------------------------------------------
    // 4. Robustness: CRLF, BOM, missing index, trailing settings, tags
    // ---------------------------------------------------------------
    val crlf = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHello CRLF\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,500\r\nSecond CRLF\r\n"
    val crlfCues = SrtParser.parse(crlf)
    assertEq("robust: CRLF cue count", 2, crlfCues.size)
    if (crlfCues.size == 2) {
        assertEq("robust: CRLF cue1", Cue(1000L, 2000L, "Hello CRLF"), crlfCues[0])
        assertEq("robust: CRLF cue2", Cue(3000L, 4500L, "Second CRLF"), crlfCues[1])
    }

    val bom = "\uFEFF1\n00:00:01,000 --> 00:00:02,000\nHello BOM\n"
    val bomCues = SrtParser.parse(bom)
    assertEq("robust: BOM cue count", 1, bomCues.size)
    if (bomCues.size == 1) {
        assertEq("robust: BOM cue", Cue(1000L, 2000L, "Hello BOM"), bomCues[0])
    }

    val bomCrlf = "\uFEFF1\r\n00:00:00,500 --> 00:00:01,500\r\nBOM CRLF\r\n"
    val bomCrlfCues = SrtParser.parse(bomCrlf)
    assertEq("robust: BOM+CRLF cue count", 1, bomCrlfCues.size)
    if (bomCrlfCues.size == 1) {
        assertEq("robust: BOM+CRLF cue", Cue(500L, 1500L, "BOM CRLF"), bomCrlfCues[0])
    }

    val noIndex = """
        00:00:01,000 --> 00:00:02,000
        No index one

        00:00:03,000 --> 00:00:04,000
        No index two
    """.trimIndent()
    val noIndexCues = SrtParser.parse(noIndex)
    assertEq("robust: missing index lines cue count", 2, noIndexCues.size)
    if (noIndexCues.size == 2) {
        assertEq("robust: missing index cue1", Cue(1000L, 2000L, "No index one"), noIndexCues[0])
        assertEq("robust: missing index cue2", Cue(3000L, 4000L, "No index two"), noIndexCues[1])
    }

    val trailingSettings = """
        1
        00:00:01,000 --> 00:00:02,000 X1:10 X2:600
        Positioned cue
    """.trimIndent()
    val trailingCues = SrtParser.parse(trailingSettings)
    assertEq("robust: trailing settings cue count", 1, trailingCues.size)
    if (trailingCues.size == 1) {
        assertEq("robust: trailing settings cue", Cue(1000L, 2000L, "Positioned cue"), trailingCues[0])
    }

    val dotSeparator = """
        1
        00:00:01.000 --> 00:00:02.500
        Dot separator cue
    """.trimIndent()
    val dotCues = SrtParser.parse(dotSeparator)
    assertEq("robust: dot separator in timing line count", 1, dotCues.size)
    if (dotCues.size == 1) {
        assertEq("robust: dot separator cue", Cue(1000L, 2500L, "Dot separator cue"), dotCues[0])
    }

    val tags = """
        1
        00:00:01,000 --> 00:00:02,000
        <i>Hello</i>

        2
        00:00:03,000 --> 00:00:04,000
        <b>Bold</b> and <font color="#ffffff">coloured</font> text
    """.trimIndent()
    val tagCues = SrtParser.parse(tags)
    assertEq("robust: tag stripping cue count", 2, tagCues.size)
    if (tagCues.size == 2) {
        assertEq("robust: <i>Hello</i> -> Hello", "Hello", tagCues[0].text)
        assertEq("robust: mixed tags stripped", "Bold and coloured text", tagCues[1].text)
    }

    // File order preserved (not re-sorted)
    val outOfOrder = """
        1
        00:00:10,000 --> 00:00:11,000
        Later first

        2
        00:00:01,000 --> 00:00:02,000
        Earlier second
    """.trimIndent()
    val oooCues = SrtParser.parse(outOfOrder)
    assertEq("robust: file order count", 2, oooCues.size)
    if (oooCues.size == 2) {
        assertEq("robust: file order preserved [0]", "Later first", oooCues[0].text)
        assertEq("robust: file order preserved [1]", "Earlier second", oooCues[1].text)
    }

    // ---------------------------------------------------------------
    // 5. cueAt
    // ---------------------------------------------------------------
    val cues = listOf(
        Cue(1000L, 2000L, "A"),
        Cue(3000L, 4000L, "B")
    )
    assertEq("cueAt: hit inside cue A", "A", SrtParser.cueAt(cues, 1500L, 0L))
    assertEq("cueAt: hit inside cue B", "B", SrtParser.cueAt(cues, 3500L, 0L))
    assertNull("cueAt: miss in gap", SrtParser.cueAt(cues, 2500L, 0L))
    assertNull("cueAt: miss before first cue", SrtParser.cueAt(cues, 500L, 0L))
    assertNull("cueAt: miss after last cue", SrtParser.cueAt(cues, 5000L, 0L))
    assertEq("cueAt: boundary startMs inclusive (A)", "A", SrtParser.cueAt(cues, 1000L, 0L))
    assertEq("cueAt: boundary startMs inclusive (B)", "B", SrtParser.cueAt(cues, 3000L, 0L))
    assertNull("cueAt: boundary endMs exclusive (A end = gap)", SrtParser.cueAt(cues, 2000L, 0L))
    assertNull("cueAt: boundary endMs exclusive (B end)", SrtParser.cueAt(cues, 4000L, 0L))
    assertEq("cueAt: just before endMs still visible", "A", SrtParser.cueAt(cues, 1999L, 0L))

    // Positive offset: lookup = pos + offset, so a later cue is found earlier
    assertEq("cueAt: positive offset finds later cue earlier", "B", SrtParser.cueAt(cues, 2500L, 500L))
    assertEq("cueAt: positive offset inside A from before start", "A", SrtParser.cueAt(cues, 500L, 500L))
    assertNull("cueAt: positive offset pushes past cue end", SrtParser.cueAt(cues, 1500L, 500L))

    // Negative offset delays cues
    assertNull("cueAt: negative offset delays A (lookup before start)", SrtParser.cueAt(cues, 1500L, -1000L))
    assertNull("cueAt: negative offset delays B (lookup in gap)", SrtParser.cueAt(cues, 3500L, -1000L))
    assertEq("cueAt: negative offset still finds B later", "B", SrtParser.cueAt(cues, 4000L, -1000L))
    assertEq("cueAt: negative offset finds A at its delayed start", "A", SrtParser.cueAt(cues, 2000L, -1000L))

    // Negative lookup returns null
    assertNull("cueAt: negative lookup (pos 500, offset -1000)", SrtParser.cueAt(cues, 500L, -1000L))
    assertNull("cueAt: negative lookup (pos 0, offset -1)", SrtParser.cueAt(cues, 0L, -1L))
    assertNull("cueAt: negative position, zero offset", SrtParser.cueAt(cues, -100L, 0L))

    // Empty cue list
    assertNull("cueAt: empty cue list", SrtParser.cueAt(emptyList(), 1500L, 0L))
    assertNull("cueAt: empty cue list with offset", SrtParser.cueAt(emptyList(), 1500L, 500L))

    // First match wins when cues overlap
    val overlapping = listOf(
        Cue(1000L, 3000L, "First"),
        Cue(2000L, 4000L, "Second")
    )
    assertEq("cueAt: overlapping cues return first match", "First", SrtParser.cueAt(overlapping, 2500L, 0L))

    // cueAt integration with parsed cues
    assertEq("cueAt: parsed cues integration", "Hello world", SrtParser.cueAt(basicCues, 1500L, 0L))
    assertNull("cueAt: parsed cues integration gap", SrtParser.cueAt(basicCues, 2750L, 0L))

    // ---------------------------------------------------------------
    // 6. Regression: whitespace-padded separators, compact -->, overflow
    // ---------------------------------------------------------------
    // F3: a separator line containing a space must still separate blocks
    // (previously the two cues merged and the second timing line rendered
    // as visible subtitle text).
    val spaceSep = "1\n00:00:01,000 --> 00:00:02,000\nFirst cue\n \n2\n00:00:03,000 --> 00:00:04,000\nSecond cue\n"
    val spaceSepCues = SrtParser.parse(spaceSep)
    assertEq("regression F3: space-padded separator cue count", 2, spaceSepCues.size)
    if (spaceSepCues.size == 2) {
        assertEq("regression F3: first cue intact", Cue(1000L, 2000L, "First cue"), spaceSepCues[0])
        assertEq("regression F3: second cue not merged", Cue(3000L, 4000L, "Second cue"), spaceSepCues[1])
    }

    // F3 variant: a tab on the separator line
    val tabSep = "1\n00:00:01,000 --> 00:00:02,000\nFirst cue\n\t\n2\n00:00:03,000 --> 00:00:04,000\nSecond cue\n"
    assertEq("regression F3: tab-padded separator cue count", 2, SrtParser.parse(tabSep).size)

    // F4: compact timing line without spaces around -->
    val compact = "1\n00:00:01,000-->00:00:02,000\nCompact cue\n"
    val compactCues = SrtParser.parse(compact)
    assertEq("regression F4: compact --> cue count", 1, compactCues.size)
    if (compactCues.size == 1) {
        assertEq("regression F4: compact --> cue", Cue(1000L, 2000L, "Compact cue"), compactCues[0])
    }

    // F5: timestamp arithmetic overflow returns null, and a block whose
    // timing overflows is skipped (previously produced negative times).
    assertNull("regression F5: overflowing timestamp -> null", SrtParser.parseTimestamp("9999999999999:00:00,000"))
    val overflowFile = "1\n9999999999999:00:00,000 --> 9999999999999:00:01,000\nGarbage cue\n"
    assertEq("regression F5: overflowing timing block skipped", 0, SrtParser.parse(overflowFile).size)

    // ---------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------
    val total = passed + failed
    println()
    println("==================================")
    println("SubtitleTest: $passed/$total assertions passed, $failed failed")
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
