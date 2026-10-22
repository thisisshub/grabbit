package dev.usenetstream.engine

import dev.usenetstream.engine.link.LinkClient
import dev.usenetstream.engine.link.LinkException
import dev.usenetstream.engine.link.LinkHandler
import dev.usenetstream.engine.link.LinkProtocol
import dev.usenetstream.engine.link.LinkServer
import dev.usenetstream.engine.link.PairingGuard
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingGuardTest {
    @Test
    fun `pairs with the code, rotates it, and locks after five misses`() {
        var t = 0L
        val g = PairingGuard(now = { t })
        val code = g.code
        assertEquals(6, code.length)

        val (r, token) = g.pair(code, "OnePlus")
        assertEquals(PairingGuard.Result.OK, r)
        assertEquals("OnePlus", g.phoneFor(token))
        assertNotEquals(code, g.code) // one code, one phone
        assertEquals(PairingGuard.Result.WRONG, g.pair(code, "Again").first)

        repeat(3) { assertEquals(PairingGuard.Result.WRONG, g.checkCode("000000".takeIf { it != g.code } ?: "111111")) }
        assertEquals(PairingGuard.Result.LOCKED, g.checkCode("x")) // 5th miss locks
        assertEquals(PairingGuard.Result.LOCKED, g.checkCode(g.code)) // even the right code while locked
        t += 61_000
        assertEquals(PairingGuard.Result.OK, g.checkCode(g.code))

        g.unpair(token!!)
        assertNull(g.phoneFor(token))
    }

    @Test
    fun `only home-network addresses are local`() {
        listOf("192.168.1.15", "10.0.0.4", "172.20.1.1", "127.0.0.1", "fe80::1", "fd12:3456::1").forEach {
            assertTrue(LinkProtocol.isLocal(InetAddress.getByName(it)), it)
        }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700::1111").forEach { assertFalse(LinkProtocol.isLocal(InetAddress.getByName(it)), it) }
    }

    @Test
    fun `json writer escapes text`() {
        val s = LinkProtocol.json("q" to "say \"hi\"\n\\", "n" to 3, "b" to true, "x" to null)
        assertEquals("""{"q":"say \"hi\"\n\\","n":3,"b":true,"x":null}""", s)
        assertEquals("say \"hi\"\n\\", LinkProtocol.parse(s)!!["q"])
    }
}

class LinkServerTest {
    private val searches = CopyOnWriteArrayList<Pair<String, String>>()
    private val plays = CopyOnWriteArrayList<List<String?>>()
    private val setups = CopyOnWriteArrayList<Map<String, Any?>>()
    private val requests = CopyOnWriteArrayList<String>()
    private val guard = PairingGuard()
    private val server = LinkServer(
        guard,
        object : LinkHandler {
            override val tvName = "Hisense TV"
            override fun onSearch(query: String, phone: String) { searches += query to phone }
            override fun onPlay(key: String, title: String, coverUrl: String?, phone: String) { plays += listOf(key, title, coverUrl, phone) }
            override fun onSetup(fields: Map<String, Any?>): String? = if (fields["host"] == null) "host missing" else null.also { setups += fields }
            override fun onPaired(token: String, phone: String) = Unit
            override fun onPairRequest(phone: String) { requests += phone }
        },
        setupPage = { "<html>setup</html>" },
        port = 0,
    ).start()

    @AfterTest
    fun stop() = server.close()

    private fun client() = LinkClient("127.0.0.1", server.boundPort)

    @Test
    fun `phone pairs, then searches and plays on the TV`() {
        val c = client()
        assertEquals("Hisense TV", c.ping())
        assertEquals(401, assertFailsWith<LinkException> { c.search("Pioneer One") }.status) // not paired yet
        assertEquals(401, assertFailsWith<LinkException> { c.pair("999999".takeIf { it != guard.code } ?: "123456", "OnePlus") }.status)

        c.requestPair("OnePlus")
        assertEquals(listOf("OnePlus"), requests.toList()) // TV now shows its code
        c.pair(guard.code, "OnePlus")
        c.search("Pioneer One")
        c.play("tvdb:170551", "Pioneer One", "p.jpg")

        assertEquals(listOf("Pioneer One" to "OnePlus"), searches.toList())
        assertEquals(listOf<List<String?>>(listOf("tvdb:170551", "Pioneer One", "p.jpg", "OnePlus")), plays.toList())
        // A forged token is refused.
        assertEquals(401, assertFailsWith<LinkException> { LinkClient("127.0.0.1", server.boundPort, token = "deadbeef").search("x") }.status)
    }

    @Test
    fun `setup page needs the code and valid fields`() {
        val page = java.net.URI("http://127.0.0.1:${server.boundPort}/setup").toURL().readText()
        assertTrue(page.contains("setup"))

        fun post(body: String): Int {
            val conn = java.net.URI("http://127.0.0.1:${server.boundPort}/setup").toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.responseCode
        }
        assertEquals(401, post(LinkProtocol.json("code" to "nope", "host" to "news.example")))
        assertEquals(422, post(LinkProtocol.json("code" to guard.code)))
        val code = guard.code
        assertEquals(200, post(LinkProtocol.json("code" to code, "host" to "news.example", "user" to "u")))
        assertEquals("news.example", setups.single()["host"])
        assertNotEquals(code, guard.code) // used up
        assertEquals(400, post("not json"))
    }
}
