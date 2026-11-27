package dev.agwidgets.widget

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Widget builder configuration (appearance_schema_v1, Android flavour).
 *
 * Deliberately free of `android.*` imports: the whole file runs on a plain JVM
 * so `WidgetConfigTest` can replay the shared golden fixtures from
 * `tests/fixtures/appearance_cases.json` next to the desktop implementation.
 *
 * Desktop block ids (`context_hero`/`quotas_grid`/`zcode_card`) map onto the
 * short ids used here (`context`/`quotas`/`zcode`); `tests/fixtures` speaks the
 * short names and each platform asserts its own namespaced expectation.
 */

/** Theme ids understood by the widget. Mirrors `AgThemeMode` without Compose. */
object WidgetThemes {
    const val SYSTEM = "system"
    const val GRAPHITE = "graphite"
    const val PAPER = "paper"
    const val OLED = "oled"
    const val GLASS = "glass"
    const val CLASSIC = "classic"

    val all: List<String> = listOf(SYSTEM, GRAPHITE, PAPER, OLED, GLASS, CLASSIC)

    /** Trim + lowercase; legacy `dark`/`light` map onto the closest modern theme. */
    fun normalize(raw: String?): String = when (val value = raw?.trim()?.lowercase().orEmpty()) {
        "dark" -> GRAPHITE
        "light" -> PAPER
        in all -> value
        else -> SYSTEM
    }
}

/** Blocks a widget can show, in canonical order. */
enum class WidgetBlockId(val id: String) {
    HEADER("header"),
    CONTEXT("context"),
    QUOTAS("quotas"),
    ZCODE("zcode"),
    ;

    companion object {
        val all: List<WidgetBlockId> = entries

        fun fromId(raw: String?): WidgetBlockId? = when (raw?.trim()?.lowercase().orEmpty()) {
            "header" -> HEADER
            "context", "context_hero" -> CONTEXT
            "quotas", "quotas_grid" -> QUOTAS
            "zcode", "zcode_card" -> ZCODE
            else -> null
        }
    }
}

/** Spacing scale for the widget body. */
enum class WidgetDensity(val id: String) {
    COMPACT("compact"),
    COMFORTABLE("comfortable"),
    SPACIOUS("spacious"),
    ;

    companion object {
        val all: List<WidgetDensity> = entries

        fun fromId(raw: String?): WidgetDensity? {
            val value = raw?.trim()?.lowercase().orEmpty()
            return all.firstOrNull { it.id == value }
        }
    }
}

/** One block: whether it renders and its per-block switches. */
data class WidgetBlockConfig(
    val id: WidgetBlockId,
    val enabled: Boolean = true,
    val options: Map<String, Boolean> = emptyMap(),
)

/**
 * Complete widget configuration for a single `appWidgetId`.
 *
 * Defaults match the historical 4x2 widget: everything on, 95% opacity and a
 * 14dp radius, so a freshly placed widget looks exactly like before.
 */
data class WidgetConfig(
    val theme: String = WidgetThemes.SYSTEM,
    val customAccent: String? = null,
    val opacity: Float = WidgetConfigCodec.DEFAULT_OPACITY,
    val cornerRadiusDp: Int = WidgetConfigCodec.DEFAULT_CORNER_RADIUS,
    val fontScale: Float = WidgetConfigCodec.DEFAULT_FONT_SCALE,
    val density: WidgetDensity = WidgetDensity.COMFORTABLE,
    val blocks: List<WidgetBlockConfig> = WidgetConfigCodec.defaultBlocks(),
) {
    fun block(id: WidgetBlockId): WidgetBlockConfig? = blocks.firstOrNull { it.id == id }

    fun isEnabled(id: WidgetBlockId): Boolean = block(id)?.enabled == true

    /** Strict boolean read: anything but a real boolean falls back. */
    fun option(id: WidgetBlockId, key: String, fallback: Boolean): Boolean =
        block(id)?.options?.get(key) ?: fallback

    /** First enabled block in the user's order - the one that hosts the inline ⟳. */
    fun firstEnabled(): WidgetBlockConfig? = blocks.firstOrNull { it.enabled }
}

/**
 * Named one-click presets; `id` is stable for shared configs and JSON.
 *
 * [previewCols]/[previewRows] exist only for the builder preview — the JSON
 * schema is unchanged, so a shared config never carries cell sizes.
 */
data class WidgetPreset(
    val id: String,
    val label: String,
    val config: WidgetConfig,
    val previewCols: Int = 4,
    val previewRows: Int = 2,
)

object WidgetPresets {
    /** 4x2 dashboard: header + quotas + context + ZCode. */
    val dashboard = WidgetPreset(
        id = "dashboard",
        label = "Дашборд 4×2",
        previewCols = 4,
        previewRows = 2,
        config = WidgetConfig(
            theme = WidgetThemes.SYSTEM,
            opacity = 0.95f,
            cornerRadiusDp = 14,
            density = WidgetDensity.COMFORTABLE,
            // Same order as `WidgetConfigCodec.defaultBlocks()`, so opening the
            // builder on a default config highlights this preset.
            blocks = listOf(
                block(WidgetBlockId.HEADER),
                block(WidgetBlockId.CONTEXT),
                block(WidgetBlockId.QUOTAS),
                block(WidgetBlockId.ZCODE),
            ),
        ),
    )

    /** The author's original 4x2 look, 1:1 (context only on taller layouts). */
    val classic = WidgetPreset(
        id = "classic",
        label = "Classic 4×2",
        previewCols = 4,
        previewRows = 2,
        config = WidgetConfig(
            theme = WidgetThemes.CLASSIC,
            opacity = 0.95f,
            cornerRadiusDp = 10,
            density = WidgetDensity.COMFORTABLE,
            blocks = listOf(
                block(WidgetBlockId.HEADER),
                block(WidgetBlockId.QUOTAS),
                block(WidgetBlockId.ZCODE),
                block(WidgetBlockId.CONTEXT, enabled = false),
            ),
        ),
    )

    /** 4x1: the full context bar (with `/compact`) and nothing else. */
    val contextCompact = WidgetPreset(
        id = "context_compact",
        label = "Контекст 4×1",
        previewCols = 4,
        previewRows = 1,
        config = WidgetConfig(
            theme = WidgetThemes.SYSTEM,
            opacity = 0.95f,
            cornerRadiusDp = 14,
            density = WidgetDensity.COMPACT,
            blocks = listOf(
                block(WidgetBlockId.CONTEXT, options = mapOf("show_compact_chip" to true)),
                block(WidgetBlockId.HEADER, enabled = false),
                block(WidgetBlockId.QUOTAS, enabled = false),
                block(WidgetBlockId.ZCODE, enabled = false),
            ),
        ),
    )

    /** 4x3: every block with every option, spacious spacing. */
    val detailed = WidgetPreset(
        id = "detailed",
        label = "Подробный 4×3",
        previewCols = 4,
        previewRows = 3,
        config = WidgetConfig(
            theme = WidgetThemes.SYSTEM,
            opacity = 0.95f,
            cornerRadiusDp = 14,
            density = WidgetDensity.SPACIOUS,
            blocks = listOf(
                block(WidgetBlockId.HEADER),
                block(WidgetBlockId.CONTEXT),
                block(WidgetBlockId.QUOTAS),
                block(WidgetBlockId.ZCODE),
            ),
        ),
    )

    /** 2x1: ZCode balance only. */
    val zcodeOnly = WidgetPreset(
        id = "zcode_only",
        label = "Только ZCode 2×1",
        previewCols = 2,
        previewRows = 1,
        config = WidgetConfig(
            theme = WidgetThemes.SYSTEM,
            opacity = 0.95f,
            cornerRadiusDp = 14,
            density = WidgetDensity.COMPACT,
            blocks = listOf(
                block(WidgetBlockId.ZCODE, options = mapOf("show_balance_bar" to true)),
                block(WidgetBlockId.HEADER, enabled = false),
                block(WidgetBlockId.CONTEXT, enabled = false),
                block(WidgetBlockId.QUOTAS, enabled = false),
            ),
        ),
    )

    val all: List<WidgetPreset> = listOf(dashboard, classic, contextCompact, detailed, zcodeOnly)

    fun byId(raw: String?): WidgetPreset? {
        val value = raw?.trim()?.lowercase().orEmpty()
        return all.firstOrNull { it.id == value }
    }

    /** Block with defaults filled in; `options` only overrides the given keys. */
    private fun block(
        id: WidgetBlockId,
        enabled: Boolean = true,
        options: Map<String, Boolean> = emptyMap(),
    ): WidgetBlockConfig = WidgetBlockConfig(
        id = id,
        enabled = enabled,
        options = WidgetConfigCodec.defaultOptions(id) + options,
    )
}

/**
 * Validation / (de)serialisation shared by the widget, the config activity and
 * the unit tests. Every entry point is total: bad input degrades to defaults
 * instead of throwing.
 */
object WidgetConfigCodec {
    const val MAX_IMPORT_BYTES = 16384

    const val DEFAULT_OPACITY = 0.95f
    const val DEFAULT_CORNER_RADIUS = 14
    const val DEFAULT_FONT_SCALE = 1.0f
    const val MIN_OPACITY = 0.0f
    const val MAX_OPACITY = 1.0f
    const val MIN_FONT_SCALE = 0.85f
    const val MAX_FONT_SCALE = 1.4f
    const val MIN_CORNER_RADIUS = 0
    const val MAX_CORNER_RADIUS = 24
    const val CORNER_RADIUS_STEP = 2
    const val SCHEMA_VERSION = 1

    private val BLOCK_OPTION_DEFAULTS: Map<WidgetBlockId, Map<String, Boolean>> = mapOf(
        WidgetBlockId.HEADER to emptyMap(),
        WidgetBlockId.CONTEXT to mapOf("show_compact_chip" to true),
        WidgetBlockId.QUOTAS to mapOf(
            "show_gemini" to true,
            "show_3p" to true,
            "show_countdown" to true,
        ),
        WidgetBlockId.ZCODE to mapOf("show_balance_bar" to true),
    )

    fun defaultOptions(id: WidgetBlockId): Map<String, Boolean> = BLOCK_OPTION_DEFAULTS[id].orEmpty()

    /** All blocks enabled, canonical order. */
    fun defaultBlocks(): List<WidgetBlockConfig> = WidgetBlockId.all.map { id ->
        WidgetBlockConfig(id = id, enabled = true, options = defaultOptions(id))
    }

    /** `#rrggbb` only; shorthands, colour functions, urls and non-strings become null. */
    fun normalizeHexAccent(raw: String?): String? {
        if (raw == null) return null
        val value = raw.trim()
        if (value.length != 7 || !value.startsWith("#")) return null
        val hex = value.substring(1)
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return "#" + hex.lowercase()
    }

    /** Round to the nearest 0.05 step, then clamp into `[min, max]`. */
    fun clampStep(value: Double?, min: Double, max: Double, fallback: Double): Double {
        if (value == null || !value.isFinite()) return fallback
        val stepped = jsRound(value * 20.0) / 20.0
        return stepped.coerceIn(min, max)
    }

    /** Round to the nearest even value, then clamp into `[0, 24]`. */
    fun clampCornerRadius(value: Double?, fallback: Int = DEFAULT_CORNER_RADIUS): Int {
        if (value == null || !value.isFinite()) return fallback
        val stepped = (jsRound(value / CORNER_RADIUS_STEP) * CORNER_RADIUS_STEP).roundToInt()
        return stepped.coerceIn(MIN_CORNER_RADIUS, MAX_CORNER_RADIUS)
    }

    /**
     * Half-up rounding that matches JavaScript's `Math.round`.
     *
     * `kotlin.math.round` is banker's rounding (2.5 -> 2.0), which would make
     * the Android codec disagree with the desktop implementation on every
     * `x.5` step - and the shared fixtures check exactly those edges.
     */
    private fun jsRound(value: Double): Double = floor(value + 0.5)

    /**
     * Ordered, de-duplicated blocks: unknown ids are dropped, duplicates after
     * the first occurrence ignored, missing blocks appended with defaults.
     */
    fun normalizeBlocks(raw: List<WidgetBlockConfig>?): List<WidgetBlockConfig> {
        val result = ArrayList<WidgetBlockConfig>(WidgetBlockId.all.size)
        val seen = HashSet<WidgetBlockId>()
        raw.orEmpty().forEach { entry ->
            if (!seen.add(entry.id)) return@forEach
            result.add(
                WidgetBlockConfig(
                    id = entry.id,
                    enabled = entry.enabled,
                    options = normalizeOptions(entry.id, entry.options),
                ),
            )
        }
        WidgetBlockId.all.forEach { id ->
            if (seen.add(id)) {
                result.add(WidgetBlockConfig(id = id, enabled = true, options = defaultOptions(id)))
            }
        }
        return result
    }

    /** Keep only known option keys, coercing every value to a strict boolean. */
    private fun normalizeOptions(
        id: WidgetBlockId,
        raw: Map<String, Boolean>,
    ): Map<String, Boolean> = defaultOptions(id).mapValues { (key, fallback) ->
        raw[key] ?: fallback
    }

    /** Full normalisation for an in-memory config or a freshly parsed one. */
    fun normalize(config: WidgetConfig?): WidgetConfig {
        val source = config ?: WidgetConfig()
        return WidgetConfig(
            theme = WidgetThemes.normalize(source.theme),
            customAccent = normalizeHexAccent(source.customAccent),
            opacity = clampStep(
                source.opacity.toDouble(),
                MIN_OPACITY.toDouble(),
                MAX_OPACITY.toDouble(),
                DEFAULT_OPACITY.toDouble(),
            ).toFloat(),
            cornerRadiusDp = clampCornerRadius(source.cornerRadiusDp.toDouble()),
            fontScale = clampStep(
                source.fontScale.toDouble(),
                MIN_FONT_SCALE.toDouble(),
                MAX_FONT_SCALE.toDouble(),
                DEFAULT_FONT_SCALE.toDouble(),
            ).toFloat(),
            density = WidgetDensity.fromId(source.density.id) ?: WidgetDensity.COMFORTABLE,
            blocks = normalizeBlocks(source.blocks),
        )
    }

    fun toJson(config: WidgetConfig): JSONObject {
        val normalized = normalize(config)
        val blocks = JSONArray()
        normalized.blocks.forEach { entry ->
            val options = JSONObject()
            entry.options.forEach { (key, value) -> options.put(key, value) }
            blocks.put(
                JSONObject()
                    .put("id", entry.id.id)
                    .put("enabled", entry.enabled)
                    .put("options", options),
            )
        }
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("theme", normalized.theme)
            .put("custom_accent", normalized.customAccent ?: JSONObject.NULL)
            .put("opacity", roundTwo(normalized.opacity))
            .put("corner_radius", normalized.cornerRadiusDp)
            .put("font_scale", roundTwo(normalized.fontScale))
            .put("density", normalized.density.id)
            .put("blocks", blocks)
    }

    fun fromJson(raw: JSONObject): WidgetConfig {
        val blocks = ArrayList<WidgetBlockConfig>()
        val array = raw.optJSONArray("blocks")
        if (array != null) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = WidgetBlockId.fromId(entry.optString("id", "")) ?: continue
                val enabled = entry.opt("enabled") as? Boolean ?: true
                val options = entry.optJSONObject("options")
                blocks.add(
                    WidgetBlockConfig(
                        id = id,
                        enabled = enabled,
                        options = defaultOptions(id).mapValues { (key, fallback) ->
                            options?.opt(key) as? Boolean ?: fallback
                        },
                    ),
                )
            }
        }
        return normalize(
            WidgetConfig(
                theme = raw.optString("theme", WidgetThemes.SYSTEM),
                customAccent = raw.opt("custom_accent") as? String,
                opacity = (raw.opt("opacity") as? Number)?.toFloat() ?: DEFAULT_OPACITY,
                cornerRadiusDp = clampCornerRadius(
                    (raw.opt("corner_radius") as? Number)?.toDouble(),
                ),
                fontScale = (raw.opt("font_scale") as? Number)?.toFloat() ?: DEFAULT_FONT_SCALE,
                density = WidgetDensity.fromId(raw.optString("density", ""))
                    ?: WidgetDensity.COMFORTABLE,
                blocks = blocks,
            ),
        )
    }

    /** Pretty JSON for the clipboard / export button. */
    fun exportJson(config: WidgetConfig): String = toJson(config).toString(2)

    /**
     * Parse a pasted preset. Returns null on any problem (empty, oversized,
     * malformed, non-object root); the caller keeps its current configuration.
     */
    fun importJson(raw: String?): WidgetConfig? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) return null
        val parsed = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return runCatching { fromJson(parsed) }.getOrNull()
    }

    private fun roundTwo(value: Float): Double = (value * 100f).roundToInt() / 100.0
}
