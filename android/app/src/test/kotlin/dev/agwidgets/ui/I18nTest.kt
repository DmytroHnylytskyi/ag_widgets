package dev.agwidgets.ui

import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.i18n.AppLanguage
import dev.agwidgets.ui.i18n.EnStrings
import dev.agwidgets.ui.i18n.RuStrings
import dev.agwidgets.ui.i18n.stringsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * Contract tests for the Android string dictionary (`dev.agwidgets.ui.i18n`).
 *
 * The suite is flavour-neutral: it only touches pure-JVM classes (no Android
 * APIs, no `Context`, no `full`-only classes), so `testFullDebugUnitTest` and
 * `testContextDebugUnitTest` exercise exactly the same expectations.
 *
 * Reflection over `AgStrings` needs no `kotlin-reflect`: the flat fields are
 * ordinary public data-class getters, so plain `java.lang.reflect` is enough.
 */
class I18nTest {

    private val placeholder = Regex("""\{([a-zA-Z0-9_]+)\}""")
    private val cyrillic = Regex("""[А-Яа-яЁё]""")

    /** Flat zero-argument `String` getters of [AgStrings], sorted by name. */
    private fun flatStringGetters(): List<Method> =
        AgStrings::class.java.methods
            .asSequence()
            .filter { it.parameterCount == 0 }
            .filter { it.returnType == String::class.java }
            .filter { it.name.startsWith("get") && it.name != "getClass" }
            .sortedBy { it.name }
            .toList()

    private fun value(getter: Method, strings: AgStrings): String =
        getter.invoke(strings) as String

    private fun placeholdersOf(text: String): Set<String> =
        placeholder.findAll(text).map { it.groupValues[1] }.toSortedSet()

    private fun isClassicGetter(getter: Method): Boolean {
        val property = getter.name.removePrefix("get")
        return listOf("Serial", "BrandAgw01", "BrandAntigravity", "StatusClassic", "WatomsClassic")
            .any { property.startsWith(it) }
    }

    // --- reflection over the flat getters --------------------------------

    @Test
    fun flatStringGettersAreNumerousAndUnique() {
        val names = flatStringGetters().map { it.name }
        assertTrue(
            "AgStrings must expose at least 300 flat String getters, found ${names.size}",
            names.size >= 300,
        )
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("Duplicate flat getter names: $duplicates", duplicates.isEmpty())
        assertFalse("getClass is not a dictionary field", names.contains("getClass"))
    }

    @Test
    fun flatStringGettersReturnNonBlankValuesInBothLanguages() {
        for (getter in flatStringGetters()) {
            val ru = value(getter, RuStrings)
            val en = value(getter, EnStrings)
            assertFalse("RuStrings.${getter.name} must not be blank", ru.isBlank())
            assertFalse("EnStrings.${getter.name} must not be blank", en.isBlank())
        }
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        for (getter in flatStringGetters()) {
            val ru = value(getter, RuStrings)
            val en = value(getter, EnStrings)
            assertEquals(
                "Placeholder drift in ${getter.name}: RU=\"$ru\" (${placeholdersOf(ru)}), " +
                    "EN=\"$en\" (${placeholdersOf(en)})",
                placeholdersOf(ru),
                placeholdersOf(en),
            )
        }
    }

    @Test
    fun englishContainsNoCyrillicExceptTheRussianSelfName() {
        for (getter in flatStringGetters()) {
            val en = value(getter, EnStrings)
            if (getter.name == "getLanguageRu") {
                assertEquals(
                    "English dictionary must keep the Russian self-name verbatim",
                    "Русский",
                    en,
                )
                continue
            }
            assertFalse(
                "EnStrings.${getter.name} contains Cyrillic: \"$en\"",
                cyrillic.containsMatchIn(en),
            )
        }
    }

    // --- AppLanguage.normalize -------------------------------------------

    @Test
    fun normalizeTrimsCaseAndFallsBackToAuto() {
        val cases = listOf<Pair<String?, String>>(
            "ru" to "ru",
            "RU" to "ru",
            " ru " to "ru",
            "en" to "en",
            "EN" to "en",
            "auto" to "auto",
            "" to "auto",
            "  " to "auto",
            null to "auto",
            "de" to "auto",
            "xx" to "auto",
        )
        for ((raw, expected) in cases) {
            val label = raw?.let { "\"$it\"" } ?: "null"
            assertEquals("normalize($label) must be \"$expected\"", expected, AppLanguage.normalize(raw))
        }
    }

    // --- AppLanguage.resolve ---------------------------------------------

    @Test
    fun resolveHonoursExplicitModeOverSystemLanguage() {
        assertEquals("ru with en-US system must stay ru", "ru", AppLanguage.resolve("ru", "en-US"))
        assertEquals("en with ru-RU system must stay en", "en", AppLanguage.resolve("en", "ru-RU"))
        assertEquals("RU with en-US system must normalise to ru", "ru", AppLanguage.resolve("RU", "en-US"))
        assertEquals(" EN  with ru-RU system must normalise to en", "en", AppLanguage.resolve(" EN ", "ru-RU"))
    }

    @Test
    fun resolveAutoFollowsRussianSystemLanguages() {
        for (lang in listOf("ru", "ru-RU", "RU")) {
            assertEquals(
                "auto with systemLang=\"$lang\" must resolve to ru",
                "ru",
                AppLanguage.resolve("auto", lang),
            )
        }
    }

    @Test
    fun resolveAutoFallsBackToEnglish() {
        for (lang in listOf("en-US", "de-DE", "")) {
            assertEquals(
                "auto with systemLang=\"$lang\" must resolve to en",
                "en",
                AppLanguage.resolve("auto", lang),
            )
        }
    }

    @Test
    fun unknownModeBehavesLikeAuto() {
        assertEquals("ru", AppLanguage.resolve("xx", "ru-RU"))
        assertEquals("en", AppLanguage.resolve("xx", "en-US"))
        assertEquals("ru", AppLanguage.resolve("de", "ru-RU"))
        assertEquals("en", AppLanguage.resolve("de", "de-DE"))
        assertEquals("null mode with ru-RU must behave like auto", "ru", AppLanguage.resolve(null, "ru-RU"))
    }

    // --- stringsFor ------------------------------------------------------

    @Test
    fun stringsForReturnsTheSharedDictionaries() {
        assertSame("stringsFor(\"ru\") must return RuStrings", RuStrings, stringsFor("ru"))
        assertSame("stringsFor(\"en\") must return EnStrings", EnStrings, stringsFor("en"))
        assertSame("Unknown language must fall back to EnStrings", EnStrings, stringsFor("de"))
    }

    // --- relative time labels --------------------------------------------

    @Test
    fun russianUpdatedLabelMatchesLegacyFormat() {
        assertEquals("только что", RuStrings.updatedLabel(0L))
        assertEquals("1 мин назад", RuStrings.updatedLabel(1L))
        assertEquals("59 мин назад", RuStrings.updatedLabel(59L))
        assertEquals("1 ч назад", RuStrings.updatedLabel(60L))
        assertEquals("2 ч назад", RuStrings.updatedLabel(120L))
        assertEquals("1 д назад", RuStrings.updatedLabel(1_440L))
        assertEquals("3 д назад", RuStrings.updatedLabel(4_320L))
    }

    @Test
    fun englishUpdatedLabelUsesCompactSuffixes() {
        assertEquals("just now", EnStrings.updatedLabel(0L))
        assertEquals("5m ago", EnStrings.updatedLabel(5L))
        assertEquals("1h ago", EnStrings.updatedLabel(90L))
        assertEquals("3d ago", EnStrings.updatedLabel(3L * 24 * 60))
    }

    @Test
    fun russianCountdownFormat() {
        assertEquals("2д 3ч", RuStrings.countdown(2L, 3L, 4L))
        assertEquals("4ч 15м", RuStrings.countdown(0L, 4L, 15L))
        assertEquals("7м", RuStrings.countdown(0L, 0L, 7L))
    }

    @Test
    fun englishCountdownFormat() {
        assertEquals("2d 3h", EnStrings.countdown(2L, 3L, 4L))
        assertEquals("4h 15m", EnStrings.countdown(0L, 4L, 15L))
        assertEquals("7m", EnStrings.countdown(0L, 0L, 7L))
    }

    // --- fill ------------------------------------------------------------

    @Test
    fun fillSubstitutesEveryVariable() {
        assertEquals(
            "All {name} placeholders must be replaced",
            "Hello, Ada (42)",
            EnStrings.fill("Hello, {name} ({age})", "name" to "Ada", "age" to 42),
        )
        assertEquals(
            "RU placeholders must be replaced too",
            "RU: 5 из 7",
            RuStrings.fill("RU: {done} из {total}", "done" to 5, "total" to 7),
        )
    }

    @Test
    fun fillKeepsPlaceholdersWithoutArguments() {
        assertEquals(
            "Missing variables must stay as literal placeholders",
            "a=1 b={b}",
            EnStrings.fill("a={a} b={b}", "a" to 1),
        )
    }

    @Test
    fun fillReplacesRepeatedPlaceholderEverywhere() {
        assertEquals(
            "A repeated placeholder must be replaced in every occurrence",
            "7 / 7 / 7",
            EnStrings.fill("{n} / {n} / {n}", "n" to 7),
        )
    }

    // --- key contract strings, Russian -----------------------------------

    @Test
    fun russianKeyStringsMatchTheContract() {
        assertEquals("Лимиты", RuStrings.notifChannelName)
        assertEquals("(неделя)", RuStrings.notifWindowWeekly)
        assertEquals("(5h)", RuStrings.notifWindow5h)
        assertTrue(
            "RU backoff must mention 429, was \"${RuStrings.dashZcodeRateLimitBackoff}\"",
            RuStrings.dashZcodeRateLimitBackoff.contains("429"),
        )

        val footers = listOf(
            RuStrings.dashFooterZcodeOnly,
            RuStrings.dashFooterFull,
            RuStrings.dashFooterContext,
        )
        footers.forEach { assertFalse("RU dash footer must not be blank", it.isBlank()) }
        assertEquals("RU dash footers must be three distinct texts", 3, footers.toSet().size)

        assertEquals("01 / CONTEXT", RuStrings.serialContext)
        assertEquals("02 / LIMITS", RuStrings.serialLimits)
        assertEquals("03 / ACCESS", RuStrings.serialAccess)
        assertEquals("AGW / 01", RuStrings.brandAgw01)
        assertEquals("НЕТ ДАННЫХ", RuStrings.statusClassicNoData)
        assertEquals("ТИХО", RuStrings.statusClassicQuiet)
        assertEquals("СТАРО", RuStrings.statusClassicStale)
        assertEquals("GEMINI 5H", RuStrings.watomsClassicGemini5h)
        assertEquals("CLAUDE/GPT WK", RuStrings.watomsClassicThirdWeekly)
        assertEquals("GEMINI 5Ч", RuStrings.dashLimitsClassicGemini5h)
        assertEquals("Тема оформления", RuStrings.setThemeTitle)
        assertEquals("Язык", RuStrings.setLanguageTitle)
        assertEquals("СМЕНИТЬ РЕЖИМ", RuStrings.diagChangeModeClassic)
        assertEquals(
            "Ожидалось зашифрованное сообщение (E2E)",
            RuStrings.ntfyExpectedE2e,
        )
        assertTrue(
            "RU wrong-key text must mention the E2E key, was \"${RuStrings.ntfyWrongKey}\"",
            RuStrings.ntfyWrongKey.contains("E2E-ключ"),
        )
        assertTrue(
            "RU encrypted-without-key text must mention the QR code, was \"${RuStrings.ntfyEncryptedNoKey}\"",
            RuStrings.ntfyEncryptedNoKey.contains("QR-код"),
        )
    }

    // --- key contract strings, English -----------------------------------

    @Test
    fun englishKeyStringsMatchTheContract() {
        assertEquals("01 / CONTEXT", EnStrings.serialContext)
        assertEquals("AGW / 01", EnStrings.brandAgw01)
        assertEquals("NO DATA", EnStrings.statusClassicNoData)
        assertEquals("GEMINI WK", EnStrings.watomsClassicGeminiWeekly)
        assertEquals("GEMINI 5H", EnStrings.dashLimitsClassicGemini5h)
        assertEquals("Language", EnStrings.setLanguageTitle)
        assertEquals("Limits", EnStrings.notifChannelName)
        assertEquals("Русский", EnStrings.languageRu)
    }

    // --- quota window units ----------------------------------------------

    @Test
    fun englishQuotaWindowsUseCompactSuffixes() {
        assertEquals("5h", EnStrings.time.quotaWindow5h)
        assertEquals("7d", EnStrings.time.quotaWindowWeekly)
        assertEquals("5h", EnStrings.time.quotaWindow5hShort)
        assertEquals("7d", EnStrings.time.quotaWindowWeeklyShort)
        assertEquals("Flattened getter must match the grouped value", "5h", EnStrings.quotaWindow5h)
        assertEquals("Gemini · 5h", EnStrings.dashLimitsGemini5h)
    }

    @Test
    fun russianQuotaWindowsUseLongSuffixes() {
        assertEquals("5 ч", RuStrings.time.quotaWindow5h)
        assertEquals("7 дн", RuStrings.time.quotaWindowWeekly)
        assertEquals("5ч", RuStrings.time.quotaWindow5hShort)
        assertEquals("7д", RuStrings.time.quotaWindowWeeklyShort)
        assertEquals("Flattened getter must match the grouped value", "5 ч", RuStrings.quotaWindow5h)
        assertEquals("Gemini · 5 ч", RuStrings.dashLimitsGemini5h)
    }

    // --- Classic techno-codes --------------------------------------------

    @Test
    fun classicTechnoCodesAreLanguageNeutral() {
        val classicGetters = flatStringGetters().filter { isClassicGetter(it) }
        assertEquals(
            "The Classic techno-code getter set changed; found " +
                classicGetters.map { it.name },
            24,
            classicGetters.size,
        )

        // Only genuine translations may differ between RU and EN.
        val translated = setOf(
            "getStatusClassicNoData",
            "getStatusClassicQuiet",
            "getStatusClassicStale",
            "getWatomsClassicChat",
            "getWatomsClassicPc",
        )
        val differing = classicGetters
            .filter { value(it, RuStrings) != value(it, EnStrings) }
            .map { it.name }
            .toSet()
        assertEquals(
            "Classic codes may differ only where a translation is expected, got $differing",
            translated,
            differing,
        )

        // The five allowed differences are exactly these translations.
        assertEquals("НЕТ ДАННЫХ", RuStrings.statusClassicNoData)
        assertEquals("NO DATA", EnStrings.statusClassicNoData)
        assertEquals("ТИХО", RuStrings.statusClassicQuiet)
        assertEquals("QUIET", EnStrings.statusClassicQuiet)
        assertEquals("СТАРО", RuStrings.statusClassicStale)
        assertEquals("STALE", EnStrings.statusClassicStale)
        assertEquals("ЧАТ", RuStrings.watomsClassicChat)
        assertEquals("CHAT", EnStrings.watomsClassicChat)
        assertEquals("ПК", RuStrings.watomsClassicPc)
        assertEquals("PC", EnStrings.watomsClassicPc)

        // Serial codes are byte-identical techno labels in both languages.
        val serialGetters = classicGetters.filter { it.name.removePrefix("get").startsWith("Serial") }
        assertEquals("Expected ten serial* getters", 10, serialGetters.size)
        for (getter in serialGetters) {
            assertEquals(
                "${getter.name} must be identical in RU and EN",
                value(getter, RuStrings),
                value(getter, EnStrings),
            )
        }
    }
}
