package dev.usenetstream.engine

import dev.usenetstream.engine.ott.Brand
import dev.usenetstream.engine.ott.DiskCachedGet
import dev.usenetstream.engine.ott.MotnClient
import dev.usenetstream.engine.ott.OttException
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OttTest {
    private fun fixture(name: String) = javaClass.getResource("/motn/$name")!!.readText()

    @Test
    fun `services carry brand colours and white logos`() {
        val s = MotnClient.parseServices(fixture("countries_in.json")).associateBy { it.id }
        assertEquals(0xFFE50914L, s.getValue("netflix").color)
        assertEquals(0xFF00A8E1L, s.getValue("prime").color)
        assertNotNull(s.getValue("netflix").logoWhite)
        // Listed as #000000: replaced so the lane is visible on a dark page.
        assertTrue(Brand.luminance(s.getValue("apple").color) > 0.02)
        assertTrue(Brand.luminance(s.getValue("hotstar").color) > 0.02)
    }

    @Test
    fun `text on every brand colour is readable`() {
        for (svc in MotnClient.parseServices(fixture("countries_in.json"))) {
            val on = Brand.onColor(svc.color)
            assertTrue(Brand.contrast(on, svc.color) >= 3.0, "${svc.id}: ${Brand.contrast(on, svc.color)}")
        }
        assertEquals(0xFF000000L, Brand.onColor(0xFFFFC900L)) // SonyLIV yellow takes black text
        assertEquals(0xFFFFFFFFL, Brand.onColor(0xFFE50914L))
    }

    @Test
    fun `top list is ranked with imdb ids, images and service links`() {
        val top = MotnClient.parseTop(fixture("top_netflix.json"), "netflix", "in")
        assertEquals(listOf(1, 2, 3), top.map { it.rank })
        val first = top.first().title
        assertEquals("Enna Vilai", first.title)
        assertEquals("tt33095205", first.imdbId)
        assertEquals(false, first.isSeries)
        assertEquals(2026, first.year)
        assertTrue(first.backdrop!!.startsWith("https://"))
        assertNotNull(first.poster)
        assertTrue(first.linkOn("netflix")!!.link.contains("netflix.com"))
    }

    @Test
    fun `new arrivals join changes with their shows and keep the cursor`() {
        val (items, cursor) = MotnClient.parseChanges(fixture("changes_new.json"), "in")
        assertEquals(4, items.size)
        assertEquals("Minerva Academy", items.first().title.title)
        assertEquals(true, items.first().title.isSeries)
        assertEquals("netflix", items.first().serviceId)
        assertNotNull(items.first().since)
        assertNotNull(cursor)
    }

    @Test
    fun `api error message surfaces`() {
        assertFailsWith<OttException> { MotnClient.parseTop("""{"message":"Invalid API key"}""", "netflix", "in") }
    }

    @Test
    fun `disk cache serves fresh copies, refetches stale ones, and falls back when offline`() {
        val dir = Files.createTempDirectory("ott").toFile()
        var now = 1_000_000L
        var calls = 0
        var online = true
        val cached = DiskCachedGet(dir, ttlMs = 1000, inner = { _, _ -> calls++; if (!online) throw IOException("offline"); "v$calls".toByteArray() }, now = { now })
        assertContentEquals("v1".toByteArray(), cached.get("u", emptyMap()))
        assertContentEquals("v1".toByteArray(), cached.get("u", emptyMap()))
        assertEquals(1, calls)
        now += 2000
        assertContentEquals("v2".toByteArray(), cached.get("u", emptyMap()))
        now += 2000
        online = false
        assertContentEquals("v2".toByteArray(), cached.get("u", emptyMap()))
        assertFailsWith<IOException> { cached.get("other", emptyMap()) }
        assertNull(dir.listFiles()!!.firstOrNull { it.length() == 0L })
    }
}
