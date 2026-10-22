package dev.usenetstream.player

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Dialog boost": an EQ on the player's audio session that lifts speech (1–4 kHz), tames rumble below 150 Hz,
 * and a limiter that keeps explosions from jumping out once the voice is louder. Needs Android 9 (DynamicsProcessing).
 * Has no effect when audio goes out undecoded (HDMI passthrough to an AV receiver).
 */
object DialogBoost {
    private lateinit var prefs: android.content.SharedPreferences
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private var effect: DynamicsProcessing? = null
    private var session = 0

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("audio", Context.MODE_PRIVATE)
        _on.value = prefs.getBoolean("dialog_boost", false)
    }

    /** @return false when the device refused the effect. */
    fun set(enabled: Boolean): Boolean {
        _on.value = enabled
        prefs.edit().putBoolean("dialog_boost", enabled).apply()
        return attach(session)
    }

    /** Called whenever the player gets (or changes) its audio session. */
    fun attach(audioSessionId: Int): Boolean {
        session = audioSessionId
        release()
        if (!_on.value || audioSessionId == 0 || !supported) return true
        return try {
            effect = build(audioSessionId).apply { enabled = true }
            true
        } catch (e: Exception) {
            effect = null
            false
        }
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun build(sessionId: Int): DynamicsProcessing {
        val cutoffs = floatArrayOf(150f, 500f, 1000f, 2500f, 4000f, 8000f, 20000f)
        val gains = floatArrayOf(-4f, -1f, 2f, 6f, 5f, 1f, 0f)
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, true, cutoffs.size, false, 0, false, 0, true,
        ).build()
        val dp = DynamicsProcessing(0, sessionId, cfg)
        val eq = DynamicsProcessing.Eq(true, true, cutoffs.size)
        for (i in cutoffs.indices) eq.setBand(i, DynamicsProcessing.EqBand(true, cutoffs[i], gains[i]))
        dp.setPreEqAllChannelsTo(eq)
        // Attack 1 ms, release 60 ms, ratio 10:1 above -6 dBFS: keeps peaks in check after the lift.
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -6f, 0f))
        return dp
    }
}
