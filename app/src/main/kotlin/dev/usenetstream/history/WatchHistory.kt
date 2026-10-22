package dev.usenetstream.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class SourceKind { INDEXER, FILE }

/**
 * One movie or one episode the user has played. [id] is the movie (IMDb id or cleaned name) or the episode
 * (`tvdb:123:S02E06`), so any release of it resumes at the same spot; [source] is how to get the NZB back:
 * indexer guid or a content URI. Episodes also carry their show ([showKey]) for rolling up into show cards.
 */
data class WatchEntry(
    val id: String,
    val title: String,
    val release: String,
    val coverUrl: String?,
    val kind: SourceKind,
    val source: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val genre: String? = null,
    val showKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
) {
    val isEpisode: Boolean get() = showKey != null && season != null && episode != null
    val code: String? get() = if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null
    val finished: Boolean get() = ResumePolicy.isFinished(positionMs, durationMs)
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

object ResumePolicy {
    const val MIN_RESUME_MS = 30_000L
    const val REWIND_MS = 5_000L
    const val FINISHED_FRACTION = 0.95

    fun isFinished(positionMs: Long, durationMs: Long) = durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

    /** Where to start: from the top if barely started or finished, else a few seconds before where they stopped. */
    fun startAt(entry: WatchEntry?): Long {
        if (entry == null || entry.positionMs < MIN_RESUME_MS || entry.finished) return 0L
        return (entry.positionMs - REWIND_MS).coerceAtLeast(0L)
    }

    fun inContinueWatching(entry: WatchEntry) = entry.positionMs >= MIN_RESUME_MS && !entry.finished

    /**
     * One card per movie or per show. A show is represented by its most recently watched episode; it stays in
     * Continue Watching even when that episode is finished, because the next one is up.
     */
    fun cards(entries: List<WatchEntry>): List<WatchEntry> {
        val seen = HashSet<String>()
        return entries.sortedByDescending { it.updatedAt }.filter { seen.add(it.showKey ?: it.id) }
    }

    fun continueWatching(entries: List<WatchEntry>): List<WatchEntry> =
        cards(entries).filter { if (it.isEpisode) it.positionMs >= MIN_RESUME_MS || it.finished else inContinueWatching(it) }
}

/** A release that would not stream (missing volume, 7z, compressed RAR...), so we stop offering it first. */
data class FailedRelease(val key: String, val title: String, val reason: String, val at: Long)

/** A title saved to watch later ("My List"). [key] matches [WatchEntry.id] / TitleGroup.key. */
data class ListEntry(val key: String, val title: String, val coverUrl: String?, val year: Int?, val addedAt: Long)

/** Small on-device SQLite db: watch progress + My List. No Room; two tables is all we need. */
class WatchHistory(context: Context, name: String? = "history.db") : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createWatch(db)
        onUpgrade(db, 1, VERSION)
    }

    /** Each step brings the previous schema up by one version; onCreate replays them all. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE watch ADD COLUMN genre TEXT")
            createMyList(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE watch ADD COLUMN show_key TEXT")
            db.execSQL("ALTER TABLE watch ADD COLUMN season INTEGER")
            db.execSQL("ALTER TABLE watch ADD COLUMN episode INTEGER")
            db.execSQL("ALTER TABLE watch ADD COLUMN episode_name TEXT")
            db.execSQL("CREATE INDEX watch_show ON watch(show_key)")
            db.execSQL(
                """
                CREATE TABLE failures (
                    release_key TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun createMyList(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE mylist (
                key TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                cover_url TEXT,
                year INTEGER,
                added_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    /** Version-1 schema; later columns are added by ALTER so new and upgraded installs end up identical. */
    private fun createWatch(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE watch (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                release TEXT NOT NULL,
                cover_url TEXT,
                kind TEXT NOT NULL,
                source TEXT NOT NULL,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX watch_updated ON watch(updated_at DESC)")
    }

    fun get(id: String): WatchEntry? =
        readableDatabase.query("watch", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toEntry() else null
        }

    fun recent(limit: Int = 200): List<WatchEntry> =
        readableDatabase.query("watch", null, null, null, null, null, "updated_at DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(c.toEntry()) }
        }

    /** Every episode row of one show. */
    fun episodes(showKey: String): List<WatchEntry> =
        readableDatabase.query("watch", null, "show_key = ?", arrayOf(showKey), null, null, "season, episode").use { c ->
            buildList { while (c.moveToNext()) add(c.toEntry()) }
        }

    fun deleteShow(showKey: String) {
        writableDatabase.delete("watch", "show_key = ?", arrayOf(showKey))
    }

    fun failedKeys(): Set<String> =
        readableDatabase.query("failures", arrayOf("release_key"), null, null, null, null, null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun failures(): List<FailedRelease> =
        readableDatabase.query("failures", null, null, null, null, null, "at DESC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FailedRelease(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)))
                }
            }
        }

    fun markFailed(f: FailedRelease) {
        val values = ContentValues().apply {
            put("release_key", f.key)
            put("title", f.title)
            put("reason", f.reason)
            put("at", f.at)
        }
        writableDatabase.insertWithOnConflict("failures", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun save(entry: WatchEntry) {
        val values = ContentValues().apply {
            put("id", entry.id)
            put("title", entry.title)
            put("release", entry.release)
            put("cover_url", entry.coverUrl)
            put("kind", entry.kind.name)
            put("source", entry.source)
            put("position_ms", entry.positionMs)
            put("duration_ms", entry.durationMs)
            put("updated_at", entry.updatedAt)
            put("genre", entry.genre)
            put("show_key", entry.showKey)
            put("season", entry.season)
            put("episode", entry.episode)
            put("episode_name", entry.episodeName)
        }
        writableDatabase.insertWithOnConflict("watch", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(id: String) {
        writableDatabase.delete("watch", "id = ?", arrayOf(id))
    }

    fun myList(): List<ListEntry> =
        readableDatabase.query("mylist", null, null, null, null, null, "added_at DESC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ListEntry(
                            key = c.getString(c.getColumnIndexOrThrow("key")),
                            title = c.getString(c.getColumnIndexOrThrow("title")),
                            coverUrl = c.getString(c.getColumnIndexOrThrow("cover_url")),
                            year = c.getColumnIndexOrThrow("year").let { if (c.isNull(it)) null else c.getInt(it) },
                            addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
                        ),
                    )
                }
            }
        }

    fun addToList(entry: ListEntry) {
        val values = ContentValues().apply {
            put("key", entry.key)
            put("title", entry.title)
            put("cover_url", entry.coverUrl)
            put("year", entry.year)
            put("added_at", entry.addedAt)
        }
        writableDatabase.insertWithOnConflict("mylist", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeFromList(key: String) {
        writableDatabase.delete("mylist", "key = ?", arrayOf(key))
    }

    private fun Cursor.toEntry() = WatchEntry(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        release = getString(getColumnIndexOrThrow("release")),
        coverUrl = getString(getColumnIndexOrThrow("cover_url")),
        kind = SourceKind.valueOf(getString(getColumnIndexOrThrow("kind"))),
        source = getString(getColumnIndexOrThrow("source")),
        positionMs = getLong(getColumnIndexOrThrow("position_ms")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        genre = getString(getColumnIndexOrThrow("genre")),
        showKey = getString(getColumnIndexOrThrow("show_key")),
        season = intOrNull("season"),
        episode = intOrNull("episode"),
        episodeName = getString(getColumnIndexOrThrow("episode_name")),
    )

    private fun Cursor.intOrNull(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }

    companion object {
        const val VERSION = 3
    }
}
