package dev.usenetstream.engine

import dev.usenetstream.engine.rar.NotStreamableException
import dev.usenetstream.engine.sevenz.JoinedSource
import dev.usenetstream.engine.sevenz.SevenZ
import dev.usenetstream.engine.sevenz.WrongPasswordException
import dev.usenetstream.engine.stream.RandomAccessSource
import dev.usenetstream.engine.stream.readFully
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fixtures made with 7-Zip 26: `7zz a -mx0 [-p"s3cret pw" -mhe=on] -v64k` over info.nfo + movie.mkv (300000 bytes). */
class SevenZTest {
    private class Bytes(val b: ByteArray) : RandomAccessSource {
        override val length: Long get() = b.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= b.size) return -1
            val n = minOf(length, b.size - position.toInt())
            System.arraycopy(b, position.toInt(), buffer, offset, n)
            return n
        }
    }

    private fun res(name: String) = Bytes(javaClass.getResource("/sevenz/$name")!!.readBytes())
    private fun volumes(base: String) = JoinedSource((1..5).map { res("$base.7z.%03d".format(it)) })

    private val movie = ByteArray(300_000) { ((it * 31 + it / 7) and 0xFF).toByte() }.also {
        it[0] = 0x1A; it[1] = 0x45; it[2] = 0xDF.toByte(); it[3] = 0xA3.toByte()
    }

    private fun checkMovie(src: RandomAccessSource) {
        assertEquals(movie.size.toLong(), src.length)
        assertContentEquals(movie, src.readFully(0, movie.size))
        // Random seeks, including across volume and AES block edges.
        val rnd = Random(7)
        repeat(200) {
            val pos = rnd.nextInt(movie.size - 1)
            val n = minOf(rnd.nextInt(1, 5000), movie.size - pos)
            assertContentEquals(movie.copyOfRange(pos, pos + n), src.readFully(pos.toLong(), n), "at $pos+$n")
        }
    }

    @Test
    fun `encrypted header and data stream byte-exact with the NZB password`() {
        val src = volumes("enc")
        val archive = SevenZ.parse(src, "s3cret pw")
        assertEquals(listOf("src/info.nfo", "src/movie.mkv"), archive.entries.map { it.name }.sorted())
        val entry = archive.entries.first { it.name.endsWith(".mkv") }
        checkMovie(SevenZ.open(src, archive, entry, "s3cret pw"))
        val nfo = archive.entries.first { it.name.endsWith(".nfo") }
        assertEquals("release notes\n", String(SevenZ.open(src, archive, nfo, "s3cret pw").readFully(0, nfo.size.toInt())))
    }

    @Test
    fun `stored archive without password streams too`() {
        val src = volumes("plain")
        val archive = SevenZ.parse(src, null)
        checkMovie(SevenZ.open(src, archive, archive.entries.first { it.name.endsWith(".mkv") }, null))
    }

    @Test
    fun `wrong or missing password is reported as such`() {
        assertFailsWith<WrongPasswordException> { SevenZ.parse(volumes("enc"), "nope") }
        assertFailsWith<WrongPasswordException> { SevenZ.parse(volumes("enc"), null) }
    }

    @Test
    fun `compressed 7z is refused, not played as garbage`() {
        val src = res("lzma.7z")
        val archive = SevenZ.parse(src, null)
        val e = assertFailsWith<NotStreamableException> { SevenZ.open(src, archive, archive.entries.single(), null) }
        assertTrue(e.message!!.contains("compressed"), e.message)
    }
}
