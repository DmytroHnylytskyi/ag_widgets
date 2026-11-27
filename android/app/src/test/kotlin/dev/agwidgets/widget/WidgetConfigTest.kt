package dev.agwidgets.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the widget appearance contract.
 *
 * No `android.*`, no Robolectric: `WidgetConfig.kt` is deliberately plain Kotlin
 * so the shared golden fixtures (`tests/fixtures/appearance_cases.json`) can be
 * replayed here next to the desktop implementation.
 */
class WidgetConfigTest {

    private lateinit var fixtures: File

    @Before
    fun setUp() {
        fixtures = fixturesDir()
    }

    // --- fixture loading --------------------------------------------------

    private fun fixturesDir(): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            "../../tests/fixtures",
            "../tests/fixtures",
            "tests/fixtures",
            "../../../tests/fixtures",
        ).map { File(base, it) }
        return candidates.firstOrNull { it.isDirectory }
            ?: run {
                throw AssertionError(
                    "Fixture directory not found from ${base.absolutePath}. Looked in:\n" +
                        candidates.joinToString("\n") { it.absolutePath },
                )
            }
    }

    private fun fixture(name: String): JSONObject {
        val file = File(fixtures, name)
        assertTrue("Fixture file not found: ${file.absolutePath}", file.isFile)
        return JSONObject(file.readText())
    }

    private fun appearanceCases(): JSONObject = fixture("appearance_cases.json")

    // --- presets ----------------------------------------------------------

    @Test
    fun presetsMatchContract() {
        assertEquals("preset count", 5, WidgetPresets.all.size)

        val classic = WidgetPresets.classic.config
        assertEquals(WidgetThemes.CLASSIC, classic.theme)
        assertEquals(10, classic.cornerRadiusDp)
        assertEquals(WidgetConfigCodec.DEFAULT_OPACITY.toDouble(), classic.opacity.toDouble(), 1e-6)
        assertTrue("classic: header", classic.isEnabled(WidgetBlockId.HEADER))
        assertTrue("classic: quotas", classic.isEnabled(WidgetBlockId.QUOTAS))
        assertTrue("classic: zcode", classic.isEnabled(WidgetBlockId.ZCODE))

        val dashboard = WidgetPresets.dashboard.config
        WidgetBlockId.all.forEach { id ->
            assertTrue("dashboard: ${id.id} enabled", dashboard.isEnabled(id))
        }
        assertEquals(14, dashboard.cornerRadiusDp)

        val contextCompact = WidgetPresets.contextCompact.config
        assertTrue("context_compact: context", contextCompact.isEnabled(WidgetBlockId.CONTEXT))
        assertTrue("context_compact: header off", !contextCompact.isEnabled(WidgetBlockId.HEADER))
        assertTrue("context_compact: quotas off", !contextCompact.isEnabled(WidgetBlockId.QUOTAS))
        assertTrue("context_compact: zcode off", !contextCompact.isEnabled(WidgetBlockId.ZCODE))
        assertEquals(WidgetDensity.COMPACT, contextCompact.density)

        val detailed = WidgetPresets.detailed.config
        WidgetBlockId.all.forEach { id ->
            assertTrue("detailed: ${id.id} enabled", detailed.isEnabled(id))
        }
        assertEquals(WidgetDensity.SPACIOUS, detailed.density)

        val zcodeOnly = WidgetPresets.zcodeOnly.config
        assertTrue("zcode_only: zcode", zcodeOnly.isEnabled(WidgetBlockId.ZCODE))
        assertTrue("zcode_only: header off", !zcodeOnly.isEnabled(WidgetBlockId.HEADER))
        assertTrue("zcode_only: context off", !zcodeOnly.isEnabled(WidgetBlockId.CONTEXT))
        assertTrue("zcode_only: quotas off", !zcodeOnly.isEnabled(WidgetBlockId.QUOTAS))
        assertTrue(
            "zcode_only: show_balance_bar",
            zcodeOnly.option(WidgetBlockId.ZCODE, "show_balance_bar", false),
        )
    }

    // --- defaults ---------------------------------------------------------

    @Test
    fun defaultBlocksAreCanonicalAndEnabled() {
        val blocks = WidgetConfigCodec.defaultBlocks()
        assertEquals("block count", WidgetBlockId.all.size, blocks.size)
        blocks.forEachIndexed { index, block ->
            assertEquals("block #$index id", WidgetBlockId.all[index], block.id)
            assertTrue("block ${block.id.id} enabled", block.enabled)
            assertEquals(
                "block ${block.id.id} options",
                WidgetConfigCodec.defaultOptions(block.id),
                block.options,
            )
        }
    }

    // --- (de)serialisation -------------------------------------------------

    @Test
    fun jsonRoundTripKeepsNormalizedConfig() {
        val config = WidgetConfig(
            theme = "  OLED ",
            customAccent = "#34D399",
            opacity = 0.83f,
            cornerRadiusDp = 12,
            fontScale = 1.07f,
            density = WidgetDensity.SPACIOUS,
            blocks = listOf(
                WidgetBlockConfig(
                    id = WidgetBlockId.ZCODE,
                    enabled = false,
                    options = mapOf("show_balance_bar" to false),
                ),
                WidgetBlockConfig(id = WidgetBlockId.CONTEXT),
            ),
        )
        val expected = WidgetConfigCodec.normalize(config)

        assertConfigEquals("toJson/fromJson", expected, WidgetConfigCodec.fromJson(WidgetConfigCodec.toJson(config)))
        assertConfigEquals(
            "export/import",
            expected,
            WidgetConfigCodec.importJson(WidgetConfigCodec.exportJson(config)),
        )
    }

    @Test
    fun normalizeClampsOutOfRangeValues() {
        assertEquals("#ffffff", WidgetConfigCodec.normalizeHexAccent("#FFFFFF"))

        val normalized = WidgetConfigCodec.normalize(
            WidgetConfig(opacity = 5f, cornerRadiusDp = 99, fontScale = 9f),
        )
        assertEquals(1f, normalized.opacity)
        assertEquals(24, normalized.cornerRadiusDp)
        assertEquals(1.4f, normalized.fontScale)
    }

    @Test
    fun desktopExportedBlockNamesImportCleanlyOnAndroid() {
        val desktopJson = """
            {
              "schema_version": 1,
              "theme": "paper",
              "custom_accent": "#34D399",
              "corner_radius": 5.2,
              "blocks": [
                { "id": "zcode_card", "enabled": true, "options": { "show_balance_bar": false } },
                { "id": "context_hero", "enabled": true },
                { "id": "quotas_grid", "enabled": false, "options": { "show_gemini": false } }
              ]
            }
        """.trimIndent()
        val parsed = WidgetConfigCodec.importJson(desktopJson)
        assertNotNull("desktop JSON must import on Android", parsed)
        parsed!!
        assertEquals("paper", parsed.theme)
        assertEquals("#34d399", parsed.customAccent)
        assertEquals(6, parsed.cornerRadiusDp)
        assertEquals(
            listOf(WidgetBlockId.ZCODE, WidgetBlockId.CONTEXT, WidgetBlockId.QUOTAS, WidgetBlockId.HEADER),
            parsed.blocks.map { it.id },
        )
        assertEquals(false, parsed.option(WidgetBlockId.ZCODE, "show_balance_bar", true))
        assertEquals(false, parsed.isEnabled(WidgetBlockId.QUOTAS))
        assertEquals(false, parsed.option(WidgetBlockId.QUOTAS, "show_gemini", true))
    }

    @Test
    fun brokenAndOversizedJsonIsRejectedWithoutThrowing() {
        val inputs = listOf(
            null,
            "",
            "   ",
            "{",
            "{\"theme\":",
            "[]",
            "42",
            "\"x\"",
            "null",
            "{\"pad\":\"" + "x".repeat(17000) + "\"}",
        )
        inputs.forEach { raw ->
            assertNull("importJson(${raw?.take(24)}) must be null", WidgetConfigCodec.importJson(raw))
        }
    }

    // --- golden fixtures ---------------------------------------------------

    @Test
    fun accentCasesMatchCodec() {
        val cases = appearanceCases().getJSONArray("accent_cases")
        assertTrue("accent_cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val expected = if (case.isNull("expected")) null else case.getString("expected")
            val actual = WidgetConfigCodec.normalizeHexAccent(case.opt("input") as? String)
            assertEquals("accent case #$index (${case.opt("input")})", expected, actual)
        }
    }

    @Test
    fun fontScaleCasesMatchClamp() {
        val cases = appearanceCases().getJSONObject("clamp_cases").getJSONArray("font_scale")
        assertTrue("font_scale cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val actual = WidgetConfigCodec.clampStep(
                case.getDouble("input"),
                WidgetConfigCodec.MIN_FONT_SCALE.toDouble(),
                WidgetConfigCodec.MAX_FONT_SCALE.toDouble(),
                WidgetConfigCodec.DEFAULT_FONT_SCALE.toDouble(),
            )
            assertEquals(
                "font_scale case #$index (${case.getDouble("input")})",
                case.getDouble("expected"),
                actual,
                1e-6,
            )
        }
    }

    @Test
    fun cornerRadiusCasesMatchClamp() {
        val cases = appearanceCases().getJSONObject("clamp_cases").getJSONArray("corner_radius")
        assertTrue("corner_radius cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(
                "corner_radius case #$index (${case.getDouble("input")})",
                case.getInt("expected"),
                WidgetConfigCodec.clampCornerRadius(case.getDouble("input")),
            )
        }
    }

    @Test
    fun opacityAndroidCasesMatchClamp() {
        val cases = appearanceCases().getJSONObject("clamp_cases").getJSONArray("opacity_android")
        assertTrue("opacity_android cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val actual = WidgetConfigCodec.clampStep(case.getDouble("input"), 0.0, 1.0, 0.95)
            assertEquals(
                "opacity_android case #$index (${case.getDouble("input")})",
                case.getDouble("expected"),
                actual,
                1e-6,
            )
        }
    }

    @Test
    fun densityCasesMatchEnum() {
        val cases = appearanceCases().getJSONObject("clamp_cases").getJSONArray("density")
        assertTrue("density cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val actual = WidgetDensity.fromId(case.opt("input") as? String)?.id ?: "comfortable"
            assertEquals("density case #$index (${case.opt("input")})", case.getString("expected"), actual)
        }
    }

    @Test
    fun blockCasesMatchNormalizer() {
        val cases = appearanceCases().getJSONArray("blocks_cases")
        assertTrue("blocks_cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val label = case.getString("name")
            val actual = WidgetConfigCodec.normalizeBlocks(blocksFromJson(case))
            assertBlocksMatch(label, case.getJSONArray("android"), actual)
        }
    }

    @Test
    fun importSuccessCasesMatchCodec() {
        val cases = appearanceCases().getJSONArray("import_success_cases")
        assertTrue("import_success_cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val label = case.getString("name")
            val parsed = WidgetConfigCodec.importJson(case.getString("input"))
            assertNotNull("$label: import must succeed", parsed)
            parsed!!

            assertEquals("$label: theme", case.getString("expected_theme"), parsed.theme)
            if (case.has("expected_accent") && !case.isNull("expected_accent")) {
                assertEquals("$label: accent", case.getString("expected_accent"), parsed.customAccent)
            }
            if (case.has("expected_corner_radius") && !case.isNull("expected_corner_radius")) {
                assertEquals(
                    "$label: corner_radius",
                    case.getInt("expected_corner_radius"),
                    parsed.cornerRadiusDp,
                )
            }
            if (case.has("expected_density") && !case.isNull("expected_density")) {
                assertEquals("$label: density", case.getString("expected_density"), parsed.density.id)
            }
        }
    }

    @Test
    fun importErrorCasesAreRejected() {
        val json = appearanceCases()
        assertEquals(
            "import_max_bytes",
            WidgetConfigCodec.MAX_IMPORT_BYTES,
            json.getInt("import_max_bytes"),
        )

        val cases = json.getJSONArray("import_error_cases")
        assertTrue("import_error_cases must not be empty", cases.length() > 0)

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val label = case.getString("name")
            val raw = if (case.has("pad_chars")) {
                "{\"pad\":\"" + "x".repeat(case.getInt("pad_chars")) + "\"}"
            } else {
                case.getString("input")
            }
            assertNull("$label: import must fail", WidgetConfigCodec.importJson(raw))
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun assertConfigEquals(label: String, expected: WidgetConfig, actual: WidgetConfig?) {
        assertNotNull("$label: config must not be null", actual)
        val config = actual!!
        assertEquals("$label: theme", expected.theme, config.theme)
        assertEquals("$label: custom_accent", expected.customAccent, config.customAccent)
        assertEquals("$label: opacity", expected.opacity.toDouble(), config.opacity.toDouble(), 1e-4)
        assertEquals("$label: corner_radius", expected.cornerRadiusDp, config.cornerRadiusDp)
        assertEquals("$label: font_scale", expected.fontScale.toDouble(), config.fontScale.toDouble(), 1e-4)
        assertEquals("$label: density", expected.density, config.density)
        assertEquals("$label: blocks", expected.blocks, config.blocks)
    }

    /** Builds the input block list exactly the way [WidgetConfigCodec.fromJson] would see it. */
    private fun blocksFromJson(case: JSONObject): List<WidgetBlockConfig> {
        val array = case.optJSONArray("input") ?: return emptyList()
        val result = ArrayList<WidgetBlockConfig>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val id = WidgetBlockId.fromId(entry.optString("id", "")) ?: continue
            val enabled = entry.opt("enabled") as? Boolean ?: true
            val rawOptions = entry.optJSONObject("options")
            val options = WidgetConfigCodec.defaultOptions(id).mapValues { (key, fallback) ->
                rawOptions?.opt(key) as? Boolean ?: fallback
            }
            result.add(WidgetBlockConfig(id = id, enabled = enabled, options = options))
        }
        return result
    }

    private fun assertBlocksMatch(label: String, expected: JSONArray, actual: List<WidgetBlockConfig>) {
        assertEquals("$label: block count", expected.length(), actual.size)
        for (index in 0 until expected.length()) {
            val entry = expected.getJSONObject(index)
            val block = actual.getOrNull(index)
            assertNotNull("$label: block #$index missing", block)
            block!!

            assertEquals("$label: block #$index id", entry.getString("id"), block.id.id)
            assertEquals("$label: block #$index enabled", entry.getBoolean("enabled"), block.enabled)
            assertEquals(
                "$label: block #$index options",
                optionsOf(entry.optJSONObject("options")),
                block.options,
            )
        }
    }

    private fun optionsOf(raw: JSONObject?): Map<String, Boolean> {
        if (raw == null) return emptyMap()
        val result = LinkedHashMap<String, Boolean>()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = raw.opt(key) as? Boolean ?: false
        }
        return result
    }
}
