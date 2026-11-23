import com.ghostblade.player.Cleaner
import com.ghostblade.player.FileOps
import com.ghostblade.player.LibFmt
import com.ghostblade.player.Library
import com.ghostblade.player.PrivateEntry
import com.ghostblade.player.RecycleEntry
import com.ghostblade.player.Settings
import com.ghostblade.player.SortMode
import com.ghostblade.player.Store
import com.ghostblade.player.VideoItem

var failures = 0
var total = 0
fun check(name: String, actual: Any?, expected: Any?) {
    total++
    if (actual == expected) println("PASS  $name")
    else { failures++; println("FAIL  $name -> expected [$expected] but got [$actual]") }
}

private fun vid(
    id: Long, title: String, folder: String, dur: Long = 60_000L,
    size: Long = 1_000_000L, date: Long = 1000L, path: String = "/s/$folder/$title.mp4"
) = VideoItem(id, title, path, dur, size, folder, date, 1920, 1080)

fun main() {
    /* ---------- Library: folderOfPath ---------- */
    check("folderOfPath basic", Library.folderOfPath("/storage/emulated/0/Movies/a.mp4"), "Movies")
    check("folderOfPath nested", Library.folderOfPath("/storage/emulated/0/DCIM/Camera/v.mp4"), "Camera")
    check("folderOfPath root file", Library.folderOfPath("/a.mp4"), "")
    check("folderOfPath no slash", Library.folderOfPath("a.mp4"), "")

    /* ---------- Library: grouping ---------- */
    val items = listOf(
        vid(1, "Bravo", "Movies"),
        vid(2, "alpha", "camera"),
        vid(3, "Charlie", "Movies"),
        vid(4, "delta", "Downloads")
    )
    val groups = Library.groupByFolder(items)
    check("group count", groups.size, 3)
    check("group order case-insensitive", groups.map { it.name }, listOf("camera", "Downloads", "Movies"))
    check("group movies size", groups.first { it.name == "Movies" }.videos.size, 2)
    check("group keeps incoming order", groups.first { it.name == "Movies" }.videos.map { it.title },
        listOf("Bravo", "Charlie"))
    check("group empty", Library.groupByFolder(emptyList()).size, 0)
    check("group total duration", groups.first { it.name == "Movies" }.totalDurationMs, 120_000L)

    /* ---------- Library: sorting ---------- */
    val byName = Library.sortVideos(items, SortMode.NAME, ascending = true)
    check("sort name asc", byName.map { it.title }, listOf("alpha", "Bravo", "Charlie", "delta"))
    check("sort name desc", Library.sortVideos(items, SortMode.NAME, ascending = false).map { it.title },
        listOf("delta", "Charlie", "Bravo", "alpha"))
    val dated = listOf(
        vid(1, "old", "F", date = 100L), vid(2, "new", "F", date = 300L), vid(3, "mid", "F", date = 200L)
    )
    check("sort date desc (newest first)", Library.sortVideos(dated, SortMode.DATE, ascending = false).map { it.title },
        listOf("new", "mid", "old"))
    check("sort date asc", Library.sortVideos(dated, SortMode.DATE, ascending = true).map { it.title },
        listOf("old", "mid", "new"))
    val sized = listOf(
        vid(1, "a", "F", dur = 5_000L, size = 300L),
        vid(2, "b", "F", dur = 9_000L, size = 100L),
        vid(3, "c", "F", dur = 1_000L, size = 200L)
    )
    check("sort duration asc", Library.sortVideos(sized, SortMode.DURATION, true).map { it.title },
        listOf("c", "a", "b"))
    check("sort size desc", Library.sortVideos(sized, SortMode.SIZE, false).map { it.title },
        listOf("a", "c", "b"))
    check("sort does not mutate input", items.map { it.title }, listOf("Bravo", "alpha", "Charlie", "delta"))

    /* ---------- Library: search filter ---------- */
    check("filter blank returns all", Library.filterByTitle(items, "  ").size, 4)
    check("filter case-insensitive", Library.filterByTitle(items, "ALPHA").map { it.title }, listOf("alpha"))
    check("filter substring", Library.filterByTitle(items, "har").map { it.title }, listOf("Charlie"))
    check("filter no match", Library.filterByTitle(items, "zzz").size, 0)

    /* ---------- Library: watched fraction ---------- */
    check("fraction half", Library.watchedFraction(100_000L, 50_000L), 0.5f)
    check("fraction zero duration", Library.watchedFraction(0L, 50_000L), 0f)
    check("fraction clamps over 1", Library.watchedFraction(100_000L, 250_000L), 1f)
    check("fraction zero pos", Library.watchedFraction(100_000L, 0L), 0f)

    /* ---------- Fmt ---------- */
    check("fmt zero", LibFmt.duration(0L), "0:00")
    check("fmt 59s", LibFmt.duration(59_000L), "0:59")
    check("fmt 60s", LibFmt.duration(60_000L), "1:00")
    check("fmt 12:34", LibFmt.duration(754_000L), "12:34")
    check("fmt 1h", LibFmt.duration(3_600_000L), "1:00:00")
    check("fmt 1:23:45", LibFmt.duration(5_025_000L), "1:23:45")
    check("fmt sub-second truncates", LibFmt.duration(61_999L), "1:01")
    check("fmt negative clamps", LibFmt.duration(-5_000L), "0:00")
    check("fmt remaining", LibFmt.remaining(754_000L, 60_000L), "▶ 11:34 left")
    check("fmt remaining over-watched", LibFmt.remaining(60_000L, 90_000L), "▶ 0:00 left")
    check("fmt size B", LibFmt.size(512L), "512 B")
    check("fmt size KB", LibFmt.size(1536L), "1.5 KB")
    check("fmt size MB", LibFmt.size(23_400_000L), "22.3 MB")
    check("fmt size GB", LibFmt.size(1_288_490_188L), "1.2 GB")
    check("fmt size negative", LibFmt.size(-1L), "0 B")
    check("fmt resolution", LibFmt.resolution(1920, 1080), "1920 × 1080")
    check("fmt resolution unknown", LibFmt.resolution(0, 1080), "—")

    /* ---------- Store: in-memory semantics ---------- */
    Store.resetForTest()
    check("resume default 0", Store.resumePos("/a.mp4"), 0L)
    check("finished default false", Store.isFinished("/a.mp4"), false)
    Store.saveResumePos("/a.mp4", 42_000L)
    check("resume saved", Store.resumePos("/a.mp4"), 42_000L)
    Store.markFinished("/a.mp4")
    check("finished after mark", Store.isFinished("/a.mp4"), true)
    check("markFinished drops position", Store.resumePos("/a.mp4"), 0L)
    Store.saveResumePos("/a.mp4", 10_000L)
    check("re-watch clears finished", Store.isFinished("/a.mp4"), false)
    check("re-watch position kept", Store.resumePos("/a.mp4"), 10_000L)
    Store.saveResumePos("/a.mp4", 0L)
    check("save 0 clears position", Store.resumePos("/a.mp4"), 0L)

    /* ---------- Store: JSON round-trip ---------- */
    Store.resetForTest()
    Store.saveResumePos("/storage/emulated/0/Movies/a.mp4", 754_000L)
    Store.saveResumePos("/storage/emulated/0/Movies/it's a \"quote\" \\ test.mp4", 5_000L)
    Store.markFinished("/storage/emulated/0/Downloads/done.mp4")
    Store.brightness = 0.7f
    Store.subtitleTextSizeSp = 22.5f
    Store.lastFolder = "Movies"
    val json = Store.toJsonString()

    Store.resetForTest()
    check("round-trip loads", Store.loadFromJsonString(json), true)
    check("round-trip position", Store.resumePos("/storage/emulated/0/Movies/a.mp4"), 754_000L)
    check("round-trip escaped path", Store.resumePos("/storage/emulated/0/Movies/it's a \"quote\" \\ test.mp4"), 5_000L)
    check("round-trip finished", Store.isFinished("/storage/emulated/0/Downloads/done.mp4"), true)
    check("round-trip brightness", Store.brightness, 0.7f)
    check("round-trip subtitle size", Store.subtitleTextSizeSp, 22.5f)
    check("round-trip last folder", Store.lastFolder, "Movies")
    check("round-trip stable bytes", Store.toJsonString(), json)

    // Defaults survive a minimal file.
    Store.resetForTest()
    check("minimal file loads", Store.loadFromJsonString("{}"), true)
    check("default brightness", Store.brightness, -1f)
    check("default subtitle size", Store.subtitleTextSizeSp, 18f)
    check("default last folder", Store.lastFolder, null)

    // Malformed / wrong-shape input is rejected and state is untouched.
    Store.resetForTest()
    Store.saveResumePos("/keep.mp4", 1234L)
    check("garbage rejected", Store.loadFromJsonString("not json"), false)
    check("truncated rejected", Store.loadFromJsonString("{\"positions\":{"), false)
    check("array root rejected", Store.loadFromJsonString("[1,2,3]"), false)
    check("positions wrong type rejected", Store.loadFromJsonString("{\"positions\": 5}"), false)
    check("finished wrong type rejected", Store.loadFromJsonString("{\"finished\": \"x\"}"), false)
    check("state untouched after rejects", Store.resumePos("/keep.mp4"), 1234L)

    /* ---------- Settings: defaults vs spec (MX-extracted) ---------- */
    check("default resume ask", Settings.defaultOf(Settings.RESUME), "ask")
    check("default resume only first", Settings.defaultOf(Settings.RESUME_ONLY_FIRST), "false")
    check("default list theme system", Settings.defaultOf(Settings.LIST_THEME), "system")
    check("default playback theme", Settings.defaultOf(Settings.PLAYBACK_THEME), "black_blueAccent")
    check("default NEW period 7d", Settings.defaultOf(Settings.NEW_TAGGED_PERIOD), "7")
    check("default speed 100", Settings.defaultOf(Settings.DEFAULT_PLAYBACK_SPEED), "100")
    check("default sticky pip", Settings.defaultOf(Settings.STICKY_VIDEO), "pip")
    check("default colour format", Settings.defaultOf(Settings.COLOR_FORMAT), "rgb565")
    check("default decoder mode", Settings.defaultOf(Settings.DECODER_MODE), "default")
    check("default omx local", Settings.defaultOf(Settings.OMX_DECODER_LOCAL), "true")
    check("default subtitle scale", Settings.defaultOf(Settings.SUBTITLE_SCALE), "100")
    check("default respect nomedia", Settings.defaultOf(Settings.RESPECT_NOMEDIA), "true")
    check("default delete subs together", Settings.defaultOf(Settings.DELETE_SUBTITLE_TOGETHER), "true")
    check("default allow edits", Settings.defaultOf(Settings.ALLOW_EDITS), "true")
    check("default floating button", Settings.defaultOf(Settings.LIST_FLOATING_BUTTON), "true")
    check("playback themes count", Settings.PLAYBACK_THEMES.size, 22)
    check("list themes count", Settings.LIST_THEMES.size, 26)
    check("omx video codecs count", Settings.OMX_VIDEO_CODEC_LIST.size, 13)
    check("omx audio codecs count", Settings.OMX_AUDIO_CODEC_LIST.size, 18)
    check("shortcuts count", Settings.SHORTCUTS.size, 15)
    check("video extensions count", Settings.VIDEO_EXTENSIONS.size, 19)
    check("theme white is light", Settings.themeColors("white").dark, false)
    check("theme black_blueAccent accent", Settings.themeColors("black_blueAccent").accent, 0xFF3C8CF0.toInt())
    check("theme dark_navy2 page", Settings.themeColors("dark_navy2").pageBg, 0xFF16202B.toInt())
    check("theme dark chrome", Settings.themeColors("black").dark, true)

    /* ---------- Store: settings map ---------- */
    Store.resetForTest()
    check("setting falls back to default", Store.stringSetting(Settings.RESUME), "ask")
    Store.setStringSetting(Settings.RESUME, "resume")
    check("setting set/get", Store.stringSetting(Settings.RESUME), "resume")
    Store.setBoolSetting(Settings.SHOW_HIDDEN, true)
    check("bool setting", Store.boolSetting(Settings.SHOW_HIDDEN), true)
    Store.setIntSetting(Settings.NEW_TAGGED_PERIOD, 3)
    check("int setting", Store.intSetting(Settings.NEW_TAGGED_PERIOD), 3)
    Store.setStringSetSetting(Settings.LIST_FIELDS, setOf("duration", "path"))
    check("set setting", Store.stringSetSetting(Settings.LIST_FIELDS), setOf("duration", "path"))
    Store.setStringListSetting(Settings.CONTROLS_QUICK_SHORTCUTS, listOf("mute", "loop"))
    check("list setting", Store.stringListSetting(Settings.CONTROLS_QUICK_SHORTCUTS), listOf("mute", "loop"))
    val settingsJson = Store.toJsonString()
    Store.resetForTest()
    check("settings round-trip loads", Store.loadFromJsonString(settingsJson), true)
    check("settings round-trip value", Store.stringSetting(Settings.RESUME), "resume")
    check("settings round-trip set", Store.stringSetSetting(Settings.LIST_FIELDS), setOf("duration", "path"))
    // Storing a default removes the override but reads the same.
    Store.setStringSetting(Settings.RESUME, "ask")
    check("setting back to default reads default", Store.stringSetting(Settings.RESUME), "ask")
    // resetSettings keeps user data (positions), clears settings.
    Store.saveResumePos("/keep.mp4", 777L)
    Store.setStringSetting(Settings.RESUME, "startover")
    Store.resetSettings()
    check("resetSettings clears setting", Store.stringSetting(Settings.RESUME), "ask")
    check("resetSettings keeps position", Store.resumePos("/keep.mp4"), 777L)

    /* ---------- Store: history ---------- */
    Store.resetForTest()
    Store.recordPlayed("/a.mp4", 1_000L)
    Store.recordPlayed("/b.mp4", 2_000L)
    Store.recordPlayed("/a.mp4", 3_000L)
    check("last played path", Store.lastPlayedPath(), "/a.mp4")
    check("last played at", Store.lastPlayedAt("/a.mp4"), 3_000L)
    Store.saveResumePos("/b.mp4", 5_000L)
    val records = Store.historyRecords()
    check("history newest first", records.map { it.path }, listOf("/a.mp4", "/b.mp4"))
    check("history resume carried", records.first { it.path == "/b.mp4" }.lastPosMs, 5_000L)
    Store.setStringSetting(Settings.RESUME, "resume")
    Store.clearHistoryData()
    check("clear history empties records", Store.historyRecords().size, 0)
    check("clear history keeps settings", Store.stringSetting(Settings.RESUME), "resume")
    check("clear history clears last played", Store.lastPlayedPath(), "")

    /* ---------- Store: search history ---------- */
    Store.resetForTest()
    Store.addSearchQuery("  cats ")
    Store.addSearchQuery("dogs")
    Store.addSearchQuery("cats")
    check("search history dedupe+order", Store.searchHistory(), listOf("cats", "dogs"))
    for (i in 1..25) Store.addSearchQuery("q$i")
    check("search history cap 20", Store.searchHistory().size, 20)
    check("search history newest first", Store.searchHistory().first(), "q25")

    /* ---------- Store: playlists / hidden / favourites / queue ---------- */
    Store.resetForTest()
    check("playlist create", Store.createPlaylist("Favs"), true)
    check("playlist create duplicate", Store.createPlaylist("Favs"), false)
    check("playlist add dedupe count", Store.addToPlaylist("Favs", listOf("/a.mp4", "/b.mp4", "/a.mp4")), 2)
    check("playlist contents", Store.playlist("Favs"), listOf("/a.mp4", "/b.mp4"))
    Store.removeFromPlaylist("Favs", "/a.mp4")
    check("playlist remove", Store.playlist("Favs"), listOf("/b.mp4"))
    check("playlist rename", Store.renamePlaylist("Favs", "Best"), true)
    check("playlist names", Store.playlistNames(), listOf("Best"))
    Store.deletePlaylist("Best")
    check("playlist deleted", Store.playlistNames().size, 0)
    Store.setHidden("/h.mp4", true)
    check("hidden set", Store.isHidden("/h.mp4"), true)
    Store.setFavourite("/f.mp4", true)
    check("favourite set", Store.isFavourite("/f.mp4"), true)
    Store.enqueueLater("/1.mp4")
    Store.enqueueNext("/0.mp4")
    check("queue order next-first", Store.playQueue(), listOf("/0.mp4", "/1.mp4"))
    Store.clearPlayQueue()
    check("queue cleared", Store.playQueue().size, 0)
    Store.setFileSelection("/a.mp4", "decoder", "4")
    check("file selection", Store.fileSelection("/a.mp4", "decoder"), "4")

    /* ---------- Store: private + recycle records round-trip ---------- */
    Store.resetForTest()
    Store.addPrivateEntry(PrivateEntry("/s/Movies/secret.mp4", "1_secret.mp4", "secret", 10L, 100L))
    Store.addRecycleEntry(RecycleEntry("9", "/s/Movies/old.mp4", "9_old.mp4", "old", 20L, 200L))
    Store.setPrivatePinHash("abc123")
    val binJson = Store.toJsonString()
    Store.resetForTest()
    check("bin round-trip loads", Store.loadFromJsonString(binJson), true)
    check("private entry round-trip", Store.privateEntries().size, 1)
    check("private entry path", Store.privateEntries().first().origPath, "/s/Movies/secret.mp4")
    check("recycle entry round-trip", Store.recycleEntries().size, 1)
    check("pin hash round-trip", Store.privatePinHash(), "abc123")
    Store.removePrivateEntry("1_secret.mp4")
    check("private entry removed", Store.privateEntries().size, 0)
    Store.removeRecycleEntry("9")
    check("recycle entry removed", Store.recycleEntries().size, 0)

    /* ---------- Library: new sort modes ---------- */
    val rich = listOf(
        VideoItem(1, "low", "/s/F/low.mkv", 60_000L, 100L, "F", 100L, 640, 360, 0L, 24f, ""),
        VideoItem(2, "high", "/s/F/high.mp4", 60_000L, 100L, "F", 100L, 1920, 1080, 0L, 60f, ""),
        VideoItem(3, "mid", "/s/F/mid.avi", 60_000L, 100L, "F", 100L, 1280, 720, 0L, 30f, "")
    )
    check("sort resolution asc", Library.sortVideos(rich, SortMode.RESOLUTION, true).map { it.title },
        listOf("low", "mid", "high"))
    check("sort framerate desc", Library.sortVideos(rich, SortMode.FRAMERATE, false).map { it.title },
        listOf("high", "mid", "low"))
    check("sort type asc (extension)", Library.sortVideos(rich, SortMode.TYPE, true).map { it.title },
        listOf("mid", "low", "high"))
    check("sort location asc (path)", Library.sortVideos(rich, SortMode.LOCATION, true).map { it.title },
        listOf("high", "low", "mid"))
    check("sort status unfinished first",
        Library.sortVideos(rich, SortMode.STATUS, true, setOf("/s/F/high.mp4")).map { it.title },
        listOf("low", "mid", "high"))
    check("sort default keeps order", Library.sortVideos(rich, SortMode.DEFAULT, true).map { it.title },
        listOf("low", "high", "mid"))
    check("sortModeForField title", Library.sortModeForField("title"), SortMode.NAME)
    check("sortModeForField framerate", Library.sortModeForField("framerate"), SortMode.FRAMERATE)
    check("sortModeForField bogus", Library.sortModeForField("bogus"), SortMode.DEFAULT)

    /* ---------- Library: scan filters / visibility / NEW tag ---------- */
    val scanItems = listOf(
        vid(1, "keep", "Movies"),
        vid(2, "other-folder", "Downloads"),
        VideoItem(3, "clip", "/s/Movies/clip.mkv", 1_000L, 1L, "Movies", 1L, 0, 0),
        VideoItem(4, "dot", "/s/Movies/.hidden.mp4", 1_000L, 1L, "Movies", 1L, 0, 0)
    )
    check("scan filter folder", Library.filterByScanSettings(
        scanItems, setOf("/s/Movies"), emptySet(), true).size, 3)
    check("scan filter extension", Library.filterByScanSettings(
        scanItems, emptySet(), setOf("mp4"), true).size, 3)
    check("scan filter dotfiles off", Library.filterByScanSettings(
        scanItems, emptySet(), emptySet(), false).size, 3)
    check("visible hides app-hidden", Library.visibleItems(
        scanItems, setOf("/s/Movies/keep.mp4"), emptySet(), false).size, 3)
    check("visible shows app-hidden when on", Library.visibleItems(
        scanItems, setOf("/s/Movies/keep.mp4"), emptySet(), true).size, 4)
    check("visible never shows private", Library.visibleItems(
        scanItems, emptySet(), setOf("/s/Movies/keep.mp4"), true).size, 3)
    val nowSec = 1_700_000_000L
    check("new tag within period", Library.isNewTag(nowSec - 3 * 86_400L, 7, nowSec), true)
    check("new tag outside period", Library.isNewTag(nowSec - 9 * 86_400L, 7, nowSec), false)
    check("new tag unknown date", Library.isNewTag(0L, 7, nowSec), false)
    check("new tag period off", Library.isNewTag(nowSec, 0, nowSec), false)
    check("extensionOf", Library.extensionOf("/s/F/Clip.MKV"), "mkv")
    check("extensionOf none", Library.extensionOf("/s/F/clip"), "")
    check("dirOfPath", Library.dirOfPath("/s/F/a.mp4"), "/s/F")

    /* ---------- FileOps: pure helpers ---------- */
    check("rename blank", FileOps.renameError("  ") != null, true)
    check("rename separator", FileOps.renameError("a/b.mp4") != null, true)
    check("rename ok", FileOps.renameError("new name.mp4"), null)
    check("rename keeps extension", FileOps.resolvedRename("old.mp4", "new"), "new.mp4")
    check("rename explicit extension", FileOps.resolvedRename("old.mp4", "new.mkv"), "new.mkv")
    check("sidecars found", FileOps.sidecarSubtitles(
        listOf("a.mp4", "a.srt", "a.en.ass", "b.srt", "a.txt"), "a.mp4"),
        listOf("a.srt", "a.txt"))
    check("unique name free", FileOps.uniqueName(setOf("b.mp4"), "a.mp4"), "a.mp4")
    check("unique name collision", FileOps.uniqueName(setOf("a.mp4", "a (1).mp4"), "a.mp4"), "a (2).mp4")
    check("pin hash deterministic", FileOps.pinHash("1234") == FileOps.pinHash("1234"), true)
    check("pin hash differs", FileOps.pinHash("1234") != FileOps.pinHash("4321"), true)
    check("pin hash hex length", FileOps.pinHash("1234").length, 64)
    check("stored name sanitised", FileOps.storedName("7", "my file!.mp4"), "7_my_file_.mp4")
    check("dateTime zero", LibFmt.dateTime(0L), "—")

    /* ---------- Cleaner (fix pass, spec #108) ---------- */
    val dupA = VideoItem(11, "movie", "/s/Movies/movie.mp4", 60_000L, 5_000L, "Movies", 1L, 0, 0)
    val dupB = VideoItem(12, "movie", "/s/Downloads/movie.mp4", 60_000L, 5_000L, "Downloads", 1L, 0, 0)
    val dupC = VideoItem(13, "Movie", "/s/Backup/Movie.mp4", 60_000L, 5_000L, "Backup", 1L, 0, 0)
    val sameNameDiffSize = VideoItem(14, "movie", "/s/Other/movie.mp4", 60_000L, 9_000L, "Other", 1L, 0, 0)
    val zeroA = VideoItem(15, "junk", "/s/Movies/junk.mp4", 0L, 0L, "Movies", 1L, 0, 0)
    val bigA = VideoItem(16, "big", "/s/Movies/big.mp4", 60_000L,
        Cleaner.LARGE_FILE_THRESHOLD_BYTES, "Movies", 1L, 0, 0)
    val cleanItems = listOf(dupA, dupB, dupC, sameNameDiffSize, zeroA, bigA)
    val dupGroups = Cleaner.duplicateGroups(cleanItems)
    check("cleaner dup group count", dupGroups.size, 1)
    check("cleaner dup group size", dupGroups.first().size, 3)
    check("cleaner dup keeper first by path", dupGroups.first().first().path, "/s/Backup/Movie.mp4")
    check("cleaner wasted", Cleaner.wastedBytes(dupGroups.first()), 10_000L)
    check("cleaner wasted single", Cleaner.wastedBytes(listOf(dupA)), 0L)
    check("cleaner large threshold inclusive", Cleaner.largeFiles(cleanItems).map { it.title },
        listOf("big"))
    check("cleaner large custom threshold", Cleaner.largeFiles(cleanItems, 4_000L).size, 5)
    check("cleaner empty files", Cleaner.emptyFiles(cleanItems).map { it.title }, listOf("junk"))
    check("cleaner empty folders", Cleaner.emptyFolders(
        listOf("/s/Empty", "/s/Movies"), cleanItems), listOf("/s/Empty"))

    /* ---------- USB / widget helpers (fix pass, spec #31 / #106) ---------- */
    check("volume root removable", Library.volumeRootOf(
        "/storage/1234-5678/Android/data/com.ghostblade.player/files"), "/storage/1234-5678")
    check("volume root primary", Library.volumeRootOf(
        "/storage/emulated/0/Android/data/com.ghostblade.player/files"), "/storage/emulated/0")
    check("volume root not app dir", Library.volumeRootOf("/storage/emulated/0/Movies"), null)
    check("tree id primary", Library.pathFromTreeDocumentId(
        "primary:Movies/a.mp4", "/storage/emulated/0"), "/storage/emulated/0/Movies/a.mp4")
    check("tree id uuid", Library.pathFromTreeDocumentId(
        "1234-5678:Movies", "/storage/emulated/0"), "/storage/1234-5678/Movies")
    check("tree id volume root", Library.pathFromTreeDocumentId(
        "primary:", "/storage/emulated/0"), "/storage/emulated/0")
    check("tree id raw", Library.pathFromTreeDocumentId(
        "raw:/storage/1234-5678/Movies", "/storage/emulated/0"), "/storage/1234-5678/Movies")
    check("tree id foreign provider", Library.pathFromTreeDocumentId(
        "downloads/42", "/storage/emulated/0"), null)
    check("video filename match", Library.isVideoFileName("Clip.MKV", setOf("mkv")), true)
    check("video filename miss", Library.isVideoFileName("notes.txt", setOf("mkv")), false)
    check("wrap next", Library.wrapIndex(0, 1, 3), 1)
    check("wrap forward end", Library.wrapIndex(2, 1, 3), 0)
    check("wrap backward start", Library.wrapIndex(0, -1, 3), 2)
    check("wrap empty queue", Library.wrapIndex(0, 1, 0), 0)

    /* ---------- Store: activity records export/import (fix pass, spec #100) ---------- */
    check("default last music queue", Settings.defaultOf(Settings.LAST_MUSIC_QUEUE), "[]")
    Store.resetForTest()
    Store.saveResumePos("/s/Movies/a.mp4", 42_000L)
    Store.markFinished("/s/Downloads/done.mp4")
    Store.recordPlayed("/s/Movies/a.mp4", 1_700_000_000_000L)
    Store.addSearchQuery("hello")
    Store.createPlaylist("Favs")
    Store.addToPlaylist("Favs", listOf("/s/Movies/a.mp4"))
    Store.setHidden("/s/Movies/secret.mp4", true)
    Store.setFavourite("/s/Movies/a.mp4", true)
    Store.setFileSelection("/s/Movies/a.mp4", "decoder", "hwplus")
    Store.enqueueLater("/s/Movies/next.mp4")
    Store.setRawSetting(Settings.LIST_THEME, "black")
    val activityJson = Store.activityJson()
    Store.resetForTest()
    check("activity import ok", Store.applyActivityJson(activityJson), true)
    check("activity resume restored", Store.resumePos("/s/Movies/a.mp4"), 42_000L)
    check("activity finished restored", Store.isFinished("/s/Downloads/done.mp4"), true)
    check("activity last played restored", Store.lastPlayedAt("/s/Movies/a.mp4"),
        1_700_000_000_000L)
    check("activity last-played pointer", Store.lastPlayedPath(), "/s/Movies/a.mp4")
    check("activity search restored", Store.searchHistory(), listOf("hello"))
    check("activity playlist restored", Store.playlist("Favs"), listOf("/s/Movies/a.mp4"))
    check("activity hidden restored", Store.isHidden("/s/Movies/secret.mp4"), true)
    check("activity favourite restored", Store.isFavourite("/s/Movies/a.mp4"), true)
    check("activity selection restored",
        Store.fileSelection("/s/Movies/a.mp4", "decoder"), "hwplus")
    check("activity queue restored", Store.playQueue(), listOf("/s/Movies/next.mp4"))
    check("activity leaves settings alone", Store.stringSetting(Settings.LIST_THEME), "system")
    check("activity malformed rejected", Store.applyActivityJson("{nope"), false)
    check("activity intact after reject", Store.resumePos("/s/Movies/a.mp4"), 42_000L)
    Store.resetForTest()

    println("----")
    println("$total checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
