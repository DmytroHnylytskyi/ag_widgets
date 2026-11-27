package dev.agwidgets.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** User-selectable theme modes; stored as lowercase ids in SharedPreferences. */
object AgThemeMode {
    const val SYSTEM = "system"
    const val GRAPHITE = "graphite"
    const val PAPER = "paper"
    const val OLED = "oled"
    const val GLASS = "glass"
    const val CLASSIC = "classic"

    val all: List<String> = listOf(SYSTEM, GRAPHITE, PAPER, OLED, GLASS, CLASSIC)
}

/** A concrete theme: renderer id, dark flag and whether the author's decor applies. */
data class ResolvedAgTheme(
    val id: String,
    val isDark: Boolean,
    val isClassic: Boolean,
)

/**
 * Maps stored values onto a known mode. Legacy `dark`/`light` come from older
 * releases; anything unknown falls back to `system` instead of throwing.
 */
fun normalizeThemeMode(raw: String?): String {
    val value = raw?.trim()?.lowercase().orEmpty()
    return when (value) {
        "dark" -> AgThemeMode.GRAPHITE
        "light" -> AgThemeMode.PAPER
        in AgThemeMode.all -> value
        else -> AgThemeMode.SYSTEM
    }
}

/** Resolves a mode against the current system theme. */
fun resolveAgTheme(mode: String?, systemDark: Boolean): ResolvedAgTheme = when (normalizeThemeMode(mode)) {
    AgThemeMode.GRAPHITE -> ResolvedAgTheme(AgThemeMode.GRAPHITE, isDark = true, isClassic = false)
    AgThemeMode.PAPER -> ResolvedAgTheme(AgThemeMode.PAPER, isDark = false, isClassic = false)
    AgThemeMode.OLED -> ResolvedAgTheme(AgThemeMode.OLED, isDark = true, isClassic = false)
    AgThemeMode.GLASS -> ResolvedAgTheme(
        if (systemDark) "glass_dark" else "glass_light",
        isDark = systemDark,
        isClassic = false,
    )
    AgThemeMode.CLASSIC -> ResolvedAgTheme(
        if (systemDark) "classic_dark" else "classic_light",
        isDark = systemDark,
        isClassic = true,
    )
    else -> ResolvedAgTheme(
        if (systemDark) AgThemeMode.GRAPHITE else AgThemeMode.PAPER,
        isDark = systemDark,
        isClassic = false,
    )
}

/**
 * One shape scale for every Material component, so `AlertDialog` (extraLarge),
 * `FilterChip` (small) and `OutlinedTextField` stop inventing 28dp pills next to
 * the 8/12dp custom surfaces.
 */
val AgShapes = Shapes(
    extraSmall = RoundedCornerShape(AgDim.ChipRadius),
    small = RoundedCornerShape(AgDim.ControlRadius),
    medium = RoundedCornerShape(AgDim.CardRadius),
    large = RoundedCornerShape(AgDim.PanelRadius),
    extraLarge = RoundedCornerShape(AgDim.PanelRadius),
)

/** Every colour role the stock Material components read, filled from [AgColors]. */
private fun materialScheme(colors: AgColors) = if (colors.isDark) {
    darkColorScheme(
        primary = colors.glow,
        onPrimary = colors.onGlow,
        primaryContainer = colors.glow.copy(alpha = 0.16f),
        onPrimaryContainer = colors.accentInk,
        secondary = colors.violet,
        onSecondary = colors.onGlow,
        secondaryContainer = colors.glow.copy(alpha = 0.16f),
        onSecondaryContainer = colors.accentInk,
        tertiary = colors.pink,
        onTertiary = colors.onGlow,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.secondary,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.card,
        surfaceContainerHighest = colors.card,
        surfaceTint = colors.glow,
        inverseSurface = colors.card,
        inverseOnSurface = colors.text,
        outline = colors.fieldBorder,
        outlineVariant = colors.border,
        error = colors.rose,
        onError = colors.onGlow,
        errorContainer = colors.card,
        onErrorContainer = colors.rose,
        scrim = colors.background,
    )
} else {
    lightColorScheme(
        primary = colors.glow,
        onPrimary = colors.onGlow,
        primaryContainer = colors.glow.copy(alpha = 0.12f),
        onPrimaryContainer = colors.accentInk,
        secondary = colors.violet,
        onSecondary = colors.onGlow,
        secondaryContainer = colors.glow.copy(alpha = 0.12f),
        onSecondaryContainer = colors.accentInk,
        tertiary = colors.pink,
        onTertiary = colors.onGlow,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.secondary,
        surfaceContainerLowest = colors.card,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.card,
        surfaceContainerHighest = colors.card,
        surfaceTint = colors.glow,
        inverseSurface = colors.card,
        inverseOnSurface = colors.text,
        outline = colors.fieldBorder,
        outlineVariant = colors.border,
        error = colors.rose,
        onError = colors.onGlow,
        errorContainer = colors.card,
        onErrorContainer = colors.rose,
        scrim = colors.background,
    )
}

/** Theme entry point: resolves [themeMode] against [systemDark] and provides the palette. */
@Composable
fun AGWidgetsTheme(
    themeMode: String = AgThemeMode.SYSTEM,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val resolved = resolveAgTheme(themeMode, systemDark)
    val palette = agColorsFor(resolved)
    CompositionLocalProvider(LocalAgColors provides palette) {
        MaterialTheme(
            colorScheme = materialScheme(palette),
            typography = Typography,
            shapes = AgShapes,
            content = content,
        )
    }
}

/** Pre-7.0 call site (`AGWidgetsTheme(darkTheme = ...)`): keeps the Classic palette. */
@Composable
fun AGWidgetsTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) ClassicDarkAgColors else ClassicLightAgColors
    CompositionLocalProvider(LocalAgColors provides palette) {
        MaterialTheme(
            colorScheme = materialScheme(palette),
            typography = Typography,
            shapes = AgShapes,
            content = content,
        )
    }
}
