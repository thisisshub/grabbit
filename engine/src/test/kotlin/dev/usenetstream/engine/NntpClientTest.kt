package dev.usenetstream.engine

import dev.usenetstream.engine.nntp.ArticleNotFoundException
import dev.usenetstream.engine.nntp.NntpAuthException
import dev.usenetstream.engine.nntp.NntpClient
import dev.usenetstream.engine.nntp.NntpPool
import dev.usenetstream.engine.nntp.socketConnector
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tiny scripted NNTP server: one article, one user, optional drop of the first connection mid-body. */
class FakeNntpServer(private val dropFirstBody: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val connections = AtomicInteger()

    val body = listOf("plain line", ".starts with dot", "..", "last")

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                val n = connections.incrementAndGet()
                thread(isDaemon = true) { serve(s, drop = dropFirstBody && n == 1) }
            }
        }
    }

    private fun serve(s: Socket, drop: Boolean) = s.use {
        val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val out = s.getOutputStream()
        fun send(line: String) = out.write("$line\r\n".toByteArray(Charsets.ISO_8859_1)).also { out.flush() }
        send("200 fake ready")
        var user: String? = null
        var authed = false
        while (true) {
            val line = input.readLine() ?: return
            when {
                line.startsWith("AUTHINFO USER ") -> { user = line.removePrefix("AUTHINFO USER "); send("381 more") }
                line.startsWith("AUTHINFO PASS ") -> {
                    authed = user == "alice" && line.removePrefix("AUTHINFO PASS ") == "secret"
                    send(if (authed) "281 ok" else "481 no")
                }
                !authed -> send("480 login first")
                line == "BODY <good@x>" -> {
                    send("222 0 <good@x>")
                    if (drop) { send(body[0]); return }
                    for (b in body) send(if (b.startsWith(".")) ".$b" else b)
                    send(".")
                }
                line.startsWith("BODY ") -> send("430 no such article")
                line == "QUIT" -> { send("205 bye"); return }
                else -> send("500 ?")
            }
        }
    }

    override fun close() = server.close()
}

class NntpClientTest {
    private val servers = ArrayList<FakeNntpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(drop: Boolean = false) = FakeNntpServer(drop).also { servers += it }

    private fun client(port: Int, pass: String = "secret") =
        NntpClient(socketConnector("127.0.0.1", port, useTls = false), "alice", pass)

    @Test
    fun `logs in and reads a body with dot-stuffing removed`() {
        val srv = server()
        client(srv.port).use { c ->
            c.open()
            val lines = c.body("good@x").map { String(it) }
            assertEquals(srv.body, lines)
            assertFailsWith<ArticleNotFoundException> { c.body("gone@x") }
            // Connection stays usable after a 430.
            assertEquals(srv.body, c.body("<good@x>").map { String(it) })
        }
    }

    @Test
    fun `bad password is an auth error, not retried by the pool`() {
        val srv = server()
        assertFailsWith<NntpAuthException> { client(srv.port, pass = "nope").open() }

        val pool = NntpPool({ client(srv.port, pass = "nope") }, size = 2)
        assertFailsWith<NntpAuthException> { pool.use { it.body("good@x") } }
        assertEquals(2, srv.connections.get()) // one direct + one from the pool; no retries
    }

    @Test
    fun `pool replaces a connection dropped mid-article and reuses it after`() {
        val srv = server(drop = true)
        val pool = NntpPool({ client(srv.port) }, size = 1)

        val first = pool.use { it.body("good@x") }
        val second = pool.use { it.body("good@x") }

        assertContentEquals(srv.body.map { it }.toTypedArray(), first.map { String(it) }.toTypedArray())
        assertEquals(srv.body, second.map { String(it) })
        assertEquals(2, srv.connections.get())
        assertFailsWith<ArticleNotFoundException> { pool.use { it.body("gone@x") } }
        assertEquals(2, srv.connections.get()) // 430 keeps the connection
        pool.close()
    }
}

class StreamingDecodeTest {
    @Test
    fun `streaming yEnc decode over NNTP equals decoding the line list`() {
        val data = kotlin.random.Random(5).nextBytes(50_000) + ByteArray(256) { it.toByte() }
        val article = YencTestEncoder.encodePart("f.bin", 90_000, 2, 1_000, data)
        val server = java.net.ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            repeat(2) {
                server.accept().use { s ->
                    val inp = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                    val out = s.getOutputStream()
                    fun send(b: ByteArray) { out.write(b); out.write("\r\n".toByteArray()) }
                    send("200 ok".toByteArray())
                    inp.readLine() // BODY
                    send("222 0 <a@x>".toByteArray())
                    // NNTP dot-stuffing: a line starting with '.' is sent as '..'
                    article.forEach { l -> send(if (l.isNotEmpty() && l[0] == '.'.code.toByte()) byteArrayOf('.'.code.toByte()) + l else l) }
                    send(".".toByteArray()); out.flush()
                    inp.readLine()
                }
            }
        }
        fun client() = NntpClient(socketConnector("127.0.0.1", server.localPort, useTls = false), null, null).apply { open() }
        val streamed = client().use { it.bodyYenc("a@x") }
        val listed = dev.usenetstream.engine.yenc.YencDecoder.decode(client().use { it.body("a@x") })
        server.close()
        assertContentEquals(data, streamed.data)
        assertContentEquals(listed.data, streamed.data)
        assertEquals(1_000L, streamed.begin)
        assertEquals(true, streamed.crcOk)
    }
}
