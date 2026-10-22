package dev.usenetstream.engine

import dev.usenetstream.engine.nzb.NzbParser
import dev.usenetstream.engine.yenc.YencDecoder
import dev.usenetstream.engine.yenc.YencException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NzbParserTest {
    @Test
    fun `parses files, sorts and dedupes segments, ignores external DTD`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">
            <nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
              <head><meta type="title">Test</meta><meta type="password">cbH pw</meta></head>
              <file poster="a@b" date="1" subject="Test [1/2] - &quot;movie.part01.rar&quot; yEnc (1/3)">
                <groups><group>alt.binaries.test</group></groups>
                <segments>
                  <segment bytes="300" number="3">c@x</segment>
                  <segment bytes="100" number="1">a@x</segment>
                  <segment bytes="200" number="2">b@x</segment>
                  <segment bytes="200" number="2">b@x</segment>
                </segments>
              </file>
              <file poster="a@b" date="1" subject="no quotes here">
                <groups><group>alt.binaries.test</group></groups>
                <segments><segment bytes="5" number="1">z@x</segment></segments>
              </file>
            </nzb>
        """.trimIndent()

        val nzb = NzbParser.parse(xml.byteInputStream())

        assertEquals(2, nzb.files.size)
        val first = nzb.files[0]
        assertEquals("movie.part01.rar", first.name)
        assertEquals(listOf(1, 2, 3), first.segments.map { it.number })
        assertEquals(listOf("a@x", "b@x", "c@x"), first.segments.map { it.messageId })
        assertEquals(600L, first.encodedBytes)
        assertEquals(listOf("alt.binaries.test"), first.groups)
        assertEquals("no quotes here", nzb.files[1].name)
        assertEquals("cbH pw", nzb.password)
    }
}

class YencDecoderTest {
    @Test
    fun `round-trips every byte value including critical characters`() {
        val data = ByteArray(256 * 4) { (it % 256).toByte() } + Random(1).nextBytes(5000)
        val lines = YencTestEncoder.encodePart("a b.bin", 10_000, 2, 1234, data, lineLength = 64)

        val part = YencDecoder.decode(lines)

        assertContentEquals(data, part.data)
        assertEquals("a b.bin", part.name)
        assertEquals(10_000L, part.totalSize)
        assertEquals(1234L, part.begin)
        assertEquals(true, part.crcOk)
    }

    @Test
    fun `known vector decodes byte-exact`() {
        // "Hello" + 42 = "r\x8F\x96\x96\x99"
        val lines = listOf(
            "=ybegin line=128 size=5 name=h.txt".toByteArray(),
            byteArrayOf(0x72, 0x8F.toByte(), 0x96.toByte(), 0x96.toByte(), 0x99.toByte()),
            "=yend size=5 crc32=f7d18982".toByteArray(),
        )

        val part = YencDecoder.decode(lines)

        assertEquals("Hello", String(part.data))
        assertEquals(0L, part.begin)
        assertEquals(true, part.crcOk)
    }

    @Test
    fun `flags a bad CRC and rejects a truncated article`() {
        val data = Random(2).nextBytes(1000)
        val lines = YencTestEncoder.encodePart("x", 1000, 1, 0, data).toMutableList()
        lines[lines.size - 1] = "=yend size=1000 part=1 pcrc32=deadbeef".toByteArray()

        assertFalse(YencDecoder.decode(lines).crcOk!!)
        assertFailsWith<YencException> { YencDecoder.decode(lines.dropLast(1)) }
        assertTrue(runCatching { YencDecoder.decode(listOf("junk".toByteArray())) }.isFailure)
    }
}
