/*
 * AG Widgets palette — six theme modes.
 *
 * Modern themes (graphite / paper / oled / glass) are calm, high-contrast
 * utility surfaces. The `classic` pair keeps the original Aetheria styling
 * (near-black glass + warm cream accent / paper white + violet accent) 1:1.
 *
 * Contrast invariants (see the shared token contract):
 *  - text / secondary / muted are >= 4.5:1 against background, surface and card;
 *  - onGlow is >= 4.5:1 on the filled glow accent;
 *  - fieldBorder and track are >= 3:1 against their own surface;
 *  - the status chip never draws its label in the colour it fills with.
 * `ThemeResolutionTest` enforces these numbers, so a palette tweak cannot
 * silently regress them.
 */

package dev.agwidgets.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AgColors(
    val background: Color,
    val surface: Color,
    val card: Color,
    val cardShadow: Color,
    val border: Color,
    val text: Color,
    val secondary: Color,
    val muted: Color,
    val glow: Color,
    val onGlow: Color,
    val violet: Color,
    val pink: Color,
    val green: Color,
    val amber: Color,
    val rose: Color,
    val badge: Color,
    val track: Color,
    val grid: Color,
    val glowMagenta: Color,
    val glowBlue: Color,
    val glowOrange: Color,
    val isDark: Boolean = true,
    val isClassic: Boolean = false,
    /** Corner radius for cards: 12 for modern themes, 10 for Classic. */
    val cardCornerDp: Int = 12,
    /** Input borders, inactive slider tracks: >= 3:1 against the surface. */
    val fieldBorder: Color = border,
    /** Accent used as text (chips, links, active labels): >= 4.5:1 on card. */
    val accentInk: Color = glow,
    /** Destructive fill that keeps white text readable: >= 4.5:1 with white. */
    val riskStrong: Color = rose,
    /** Hairline drawn on top of a glass card, inside its top edge. */
    val innerHighlight: Color = Color.Transparent,
    /** Glass themes paint a soft atmospheric wash instead of a flat background. */
    val isGlass: Boolean = false,
    /** Top-of-screen wash for glass themes; transparent everywhere else. */
    val backdropTint: Color = Color.Transparent,
)

/** Calm dark charcoal with a soft ice-blue accent. */
val GraphiteAgColors = AgColors(
    background = Color(0xFF121417),
    surface = Color(0xFF171A20),
    card = Color(0xFF1B1F26),
    cardShadow = Color(0x1F000000),
    border = Color(0x33FFFFFF),
    text = Color(0xFFE6EAF0),
    secondary = Color(0xFFA4ACB9),
    muted = Color(0xFF8E97A6),
    glow = Color(0xFF60A5FA),
    onGlow = Color(0xFF0B1220),
    violet = Color(0xFFA78BFA),
    pink = Color(0xFFA78BFA),
    green = Color(0xFF34D399),
    amber = Color(0xFFF5B544),
    rose = Color(0xFFF87171),
    badge = Color(0x12FFFFFF),
    track = Color(0x5CFFFFFF),
    grid = Color(0x00000000),
    glowMagenta = Color(0x00000000),
    glowBlue = Color(0x00000000),
    glowOrange = Color(0x00000000),
    isDark = true,
    isClassic = false,
    cardCornerDp = 12,
    fieldBorder = Color(0x66FFFFFF),
    accentInk = Color(0xFF7CB8FF),
    riskStrong = Color(0xFFDC2626),
)

/** Soft warm light paper, never blinding white. */
val PaperAgColors = AgColors(
    background = Color(0xFFF2F1EC),
    surface = Color(0xFFF6F5F0),
    card = Color(0xFFFAF9F6),
    cardShadow = Color(0x14000000),
    border = Color(0x4718181B),
    text = Color(0xFF1F2328),
    secondary = Color(0xFF4B535D),
    muted = Color(0xFF5E6670),
    glow = Color(0xFF2563EB),
    onGlow = Color(0xFFFFFFFF),
    violet = Color(0xFF6D28D9),
    pink = Color(0xFF6D28D9),
    green = Color(0xFF047857),
    amber = Color(0xFFB45309),
    rose = Color(0xFFBE123C),
    badge = Color(0x0F18181B),
    track = Color(0x7818181B),
    grid = Color(0x00000000),
    glowMagenta = Color(0x00000000),
    glowBlue = Color(0x00000000),
    glowOrange = Color(0x00000000),
    isDark = false,
    isClassic = false,
    cardCornerDp = 12,
    fieldBorder = Color(0x7A18181B),
    accentInk = Color(0xFF1D4ED8),
    riskStrong = Color(0xFFB91C1C),
)

/** Pure black AMOLED surface. */
val OledAgColors = AgColors(
    background = Color(0xFF000000),
    surface = Color(0xFF090B0E),
    card = Color(0xFF0E1116),
    cardShadow = Color(0x33000000),
    border = Color(0x38FFFFFF),
    text = Color(0xFFF4F6F8),
    secondary = Color(0xFFB4BCC6),
    muted = Color(0xFF8F99A8),
    glow = Color(0xFF60A5FA),
    onGlow = Color(0xFF05070A),
    violet = Color(0xFFA78BFA),
    pink = Color(0xFFA78BFA),
    green = Color(0xFF34D399),
    amber = Color(0xFFF5B544),
    rose = Color(0xFFF87171),
    badge = Color(0x14FFFFFF),
    track = Color(0x5CFFFFFF),
    grid = Color(0x00000000),
    glowMagenta = Color(0x00000000),
    glowBlue = Color(0x00000000),
    glowOrange = Color(0x00000000),
    isDark = true,
    isClassic = false,
    cardCornerDp = 12,
    fieldBorder = Color(0x66FFFFFF),
    accentInk = Color(0xFF7CB8FF),
    riskStrong = Color(0xFFDC2626),
)

/**
 * Matte glass, dark: cool slate-blue ground with an atmospheric wash, a
 * translucent card and an ice-cyan accent — never a Graphite clone.
 */
val GlassDarkAgColors = AgColors(
    background = Color(0xFF0B1220),
    surface = Color(0xCC131D2D),
    card = Color(0xBF1A263A),
    cardShadow = Color(0x1F000000),
    border = Color(0x47BAD6FF),
    text = Color(0xFFEDF2FA),
    secondary = Color(0xFFB2BFCF),
    muted = Color(0xFF94A3B8),
    glow = Color(0xFF7DD3FC),
    onGlow = Color(0xFF08121E),
    violet = Color(0xFFC4B5FD),
    pink = Color(0xFFC4B5FD),
    green = Color(0xFF34D399),
    amber = Color(0xFFF5B544),
    rose = Color(0xFFF87171),
    badge = Color(0x1ABAD6FF),
    track = Color(0x75BAD6FF),
    grid = Color(0x00000000),
    glowMagenta = Color(0x00000000),
    glowBlue = Color(0x00000000),
    glowOrange = Color(0x00000000),
    isDark = true,
    isClassic = false,
    cardCornerDp = 12,
    fieldBorder = Color(0x75BAD6FF),
    accentInk = Color(0xFF93DEFF),
    riskStrong = Color(0xFFDC2626),
    innerHighlight = Color(0x47BAD6FF),
    isGlass = true,
    backdropTint = Color(0x7316243D),
)

/**
 * Matte glass, light: cool misty blue that reads clearly colder than the warm
 * beige Paper, a translucent white card and a deep blue accent.
 */
val GlassLightAgColors = AgColors(
    background = Color(0xFFE4EAF2),
    surface = Color(0xCCEEF4FC),
    card = Color(0xD9FFFFFF),
    cardShadow = Color(0x14000000),
    border = Color(0x52172540),
    text = Color(0xFF161C24),
    secondary = Color(0xFF475261),
    muted = Color(0xFF586372),
    glow = Color(0xFF1D4ED8),
    onGlow = Color(0xFFFFFFFF),
    violet = Color(0xFF5B21B6),
    pink = Color(0xFF5B21B6),
    green = Color(0xFF047857),
    amber = Color(0xFFB45309),
    rose = Color(0xFFBE123C),
    badge = Color(0x12172540),
    track = Color(0x80172540),
    grid = Color(0x00000000),
    glowMagenta = Color(0x00000000),
    glowBlue = Color(0x00000000),
    glowOrange = Color(0x00000000),
    isDark = false,
    isClassic = false,
    cardCornerDp = 12,
    fieldBorder = Color(0x80172540),
    accentInk = Color(0xFF1E40AF),
    riskStrong = Color(0xFFB91C1C),
    innerHighlight = Color(0xD9FFFFFF),
    isGlass = true,
    backdropTint = Color(0xB8F7FAFF),
)

/** Original author styling, dark: near-black glass + warm cream accent. */
val ClassicDarkAgColors = AgColors(
    background = Color(0xFF050308),
    surface = Color(0xFF0C0A10),
    card = Color(0xF20C0A10),
    cardShadow = Color(0x99000000),
    border = Color(0x33FFFFFF),
    text = Color(0xFFFFFFFF),
    secondary = Color(0xFFA1A1AA),
    muted = Color(0xFF8F8F9A),
    glow = Color(0xFFFFE9B8),
    onGlow = Color(0xFF050308),
    violet = Color(0xFFB026FF),
    pink = Color(0xFFB026FF),
    green = Color(0xFF4EF2D2),
    amber = Color(0xFFF5A623),
    rose = Color(0xFFFF4D6D),
    badge = Color(0x0FFFFFFF),
    track = Color(0x1FFFFFFF),
    grid = Color(0x09FFFFFF),
    glowMagenta = Color(0x55B026FF),
    glowBlue = Color(0x442E7FD6),
    glowOrange = Color(0x2EFF6B35),
    isDark = true,
    isClassic = true,
    cardCornerDp = 10,
    fieldBorder = Color(0x44FFFFFF),
    accentInk = Color(0xFFFFE9B8),
    riskStrong = Color(0xFFDC2626),
)

/** Original author styling, light: paper white + violet accent. */
val ClassicLightAgColors = AgColors(
    background = Color(0xFFFBFBFA),
    surface = Color(0xFFFFFFFF),
    card = Color(0xF7FFFFFF),
    cardShadow = Color(0x24000000),
    border = Color(0x2409090B),
    text = Color(0xFF09090B),
    secondary = Color(0xFF52525B),
    muted = Color(0xFF6B6B76),
    glow = Color(0xFF7C3AED),
    onGlow = Color(0xFFFFFFFF),
    violet = Color(0xFF7C3AED),
    pink = Color(0xFF7C3AED),
    green = Color(0xFF047857),
    amber = Color(0xFFB45309),
    rose = Color(0xFFBE123C),
    badge = Color(0x0A09090B),
    track = Color(0x1F09090B),
    grid = Color(0x0909090B),
    glowMagenta = Color(0x1FB026FF),
    glowBlue = Color(0x1F2E7FD6),
    glowOrange = Color(0x14FF6B35),
    isDark = false,
    isClassic = true,
    cardCornerDp = 10,
    fieldBorder = Color(0x3809090B),
    accentInk = Color(0xFF6D28D9),
    riskStrong = Color(0xFFB91C1C),
)

/** Backwards-compatible aliases for the pre-7.0 two-palette API. */
val DarkAgColors = ClassicDarkAgColors
val LightAgColors = ClassicLightAgColors

/** Palette for a resolved theme id; unknown ids fall back to the modern pair. */
fun agColorsFor(resolved: ResolvedAgTheme): AgColors = when (resolved.id) {
    "graphite" -> GraphiteAgColors
    "paper" -> PaperAgColors
    "oled" -> OledAgColors
    "glass_dark" -> GlassDarkAgColors
    "glass_light" -> GlassLightAgColors
    "classic_dark" -> ClassicDarkAgColors
    "classic_light" -> ClassicLightAgColors
    else -> if (resolved.isDark) GraphiteAgColors else PaperAgColors
}

/**
 * Text colour for arbitrary user accents: light fills get a dark label, dark
 * fills get a white one. Keeps every filled control at >= 4.5:1.
 */
fun onAccentFor(accent: Color): Color =
    if (relativeLuminance(accent) > 0.32f) Color(0xFF0B0E14) else Color(0xFFFFFFFF)

/** sRGB relative luminance (WCAG 2.1), ignoring alpha. */
fun relativeLuminance(color: Color): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/** Composited colour for translucent tokens drawn over [background]. */
fun Color.compositeOver(background: Color): Color {
    val a = alpha
    if (a >= 1f) return this
    return Color(
        red = red * a + background.red * (1f - a),
        green = green * a + background.green * (1f - a),
        blue = blue * a + background.blue * (1f - a),
        alpha = 1f,
    )
}

/** WCAG 2.1 contrast ratio between two opaque colours. */
fun contrastRatio(foreground: Color, background: Color): Float {
    val first = relativeLuminance(foreground)
    val second = relativeLuminance(background)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05f) / (darker + 0.05f)
}

val LocalAgColors = staticCompositionLocalOf { GraphiteAgColors }

object AgTheme {
    val colors: AgColors
        @Composable get() = LocalAgColors.current
}
