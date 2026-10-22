package dev.usenetstream.engine.nntp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class NntpConfig(
    val host: String,
    val port: Int = 563,
    val useTls: Boolean = true,
    val username: String,
    val password: String,
    val connections: Int = 10,
)

class ArticleNotFoundException(messageId: String) : IOException("article not found: $messageId")
class NntpAuthException(message: String) : IOException(message)
class NntpProtocolException(message: String) : IOException(message)

data class NntpStatus(val code: Int, val text: String)

fun socketConnector(host: String, port: Int, useTls: Boolean, timeoutMs: Int = 15_000): () -> Socket = {
    val plain = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
    if (!useTls) {
        plain
    } else {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(plain, host, port, true) as SSLSocket).apply {
            // SSLSocketFactory does not check the host name on its own.
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            startHandshake()
        }
    }
}

/** One blocking NNTP connection. Not thread-safe; [NntpPool] hands it to one caller at a time. */
class NntpClient(
    private val connect: () -> Socket,
    private val username: String?,
    private val password: String?,
    private val readTimeoutMs: Int = 30_000,
) : Closeable {
    private var socket: Socket? = null
    private lateinit var input: LineReader
    private lateinit var output: OutputStream

    fun open() {
        val s = connect()
        s.soTimeout = readTimeoutMs
        socket = s
        input = LineReader(BufferedInputStream(s.getInputStream(), 64 * 1024))
        output = BufferedOutputStream(s.getOutputStream())

        val greeting = readStatus()
        if (greeting.code != 200 && greeting.code != 201) throw NntpProtocolException("greeting: $greeting")
        if (username != null) authenticate()
    }

    private fun authenticate() {
        var status = command("AUTHINFO USER $username")
        if (status.code == 381) status = command("AUTHINFO PASS $password")
        if (status.code != 281) throw NntpAuthException("login failed (${status.code} ${status.text})")
    }

    /** Raw body lines with CRLF stripped and dot-stuffing removed. */
    fun body(messageId: String): List<ByteArray> {
        val lines = ArrayList<ByteArray>(256)
        bodyLines(messageId) { buf, off, len -> lines += buf.copyOfRange(off, off + len) }
        return lines
    }

    /** The body decoded as yEnc while it streams in (no per-line arrays kept). */
    fun bodyYenc(messageId: String): dev.usenetstream.engine.yenc.YencPart {
        val d = dev.usenetstream.engine.yenc.YencDecoder.Streaming()
        bodyLines(messageId, d::line)
        return d.finish()
    }

    /** Streams body lines into [sink] (buffer reused between calls; dot-stuffing already removed). */
    private fun bodyLines(messageId: String, sink: (ByteArray, Int, Int) -> Unit) {
        val id = if (messageId.startsWith("<")) messageId else "<$messageId>"
        val status = command("BODY $id")
        when (status.code) {
            222 -> Unit
            430, 423 -> throw ArticleNotFoundException(id)
            480 -> throw NntpAuthException("server wants login (480)")
            else -> throw NntpProtocolException("BODY $id: $status")
        }
        while (true) {
            var done = false
            val more = input.readLine { buf, len ->
                when {
                    len == 1 && buf[0] == DOT -> done = true
                    len > 0 && buf[0] == DOT -> sink(buf, 1, len - 1)
                    else -> sink(buf, 0, len)
                }
            }
            if (!more) throw EOFException("connection closed mid-article")
            if (done) return
        }
    }

    private fun command(text: String): NntpStatus {
        output.write((text + "\r\n").toByteArray(Charsets.ISO_8859_1))
        output.flush()
        return readStatus()
    }

    private fun readStatus(): NntpStatus {
        val line = input.readLine() ?: throw EOFException("connection closed")
        val text = String(line, Charsets.ISO_8859_1)
        val code = text.take(3).toIntOrNull() ?: throw NntpProtocolException("bad status line: $text")
        return NntpStatus(code, text.drop(4))
    }

    override fun close() {
        val s = socket ?: return
        socket = null
        try {
            output.write("QUIT\r\n".toByteArray())
            output.flush()
        } catch (_: IOException) {
        }
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val DOT = '.'.code.toByte()
    }
}

/**
 * Up to [size] live connections, reused across calls. A dropped connection is replaced and the call retried;
 * a missing article or a bad login is not retried.
 */
class NntpPool(private val newClient: () -> NntpClient, val size: Int) : Closeable {
    private val idle = LinkedBlockingDeque<NntpClient>()
    private val permits = Semaphore(size)

    fun <T> use(block: (NntpClient) -> T): T {
        permits.acquire()
        try {
            var attempt = 0
            while (true) {
                val client = try {
                    idle.pollFirst() ?: newClient().also { it.open() }
                } catch (e: NntpAuthException) {
                    throw e
                } catch (e: IOException) {
                    if (++attempt >= MAX_ATTEMPTS) throw e
                    continue
                }
                try {
                    return block(client).also { idle.offerFirst(client) }
                } catch (e: ArticleNotFoundException) {
                    idle.offerFirst(client)
                    throw e
                } catch (e: NntpAuthException) {
                    client.close()
                    throw e
                } catch (e: IOException) {
                    client.close()
                    if (++attempt >= MAX_ATTEMPTS) throw e
                }
            }
        } finally {
            permits.release()
        }
    }

    override fun close() {
        while (true) (idle.pollFirst() ?: break).close()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/** Splits a stream on LF and drops a trailing CR. */
internal class LineReader(private val input: InputStream) {
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var lim = 0
    private var acc = ByteArray(1024)
    private var accLen = 0

    /** Like [readLine] but hands the line to [sink] without copying; false at end of stream. */
    fun readLine(sink: (ByteArray, Int) -> Unit): Boolean {
        accLen = 0
        while (true) {
            if (pos == lim) {
                val n = input.read(buf)
                if (n <= 0) {
                    if (accLen == 0) return false
                    sink(acc, trimmedLen())
                    return true
                }
                pos = 0
                lim = n
            }
            var i = pos
            while (i < lim && buf[i] != LF) i++
            append(i - pos)
            if (i < lim) {
                pos = i + 1
                sink(acc, trimmedLen())
                return true
            }
            pos = lim
        }
    }

    private fun trimmedLen(): Int = if (accLen > 0 && acc[accLen - 1] == CR) accLen - 1 else accLen

    fun readLine(): ByteArray? {
        accLen = 0
        while (true) {
            if (pos == lim) {
                val n = input.read(buf)
                if (n <= 0) return if (accLen > 0) finish() else null
                pos = 0
                lim = n
            }
            var i = pos
            while (i < lim && buf[i] != LF) i++
            append(i - pos)
            if (i < lim) {
                pos = i + 1
                return finish()
            }
            pos = lim
        }
    }

    private fun append(n: Int) {
        if (accLen + n > acc.size) acc = acc.copyOf(maxOf(acc.size * 2, accLen + n))
        System.arraycopy(buf, pos, acc, accLen, n)
        accLen += n
    }

    private fun finish(): ByteArray {
        var n = accLen
        if (n > 0 && acc[n - 1] == CR) n--
        return acc.copyOf(n)
    }

    private companion object {
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}
