package dev.usenetstream.engine.yenc

import java.io.IOException
import java.util.zip.CRC32

class YencException(message: String) : IOException(message)

/**
 * One decoded yEnc article.
 * [begin] is the 0-based offset of [data] inside the whole posted file (from `=ypart begin=`, which is 1-based).
 */
class YencPart(
    val name: String,
    val totalSize: Long,
    val begin: Long,
    val data: ByteArray,
    /** null when the article carried no CRC to check. */
    val crcOk: Boolean?,
)

object YencDecoder {
    private val YBEGIN = "=ybegin ".toByteArray()
    private val YPART = "=ypart ".toByteArray()
    private val YEND = "=yend".toByteArray()

    /** [lines] is an article body with CRLF stripped and NNTP dot-stuffing already removed. */
    fun decode(lines: List<ByteArray>): YencPart = Streaming().apply { lines.forEach { line(it, 0, it.size) } }.finish()

    /**
     * Line-by-line decoder: feed each body line as it is read off the socket (no per-line copies, no list of
     * thousands of small arrays), then [finish]. Keeps memory per article to roughly the decoded size.
     */
    class Streaming {
        private var head: Map<String, String>? = null
        private var part: Map<String, String>? = null
        private var tail: Map<String, String>? = null
        private var out: ByteBuffer? = null
        private var expectPart = false

        fun line(buf: ByteArray, off: Int, len: Int) {
            if (tail != null) return
            if (head == null) {
                if (startsWith(buf, off, len, YBEGIN)) {
                    head = parseHeader(buf, off, len)
                    expectPart = true
                }
                return
            }
            if (expectPart) {
                expectPart = false
                if (startsWith(buf, off, len, YPART)) {
                    part = parseHeader(buf, off, len)
                    return
                }
            }
            if (startsWith(buf, off, len, YEND)) {
                tail = parseHeader(buf, off, len)
                return
            }
            decodeLine(buf, off, len, buffer())
        }

        private fun buffer(): ByteBuffer = out ?: run {
            val total = head!!["size"]?.toLongOrNull() ?: 0L
            val begin = part?.get("begin")?.toLongOrNull()?.minus(1) ?: 0L
            val end = part?.get("end")?.toLongOrNull() ?: total
            ByteBuffer((end - begin).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()).also { out = it }
        }

        fun finish(): YencPart {
            val h = head ?: throw YencException("no =ybegin line")
            val t = tail ?: throw YencException("no =yend line (truncated article)")
            val totalSize = h["size"]?.toLongOrNull() ?: throw YencException("=ybegin without size")
            val begin = part?.get("begin")?.toLongOrNull()?.minus(1) ?: 0L
            val data = (out ?: buffer()).toByteArray()
            val expectedCrc = (if (part != null) t["pcrc32"] else t["crc32"])?.toLongOrNull(16)
            val crcOk = expectedCrc?.let { crc -> CRC32().apply { update(data) }.value == crc }
            return YencPart(h["name"].orEmpty(), totalSize, begin, data, crcOk)
        }
    }

    private fun decodeLine(line: ByteArray, off: Int, len: Int, out: ByteBuffer) {
        var j = off
        val end = off + len
        while (j < end) {
            var b = line[j].toInt() and 0xFF
            if (b == '='.code) {
                j++
                if (j >= end) break
                b = (line[j].toInt() and 0xFF) - 64
            } else if (b == '\r'.code || b == '\n'.code) {
                j++
                continue
            }
            out.put((b - 42).toByte())
            j++
        }
    }

    /** `=ybegin part=1 line=128 size=123 name=a b.mkv` -> map; `name` always runs to end of line. */
    private fun parseHeader(line: ByteArray, off: Int, len: Int): Map<String, String> {
        val text = String(line, off, len, Charsets.ISO_8859_1)
        val map = HashMap<String, String>()
        val nameAt = text.indexOf(" name=")
        val keys = if (nameAt >= 0) {
            map["name"] = text.substring(nameAt + 6).trim()
            text.substring(0, nameAt)
        } else {
            text
        }
        for (token in keys.split(' ').drop(1)) {
            val eq = token.indexOf('=')
            if (eq > 0) map[token.substring(0, eq)] = token.substring(eq + 1).trim()
        }
        return map
    }

    private fun startsWith(buf: ByteArray, off: Int, len: Int, prefix: ByteArray): Boolean {
        if (len < prefix.size) return false
        for (k in prefix.indices) if (buf[off + k] != prefix[k]) return false
        return true
    }

    /** Unsynchronized growable byte array; ByteArrayOutputStream is slow per-byte. */
    private class ByteBuffer(initial: Int) {
        private var bytes = ByteArray(initial.coerceAtLeast(16))
        private var len = 0

        fun put(b: Byte) {
            if (len == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[len++] = b
        }

        fun toByteArray(): ByteArray = if (len == bytes.size) bytes else bytes.copyOf(len)
    }
}
