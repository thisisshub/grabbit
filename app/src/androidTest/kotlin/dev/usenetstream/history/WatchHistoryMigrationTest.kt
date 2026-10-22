package dev.usenetstream.history

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses its own db file, never the app's real history. */
@RunWith(AndroidJUnit4::class)
class WatchHistoryMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test
    fun upgradesVersion1KeepingProgressAndAddsNewTables() {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(
                "CREATE TABLE watch (id TEXT PRIMARY KEY, title TEXT NOT NULL, release TEXT NOT NULL, cover_url TEXT, " +
                    "kind TEXT NOT NULL, source TEXT NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX watch_updated ON watch(updated_at DESC)")
            db.execSQL("INSERT INTO watch VALUES ('imdb:01254207','Big Buck Bunny','bbb 480p',NULL,'INDEXER','guid1',547489,596461,1)")
            db.version = 1
        }

        val h = WatchHistory(context, name)
        val old = h.get("imdb:01254207")!!
        assertEquals(547489L, old.positionMs)
        assertNull(old.genre)
        assertNull(old.showKey)

        h.save(
            WatchEntry("tvdb:170551:S01E02", "Pioneer One", "P1.S01E02", null, SourceKind.INDEXER, "g2", 1_000_000, 2_000_000, 5,
                genre = "Drama", showKey = "tvdb:170551", season = 1, episode = 2, episodeName = "The Man From Mars"),
        )
        val eps = h.episodes("tvdb:170551")
        assertEquals(listOf("S01E02"), eps.map { it.code })
        assertEquals("The Man From Mars", eps.single().episodeName)

        h.addToList(ListEntry("tvdb:170551", "Pioneer One", null, 2010, 6))
        assertEquals(listOf("tvdb:170551"), h.myList().map { it.key })

        h.markFailed(FailedRelease("guid-bad", "Broken.1080p", "missing volume", 7))
        assertTrue("guid-bad" in h.failedKeys())
        assertEquals(WatchHistory.VERSION, h.readableDatabase.version)
        h.close()
    }

    @Test
    fun freshInstallHasTheSameSchemaAsUpgraded() {
        context.deleteDatabase(name)
        val h = WatchHistory(context, name)
        val cols = h.readableDatabase.rawQuery("PRAGMA table_info(watch)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        assertEquals(
            listOf("id", "title", "release", "cover_url", "kind", "source", "position_ms", "duration_ms", "updated_at",
                "genre", "show_key", "season", "episode", "episode_name"),
            cols,
        )
        h.close()
    }
}
