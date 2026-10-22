package dev.usenetstream.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Folds 5.1/7.1 down to stereo ourselves so dialog boost can work where the voices are: the centre channel.
 * Boost off = standard ITU downmix (centre and surrounds at -3 dB, LFE dropped). Boost on = centre lifted, the
 * front pair and surrounds pulled back. Gains are read live, so the switch takes effect immediately.
 * Channel order (Android): FL, FR, FC, LFE, BL, BR[, SL, SR].
 */
@OptIn(UnstableApi::class)
class DialogMix : BaseAudioProcessor() {
    private var channels = 0
    private var float = false

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.channelCount != 6 && input.channelCount != 8) return AudioProcessor.AudioFormat.NOT_SET
        if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        channels = input.channelCount
        float = input.encoding == C.ENCODING_PCM_FLOAT
        return AudioProcessor.AudioFormat(input.sampleRate, 2, input.encoding)
    }

    override fun queueInput(input: ByteBuffer) {
        val bytesPerSample = if (float) 4 else 2
        val frames = input.remaining() / (bytesPerSample * channels)
        val out = replaceOutputBuffer(frames * 2 * bytesPerSample).order(ByteOrder.nativeOrder())
        val g = gains(DialogBoost.on.value)
        val src = input.order(ByteOrder.nativeOrder())
        val ch = FloatArray(channels)
        repeat(frames) {
            for (c in 0 until channels) ch[c] = if (float) src.float else src.short / 32768f
            val sideL = ch[4] + if (channels == 8) ch[6] else 0f
            val sideR = ch[5] + if (channels == 8) ch[7] else 0f
            var l = g.front * ch[0] + g.centre * ch[2] + g.surround * sideL + g.lfe * ch[3]
            var r = g.front * ch[1] + g.centre * ch[2] + g.surround * sideR + g.lfe * ch[3]
            // Soft clip instead of hard wrap when everything is loud at once.
            l = softClip(l)
            r = softClip(r)
            if (float) {
                out.putFloat(l); out.putFloat(r)
            } else {
                out.putShort((l * 32767f).toInt().toShort()); out.putShort((r * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }

    private data class Gains(val front: Float, val centre: Float, val surround: Float, val lfe: Float)

    private fun gains(boost: Boolean) =
        if (boost) Gains(front = 0.55f, centre = 1.25f, surround = 0.35f, lfe = 0.15f)
        else Gains(front = 0.71f, centre = 0.71f, surround = 0.5f, lfe = 0.25f)

    /** Linear up to 0.9, then eases into ±1 instead of wrapping around. */
    private fun softClip(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= 0.9f) return x
        return kotlin.math.sign(x) * (0.9f + 0.1f * kotlin.math.tanh((a - 0.9f) / 0.1f))
    }
}
