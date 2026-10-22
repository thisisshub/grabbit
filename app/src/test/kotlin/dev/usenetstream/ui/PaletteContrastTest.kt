package dev.usenetstream.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

/** Light-weight fonts need real contrast: every text/background pair must pass WCAG AA (4.5:1). */
class PaletteContrastTest {
    private fun check(p: Palette) {
        val pairs = listOf(
            "fg/bg" to (p.fg to p.bg),
            "fg/surface" to (p.fg to p.surface),
            "fg/surface2" to (p.fg to p.surface2),
            "muted/bg" to (p.muted to p.bg),
            "muted/surface" to (p.muted to p.surface),
            "accent/bg" to (p.accent to p.bg),
            "onAccent/accent" to (p.onAccent to p.accent),
            "surface2/fg" to (p.fg to p.surface2),
            // Glass is translucent: judge text against glass laid over the page background.
            "fg/glass-on-bg" to (p.fg to over(p.glass, p.bg)),
        )
        for ((name, pair) in pairs) {
            val r = contrast(pair.first, pair.second)
            assertTrue(r >= 4.5, "${if (p.isDark) "dark" else "light"} $name contrast ${"%.2f".format(r)} < 4.5")
        }
    }

    private fun over(top: Color, bottom: Color): Color {
        val a = top.alpha
        return Color(
            red = top.red * a + bottom.red * (1 - a),
            green = top.green * a + bottom.green * (1 - a),
            blue = top.blue * a + bottom.blue * (1 - a),
        )
    }

    /** The red CTA label is 18sp Bold = WCAG "large text", whose minimum is 3:1 (white on #E50914 is ~4.0). */
    @Test
    fun `CTA label meets AA for large text`() {
        for (p in listOf(LightPalette, DarkPalette)) assertTrue(contrast(p.onPrimary, p.primary) >= 3.0)
    }

    @Test
    fun `light palette meets AA`() = check(LightPalette)

    @Test
    fun `dark palette meets AA`() = check(DarkPalette)
}

class GuideLabelTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `new and leaving labels count whole days`() {
        kotlin.test.assertEquals("NEW · today", sinceLabel(now / 1000 - 3_600, now))
        kotlin.test.assertEquals("NEW · yesterday", sinceLabel(now / 1000 - 86_400 - 60, now))
        kotlin.test.assertEquals("NEW · 5d ago", sinceLabel(now / 1000 - 5 * 86_400 - 60, now))
        kotlin.test.assertEquals("Leaves in 3d", leavesLabel(now / 1000 + 3 * 86_400 + 60, now))
        kotlin.test.assertEquals("Leaves today", leavesLabel(now / 1000 + 60, now))
    }
}

class FeedbackScrubTest {
    @Test
    fun `error text loses keys, passwords and links before it goes to GitHub`() {
        val raw = "NZB download failed (https://api.nzbgeek.info/api?t=get&id=1&apikey=abc123): HTTP 429; key motn-key-v4-XYZ; password=hunter2 user=bob"
        val out = dev.usenetstream.feedback.Feedback.scrub(raw)
        for (secret in listOf("abc123", "motn-key-v4-XYZ", "hunter2", "bob", "nzbgeek")) {
            kotlin.test.assertFalse(out.contains(secret), "$secret leaked: $out")
        }
        kotlin.test.assertTrue(out.contains("HTTP 429"))
    }
}

class PlaybackCategoryTest {
    @Test
    fun `failure reasons map to title-free categories`() {
        val c = dev.usenetstream.feedback.Relay.Companion::categoryOf
        kotlin.test.assertEquals("compressed-rar", c("compressed RAR (not -m0); download needed"))
        kotlin.test.assertEquals("compressed-7z", c("compressed 7z (LZMA); download needed"))
        kotlin.test.assertEquals("missing-volumes", c("first RAR volume is missing; unreadable: Some.Movie.2010.part03.rar"))
        kotlin.test.assertEquals("missing-articles", c("x.mkv: article not found: <abc@def>"))
        kotlin.test.assertEquals("archive-password", c("wrong archive password"))
        kotlin.test.assertEquals("other", c("something new"))
    }
}
