package dev.usenetstream.engine

import dev.usenetstream.engine.update.AppVersion
import dev.usenetstream.engine.update.Releases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test
    fun `versions order like people expect`() {
        val ordered = listOf("0.1.0-beta.2", "0.1.0-beta.3", "0.1.0-beta.10", "0.1.0", "0.1.1-beta.1", "0.1.1", "0.2.0", "1.0.0")
        assertEquals(ordered, ordered.shuffled(kotlin.random.Random(3)).sortedWith(compareBy { v(it) }))
        assertEquals(0, v("v0.1.0-beta.3").compareTo(v("0.1.0-beta.3")))
        // The friends build is the same version, not a pre-release of it.
        assertEquals(0, v("0.1.0-beta.3-friends").compareTo(v("0.1.0-beta.3")))
        assertEquals(0, v("0.1.0-friends").compareTo(v("0.1.0")))
    }

    @Test
    fun `picks the newest public apk from real release data`() {
        val json = javaClass.getResource("/github_releases.json")!!.readText()
        val newest = Releases.newest(json)!!
        assertEquals("v0.1.0-beta.3", newest.tag)
        assertTrue(newest.apkUrl.endsWith("Grabbit-0.1.0-beta.3.apk"))
        assertTrue(newest.apkBytes > 1_000_000)
        assertEquals("v0.1.0-beta.3", Releases.updateFor("0.1.0-beta.2", json)?.tag)
        assertEquals("v0.1.0-beta.3", Releases.updateFor("0.1.0-beta.1-friends", json)?.tag)
        assertNull(Releases.updateFor("0.1.0-beta.3", json))
        assertNull(Releases.updateFor("0.1.0-beta.3-friends", json))
    }

    @Test
    fun `never offers drafts or friends-only assets`() {
        val json = """[
          {"tag_name":"v0.2.0","draft":true,"assets":[{"name":"Grabbit-0.2.0.apk","size":5,"browser_download_url":"https://x/d.apk"}]},
          {"tag_name":"v0.1.5","draft":false,"assets":[{"name":"Grabbit-0.1.5-friends.apk","size":5,"browser_download_url":"https://x/f.apk"}]},
          {"tag_name":"v0.1.4","draft":false,"assets":[{"name":"notes.txt","size":5,"browser_download_url":"https://x/n"},{"name":"Grabbit-0.1.4.apk","size":5,"browser_download_url":"https://x/p.apk"}]}
        ]"""
        assertEquals("v0.1.4", Releases.newest(json)?.tag)
        assertEquals("https://x/p.apk", Releases.newest(json)?.apkUrl)
    }
}
