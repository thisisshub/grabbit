package dev.usenetstream.player

import android.view.KeyEvent as K
import kotlin.test.Test
import kotlin.test.assertEquals

class TvKeysTest {
    private fun a(code: Int, bar: Boolean = false, panel: Boolean = false) = TvKeys.action(code, bar, panel)

    @Test
    fun `video focused - remote keys drive playback`() {
        assertEquals(TvAction.PLAY_PAUSE, a(K.KEYCODE_DPAD_CENTER))
        assertEquals(TvAction.PLAY_PAUSE, a(K.KEYCODE_ENTER))
        assertEquals(TvAction.SEEK_BACK, a(K.KEYCODE_DPAD_LEFT))
        assertEquals(TvAction.SEEK_FORWARD, a(K.KEYCODE_DPAD_RIGHT))
        assertEquals(TvAction.SHOW_TRACKS, a(K.KEYCODE_DPAD_UP))
        assertEquals(TvAction.SHOW_CONTROLS, a(K.KEYCODE_DPAD_DOWN))
        assertEquals(TvAction.SHOW_EPISODES, a(K.KEYCODE_MENU))
    }

    @Test
    fun `control bar focused - D-pad moves between buttons, media keys still work`() {
        listOf(K.KEYCODE_DPAD_LEFT, K.KEYCODE_DPAD_RIGHT, K.KEYCODE_DPAD_UP, K.KEYCODE_DPAD_DOWN, K.KEYCODE_DPAD_CENTER)
            .forEach { assertEquals(TvAction.NONE, a(it, bar = true)) }
        assertEquals(TvAction.PLAY_PAUSE, a(K.KEYCODE_MEDIA_PLAY_PAUSE, bar = true))
        assertEquals(TvAction.SEEK_FORWARD, a(K.KEYCODE_MEDIA_FAST_FORWARD, bar = true))
        assertEquals(TvAction.SEEK_BACK, a(K.KEYCODE_MEDIA_REWIND, bar = true))
    }

    @Test
    fun `open panel owns every key`() {
        listOf(K.KEYCODE_DPAD_CENTER, K.KEYCODE_DPAD_LEFT, K.KEYCODE_MEDIA_PLAY_PAUSE).forEach { assertEquals(TvAction.NONE, a(it, panel = true)) }
    }

    @Test
    fun `holding a seek key speeds it up`() {
        assertEquals(10_000L, TvKeys.seekStepMs(0))
        assertEquals(10_000L, TvKeys.seekStepMs(3))
        assertEquals(30_000L, TvKeys.seekStepMs(4))
        assertEquals(60_000L, TvKeys.seekStepMs(12))
    }
}
