package dev.agwidgets.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.agwidgets.ui.theme.AgColors
import dev.agwidgets.ui.theme.AgThemeMode
import dev.agwidgets.ui.theme.agColorsFor
import dev.agwidgets.ui.theme.compositeOver
import dev.agwidgets.ui.theme.contrastRatio
import dev.agwidgets.ui.theme.normalizeThemeMode
import dev.agwidgets.ui.theme.onAccentFor
import dev.agwidgets.ui.theme.resolveAgTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the six Android theme modes and their palettes.
 * No Android or Robolectric APIs are involved: only the theme resolution
 * functions and immutable Compose `Color` values.
 */
class ThemeResolutionTest {

    private val systemDarkStates = listOf(true, false)

    // ---- normalizeThemeMode ---------------------------------------------

    @Test
    fun normalizeAcceptsAllSixCanonicalModes() {
        assertEquals(6, AgThemeMode.all.size)
        AgThemeMode.all.forEach { mode -> assertEquals(mode, normalizeThemeMode(mode)) }
    }

    @Test
    fun normalizeIsCaseInsensitiveAndTrimsWhitespace() {
        assertEquals(AgThemeMode.GRAPHITE, normalizeThemeMode("Graphite"))
        assertEquals(AgThemeMode.PAPER, normalizeThemeMode("PAPER"))
        assertEquals(AgThemeMode.OLED, normalizeThemeMode("  Oled  "))
        assertEquals(AgThemeMode.GLASS, normalizeThemeMode("glass"))
        assertEquals(AgThemeMode.CLASSIC, normalizeThemeMode("Classic"))
        assertEquals(AgThemeMode.SYSTEM, normalizeThemeMode("SYSTEM"))
    }

    @Test
    fun normalizeMigratesLegacyModes() {
        assertEquals(AgThemeMode.GRAPHITE, normalizeThemeMode("dark"))
        assertEquals(AgThemeMode.PAPER, normalizeThemeMode("light"))
    }

    @Test
    fun normalizeFallsBackToSystemForMissingOrUnknownValues() {
        assertEquals(AgThemeMode.SYSTEM, normalizeThemeMode(null))
        assertEquals(AgThemeMode.SYSTEM, normalizeThemeMode(""))
        assertEquals(AgThemeMode.SYSTEM, normalizeThemeMode("   "))
        assertEquals(AgThemeMode.SYSTEM, normalizeThemeMode("neon"))
    }

    // ---- resolveAgTheme -------------------------------------------------

    @Test
    fun systemResolvesToGraphiteOrPaper() {
        systemDarkStates.forEach { systemDark ->
            val resolved = resolveAgTheme(AgThemeMode.SYSTEM, systemDark)
            assertEquals(if (systemDark) "graphite" else "paper", resolved.id)
            assertEquals(systemDark, resolved.isDark)
            assertFalse(resolved.isClassic)
        }
    }

    @Test
    fun graphiteAndOledAreAlwaysDark() {
        systemDarkStates.forEach { systemDark ->
            val graphite = resolveAgTheme(AgThemeMode.GRAPHITE, systemDark)
            assertEquals("graphite", graphite.id)
            assertTrue(graphite.isDark)
            assertFalse(graphite.isClassic)

            val oled = resolveAgTheme(AgThemeMode.OLED, systemDark)
            assertEquals("oled", oled.id)
            assertTrue(oled.isDark)
            assertFalse(oled.isClassic)
        }
    }

    @Test
    fun paperIsAlwaysLight() {
        systemDarkStates.forEach { systemDark ->
            val paper = resolveAgTheme(AgThemeMode.PAPER, systemDark)
            assertEquals("paper", paper.id)
            assertFalse(paper.isDark)
            assertFalse(paper.isClassic)
        }
    }

    @Test
    fun glassFollowsTheSystemBrightness() {
        systemDarkStates.forEach { systemDark ->
            val resolved = resolveAgTheme(AgThemeMode.GLASS, systemDark)
            assertEquals(if (systemDark) "glass_dark" else "glass_light", resolved.id)
            assertEquals(systemDark, resolved.isDark)
            assertFalse(resolved.isClassic)
        }
    }

    @Test
    fun classicFollowsTheSystemBrightness() {
        systemDarkStates.forEach { systemDark ->
            val resolved = resolveAgTheme(AgThemeMode.CLASSIC, systemDark)
            assertEquals(if (systemDark) "classic_dark" else "classic_light", resolved.id)
            assertEquals(systemDark, resolved.isDark)
            assertTrue(resolved.isClassic)
        }
    }

    @Test
    fun onlyClassicEnablesTheAuthorsDecorations() {
        AgThemeMode.all.forEach { mode ->
            systemDarkStates.forEach { systemDark ->
                val resolved = resolveAgTheme(mode, systemDark)
                if (mode == AgThemeMode.CLASSIC) {
                    assertTrue("$mode/$systemDark must be classic", resolved.isClassic)
                } else {
                    assertFalse("$mode/$systemDark must not be classic", resolved.isClassic)
                }
            }
        }
    }

    @Test
    fun legacyStoredModesResolveLikeTheirModernReplacement() {
        systemDarkStates.forEach { systemDark ->
            assertEquals(
                resolveAgTheme(AgThemeMode.GRAPHITE, systemDark),
                resolveAgTheme("dark", systemDark)
            )
            assertEquals(
                resolveAgTheme(AgThemeMode.PAPER, systemDark),
                resolveAgTheme("light", systemDark)
            )
        }
    }

    // ---- agColorsFor ----------------------------------------------------

    @Test
    fun cardCornerRadiusMatchesTheThemeLanguage() {
        // Classic keeps the author's 10dp; modern themes sit on the 4dp grid.
        assertEquals(10, agColorsFor(resolveAgTheme(AgThemeMode.CLASSIC, true)).cardCornerDp)
        assertEquals(10, agColorsFor(resolveAgTheme(AgThemeMode.CLASSIC, false)).cardCornerDp)
        listOf(AgThemeMode.GRAPHITE, AgThemeMode.PAPER, AgThemeMode.OLED).forEach { mode ->
            assertEquals(12, agColorsFor(resolveAgTheme(mode, true)).cardCornerDp)
        }
        assertEquals(12, agColorsFor(resolveAgTheme(AgThemeMode.GLASS, true)).cardCornerDp)
        assertEquals(12, agColorsFor(resolveAgTheme(AgThemeMode.GLASS, false)).cardCornerDp)
    }

    // ---- WCAG AA contrast invariants ------------------------------------

    /** Every resolved palette, with the label used in assertion messages. */
    private fun allPalettes(): List<Pair<String, AgColors>> = listOf(
        "graphite" to agColorsFor(resolveAgTheme(AgThemeMode.GRAPHITE, true)),
        "paper" to agColorsFor(resolveAgTheme(AgThemeMode.PAPER, false)),
        "oled" to agColorsFor(resolveAgTheme(AgThemeMode.OLED, true)),
        "glass_dark" to agColorsFor(resolveAgTheme(AgThemeMode.GLASS, true)),
        "glass_light" to agColorsFor(resolveAgTheme(AgThemeMode.GLASS, false)),
        "classic_dark" to agColorsFor(resolveAgTheme(AgThemeMode.CLASSIC, true)),
        "classic_light" to agColorsFor(resolveAgTheme(AgThemeMode.CLASSIC, false)),
    )

    /** Translucent tokens are compared after compositing over [background]. */
    private fun onCard(token: Color, colors: AgColors): Color =
        token.compositeOver(colors.card.compositeOver(colors.background))

    private fun onBackground(token: Color, colors: AgColors): Color =
        token.compositeOver(colors.background)

    @Test
    fun textRolesMeetAaOnCardAndBackground() {
        allPalettes().forEach { (label, colors) ->
            listOf(
                "text" to colors.text,
                "secondary" to colors.secondary,
                "muted" to colors.muted,
            ).forEach { (role, value) ->
                assertTrue(
                    "$label: $role on card is ${onCard(value, colors).ratio(colors.card.compositeOver(colors.background))}",
                    onCard(value, colors).ratio(colors.card.compositeOver(colors.background)) >= 4.5,
                )
                assertTrue(
                    "$label: $role on background",
                    onBackground(value, colors).ratio(colors.background) >= 4.5,
                )
            }
        }
    }

    @Test
    fun mutedKeepsTheComfortTargetOnCard() {
        allPalettes().forEach { (label, colors) ->
            val ratio = onCard(colors.muted, colors).ratio(colors.card.compositeOver(colors.background))
            assertTrue("$label: muted on card is $ratio, want >= 4.6", ratio >= 4.6)
        }
    }

    @Test
    fun onGlowMeetsAaOnTheFilledAccent() {
        allPalettes().forEach { (label, colors) ->
            val ratio = colors.onGlow.ratio(colors.glow)
            assertTrue("$label: onGlow on glow is $ratio, want >= 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun accentInkMeetsAaOnCard() {
        allPalettes().forEach { (label, colors) ->
            // The real backdrop of accent-ink: `card` tinted with `glow@16%`.
            // The old assertion composited the opaque ink over that tint (a
            // no-op) and then measured against the plain card, so it could never
            // fail.
            val tintedCard = colors.glow.copy(alpha = 0.16f)
                .compositeOver(colors.card.compositeOver(colors.background))
            val ratio = colors.accentInk.ratio(tintedCard)
            assertTrue("$label: accentInk on tinted card is $ratio, want >= 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun violetCtaMeetsAaInEveryTheme() {
        // «Войти в Z.ai» fills with `violet` and labels with
        // `onAccentFor(violet)` in both AccessCard and ZCodeDashboardCard; the
        // pair it replaced (`onGlow` on `violet`) measured 3.75:1 in Classic Dark.
        allPalettes().forEach { (label, colors) ->
            val ratio = onAccentFor(colors.violet).ratio(colors.violet)
            assertTrue(
                "$label: onAccentFor(violet) on violet is $ratio, want >= 4.5",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun controlBordersAndTracksStayVisible() {
        // Modern palettes only: Classic keeps the author's faint hairlines 1:1.
        allPalettes().filterNot { it.second.isClassic }.forEach { (label, colors) ->
            val card = colors.card.compositeOver(colors.background)
            val field = colors.fieldBorder.compositeOver(card)
            val track = colors.track.compositeOver(card)
            val border = colors.border.compositeOver(card)
            assertTrue("$label: fieldBorder ${field.ratio(card)}", field.ratio(card) >= 3f)
            assertTrue("$label: track ${track.ratio(card)}", track.ratio(card) >= 3f)
            assertTrue("$label: card border ${border.ratio(card)}", border.ratio(card) >= 1.8f)
        }
    }

    @Test
    fun customAccentPicksAReadableLabel() {
        val amber = Color(0xFFF59E0B)
        val deepBlue = Color(0xFF1D4ED8)
        assertTrue(amber.ratio(onAccentFor(amber)) >= 4.5f)
        assertTrue(deepBlue.ratio(onAccentFor(deepBlue)) >= 4.5f)
    }

    @Test
    fun glassIsVisiblyDifferentFromGraphiteAndPaper() {
        val graphite = agColorsFor(resolveAgTheme(AgThemeMode.GRAPHITE, true))
        val glassDark = agColorsFor(resolveAgTheme(AgThemeMode.GLASS, true))
        val paper = agColorsFor(resolveAgTheme(AgThemeMode.PAPER, false))
        val glassLight = agColorsFor(resolveAgTheme(AgThemeMode.GLASS, false))

        // Same family of darkness, but a genuinely cool slate-blue ground.
        assertNotEquals("glass_dark background must differ", glassDark.background, graphite.background)
        assertTrue(
            "glass_dark must lean blue (r=${glassDark.background.red} b=${glassDark.background.blue})",
            glassDark.background.blue - glassDark.background.red > 0.05f,
        )
        assertTrue(
            "graphite must stay neutral (r=${graphite.background.red} b=${graphite.background.blue})",
            kotlin.math.abs(graphite.background.blue - graphite.background.red) < 0.05f,
        )
        // Same family of lightness, but cool instead of warm.
        assertNotEquals("glass_light background must differ", glassLight.background, paper.background)
        assertTrue(
            "glass_light must lean blue (r=${glassLight.background.red} b=${glassLight.background.blue})",
            glassLight.background.blue > glassLight.background.red,
        )
        assertTrue(
            "paper must stay warm (r=${paper.background.red} b=${paper.background.blue})",
            paper.background.red > paper.background.blue,
        )
        assertTrue("glass_dark accent must differ from graphite accent", glassDark.glow != graphite.glow)
        assertTrue("glass_light accent must differ from paper accent", glassLight.glow != paper.glow)
        assertTrue("glass_dark must declare glass", glassDark.isGlass)
        assertTrue("glass_light must declare glass", glassLight.isGlass)
        assertTrue("glass_dark card must be translucent", glassDark.card.alpha < 1f)
        assertTrue("glass_light card must be translucent", glassLight.card.alpha < 1f)
        assertNotEquals("glass_dark wash must exist", glassDark.backdropTint, Color.Transparent)
        assertNotEquals("glass_light wash must exist", glassLight.backdropTint, Color.Transparent)
        assertNotEquals("glass_dark needs an inner hairline", glassDark.innerHighlight, Color.Transparent)
        assertNotEquals("glass_light needs an inner hairline", glassLight.innerHighlight, Color.Transparent)
    }

    @Test
    fun accentAndSecondaryAccentAreDifferentInModernThemes() {
        // glow drives progress/primary, violet drives the Claude/GPT family.
        allPalettes().filterNot { it.second.isClassic }.forEach { (label, colors) ->
            assertNotEquals("$label: glow and violet must differ", colors.glow, colors.violet)
        }
    }

    private fun Color.ratio(other: Color): Float = contrastRatio(this, other)

    @Test
    fun paletteDarkFlagMatchesTheResolvedTheme() {
        AgThemeMode.all.forEach { mode ->
            systemDarkStates.forEach { systemDark ->
                val resolved = resolveAgTheme(mode, systemDark)
                assertEquals(
                    "$mode/$systemDark",
                    resolved.isDark,
                    agColorsFor(resolved).isDark
                )
            }
        }
    }

    @Test
    fun classicPalettesKeepTheClassicFlagAndModernDoNot() {
        assertTrue(agColorsFor(resolveAgTheme(AgThemeMode.CLASSIC, true)).isClassic)
        assertTrue(agColorsFor(resolveAgTheme(AgThemeMode.CLASSIC, false)).isClassic)
        listOf(AgThemeMode.GRAPHITE, AgThemeMode.PAPER, AgThemeMode.OLED).forEach { mode ->
            assertFalse(agColorsFor(resolveAgTheme(mode, true)).isClassic)
        }
        assertFalse(agColorsFor(resolveAgTheme(AgThemeMode.GLASS, true)).isClassic)
        assertFalse(agColorsFor(resolveAgTheme(AgThemeMode.GLASS, false)).isClassic)
    }

    @Test
    fun graphiteAndPaperAreProperlyContrastingSurfaces() {
        val graphite = agColorsFor(resolveAgTheme(AgThemeMode.GRAPHITE, true))
        val paper = agColorsFor(resolveAgTheme(AgThemeMode.PAPER, false))

        assertNotEquals("graphite and paper backgrounds must differ", graphite.background, paper.background)
        assertNotEquals("text must not blend into a dark background", graphite.text, graphite.background)
        assertNotEquals("text must not blend into a light background", paper.text, paper.background)

        // Dark theme: light text on a dark surface, with a comfortable gap.
        assertTrue(
            "graphite text must be lighter than its background",
            luminance(graphite.text) > luminance(graphite.background)
        )
        assertTrue(
            "graphite text contrast is too low",
            luminance(graphite.text) - luminance(graphite.background) > 0.35
        )

        // Light theme: dark text on a soft paper surface.
        assertTrue(
            "paper text must be darker than its background",
            luminance(paper.text) < luminance(paper.background)
        )
    }

    @Test
    fun oledUsesAPureBlackSurfaceWithReadableText() {
        val oled = agColorsFor(resolveAgTheme(AgThemeMode.OLED, true))
        assertEquals(0xFF000000.toInt(), oled.background.toArgb())
        assertTrue("oled text must be lighter than pure black", luminance(oled.text) > 0.5)
    }

    /** Rough sRGB relative luminance used only for contrast sanity checks. */
    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
