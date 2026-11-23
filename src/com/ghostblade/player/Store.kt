package com.ghostblade.player

import android.content.Context
import java.io.File

/** Playback state + settings for GhostBlade Player, persisted as
 *  JSON in the app-private files dir (`ghostplayer-state.json`).
 *  Fully offline: this file is the only storage, nothing leaves the
 *  device.
 *
 *  v0.1 SPEC contract (unchanged): [init], [resumePos],
 *  [saveResumePos], [isFinished], [markFinished], [brightness],
 *  [subtitleTextSizeSp], [lastFolder].
 *
 *  v0.2 additions:
 *  - a general settings map ([rawSetting] + typed accessors) keyed
 *    by [Settings] constants, falling back to [Settings.DEFAULTS];
 *  - playback history ([recordPlayed], [historyRecords],
 *    [lastPlayedAt]);
 *  - search history, video playlists, hidden/favourite path sets,
 *    per-file remembered selections, the play-next/later queue;
 *  - private-folder records ([PrivateEntry], PIN hash) and
 *    recycle-bin records ([RecycleEntry]) — the files themselves
 *    live in app-private dirs, managed by FileOps.
 *
 *  State semantics (v0.1, unchanged):
 *  - [saveResumePos] with posMs > 0 records the position AND clears
 *    any finished flag for that path. posMs <= 0 clears the position.
 *  - [markFinished] records the path as finished AND drops its
 *    stored position.
 *
 *  The JSON codec below is small pure Kotlin (no org.json, no
 *  Android), so [toJsonString] / [loadFromJsonString] run on a plain
 *  JVM — that is what tests/TestMain.kt exercises.
 *  [loadFromJsonString] returns false on malformed input and leaves
 *  the live state completely untouched. Unknown keys are ignored and
 *  missing keys keep defaults, so v0.1 files load into v0.2 cleanly.
 */
object Store {

    private const val FILE_NAME = "ghostplayer-state.json"
    private const val VERSION = 1

    private var file: File? = null
    private var loading = false

    private val positions = mutableMapOf<String, Long>()
    private val finished = mutableSetOf<String>()
    private val settings = mutableMapOf<String, String>()
    private val lastPlayedAt = mutableMapOf<String, Long>()
    private val searchHistory = mutableListOf<String>()
    private val playlists = mutableMapOf<String, MutableList<String>>()
    private val hiddenPaths = mutableSetOf<String>()
    private val favouritePaths = mutableSetOf<String>()
    private val selections = mutableMapOf<String, MutableMap<String, String>>()
    private val playQueue = mutableListOf<String>()
    private var privatePinHash: String = ""
    private val privateEntries = mutableListOf<PrivateEntry>()
    private val recycleEntries = mutableListOf<RecycleEntry>()

    /** Loads state from the app-private dir. Safe to call again —
     *  it simply re-reads the file. */
    fun init(ctx: Context) {
        file = File(ctx.filesDir, FILE_NAME)
        val f = file
        if (f != null && f.exists()) {
            try {
                loadFromJsonString(f.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                /* unreadable state file: start clean rather than crash */
            }
        }
    }

    /* ---------- resume positions (v0.1 contract) ---------- */

    /** Stored resume position for [path] in ms, 0 when none. */
    fun resumePos(path: String): Long = positions[path] ?: 0L

    /** Records the resume position (see class doc for semantics)
     *  and persists immediately. */
    fun saveResumePos(path: String, posMs: Long) {
        if (posMs > 0L) {
            positions[path] = posMs
            finished.remove(path)
        } else {
            positions.remove(path)
        }
        persist()
    }

    fun isFinished(path: String): Boolean = finished.contains(path)

    /** Marks [path] finished and drops its resume position. */
    fun markFinished(path: String) {
        finished.add(path)
        positions.remove(path)
        persist()
    }

    /* ---------- v0.1 settings properties ---------- */

    /** -1f = unset (follow system); otherwise 0f..1f. */
    var brightness: Float = -1f
        set(value) {
            field = value
            persist()
        }

    var subtitleTextSizeSp: Float = 18f
        set(value) {
            field = value
            persist()
        }

    var lastFolder: String? = null
        set(value) {
            field = value
            persist()
        }

    /* ---------- v0.2 general settings map ---------- */

    /** Raw stored value for [key], or its spec default. */
    fun rawSetting(key: String): String = settings[key] ?: Settings.defaultOf(key)

    /** Stores a raw value; storing the default removes the entry so
     *  the file stays minimal and [resetSettings] is just a clear. */
    fun setRawSetting(key: String, value: String) {
        if (value == Settings.defaultOf(key)) settings.remove(key) else settings[key] = value
        persist()
    }

    fun boolSetting(key: String): Boolean = rawSetting(key) == "true"
    fun setBoolSetting(key: String, value: Boolean) = setRawSetting(key, value.toString())

    fun intSetting(key: String): Int = rawSetting(key).toIntOrNull() ?: 0
    fun setIntSetting(key: String, value: Int) = setRawSetting(key, value.toString())

    fun floatSetting(key: String): Float = rawSetting(key).toFloatOrNull() ?: 0f
    fun setFloatSetting(key: String, value: Float) = setRawSetting(key, value.toString())

    fun stringSetting(key: String): String = rawSetting(key)
    fun setStringSetting(key: String, value: String) = setRawSetting(key, value)

    /** String set stored as a JSON array; empty set = "no
     *  restriction" for the scan keys (see Settings docs). */
    fun stringSetSetting(key: String): Set<String> = parseStringArray(rawSetting(key)).toSet()
    fun setStringSetSetting(key: String, value: Set<String>) =
        setRawSetting(key, encodeStringArray(value.sorted()))

    fun stringListSetting(key: String): List<String> = parseStringArray(rawSetting(key))
    fun setStringListSetting(key: String, value: List<String>) =
        setRawSetting(key, encodeStringArray(value))

    /** Restores every settings-map entry to its spec default.
     *  Playback state (positions, history, playlists) is kept —
     *  MX's "Reset settings" resets settings, not user data. */
    fun resetSettings() {
        settings.clear()
        persist()
    }

    /* ---------- playback history ---------- */

    /** Records that [path] was played at [atMs] (epoch millis) and
     *  makes it the library's last-played media. */
    fun recordPlayed(path: String, atMs: Long = System.currentTimeMillis()) {
        lastPlayedAt[path] = atMs
        setRawSettingSilent(Settings.LAST_PLAYED_PATH, path)
        persist()
    }

    fun lastPlayedAt(path: String): Long = lastPlayedAt[path] ?: 0L

    fun lastPlayedPath(): String = stringSetting(Settings.LAST_PLAYED_PATH)

    /** History rows (most recent first) joining positions, finished
     *  flags and last-played times — the §2.9 "Playback history"
     *  group and the History surface read from here. */
    fun historyRecords(): List<HistoryRecord> {
        val paths = (positions.keys + finished + lastPlayedAt.keys).toSet()
        return paths.map { p ->
            HistoryRecord(p, resumePos(p), lastPlayedAt[p] ?: 0L, isFinished(p))
        }.sortedWith(compareByDescending<HistoryRecord> { it.lastPlayedAtMs }.thenBy { it.path })
    }

    /** MX "Clear history": playback records + search history. */
    fun clearHistoryData() {
        positions.clear()
        finished.clear()
        lastPlayedAt.clear()
        searchHistory.clear()
        setRawSettingSilent(Settings.LAST_PLAYED_PATH, "")
        persist()
    }

    /* ---------- search history ---------- */

    fun searchHistory(): List<String> = searchHistory.toList()

    fun addSearchQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchHistory.remove(q)
        searchHistory.add(0, q)
        while (searchHistory.size > 20) searchHistory.removeAt(searchHistory.size - 1)
        persist()
    }

    /* ---------- playlists ---------- */

    fun playlistNames(): List<String> = playlists.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun playlist(name: String): List<String> = playlists[name]?.toList() ?: emptyList()

    /** Creates an empty playlist; returns false if the name is
     *  blank or already taken. */
    fun createPlaylist(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || playlists.containsKey(n)) return false
        playlists[n] = mutableListOf()
        persist()
        return true
    }

    fun deletePlaylist(name: String) {
        playlists.remove(name)
        persist()
    }

    fun renamePlaylist(old: String, new: String): Boolean {
        val n = new.trim()
        val items = playlists[old] ?: return false
        if (n.isEmpty() || playlists.containsKey(n)) return false
        playlists.remove(old)
        playlists[n] = items
        persist()
        return true
    }

    /** Adds [paths] to a playlist (created on demand), skipping
     *  duplicates; returns how many were actually added — the
     *  "Add to playlist" toast reports this count. */
    fun addToPlaylist(name: String, paths: List<String>): Int {
        val list = playlists.getOrPut(name) { mutableListOf() }
        var added = 0
        for (p in paths) if (!list.contains(p)) {
            list.add(p)
            added++
        }
        persist()
        return added
    }

    fun removeFromPlaylist(name: String, path: String) {
        playlists[name]?.remove(path)
        persist()
    }

    /* ---------- hidden / favourites ---------- */

    fun isHidden(path: String): Boolean = hiddenPaths.contains(path)
    fun hiddenPaths(): Set<String> = hiddenPaths.toSet()
    fun setHidden(path: String, hidden: Boolean) {
        if (hidden) hiddenPaths.add(path) else hiddenPaths.remove(path)
        persist()
    }

    fun isFavourite(path: String): Boolean = favouritePaths.contains(path)
    fun favouritePaths(): Set<String> = favouritePaths.toSet()
    fun setFavourite(path: String, favourite: Boolean) {
        if (favourite) favouritePaths.add(path) else favouritePaths.remove(path)
        persist()
    }

    /* ---------- per-file remembered selections ---------- */

    /** Remembered selection ("audio" / "subtitle" / "decoder") for
     *  [path], or null. Read by the player when
     *  [Settings.REMEMBER_SELECTIONS] is on. */
    fun fileSelection(path: String, kind: String): String? = selections[path]?.get(kind)

    fun setFileSelection(path: String, kind: String, value: String) {
        selections.getOrPut(path) { mutableMapOf() }[kind] = value
        persist()
    }

    /* ---------- play-next / play-later queue ---------- */

    fun playQueue(): List<String> = playQueue.toList()

    /** "Play next": the item goes to the front of the queue. */
    fun enqueueNext(path: String) {
        playQueue.remove(path)
        playQueue.add(0, path)
        persist()
    }

    /** "Play later": the item goes to the back of the queue. */
    fun enqueueLater(path: String) {
        playQueue.remove(path)
        playQueue.add(path)
        persist()
    }

    fun clearPlayQueue() {
        playQueue.clear()
        persist()
    }

    /* ---------- private folder records ---------- */

    fun privatePinHash(): String = privatePinHash
    fun setPrivatePinHash(hash: String) {
        privatePinHash = hash
        persist()
    }

    fun privateEntries(): List<PrivateEntry> = privateEntries.toList()
    fun addPrivateEntry(entry: PrivateEntry) {
        privateEntries.add(entry)
        persist()
    }

    fun removePrivateEntry(storedName: String) {
        privateEntries.removeAll { it.storedName == storedName }
        persist()
    }

    /* ---------- recycle bin records ---------- */

    fun recycleEntries(): List<RecycleEntry> = recycleEntries.toList()
    fun addRecycleEntry(entry: RecycleEntry) {
        recycleEntries.add(entry)
        persist()
    }

    fun removeRecycleEntry(id: String) {
        recycleEntries.removeAll { it.id == id }
        persist()
    }

    fun clearRecycleEntries() {
        recycleEntries.clear()
        persist()
    }

    /* ---------- activity records export / import (spec #100) ---------- */

    /** Serialises ONLY the activity records — resume positions,
     *  finished marks, last-played times, search history, playlists,
     *  hidden/favourite sets, per-file selections and the play
     *  queue — as a standalone JSON object (same field names as the
     *  state file). Settings are NOT included; the settings export
     *  embeds this object beside the effective settings values
     *  (Monitoring v0.2 P2-6). Pure — JVM-tested. */
    fun activityJson(): String {
        val sb = StringBuilder()
        sb.append("{\"positions\":{")
        var first = true
        for ((path, pos) in positions.toSortedMap()) {
            if (!first) sb.append(',')
            first = false
            sb.append(Json.quote(path)).append(':').append(pos)
        }
        sb.append('}')
        sb.append(",\"finished\":").append(encodeStringArray(finished.sorted()))
        sb.append(",\"lastPlayedAt\":{")
        first = true
        for ((path, at) in lastPlayedAt.toSortedMap()) {
            if (!first) sb.append(',')
            first = false
            sb.append(Json.quote(path)).append(':').append(at)
        }
        sb.append('}')
        sb.append(",\"searchHistory\":").append(encodeStringArray(searchHistory))
        sb.append(",\"playlists\":{")
        first = true
        for ((name, items) in playlists.toSortedMap()) {
            if (!first) sb.append(',')
            first = false
            sb.append(Json.quote(name)).append(':').append(encodeStringArray(items))
        }
        sb.append('}')
        sb.append(",\"hidden\":").append(encodeStringArray(hiddenPaths.sorted()))
        sb.append(",\"favourites\":").append(encodeStringArray(favouritePaths.sorted()))
        sb.append(",\"selections\":{")
        first = true
        for ((path, kinds) in selections.toSortedMap()) {
            if (!first) sb.append(',')
            first = false
            sb.append(Json.quote(path)).append(":{")
            var innerFirst = true
            for ((kind, value) in kinds.toSortedMap()) {
                if (!innerFirst) sb.append(',')
                innerFirst = false
                sb.append(Json.quote(kind)).append(':').append(Json.quote(value))
            }
            sb.append('}')
        }
        sb.append('}')
        sb.append(",\"playQueue\":").append(encodeStringArray(playQueue))
        sb.append('}')
        return sb.toString()
    }

    /** Replaces the activity records from [text] (the shape
     *  [activityJson] produces). Returns false — leaving the live
     *  state completely untouched — on malformed input. Settings,
     *  brightness, lastFolder and the private/recycle records are
     *  NOT touched (their files live in app-private dirs and would
     *  be meaningless in an export). Pure — JVM-tested. */
    fun applyActivityJson(text: String): Boolean {
        val root: Map<String, Any?>
        try {
            val parsed = Json.parse(text)
            @Suppress("UNCHECKED_CAST")
            root = parsed as? Map<String, Any?> ?: return false
        } catch (t: Throwable) {
            return false
        }
        val newPositions = mutableMapOf<String, Long>()
        val posNode = root["positions"]
        if (posNode != null) {
            val posMap = posNode as? Map<*, *> ?: return false
            for ((k, v) in posMap) {
                val key = k as? String ?: return false
                val num = v as? Number ?: return false
                newPositions[key] = num.toLong()
            }
        }
        fun stringSet(node: Any?): MutableSet<String>? {
            if (node == null) return mutableSetOf()
            val list = node as? List<*> ?: return null
            val out = mutableSetOf<String>()
            for (item in list) out.add(item as? String ?: return null)
            return out
        }
        fun stringList(node: Any?): MutableList<String>? {
            if (node == null) return mutableListOf()
            val list = node as? List<*> ?: return null
            val out = mutableListOf<String>()
            for (item in list) out.add(item as? String ?: return null)
            return out
        }
        val newFinished = stringSet(root["finished"]) ?: return false
        val newLastPlayedAt = mutableMapOf<String, Long>()
        val lpaNode = root["lastPlayedAt"]
        if (lpaNode != null) {
            val lpaMap = lpaNode as? Map<*, *> ?: return false
            for ((k, v) in lpaMap) {
                val key = k as? String ?: return false
                val num = v as? Number ?: return false
                newLastPlayedAt[key] = num.toLong()
            }
        }
        val newSearchHistory = stringList(root["searchHistory"]) ?: return false
        val newPlaylists = mutableMapOf<String, MutableList<String>>()
        val plNode = root["playlists"]
        if (plNode != null) {
            val plMap = plNode as? Map<*, *> ?: return false
            for ((k, v) in plMap) {
                val name = k as? String ?: return false
                newPlaylists[name] = stringList(v) ?: return false
            }
        }
        val newHidden = stringSet(root["hidden"]) ?: return false
        val newFavourites = stringSet(root["favourites"]) ?: return false
        val newSelections = mutableMapOf<String, MutableMap<String, String>>()
        val selNode = root["selections"]
        if (selNode != null) {
            val selMap = selNode as? Map<*, *> ?: return false
            for ((k, v) in selMap) {
                val path = k as? String ?: return false
                val kinds = v as? Map<*, *> ?: return false
                val inner = mutableMapOf<String, String>()
                for ((kind, value) in kinds) {
                    inner[kind as? String ?: return false] = value as? String ?: return false
                }
                newSelections[path] = inner
            }
        }
        val newPlayQueue = stringList(root["playQueue"]) ?: return false

        positions.clear(); positions.putAll(newPositions)
        finished.clear(); finished.addAll(newFinished)
        lastPlayedAt.clear(); lastPlayedAt.putAll(newLastPlayedAt)
        // Keep the last-played pointer consistent with the records.
        val newest = newLastPlayedAt.entries.maxByOrNull { it.value }?.key ?: ""
        setRawSettingSilent(Settings.LAST_PLAYED_PATH, newest)
        searchHistory.clear(); searchHistory.addAll(newSearchHistory)
        playlists.clear(); playlists.putAll(newPlaylists)
        hiddenPaths.clear(); hiddenPaths.addAll(newHidden)
        favouritePaths.clear(); favouritePaths.addAll(newFavourites)
        selections.clear(); selections.putAll(newSelections)
        playQueue.clear(); playQueue.addAll(newPlayQueue)
        persist()
        return true
    }

    /* ---------- persistence ---------- */

    private fun setRawSettingSilent(key: String, value: String) {
        if (value == Settings.defaultOf(key)) settings.remove(key) else settings[key] = value
    }

    /** v0.5.6 smoothness: when the app enables this, persist()
     *  serialises on the caller thread but hands the actual flash
     *  write to a single coalescing writer thread — the player's
     *  5-second position save used to write the whole state file
     *  synchronously on the UI thread, a periodic playback hitch
     *  MX never takes. Tests keep the synchronous default so file
     *  assertions stay deterministic. */
    @Volatile var asyncWrites = false
    private val writeLock = Object()
    private var pendingWrite: String? = null
    private var writerStarted = false

    private fun persist() {
        if (loading) return
        val f = file ?: return
        val json = try { toJsonString() } catch (t: Throwable) { return }
        if (!asyncWrites) {
            try { f.writeText(json, Charsets.UTF_8) } catch (t: Throwable) {
                /* a failed write must never crash playback */
            }
            return
        }
        queueWrite(f, json)
    }

    /** Hands one serialised payload to the coalescing writer
     *  thread (started on first use). The writer always writes the
     *  LATEST queued payload, so bursts collapse into one flash
     *  write and no caller ever blocks on storage. */
    private fun queueWrite(f: File, json: String) {
        synchronized(writeLock) {
            pendingWrite = json
            if (!writerStarted) {
                writerStarted = true
                val th = Thread {
                    while (true) {
                        val payload: String?
                        synchronized(writeLock) {
                            payload = pendingWrite
                            pendingWrite = null
                            if (payload == null) {
                                try { writeLock.wait(500) } catch (e: InterruptedException) { /* loop */ }
                            }
                        }
                        if (payload != null) {
                            try { f.writeText(payload, Charsets.UTF_8) } catch (t: Throwable) { /* never crash */ }
                        }
                    }
                }
                th.isDaemon = true
                th.name = "gb-store-writer"
                th.start()
            }
            writeLock.notifyAll()
        }
    }

    /** v0.5.7: lifecycle flush WITHOUT the flash write on the
     *  caller thread. PlayerActivity's onPause/onDestroy used to
     *  call [persistSync] — a full synchronous file write in the
     *  middle of the PiP/home transition, exactly where Sir felt
     *  the hitch. This serialises the current state (cheap, in
     *  memory) and hands the write to the writer thread regardless
     *  of the [asyncWrites] flag. The payload is queued before the
     *  call returns, and the writer is a live daemon that drains
     *  it immediately; a later [persistSync] (or any persist) can
     *  still force durability where a caller truly needs it. */
    fun persistAsync() {
        if (loading) return
        val f = file ?: return
        val json = try { toJsonString() } catch (t: Throwable) { return }
        queueWrite(f, json)
    }

    /** Flush any coalesced write synchronously — lifecycle exits
     *  (pause/destroy) call this so the last position is durable
     *  even if the process dies before the writer thread wakes. */
    fun persistSync() {
        if (loading) return
        val f = file ?: return
        val json: String
        synchronized(writeLock) {
            json = try { toJsonString() } catch (t: Throwable) { return }
            pendingWrite = null
        }
        try { f.writeText(json, Charsets.UTF_8) } catch (t: Throwable) { /* never crash */ }
    }

    private fun encodeStringArray(items: List<String>): String =
        items.joinToString(",", "[", "]") { Json.quote(it) }

    private fun parseStringArray(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val v = Json.parse(raw)
            (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Serialises the whole state. Pure — no Android, JVM-safe. */
    fun toJsonString(): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(VERSION)
        sb.append(",\"positions\":{")
        var first = true
        for ((path, pos) in positions.toSortedMap()) {
            if (!first) sb.append(',')
            first = false
            sb.append(Json.quote(path)).append(':').append(pos)
        }
        sb.append('}')
        sb.append(",\"finished\":[")
        first = true
        for (path in finished.sorted()) {
            if (!first) sb.append(',')
            first = false
            sb.append(Json.quote(path))
        }
        sb.append(']')
        sb.append(",\"brightness\":").append(brightness)
        sb.append(",\"subtitleTextSizeSp\":").append(subtitleTextSizeSp)
        sb.append(",\"lastFolder\":")
        val lf = lastFolder
        if (lf == null) sb.append("null") else sb.append(Json.quote(lf))
        if (settings.isNotEmpty()) {
            sb.append(",\"settings\":{")
            first = true
            for ((k, v) in settings.toSortedMap()) {
                if (!first) sb.append(',')
                first = false
                sb.append(Json.quote(k)).append(':').append(Json.quote(v))
            }
            sb.append('}')
        }
        if (lastPlayedAt.isNotEmpty()) {
            sb.append(",\"lastPlayedAt\":{")
            first = true
            for ((path, at) in lastPlayedAt.toSortedMap()) {
                if (!first) sb.append(',')
                first = false
                sb.append(Json.quote(path)).append(':').append(at)
            }
            sb.append('}')
        }
        if (searchHistory.isNotEmpty()) {
            sb.append(",\"searchHistory\":").append(encodeStringArray(searchHistory))
        }
        if (playlists.isNotEmpty()) {
            sb.append(",\"playlists\":{")
            first = true
            for ((name, items) in playlists.toSortedMap()) {
                if (!first) sb.append(',')
                first = false
                sb.append(Json.quote(name)).append(':').append(encodeStringArray(items))
            }
            sb.append('}')
        }
        if (hiddenPaths.isNotEmpty()) {
            sb.append(",\"hidden\":").append(encodeStringArray(hiddenPaths.sorted()))
        }
        if (favouritePaths.isNotEmpty()) {
            sb.append(",\"favourites\":").append(encodeStringArray(favouritePaths.sorted()))
        }
        if (selections.isNotEmpty()) {
            sb.append(",\"selections\":{")
            first = true
            for ((path, kinds) in selections.toSortedMap()) {
                if (!first) sb.append(',')
                first = false
                sb.append(Json.quote(path)).append(":{")
                var innerFirst = true
                for ((kind, value) in kinds.toSortedMap()) {
                    if (!innerFirst) sb.append(',')
                    innerFirst = false
                    sb.append(Json.quote(kind)).append(':').append(Json.quote(value))
                }
                sb.append('}')
            }
            sb.append('}')
        }
        if (playQueue.isNotEmpty()) {
            sb.append(",\"playQueue\":").append(encodeStringArray(playQueue))
        }
        if (privatePinHash.isNotEmpty()) {
            sb.append(",\"privatePin\":").append(Json.quote(privatePinHash))
        }
        if (privateEntries.isNotEmpty()) {
            sb.append(",\"privateEntries\":[")
            first = true
            for (e in privateEntries) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"orig\":").append(Json.quote(e.origPath))
                    .append(",\"stored\":").append(Json.quote(e.storedName))
                    .append(",\"title\":").append(Json.quote(e.title))
                    .append(",\"size\":").append(e.sizeBytes)
                    .append(",\"added\":").append(e.addedAtMs)
                    .append('}')
            }
            sb.append(']')
        }
        if (recycleEntries.isNotEmpty()) {
            sb.append(",\"recycleEntries\":[")
            first = true
            for (e in recycleEntries) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"id\":").append(Json.quote(e.id))
                    .append(",\"orig\":").append(Json.quote(e.origPath))
                    .append(",\"stored\":").append(Json.quote(e.storedName))
                    .append(",\"title\":").append(Json.quote(e.title))
                    .append(",\"size\":").append(e.sizeBytes)
                    .append(",\"deleted\":").append(e.deletedAtMs)
                    .append('}')
            }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    /** Loads state from [text]. Returns false — leaving the live
     *  state completely untouched — on malformed JSON or a wrong
     *  top-level shape. Unknown keys are ignored; missing keys keep
     *  defaults, so older/newer files load forward and back. */
    fun loadFromJsonString(text: String): Boolean {
        val root: Map<String, Any?>
        try {
            val parsed = Json.parse(text)
            @Suppress("UNCHECKED_CAST")
            root = parsed as? Map<String, Any?> ?: return false
        } catch (t: Throwable) {
            return false
        }

        // Parse into temporaries first; commit only on full success.
        val newPositions = mutableMapOf<String, Long>()
        val posNode = root["positions"]
        if (posNode != null) {
            val posMap = posNode as? Map<*, *> ?: return false
            for ((k, v) in posMap) {
                val key = k as? String ?: return false
                val num = v as? Number ?: return false
                newPositions[key] = num.toLong()
            }
        }
        val newFinished = mutableSetOf<String>()
        val finNode = root["finished"]
        if (finNode != null) {
            val finList = finNode as? List<*> ?: return false
            for (item in finList) {
                newFinished.add(item as? String ?: return false)
            }
        }
        val newBrightness = (root["brightness"] as? Number)?.toFloat() ?: -1f
        val newSubSize = (root["subtitleTextSizeSp"] as? Number)?.toFloat() ?: 18f
        val newLastFolder = root["lastFolder"] as? String

        val newSettings = mutableMapOf<String, String>()
        val setNode = root["settings"]
        if (setNode != null) {
            val setMap = setNode as? Map<*, *> ?: return false
            for ((k, v) in setMap) {
                val key = k as? String ?: return false
                val value = v as? String ?: return false
                newSettings[key] = value
            }
        }
        val newLastPlayedAt = mutableMapOf<String, Long>()
        val lpaNode = root["lastPlayedAt"]
        if (lpaNode != null) {
            val lpaMap = lpaNode as? Map<*, *> ?: return false
            for ((k, v) in lpaMap) {
                val key = k as? String ?: return false
                val num = v as? Number ?: return false
                newLastPlayedAt[key] = num.toLong()
            }
        }
        val newSearchHistory = mutableListOf<String>()
        val shNode = root["searchHistory"]
        if (shNode != null) {
            val shList = shNode as? List<*> ?: return false
            for (item in shList) newSearchHistory.add(item as? String ?: return false)
        }
        val newPlaylists = mutableMapOf<String, MutableList<String>>()
        val plNode = root["playlists"]
        if (plNode != null) {
            val plMap = plNode as? Map<*, *> ?: return false
            for ((k, v) in plMap) {
                val name = k as? String ?: return false
                val items = v as? List<*> ?: return false
                val list = mutableListOf<String>()
                for (item in items) list.add(item as? String ?: return false)
                newPlaylists[name] = list
            }
        }
        fun stringSet(node: Any?): MutableSet<String>? {
            if (node == null) return mutableSetOf()
            val list = node as? List<*> ?: return null
            val out = mutableSetOf<String>()
            for (item in list) out.add(item as? String ?: return null)
            return out
        }
        val newHidden = stringSet(root["hidden"]) ?: return false
        val newFavourites = stringSet(root["favourites"]) ?: return false
        val newSelections = mutableMapOf<String, MutableMap<String, String>>()
        val selNode = root["selections"]
        if (selNode != null) {
            val selMap = selNode as? Map<*, *> ?: return false
            for ((k, v) in selMap) {
                val path = k as? String ?: return false
                val kinds = v as? Map<*, *> ?: return false
                val inner = mutableMapOf<String, String>()
                for ((kind, value) in kinds) {
                    inner[kind as? String ?: return false] = value as? String ?: return false
                }
                newSelections[path] = inner
            }
        }
        val newPlayQueue = mutableListOf<String>()
        val pqNode = root["playQueue"]
        if (pqNode != null) {
            val pqList = pqNode as? List<*> ?: return false
            for (item in pqList) newPlayQueue.add(item as? String ?: return false)
        }
        val newPin = root["privatePin"] as? String ?: ""
        val newPrivate = mutableListOf<PrivateEntry>()
        val peNode = root["privateEntries"]
        if (peNode != null) {
            val peList = peNode as? List<*> ?: return false
            for (item in peList) {
                val m = item as? Map<*, *> ?: return false
                newPrivate.add(
                    PrivateEntry(
                        origPath = m["orig"] as? String ?: return false,
                        storedName = m["stored"] as? String ?: return false,
                        title = m["title"] as? String ?: return false,
                        sizeBytes = (m["size"] as? Number)?.toLong() ?: return false,
                        addedAtMs = (m["added"] as? Number)?.toLong() ?: return false
                    )
                )
            }
        }
        val newRecycle = mutableListOf<RecycleEntry>()
        val reNode = root["recycleEntries"]
        if (reNode != null) {
            val reList = reNode as? List<*> ?: return false
            for (item in reList) {
                val m = item as? Map<*, *> ?: return false
                newRecycle.add(
                    RecycleEntry(
                        id = m["id"] as? String ?: return false,
                        origPath = m["orig"] as? String ?: return false,
                        storedName = m["stored"] as? String ?: return false,
                        title = m["title"] as? String ?: return false,
                        sizeBytes = (m["size"] as? Number)?.toLong() ?: return false,
                        deletedAtMs = (m["deleted"] as? Number)?.toLong() ?: return false
                    )
                )
            }
        }

        loading = true
        try {
            positions.clear(); positions.putAll(newPositions)
            finished.clear(); finished.addAll(newFinished)
            brightness = newBrightness
            subtitleTextSizeSp = newSubSize
            lastFolder = newLastFolder
            settings.clear(); settings.putAll(newSettings)
            lastPlayedAt.clear(); lastPlayedAt.putAll(newLastPlayedAt)
            searchHistory.clear(); searchHistory.addAll(newSearchHistory)
            playlists.clear(); playlists.putAll(newPlaylists)
            hiddenPaths.clear(); hiddenPaths.addAll(newHidden)
            favouritePaths.clear(); favouritePaths.addAll(newFavourites)
            selections.clear(); selections.putAll(newSelections)
            playQueue.clear(); playQueue.addAll(newPlayQueue)
            privatePinHash = newPin
            privateEntries.clear(); privateEntries.addAll(newPrivate)
            recycleEntries.clear(); recycleEntries.addAll(newRecycle)
        } finally {
            loading = false
        }
        return true
    }

    /** Test hook: clears all in-memory state (does not touch disk). */
    fun resetForTest() {
        loading = true
        try {
            positions.clear()
            finished.clear()
            brightness = -1f
            subtitleTextSizeSp = 18f
            lastFolder = null
            settings.clear()
            lastPlayedAt.clear()
            searchHistory.clear()
            playlists.clear()
            hiddenPaths.clear()
            favouritePaths.clear()
            selections.clear()
            playQueue.clear()
            privatePinHash = ""
            privateEntries.clear()
            recycleEntries.clear()
        } finally {
            loading = false
        }
    }
}

/** Minimal JSON writer/parser for the fixed Store schema — pure
 *  Kotlin, no dependencies. Parses into Map/List/String/Double/
 *  Boolean/null. Throws IllegalArgumentException on malformed input. */
internal object Json {

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    sb.append(c.code.toString(16).padStart(4, '0'))
                } else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (!p.atEnd()) throw IllegalArgumentException("trailing characters")
        return v
    }

    private class Parser(private val text: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= text.length

        fun ws() {
            while (i < text.length && text[i] <= ' ') i++
        }

        fun value(): Any? {
            ws()
            if (i >= text.length) throw IllegalArgumentException("unexpected end")
            return when (text[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!text.startsWith(word, i)) throw IllegalArgumentException("bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (i < text.length && text[i] == '}') { i++; return out }
            while (true) {
                ws()
                val key = string()
                ws()
                if (i >= text.length || text[i] != ':') throw IllegalArgumentException("expected : at $i")
                i++
                out[key] = value()
                ws()
                if (i >= text.length) throw IllegalArgumentException("unterminated object")
                when (text[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or } at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = mutableListOf<Any?>()
            i++ // [
            ws()
            if (i < text.length && text[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                if (i >= text.length) throw IllegalArgumentException("unterminated array")
                when (text[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or ] at $i")
                }
            }
        }

        private fun string(): String {
            if (i >= text.length || text[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < text.length) {
                val c = text[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= text.length) throw IllegalArgumentException("bad escape")
                        when (val e = text[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (i + 4 > text.length) throw IllegalArgumentException("bad \\u escape")
                                val hex = text.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape: $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun number(): Double {
            val start = i
            if (i < text.length && text[i] == '-') i++
            while (i < text.length && (text[i].isDigit() || text[i] == '.' ||
                    text[i] == 'e' || text[i] == 'E' || text[i] == '+' || text[i] == '-')
            ) i++
            if (start == i) throw IllegalArgumentException("bad value at $i")
            return text.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number at $start")
        }
    }
}
