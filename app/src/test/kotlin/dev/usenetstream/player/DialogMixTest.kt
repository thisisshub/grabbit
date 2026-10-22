package dev.usenetstream.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DialogMixTest {
    /** One 5.1 frame in, the stereo left sample out. */
    private fun leftOut(frame: FloatArray, boost: Boolean): Float {
        val field = DialogBoost::class.java.getDeclaredField("_on").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(DialogBoost) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = boost
        val p = DialogMix()
        val fmt = p.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_FLOAT))
        assertEquals(2, fmt.channelCount)
        p.flush()
        val buf = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
        frame.forEach { buf.putFloat(it) }
        buf.flip()
        p.queueInput(buf)
        return p.output.order(ByteOrder.nativeOrder()).float
    }

    private val voice = floatArrayOf(0f, 0f, 0.5f, 0f, 0f, 0f) // centre only
    private val boom = floatArrayOf(0f, 0f, 0f, 0.5f, 0.5f, 0.5f) // LFE + surrounds

    @Test
    fun `boost makes voices louder and effects quieter`() {
        assertTrue(leftOut(voice, boost = true) > leftOut(voice, boost = false) * 1.5f)
        assertTrue(leftOut(boom, boost = true) < leftOut(boom, boost = false) * 0.8f)
    }

    @Test
    fun `stereo input is left alone`() {
        val p = DialogMix()
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, runCatching { p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)) }.getOrNull())
    }

    @Test
    fun `loud frames never wrap around`() {
        val all = FloatArray(6) { 1f }
        val l = leftOut(all, boost = true)
        assertTrue(l in 0.9f..1f, "$l")
    }
}
