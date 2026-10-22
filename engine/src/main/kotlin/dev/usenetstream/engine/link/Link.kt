package dev.usenetstream.engine.link

import dev.usenetstream.engine.tv.Json
import dev.usenetstream.engine.tv.obj
import dev.usenetstream.engine.tv.str
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Phone -> TV link on the home network.
 *
 * The TV runs [LinkServer]. A phone pairs once with the code shown on the TV ([PairingGuard]) and gets a token;
 * after that it can send search text and "play this". The same code also unlocks the one-time setup page, so
 * the server/indexer details can be typed on the phone instead of with a remote.
 */
object LinkProtocol {
    const val SERVICE_TYPE = "_usenetstream._tcp"
    const val PORT = 47331
    const val TOKEN_HEADER = "x-link-token"

    fun json(vararg pairs: Pair<String, Any?>): String = pairs.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "${quote(it.key.toString())}:${value(it.value)}" }
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> quote(v.toString())
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** Parses a JSON object body; null if it isn't one. */
    fun parse(body: String): Map<String, Any?>? = runCatching { Json.parse(body).obj() }.getOrNull()

    fun Map<String, Any?>.text(key: String, max: Int = 500): String? = str(key)?.trim()?.take(max)?.ifEmpty { null }

    /** Only home-network peers: private IPv4/IPv6 ranges and loopback. */
    fun isLocal(a: InetAddress): Boolean = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
        (a.address.size == 16 && (a.address[0].toInt() and 0xFE) == 0xFC) // fc00::/7 unique-local
}

/**
 * The TV's pairing code and the tokens of phones that used it.
 * Wrong codes are counted; [maxAttempts] misses inside [lockMs] lock pairing until the window passes.
 */
class PairingGuard(
    initialTokens: Map<String, String> = emptyMap(),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val maxAttempts: Int = 5,
    private val lockMs: Long = 60_000,
) {
    /** token -> phone name. */
    private val tokens = ConcurrentHashMap(initialTokens)
    private val misses = ArrayDeque<Long>()

    @Volatile var code: String = newCode()
        private set

    fun rotateCode(): String = newCode().also { code = it }

    private fun newCode() = "%06d".format(random.nextInt(1_000_000))

    val paired: Map<String, String> get() = tokens.toMap()

    @Synchronized
    fun lockedFor(): Long {
        val t = now()
        while (misses.isNotEmpty() && t - misses.first() > lockMs) misses.removeFirst()
        return if (misses.size >= maxAttempts) lockMs - (t - misses.first()) else 0
    }

    /** Checks [attempt] against the current code without pairing (the setup page). */
    @Synchronized
    fun checkCode(attempt: String?): Result {
        if (lockedFor() > 0) return Result.LOCKED
        if (attempt != null && constantTimeEquals(attempt.trim(), code)) return Result.OK
        misses.addLast(now())
        return if (lockedFor() > 0) Result.LOCKED else Result.WRONG
    }

    /** Pairs [phoneName] with [attempt]; returns its new token, or why not. */
    @Synchronized
    fun pair(attempt: String?, phoneName: String): Pair<Result, String?> {
        val r = checkCode(attempt)
        if (r != Result.OK) return r to null
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tokens[token] = phoneName.take(60).ifBlank { "Phone" }
        rotateCode() // a code pairs one phone
        return Result.OK to token
    }

    fun phoneFor(token: String?): String? = token?.let { tokens[it] }

    fun unpair(token: String) {
        tokens.remove(token)
    }

    enum class Result { OK, WRONG, LOCKED }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

/** What the TV app does with requests that passed the checks. */
interface LinkHandler {
    val tvName: String
    fun onSearch(query: String, phone: String)
    fun onPlay(key: String, title: String, coverUrl: String?, phone: String)
    /** Setup page submit; return an error message, or null when saved. */
    fun onSetup(fields: Map<String, Any?>): String?
    fun onPaired(token: String, phone: String)
    /** A phone is about to pair: show the code on screen. */
    fun onPairRequest(phone: String) = Unit
}

/**
 * Minimal HTTP/1.1 server (one request per connection) for the link. Plain sockets, no dependencies,
 * so it runs and is tested on the JVM too.
 */
class LinkServer(
    private val guard: PairingGuard,
    private val handler: LinkHandler,
    private val setupPage: () -> String,
    private val port: Int = LinkProtocol.PORT,
) : Closeable {
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "link-http").apply { isDaemon = true } }

    val boundPort: Int get() = socket?.localPort ?: -1

    fun start(): LinkServer {
        val s = ServerSocket(port)
        socket = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: SocketException) { break }
                pool.execute { c.use(::serve) }
            }
        }, "link-accept").apply { isDaemon = true }.start()
        return this
    }

    override fun close() {
        socket?.close()
        pool.shutdownNow()
    }

    private fun serve(c: Socket) {
        c.soTimeout = 10_000
        if (!LinkProtocol.isLocal(c.inetAddress)) return respond(c.getOutputStream(), 403, err("home network only"))
        val req = try { readRequest(BufferedInputStream(c.getInputStream())) } catch (e: IOException) { return } ?: return
        val out = c.getOutputStream()
        val path = req.path.substringBefore('?')
        val body by lazy { LinkProtocol.parse(req.body) }
        val phone = guard.phoneFor(req.headers[LinkProtocol.TOKEN_HEADER])
        with(LinkProtocol) {
            when {
                req.method == "GET" && path == "/ping" -> respond(out, 200, json("name" to handler.tvName, "paired" to (phone != null)))
                req.method == "GET" && path == "/setup" -> respond(out, 200, setupPage(), "text/html; charset=utf-8")
                req.method == "POST" && path == "/pair/request" -> {
                    // Only puts the code on the TV screen; pairing still needs it typed on the phone.
                    if (guard.lockedFor() > 0) return respond(out, 429, err("too many tries, wait a minute"))
                    handler.onPairRequest(body?.text("name", 60) ?: "A phone")
                    respond(out, 200, json("ok" to true, "name" to handler.tvName))
                }
                req.method == "POST" && path == "/pair" -> {
                    val b = body ?: return respond(out, 400, err("bad json"))
                    val (r, token) = guard.pair(b.text("code", 12), b.text("name", 60) ?: "Phone")
                    when (r) {
                        PairingGuard.Result.OK -> {
                            handler.onPaired(token!!, b.text("name", 60) ?: "Phone")
                            respond(out, 200, json("token" to token, "name" to handler.tvName))
                        }
                        PairingGuard.Result.WRONG -> respond(out, 401, err("wrong code"))
                        PairingGuard.Result.LOCKED -> respond(out, 429, err("too many tries, wait a minute"))
                    }
                }
                req.method == "POST" && path == "/setup" -> {
                    val b = body ?: return respond(out, 400, err("bad json"))
                    when (guard.checkCode(b.text("code", 12))) {
                        PairingGuard.Result.OK -> {
                            val problem = handler.onSetup(b)
                            if (problem == null) {
                                guard.rotateCode()
                                respond(out, 200, json("ok" to true))
                            } else {
                                respond(out, 422, err(problem))
                            }
                        }
                        PairingGuard.Result.WRONG -> respond(out, 401, err("wrong code"))
                        PairingGuard.Result.LOCKED -> respond(out, 429, err("too many tries, wait a minute"))
                    }
                }
                req.method == "POST" && (path == "/search" || path == "/play") && phone == null -> respond(out, 401, err("not paired"))
                req.method == "POST" && path == "/search" -> {
                    val q = body?.text("query", 200) ?: return respond(out, 400, err("empty query"))
                    handler.onSearch(q, phone!!)
                    respond(out, 200, json("ok" to true))
                }
                req.method == "POST" && path == "/play" -> {
                    val b = body ?: return respond(out, 400, err("bad json"))
                    val key = b.text("key", 200) ?: return respond(out, 400, err("no key"))
                    handler.onPlay(key, b.text("title", 200) ?: key, b.text("cover", 500), phone!!)
                    respond(out, 200, json("ok" to true))
                }
                else -> respond(out, 404, err("not found"))
            }
        }
    }

    private fun err(msg: String) = LinkProtocol.json("error" to msg)

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        fun line(): String? {
            val b = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return if (b.isEmpty()) null else b.toString()
                if (c == '\n'.code) return b.toString().trimEnd('\r')
                if (b.length > 8_192) throw IOException("header too long")
                b.append(c.toChar())
            }
        }
        val start = line() ?: return null
        val parts = start.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        return Request(parts[0].uppercase(), parts[1], headers, String(body, 0, read, Charsets.UTF_8))
    }

    private fun respond(out: OutputStream, status: Int, body: String, type: String = "application/json") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 422 to "Unprocessable", 429 to "Too Many")[status] ?: "OK"
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }
}

/** The phone's side: talks to one TV. Throws [LinkException] with the TV's message on errors. */
class LinkClient(private val host: String, private val port: Int = LinkProtocol.PORT, var token: String? = null, private val timeoutMs: Int = 4_000) {
    fun ping(): String = call("GET", "/ping", null).text("name") ?: "TV"

    /** Asks the TV to show its pairing code. */
    fun requestPair(phoneName: String) {
        call("POST", "/pair/request", LinkProtocol.json("name" to phoneName))
    }

    /** Pairs with the code shown on the TV; stores and returns the token. */
    fun pair(code: String, phoneName: String): String =
        call("POST", "/pair", LinkProtocol.json("code" to code, "name" to phoneName)).text("token")!!.also { token = it }

    fun search(query: String) {
        call("POST", "/search", LinkProtocol.json("query" to query))
    }

    fun play(key: String, title: String, coverUrl: String?) {
        call("POST", "/play", LinkProtocol.json("key" to key, "title" to title, "cover" to coverUrl))
    }

    private fun Map<String, Any?>.text(k: String) = with(LinkProtocol) { text(k) }

    private fun call(method: String, path: String, body: String?): Map<String, Any?> {
        val conn = java.net.URI("http://$host:$port$path").toURL().openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            token?.let { conn.setRequestProperty(LinkProtocol.TOKEN_HEADER, it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }.orEmpty()
            val json = LinkProtocol.parse(text) ?: emptyMap()
            if (status !in 200..299) throw LinkException(status, (json["error"] as? String) ?: "HTTP $status")
            return json
        } finally {
            conn.disconnect()
        }
    }
}

class LinkException(val status: Int, message: String) : IOException(message)
