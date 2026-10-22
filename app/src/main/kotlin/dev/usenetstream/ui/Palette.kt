package dev.usenetstream.ui

import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Every color the UI uses. Screens read these through [LocalPalette] (see Theme.kt), never hard-coded. */
data class Palette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val fg: Color,
    val muted: Color,
    /** Small accent things: progress bars, "BEST", "Up next", links. Tuned so small text passes AA. */
    val accent: Color,
    val onAccent: Color,
    /** The CTA (Play): indigo with a white label. */
    val primary: Color,
    val onPrimary: Color,
    val divider: Color,
    /** Frosted-glass fill and its light-catching edge. */
    val glass: Color,
    val glassEdge: Color,
    /** Bars that float over content (bottom nav, action bar). */
    val bar: Color,
)

/** Light: blue-white canvas, white cards, near-black indigo ink, electric indigo CTA. */
val LightPalette = Palette(
    isDark = false,
    bg = Color(0xFFF6F6FD),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE8E9FB),
    fg = Color(0xFF080616),
    muted = Color(0xFF4B4E78),
    accent = Color(0xFF2F2FE4),
    onAccent = Color(0xFFFFFFFF),
    primary = Color(0xFF2F2FE4),
    onPrimary = Color(0xFFFFFFFF),
    divider = Color(0xFFDCDDF3),
    glass = Color(0xD9E8E9FB),
    glassEdge = Color(0xF2FFFFFF),
    bar = Color(0xCCF6F6FD),
)

/** Dark (default): space-black #080616, midnight #1A1953 cards, navy #162E93 chips, indigo #2F2FE4 CTA. */
val DarkPalette = Palette(
    isDark = true,
    bg = Color(0xFF080616),
    surface = Color(0xFF1A1953),
    surface2 = Color(0xFF162E93),
    fg = Color(0xFFF4F4FF),
    muted = Color(0xFFA9ABD9),
    // Small text/progress on the near-black page: the CTA indigo is too dark there, so a lighter tint.
    accent = Color(0xFF8C8CFF),
    onAccent = Color(0xFF080616),
    primary = Color(0xFF2F2FE4),
    onPrimary = Color(0xFFFFFFFF),
    divider = Color(0xFF24236A),
    glass = Color(0x24FFFFFF),
    glassEdge = Color(0x40FFFFFF),
    bar = Color(0xB8080616),
)

/** WCAG relative luminance contrast ratio (1..21). */
fun contrast(a: Color, b: Color): Double {
    fun lum(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}
