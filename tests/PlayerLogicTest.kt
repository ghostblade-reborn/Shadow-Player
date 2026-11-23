package com.ghostblade.player

/**
 * JVM tests for the pure player logic in PlayerActivity.kt:
 * A–B repeat state, sleep-timer math, zoom/pan math, speed /
 * subtitle-lookup / decoder helpers, and Fmt. Same plain main()
 * style as SubtitleTest. Android classes on the compile classpath
 * are never *called* from these objects.
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
    // ---------- ABRepeat ----------
    ABRepeat.clear()
    check("AB starts inactive", !ABRepeat.active)
    assertEq("AB pressB without A", "Set A first", ABRepeat.pressB(5_000))
    ABRepeat.pressA(1_000)
    check("AB A-only inactive", !ABRepeat.active)
    assertEq("AB A stored", 1_000L, ABRepeat.aMs)
    assertEq("AB pressB before A rejected", "B must be after A", ABRepeat.pressB(500))
    ABRepeat.pressB(5_000)
    check("AB active after A+B", ABRepeat.active)
    assertEq("AB onPosition before B", null, ABRepeat.onPosition(4_999))
    assertEq("AB onPosition at B seeks to A", 1_000L, ABRepeat.onPosition(5_000))
    assertEq("AB onPosition past B seeks to A", 1_000L, ABRepeat.onPosition(9_000))
    // Re-pressing A past B clears B (state stays consistent).
    ABRepeat.pressA(7_000)
    check("AB re-A past B clears B", !ABRepeat.active && ABRepeat.bMs == -1L)
    ABRepeat.clear()
    check("AB clear resets", ABRepeat.aMs == -1L && ABRepeat.bMs == -1L && !ABRepeat.active)

    // ---------- SleepTimerCalc ----------
    val now = 1_700_000_000_000L
    assertEq(
        "Sleep preset 15m", now + 15 * 60_000L,
        SleepTimerCalc.presetDeadline(now, 15)
    )
    assertEq(
        "Sleep preset 60m", now + 60 * 60_000L,
        SleepTimerCalc.presetDeadline(now, 60)
    )
    assertEq("Sleep preset labels", 6, SleepTimerCalc.PRESET_LABELS.size)
    assertEq("Sleep preset minutes", 4, SleepTimerCalc.PRESET_MINUTES.size)
    // Custom time-of-day: a time later today stays today; an earlier
    // time rolls to tomorrow.
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val laterToday = SleepTimerCalc.customDeadline(now, (h + 2) % 24, 30)
    check("Sleep custom later is after now", laterToday > now)
    check(
        "Sleep custom later is within a day",
        laterToday - now <= 24L * 3600 * 1000
    )
    val earlier = SleepTimerCalc.customDeadline(now, h, cal.get(java.util.Calendar.MINUTE))
    check("Sleep custom same-time rolls forward", earlier > now)
    check(
        "Sleep confirm same-day wording",
        SleepTimerCalc.confirmText(now, now + 3_600_000L, "23:13")
            .startsWith("Your sleep timer is now set. Will stop playing at ")
    )
    check(
        "Sleep confirm tomorrow wording",
        SleepTimerCalc.confirmText(now, now + 26L * 3600 * 1000, "01:13")
            .contains("tomorrow at 01:13")
    )
    check(
        "Sleep isTomorrow false same day",
        !SleepTimerCalc.isTomorrow(now, now + 60_000L)
    )

    // ---------- ZoomMath ----------
    assertEq("Zoom clamp low", 0.5f, ZoomMath.clampScale(0.1f))
    assertEq("Zoom clamp high", 4.0f, ZoomMath.clampScale(9f))
    assertEq("Zoom clamp mid", 1.75f, ZoomMath.clampScale(1.75f))
    assertEq("Zoom quick up 1.0→1.5", 1.5f, ZoomMath.stepUp(1.0f, quick = true))
    assertEq("Zoom quick up 1.5→2.0", 2.0f, ZoomMath.stepUp(1.5f, quick = true))
    assertEq("Zoom quick up 3.0→max", 4.0f, ZoomMath.stepUp(3.0f, quick = true))
    assertEq("Zoom quick down 2.0→1.5", 1.5f, ZoomMath.stepDown(2.0f, quick = true))
    assertEq("Zoom quick down 1.0→fit", 1.0f, ZoomMath.stepDown(1.0f, quick = true))
    assertEq("Zoom fine up", 1.25f, ZoomMath.stepUp(1.0f, quick = false))
    assertEq("Zoom fine down clamps", 0.5f, ZoomMath.stepDown(0.6f, quick = false))
    assertEq(
        "Pan clamp smaller video", 0f,
        ZoomMath.clampPan(50f, viewSize = 1000f, scaledSize = 800f)
    )
    assertEq(
        "Pan clamp inside range", 100f,
        ZoomMath.clampPan(100f, viewSize = 1000f, scaledSize = 2000f)
    )
    assertEq(
        "Pan clamp at edge", 500f,
        ZoomMath.clampPan(900f, viewSize = 1000f, scaledSize = 2000f)
    )
    assertEq(
        "Pan clamp negative edge", -500f,
        ZoomMath.clampPan(-900f, viewSize = 1000f, scaledSize = 2000f)
    )

    // ---------- PlayerLogic ----------
    assertEq("Speed clamp low", 25, PlayerLogic.clampSpeedPercent(10))
    assertEq("Speed clamp high", 400, PlayerLogic.clampSpeedPercent(999))
    assertEq("Speed clamp mid", 150, PlayerLogic.clampSpeedPercent(150))
    assertEq("Speed steps", 5, PlayerLogic.SPEED_STEPS.size)
    assertEq(
        "Sub lookup 1.0x no offset", 10_000L,
        PlayerLogic.subLookupMs(10_000, 1.0f, 0)
    )
    assertEq(
        "Sub lookup 2.0x", 20_000L,
        PlayerLogic.subLookupMs(10_000, 2.0f, 0)
    )
    assertEq(
        "Sub lookup offset", 9_500L,
        PlayerLogic.subLookupMs(10_000, 1.0f, -500)
    )
    assertEq("Decoder label default", "Default", PlayerLogic.decoderLabel(0))
    assertEq("Decoder label HW", "HW", PlayerLogic.decoderLabel(1))
    assertEq("Decoder label SW", "SW", PlayerLogic.decoderLabel(2))
    assertEq("Decoder label HW+", "HW+", PlayerLogic.decoderLabel(4))
    check("Smart prev restarts past 3s", PlayerLogic.smartPrevRestarts(3_001))
    check("Smart prev goes back at 3s", !PlayerLogic.smartPrevRestarts(3_000))
    for (item in listOf(
        "boost", "equalizer", "audio_delay", "bt_delay",
        "passthrough", "pref_audio_lang", "deinterlace"
    )) {
        check("HW excludes $item", PlayerLogic.hwExcluded(item))
    }
    check("HW does not exclude speed", !PlayerLogic.hwExcluded("speed"))
    check("HW does not exclude loop", !PlayerLogic.hwExcluded("loop"))

    // ---------- MediaButtonLogic (§2.7.16) ----------
    val mb = MediaButtonLogic
    check(
        "MB disabled swallows play-pause",
        mb.keyAction(mb.KEY_PLAY_PAUSE, 1, false, true, false, false) == MediaButtonLogic.Action.NONE
    )
    check(
        "MB hook single toggles",
        mb.keyAction(mb.KEY_HEADSETHOOK, 1, true, true, false, false) == MediaButtonLogic.Action.TOGGLE
    )
    check(
        "MB hook double -> next",
        mb.keyAction(mb.KEY_HEADSETHOOK, 2, true, true, false, false) == MediaButtonLogic.Action.NEXT
    )
    check(
        "MB hook triple -> prev",
        mb.keyAction(mb.KEY_HEADSETHOOK, 3, true, true, false, false) == MediaButtonLogic.Action.PREV
    )
    check(
        "MB hook double ignored when multi-press off",
        mb.keyAction(mb.KEY_HEADSETHOOK, 2, true, false, false, false) == MediaButtonLogic.Action.TOGGLE
    )
    check(
        "MB hook double remapped -> FF",
        mb.keyAction(mb.KEY_HEADSETHOOK, 2, true, true, true, false) == MediaButtonLogic.Action.FF
    )
    check(
        "MB hook triple remapped -> REW",
        mb.keyAction(mb.KEY_HEADSETHOOK, 3, true, true, true, false) == MediaButtonLogic.Action.REW
    )
    check(
        "MB play is play-only by default",
        mb.keyAction(mb.KEY_PLAY, 1, true, true, false, false) == MediaButtonLogic.Action.PLAY
    )
    check(
        "MB play toggles when pref on",
        mb.keyAction(mb.KEY_PLAY, 1, true, true, false, true) == MediaButtonLogic.Action.TOGGLE
    )
    check(
        "MB pause key pauses",
        mb.keyAction(mb.KEY_PAUSE, 1, true, true, false, false) == MediaButtonLogic.Action.PAUSE
    )
    check(
        "MB next key",
        mb.keyAction(mb.KEY_NEXT, 1, true, true, false, false) == MediaButtonLogic.Action.NEXT
    )
    check(
        "MB next key remapped",
        mb.keyAction(mb.KEY_NEXT, 1, true, true, true, false) == MediaButtonLogic.Action.FF
    )
    check(
        "MB prev key remapped",
        mb.keyAction(mb.KEY_PREVIOUS, 1, true, true, true, false) == MediaButtonLogic.Action.REW
    )
    check(
        "MB stop key",
        mb.keyAction(mb.KEY_STOP, 1, true, true, false, false) == MediaButtonLogic.Action.STOP
    )
    check(
        "MB unknown key",
        mb.keyAction(9999, 1, true, true, false, false) == MediaButtonLogic.Action.NONE
    )
    check("MB isMediaKey hook", mb.isMediaKey(mb.KEY_HEADSETHOOK))
    check("MB isMediaKey rejects volume", !mb.isMediaKey(24)) // KEYCODE_VOLUME_UP

    // ---------- HookPressTracker ----------
    val tracker = HookPressTracker(windowMs = 500)
    assertEq("Hook burst 1st", 1, tracker.onPress(1_000))
    assertEq("Hook burst 2nd", 2, tracker.onPress(1_300))
    assertEq("Hook burst 3rd", 3, tracker.onPress(1_600))
    assertEq("Hook burst resets after gap", 1, tracker.onPress(5_000))
    tracker.reset()
    assertEq("Hook burst after reset", 1, tracker.onPress(6_000))

    // ---------- AvSyncPolicy (#67) ----------
    val av = AvSyncPolicy
    check(
        "AV on drift +500 seeks",
        av.decide(true, 500, true, true, false) == AvSyncPolicy.Decision.SEEK
    )
    check(
        "AV on drift -500 seeks",
        av.decide(true, -500, true, true, false) == AvSyncPolicy.Decision.SEEK
    )
    check(
        "AV on drift +300 holds playing video",
        av.decide(true, 300, true, true, false) == AvSyncPolicy.Decision.HOLD
    )
    check(
        "AV on drift +300 already held stays",
        av.decide(true, 300, true, false, false) == AvSyncPolicy.Decision.NONE
    )
    check(
        "AV on drift small resumes held video",
        av.decide(true, 100, true, false, false) == AvSyncPolicy.Decision.RESUME
    )
    check(
        "AV on drift small playing is none",
        av.decide(true, 100, true, true, false) == AvSyncPolicy.Decision.NONE
    )
    check(
        "AV off never corrects drift",
        av.decide(false, 900, true, true, false) == AvSyncPolicy.Decision.NONE
    )
    check(
        "AV off releases a sync hold",
        av.decide(false, 300, true, false, false) == AvSyncPolicy.Decision.RESUME
    )
    check(
        "AV resume blocked while user seeking",
        av.decide(true, 0, true, false, true) == AvSyncPolicy.Decision.NONE
    )
    check(
        "AV resume blocked when not playWhenReady",
        av.decide(true, 0, false, false, false) == AvSyncPolicy.Decision.NONE
    )

    // ---------- EngineClockMath (#67: delay is native now) ----------
    // SwAudioSession.setDelayMs shifts the engine's master clock
    // itself, so position reporting returns clockMs() unmodified —
    // the only transform left is the >=0 display clamp (a negative
    // delay can read slightly negative at stream start).
    assertEq("Clock display passes clock through", 10_000L, EngineClockMath.displayPosMs(10_000))
    assertEq("Clock display clamps negative", 0L, EngineClockMath.displayPosMs(-50))

    // ---------- EngineAllowlist (Decoder HW+ audio codecs) ----------
    val allLabels = setOf(
        "AAC", "ALAC", "AMR NB", "AMR WB", "FLAC", "G.711 A-law",
        "G.711 μ-law", "GSM", "MP1", "MP2", "MP3", "Opus", "PCM",
        "QCELP", "Vorbis", "AC3", "EAC3", "DTS"
    )
    check(
        "Allowlist all enabled = no restriction",
        EngineAllowlist.allowedRawNames(allLabels) == null
    )
    check(
        "Allowlist superset = no restriction",
        EngineAllowlist.allowedRawNames(allLabels + "BOGUS") == null
    )
    val noDts = EngineAllowlist.allowedRawNames(allLabels - "DTS")
    check("Allowlist without DTS is restricted", noDts != null)
    check(
        "Allowlist without DTS excludes dca",
        noDts != null && "dca" !in noDts && "dts" !in noDts
    )
    check(
        "Allowlist without DTS keeps ac3",
        noDts != null && "ac3" in noDts
    )
    val noAc3 = EngineAllowlist.allowedRawNames(allLabels - "AC3")
    check(
        "Allowlist without AC3 excludes ac3 but keeps eac3",
        noAc3 != null && "ac3" !in noAc3 && "eac3" in noAc3
    )
    check(
        "Allowlist G.711 A-law maps to pcm_alaw",
        EngineAllowlist.allowedRawNames(setOf("G.711 A-law")) == setOf("pcm_alaw")
    )
    check(
        "Allowlist empty set blocks everything",
        EngineAllowlist.allowedRawNames(emptySet()) == emptySet<String>()
    )
    check(
        "Allowlist unknown labels ignored",
        EngineAllowlist.allowedRawNames(setOf("BOGUS")) == emptySet<String>()
    )

    // ---------- LockGesturePolicy (#48) ----------
    val lg = LockGesturePolicy
    check("Lock mode1 tap unlocks", lg.unlocks(0, LockGesturePolicy.Gesture.TAP))
    check("Lock mode1 long-press does not", !lg.unlocks(0, LockGesturePolicy.Gesture.LONG_PRESS))
    check("Lock mode2 long-press unlocks", lg.unlocks(1, LockGesturePolicy.Gesture.LONG_PRESS))
    check("Lock mode2 tap does not", !lg.unlocks(1, LockGesturePolicy.Gesture.TAP))
    check("Lock mode3 double-tap unlocks", lg.unlocks(2, LockGesturePolicy.Gesture.DOUBLE_TAP))
    check("Lock mode3 single tap does not", !lg.unlocks(2, LockGesturePolicy.Gesture.TAP))
    check("Lock unknown mode falls back to tap", lg.unlocks(99, LockGesturePolicy.Gesture.TAP))
    check(
        "Lock hints are distinct per mode",
        setOf(lg.hint(0), lg.hint(1), lg.hint(2)).size == 3
    )

    // ---------- Fmt ----------
    assertEq("Fmt zero", "0:00", Fmt.ms(0))
    assertEq("Fmt negative", "0:00", Fmt.ms(-5))
    assertEq("Fmt 59s", "0:59", Fmt.ms(59_000))
    assertEq("Fmt 61s", "1:01", Fmt.ms(61_000))
    assertEq("Fmt 1h", "1:00:00", Fmt.ms(3_600_000))
    assertEq("Fmt 1h1m1s", "1:01:01", Fmt.ms(3_661_000))
    assertEq("Fmt remaining", "-1:30", Fmt.remaining(90_000))
    assertEq("Fmt remaining clamps", "-0:00", Fmt.remaining(-1))

    // ---------- Fmt.msPadded (v0.3 MX seek-row format) ----------
    assertEq("Padded zero", "00:00", Fmt.msPadded(0))
    assertEq("Padded negative", "00:00", Fmt.msPadded(-5))
    assertEq("Padded 12s", "00:12", Fmt.msPadded(12_000))
    assertEq("Padded 59s", "00:59", Fmt.msPadded(59_000))
    assertEq("Padded 61s", "01:01", Fmt.msPadded(61_000))
    assertEq("Padded 26m05s", "26:05", Fmt.msPadded(1_565_000))
    assertEq("Padded 1h keeps H:MM:SS", "1:04:24", Fmt.msPadded(3_864_000))
    assertEq("Padded 1h1m1s", "1:01:01", Fmt.msPadded(3_661_000))

    // ---------- AspectCycle (v0.3 frame spec §2) ----------
    assertEq("Aspect order", listOf("fit", "stretch", "crop", "100"), AspectCycle.MODES)
    assertEq("Aspect fit->stretch", "stretch", AspectCycle.next("fit"))
    assertEq("Aspect stretch->crop", "crop", AspectCycle.next("stretch"))
    assertEq("Aspect crop->100", "100", AspectCycle.next("crop"))
    assertEq("Aspect 100 wraps to fit", "fit", AspectCycle.next("100"))
    assertEq("Aspect custom enters at fit", "fit", AspectCycle.next("custom"))
    assertEq("Aspect unknown enters at fit", "fit", AspectCycle.next("bogus"))
    assertEq("Aspect OSD fit", "FIT TO SCREEN", AspectCycle.osdText("fit"))
    assertEq("Aspect OSD stretch", "STRETCH", AspectCycle.osdText("stretch"))
    assertEq("Aspect OSD crop", "CROP", AspectCycle.osdText("crop"))
    assertEq("Aspect OSD 100", "100%", AspectCycle.osdText("100"))
    // A full cycle of four taps returns to the start mode.
    var mode = "crop"
    repeat(4) { mode = AspectCycle.next(mode) }
    assertEq("Aspect full cycle returns", "crop", mode)

    // ---------- SwFrameScheduler (SW video sync policy) ----------
    // EOF and hard error both end the stream.
    assertEq("SW sched EOF ends", SwFrameScheduler.Decision.ENDED,
        SwFrameScheduler.decide(SwFrameScheduler.PEEK_EOF, 5_000))
    assertEq("SW sched error ends", SwFrameScheduler.Decision.ENDED,
        SwFrameScheduler.decide(SwFrameScheduler.PEEK_ERROR, 5_000))
    // A frame due exactly at the clock shows; one already due shows.
    assertEq("SW sched due frame shows", SwFrameScheduler.Decision.SHOW,
        SwFrameScheduler.decide(5_000, 5_000))
    assertEq("SW sched slightly late shows", SwFrameScheduler.Decision.SHOW,
        SwFrameScheduler.decide(5_000, 5_000 + SwFrameScheduler.LATE_DROP_MS))
    // Past the late threshold the frame is skipped (catch-up beats drift).
    assertEq("SW sched very late skips", SwFrameScheduler.Decision.SKIP,
        SwFrameScheduler.decide(5_000, 5_000 + SwFrameScheduler.LATE_DROP_MS + 1))
    // A future frame waits.
    assertEq("SW sched future waits", SwFrameScheduler.Decision.WAIT,
        SwFrameScheduler.decide(5_040, 5_000))
    // Wait slices: until due, capped at MAX_WAIT_MS, at least 1.
    assertEq("SW sched wait until due", 4L, SwFrameScheduler.waitMs(5_004, 5_000))
    assertEq("SW sched wait capped", SwFrameScheduler.MAX_WAIT_MS,
        SwFrameScheduler.waitMs(60_000, 5_000))
    assertEq("SW sched wait floor", 1L, SwFrameScheduler.waitMs(5_000, 5_000))
    // Display size: only right-angle rotations swap the axes.
    assertEq("SW size rot 0", 1920 to 1080, SwFrameScheduler.displaySize(1920, 1080, 0))
    assertEq("SW size rot 90 swaps", 1080 to 1920, SwFrameScheduler.displaySize(1920, 1080, 90))
    assertEq("SW size rot 180 keeps", 1920 to 1080, SwFrameScheduler.displaySize(1920, 1080, 180))
    assertEq("SW size rot 270 swaps", 1080 to 1920, SwFrameScheduler.displaySize(1920, 1080, 270))
    assertEq("SW size unknown keeps", 1920 to 1080, SwFrameScheduler.displaySize(1920, 1080, 45))

    // ---------- SwVideoAllowlist (SW codec picker, label space) ----------
    assertEq("SW list h264 label", "H.264", SwVideoAllowlist.labelFor("h264", false))
    assertEq("SW list h264 10bit label", "H.264 10bits", SwVideoAllowlist.labelFor("h264", true))
    assertEq("SW list hevc label", "H.265", SwVideoAllowlist.labelFor("hevc", false))
    assertEq("SW list hevc 10bit label", "H.265 10bits", SwVideoAllowlist.labelFor("hevc", true))
    assertEq("SW list mpeg4 variant label", "MPEG-4", SwVideoAllowlist.labelFor("msmpeg4v3", false))
    assertEq("SW list vp9 label", "VP9", SwVideoAllowlist.labelFor("vp9", false))
    assertEq("SW list unmapped codec", null, SwVideoAllowlist.labelFor("av1", false))
    // No restriction set: everything opens.
    check("SW list null set allows", SwVideoAllowlist.allowed("h264", false, null))
    // A disabled label blocks exactly that label — 8-bit and 10-bit
    // H.264 are separate picker entries, enforced separately.
    val noH264 = Settings.OMX_VIDEO_CODEC_LIST.toSet() - "H.264"
    check("SW list disabled h264 blocked", !SwVideoAllowlist.allowed("h264", false, noH264))
    check("SW list h264 10bit still allowed", SwVideoAllowlist.allowed("h264", true, noH264))
    // Unmapped codecs are never blocked by the list.
    check("SW list unmapped never blocked", SwVideoAllowlist.allowed("av1", false, noH264))
    check("SW list null codec allowed", SwVideoAllowlist.allowed(null, false, noH264))

    println("PlayerLogicTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL TESTS PASSED")
}
