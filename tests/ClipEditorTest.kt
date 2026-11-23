package com.ghostblade.player

import java.io.File
import java.nio.file.Files

/**
 * JVM tests for the pure clip-editor logic in ClipEditor.kt
 * ([ClipLogic]): range clamping, handle movement, the
 * container/codec plan matrix, output naming with collision
 * avoidance, duration verification and time formatting.
 * Same plain main() style as the other suites. The Android engine
 * half ([ClipTrimmer]) is never called — android.jar is only on
 * the compile classpath.
 *
 * Run: tests/run-clip-tests.sh
 */
private var checks = 0
private var failures = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun <T> assertEq(name: String, expected: T, actual: T) {
    checks++
    if (expected != actual) {
        failures++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

fun main() {
    // ---------- clampRange ----------
    assertEq("clamp normal", 10_000L to 40_000L,
        ClipLogic.clampRange(10_000, 40_000, 60_000))
    assertEq("clamp negative start", 0L to 40_000L,
        ClipLogic.clampRange(-5_000, 40_000, 60_000))
    assertEq("clamp end past duration", 10_000L to 60_000L,
        ClipLogic.clampRange(10_000, 90_000, 60_000))
    assertEq("clamp inverted range repaired to min length",
        30_000L to 31_000L, ClipLogic.clampRange(30_000, 20_000, 60_000))
    assertEq("clamp too-short range extended",
        10_000L to 11_000L, ClipLogic.clampRange(10_000, 10_400, 60_000))
    assertEq("clamp at tail pulls start back",
        59_000L to 60_000L, ClipLogic.clampRange(59_500, 60_000, 60_000))
    assertEq("clamp whole short file", 0L to 800L,
        ClipLogic.clampRange(100, 700, 800))
    assertEq("clamp zero duration", 0L to 0L,
        ClipLogic.clampRange(0, 0, 0))
    // Invariants over a sweep: ordered, in-bounds, min length.
    var sweepOk = true
    for (s in -5_000L..65_000L step 7_919L) {
        for (e in -5_000L..65_000L step 6_667L) {
            val (cs, ce) = ClipLogic.clampRange(s, e, 60_000)
            if (cs < 0 || ce > 60_000 || cs >= ce || ce - cs < ClipLogic.MIN_CLIP_MS) {
                sweepOk = false
            }
        }
    }
    check("clamp sweep invariants", sweepOk)

    // ---------- handle movement ----------
    assertEq("moveStart free", 20_000L, ClipLogic.moveStart(20_000, 40_000, 60_000))
    assertEq("moveStart stops at end-min", 39_000L,
        ClipLogic.moveStart(45_000, 40_000, 60_000))
    assertEq("moveStart clamps to 0", 0L, ClipLogic.moveStart(-1, 40_000, 60_000))
    assertEq("moveEnd free", 50_000L, ClipLogic.moveEnd(10_000, 50_000, 60_000))
    assertEq("moveEnd stops at start+min", 11_000L,
        ClipLogic.moveEnd(10_000, 5_000, 60_000))
    assertEq("moveEnd clamps to duration", 60_000L,
        ClipLogic.moveEnd(10_000, 99_000, 60_000))

    // ---------- plan matrix ----------
    val api = 34
    assertEq("h264+aac -> mp4", ClipLogic.Plan.MP4,
        ClipLogic.choosePlan("video/avc", listOf("audio/mp4a-latm"), api).plan)
    assertEq("h264 alone -> mp4", ClipLogic.Plan.MP4,
        ClipLogic.choosePlan("video/avc", emptyList(), api).plan)
    assertEq("hevc+aac api34 -> mp4", ClipLogic.Plan.MP4,
        ClipLogic.choosePlan("video/hevc", listOf("audio/mp4a-latm"), api).plan)
    assertEq("hevc api24 -> unsupported", ClipLogic.Plan.UNSUPPORTED,
        ClipLogic.choosePlan("video/hevc", listOf("audio/mp4a-latm"), 24).plan)
    assertEq("mp4v-es -> mp4", ClipLogic.Plan.MP4,
        ClipLogic.choosePlan("video/mp4v-es", emptyList(), api).plan)
    assertEq("vp9+opus api34 -> webm", ClipLogic.Plan.WEBM,
        ClipLogic.choosePlan("video/x-vnd.on2.vp9", listOf("audio/opus"), api).plan)
    assertEq("vp9+opus api28 -> unsupported", ClipLogic.Plan.UNSUPPORTED,
        ClipLogic.choosePlan("video/x-vnd.on2.vp9", listOf("audio/opus"), 28).plan)
    assertEq("vp8+vorbis api24 -> webm", ClipLogic.Plan.WEBM,
        ClipLogic.choosePlan("video/x-vnd.on2.vp8", listOf("audio/vorbis"), 24).plan)
    assertEq("h264+mp3 -> unsupported (mp3 fits neither)",
        ClipLogic.Plan.UNSUPPORTED,
        ClipLogic.choosePlan("video/avc", listOf("audio/mpeg"), api).plan)
    assertEq("mpeg2 -> unsupported", ClipLogic.Plan.UNSUPPORTED,
        ClipLogic.choosePlan("video/mpeg2", listOf("audio/mp4a-latm"), api).plan)
    assertEq("no video -> unsupported", ClipLogic.Plan.UNSUPPORTED,
        ClipLogic.choosePlan(null, listOf("audio/mp4a-latm"), api).plan)
    assertEq("mp4 extension", "mp4",
        ClipLogic.choosePlan("video/avc", emptyList(), api).extension)
    assertEq("webm extension", "webm",
        ClipLogic.choosePlan("video/x-vnd.on2.vp9", emptyList(), api).extension)
    check("unsupported names the codec",
        ClipLogic.choosePlan("video/avc", listOf("audio/mpeg"), api)
            .reason?.contains("audio/mpeg") == true)
    check("no-video reason explains",
        ClipLogic.choosePlan(null, emptyList(), api)
            .reason?.contains("no video track") == true)
    // A second audio track that doesn't fit sinks the plan too.
    assertEq("second bad audio track -> unsupported",
        ClipLogic.Plan.UNSUPPORTED,
        ClipLogic.choosePlan("video/avc",
            listOf("audio/mp4a-latm", "audio/mpeg"), api).plan)

    // ---------- output naming ----------
    assertEq("name mp4", "movie_clip.mp4",
        ClipLogic.outputFileName("movie.mkv", "mp4"))
    assertEq("name keeps base dots", "my.movie_clip.webm",
        ClipLogic.outputFileName("my.movie.webm", "webm"))
    assertEq("name no extension source", "clip_clip.mp4",
        ClipLogic.outputFileName("clip", "mp4"))
    assertEq("name blank base", "video_clip.mp4",
        ClipLogic.outputFileName(".mkv", "mp4"))

    val dir = Files.createTempDirectory("clip-test").toFile()
    try {
        assertEq("unique no collision", "a_clip.mp4",
            ClipLogic.uniqueFile(dir, "a_clip.mp4").name)
        File(dir, "a_clip.mp4").createNewFile()
        assertEq("unique first collision", "a_clip (1).mp4",
            ClipLogic.uniqueFile(dir, "a_clip.mp4").name)
        File(dir, "a_clip (1).mp4").createNewFile()
        assertEq("unique second collision", "a_clip (2).mp4",
            ClipLogic.uniqueFile(dir, "a_clip.mp4").name)
    } finally {
        dir.deleteRecursively()
    }

    // ---------- duration verification ----------
    check("verify exact", ClipLogic.verifyDurationOk(30_000, 30_000))
    check("verify keyframe overshoot ok", ClipLogic.verifyDurationOk(30_000, 32_500))
    check("verify small undershoot ok", ClipLogic.verifyDurationOk(30_000, 29_600))
    check("verify big undershoot fails", !ClipLogic.verifyDurationOk(30_000, 28_000))
    check("verify huge overshoot fails", !ClipLogic.verifyDurationOk(30_000, 40_000))
    check("verify zero actual fails", !ClipLogic.verifyDurationOk(30_000, 0))
    check("verify long clip 10% band", ClipLogic.verifyDurationOk(600_000, 655_000))
    check("verify long clip beyond band", !ClipLogic.verifyDurationOk(600_000, 665_000))

    // ---------- time formatting ----------
    assertEq("fmt zero", "00:00.000", ClipLogic.formatTimeMs(0))
    assertEq("fmt seconds", "00:05.250", ClipLogic.formatTimeMs(5_250))
    assertEq("fmt minutes", "01:05.250", ClipLogic.formatTimeMs(65_250))
    assertEq("fmt hours", "1:01:05.250", ClipLogic.formatTimeMs(3_665_250))
    assertEq("fmt negative clamps", "00:00.000", ClipLogic.formatTimeMs(-10))

    println("ClipEditorTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL TESTS PASSED")
}
