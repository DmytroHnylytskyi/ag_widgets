package dev.agwidgets.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.agwidgets.R
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.ContextSummary
import dev.agwidgets.data.QuotaBucket
import dev.agwidgets.data.ZCodeStatus
import dev.agwidgets.ui.formatCountdownShort
import dev.agwidgets.ui.formatK
import dev.agwidgets.ui.formatUnits
import dev.agwidgets.ui.formatWindow
import dev.agwidgets.ui.parseInstant
import dev.agwidgets.ui.prettyModel
import dev.agwidgets.ui.theme.AgColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Modular Glance atoms shared by the widget body. Every atom is a pure function
 * of the stored config and the resolved palette, so the host widget only decides
 * block order, spacing and background.
 *
 * Two platform limits of glance 1.2.0 shape this file:
 *  - `Text` has no `overflow` parameter, so variable-length strings are cut at a
 *    character boundary by [fit] instead of being clipped mid-glyph by the host;
 *  - `GlanceModifier` has no fractional `fillMaxWidth`, so bars draw their fill
 *    with a computed dp width instead of a stretched bitmap.
 */

/**
 * Horizontal padding scale: compact 8dp, comfortable 12dp, spacious 14dp.
 *
 * Deliberately narrower than the app scale: the widget lives on a 4-column grid
 * and every dp of side padding is taken from the quota bars. Classic 4×2 used
 * 12dp, so its look stays 1:1.
 */
internal fun densityPadding(density: WidgetDensity): Int = when (density) {
    WidgetDensity.COMPACT -> 8
    WidgetDensity.COMFORTABLE -> 12
    WidgetDensity.SPACIOUS -> 14
}

/** Vertical body padding: never the same value as the horizontal one. */
internal fun densityVerticalPadding(density: WidgetDensity): Int = when (density) {
    WidgetDensity.COMPACT -> 8
    WidgetDensity.COMFORTABLE -> 10
    WidgetDensity.SPACIOUS -> 12
}

/**
 * Scales a base font size with a floor of 11sp in modern themes (the contract
 * forbids anything smaller) and 8sp in Classic, which keeps its campaign sizes.
 */
internal fun scaledSp(base: Float, fontScale: Float, colors: AgColors): Float =
    (base * fontScale).coerceIn(if (colors.isClassic) 8f else 11f, 24f)

/**
 * Vertical gap between two stacked blocks: compact 4dp, comfortable 6dp,
 * spacious 8dp. The same token drives the gap between the two quota rows, so a
 * 4×2 body never spends 16dp per seam (that is what pushed Classic to 4×3).
 */
internal fun blockGap(density: WidgetDensity): Int = when (density) {
    WidgetDensity.COMPACT -> 4
    WidgetDensity.COMFORTABLE -> 6
    WidgetDensity.SPACIOUS -> 8
}

/** Radius tokens inside the widget: chips are 6dp, bars 2dp. */
private const val CHIP_RADIUS = 6
private const val BAR_RADIUS = 2

/**
 * Ellipsis stand-in: cuts on a character boundary and appends `…`.
 *
 * The Glance `Text` of this version has no `overflow`, and the host renders a
 * clipped glyph otherwise. Budgets are deliberately generous — they only have to
 * catch runaway values (long model ids, plan tiers, collector errors).
 */
internal fun fit(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"

/**
 * Compact 40×32 refresh plate for the header row.
 *
 * A 48×48 box in the header alone inflated the Classic 4×2 header to 48dp and
 * pushed the whole widget into a 4×3 cell. The visible glyph stays 17dp and the
 * accessibility label is unchanged.
 */
@Composable
fun HeaderRefreshButton(colors: AgColors) {
    Box(
        modifier = GlanceModifier
            .clickable(actionRunCallback<RefreshActionCallback>())
            .width(40.dp)
            .height(32.dp)
            .semantics { contentDescription = "Обновить данные" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .size(28.dp)
                .background(ColorProvider(colors.badge))
                .cornerRadius(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(colors.text)),
                modifier = GlanceModifier.size(17.dp),
            )
        }
    }
}

/**
 * 40×40 touch target with a 28×28 visible plate and a vector glyph.
 *
 * Sized for the short 4×1 / 2×1 widgets it lives in: a 48dp box next to a single
 * context line made the block taller than the cell.
 */
@Composable
fun InlineRefreshButton(colors: AgColors, fontScale: Float) {
    Box(
        modifier = GlanceModifier
            .clickable(actionRunCallback<RefreshActionCallback>())
            .size(40.dp)
            .semantics { contentDescription = "Обновить данные" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .size(28.dp)
                .background(ColorProvider(colors.badge))
                .cornerRadius(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ColorProvider(colors.text)),
                modifier = GlanceModifier.size(16.dp),
            )
        }
    }
}

/** Brand line, plan tier, freshness time and the always-present refresh plate. */
@Composable
fun HeaderFreshnessAtom(
    status: AgStatus?,
    config: WidgetConfig,
    colors: AgColors,
    compact: Boolean,
    stale: Boolean,
) {
    val fg = ColorProvider(colors.text)
    val subtle = ColorProvider(colors.muted)
    val accent = ColorProvider(colors.glow)
    // The author's `AGW / 01` code stays exclusively in Classic.
    val brand = if (colors.isClassic) "AGW / 01" else "AG Widgets"
    val time = formatTimeShort(status?.pcUpdatedAt ?: status?.updatedAt) ?: "—"
    val headline = buildString {
        append("Antigravity")
        status?.planTier?.let { append(" · ").append(it) }
        if (stale) append(" · устарело")
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .background(accent)
                .cornerRadius(3.dp),
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = brand,
            style = TextStyle(
                color = if (colors.isClassic) accent else subtle,
                fontSize = scaledSp(if (compact) 9f else 10f, config.fontScale, colors).sp,
                fontFamily = if (colors.isClassic) FontFamily.Monospace else FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = fit(headline, 28),
            style = TextStyle(
                color = if (stale) ColorProvider(colors.amber) else fg,
                fontSize = scaledSp(if (compact) 11f else 12f, config.fontScale, colors).sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        HeaderRefreshButton(colors = colors)
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = time,
            style = TextStyle(
                color = subtle,
                fontSize = scaledSp(10f, config.fontScale, colors).sp,
                fontFamily = FontFamily.Monospace,
            ),
            maxLines = 1,
        )
    }
}

/** Active context window: model, token usage, colour-coded bar and `/compact` chip. */
@Composable
fun ContextBarAtom(
    ctx: ContextSummary?,
    config: WidgetConfig,
    colors: AgColors,
    showInlineRefresh: Boolean,
    barWidthDp: Float,
) {
    val subtle = ColorProvider(colors.muted)
    val tokens = ctx?.tokens
    if (ctx == null || tokens == null) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Контекст недоступен",
                style = TextStyle(
                    color = ColorProvider(colors.secondary),
                    fontSize = scaledSp(11f, config.fontScale, colors).sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (showInlineRefresh) InlineRefreshButton(colors = colors, fontScale = config.fontScale)
        }
        return
    }
    val window = ctx.window
    // NaN/Infinity must never reach the renderer or the percent label.
    val pct = (ctx.pct ?: window?.takeIf { it > 0 }?.let { tokens * 100.0 / it })
        ?.takeIf { it.isFinite() }
    val fraction = (pct?.div(100.0) ?: 0.0).toFloat().coerceIn(0f, 1f)
    val levelColor: Color = when (ctx.level) {
        "red" -> colors.rose
        "yellow" -> colors.amber
        "unknown" -> colors.muted
        "green" -> colors.green
        else -> colors.glow
    }
    val showChip = config.option(WidgetBlockId.CONTEXT, "show_compact_chip", true) && ctx.level == "red"
    val barHeight = if (config.density == WidgetDensity.COMPACT) 4 else 5

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Контекст",
                style = TextStyle(
                    color = subtle,
                    fontSize = scaledSp(11f, config.fontScale, colors).sp,
                    fontFamily = if (colors.isClassic) FontFamily.Monospace else FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = fit(prettyModel(ctx.model.orEmpty().ifBlank { "—" }), 18),
                style = TextStyle(
                    color = ColorProvider(colors.text),
                    fontSize = scaledSp(12f, config.fontScale, colors).sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = buildString {
                    append(formatK(tokens))
                    append(" / ")
                    append(window?.let { formatWindow(it) } ?: "—")
                    pct?.let { append(" (").append(it.roundToInt()).append("%)") }
                },
                style = TextStyle(
                    color = subtle,
                    fontSize = scaledSp(10f, config.fontScale, colors).sp,
                    fontFamily = FontFamily.Monospace,
                ),
                maxLines = 1,
            )
            if (showChip) {
                Spacer(GlanceModifier.width(4.dp))
                CompactChip(colors, config.fontScale)
            }
            if (showInlineRefresh) InlineRefreshButton(colors = colors, fontScale = config.fontScale)
        }
        Spacer(GlanceModifier.height(4.dp))
        Bar(
            fraction = fraction,
            fillColor = levelColor,
            trackColor = colors.track,
            heightDp = barHeight,
            widthDp = if (showChip) barWidthDp * 0.72f else barWidthDp,
        )
    }
}

/** Gemini and Claude/GPT quota pairs as a 2x2 grid (or a single row in short widgets). */
@Composable
fun QuotasAtom(
    status: AgStatus?,
    config: WidgetConfig,
    colors: AgColors,
    isCompactHeight: Boolean,
    contentWidthDp: Float,
) {
    val showGemini = config.option(WidgetBlockId.QUOTAS, "show_gemini", true)
    val show3p = config.option(WidgetBlockId.QUOTAS, "show_3p", true)
    val showCountdown = config.option(WidgetBlockId.QUOTAS, "show_countdown", true)
    // Both groups disabled means the block disappears entirely: no empty holes.
    if (!showGemini && !show3p) return

    val buckets = status?.buckets.orEmpty()
    // Gemini slots never use 3p- buckets; Claude/GPT slots use only 3p- buckets.
    val geminiBuckets = buckets.filter { !it.id.startsWith("3p-") }
    val fiveHour = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "5h" }
        ?: geminiBuckets.firstOrNull { it.kind == "5h" }
    val weekly = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "weekly" }
        ?: geminiBuckets.firstOrNull { it.kind == "weekly" }
    val thirdFiveHour = buckets.firstOrNull { it.id.startsWith("3p-") && it.kind == "5h" }
    val thirdWeekly = buckets.firstOrNull { it.id.startsWith("3p-") && it.kind == "weekly" }

    val gap = blockGap(config.density)
    val now = System.currentTimeMillis()
    val cellGap = if (isCompactHeight) 6.dp else 8.dp
    val grid = showGemini && show3p && !isCompactHeight
    // Width budget for one bar cell, so the fill never relies on stretching.
    val cellCount = if (grid) 2 else listOf(showGemini, show3p).count { it } * 2
    val cellWidth = ((contentWidthDp - cellGap.value * (cellCount - 1)) / cellCount)
        .coerceAtLeast(8f)
    // Classic keeps the author's campaign vocabulary (`GEMINI 5H` / `WK`).
    val geminiFiveHourLabel = if (colors.isClassic) "GEMINI 5H" else "Gemini 5 ч"
    val geminiWeeklyLabel = if (colors.isClassic) "GEMINI WK" else "Gemini 7 дн"
    val thirdFiveHourLabel = if (colors.isClassic) "CLAUDE/GPT 5H" else "Claude / GPT 5 ч"
    val thirdWeeklyLabel = if (colors.isClassic) "CLAUDE/GPT WK" else "Claude / GPT 7 дн"

    if (grid) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                QuotaCell(
                    label = geminiFiveHourLabel,
                    bucket = fiveHour,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.glow,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(cellGap))
                QuotaCell(
                    label = geminiWeeklyLabel,
                    bucket = weekly,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.glow,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
            Spacer(GlanceModifier.height(gap.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                QuotaCell(
                    label = thirdFiveHourLabel,
                    bucket = thirdFiveHour,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.violet,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(cellGap))
                QuotaCell(
                    label = thirdWeeklyLabel,
                    bucket = thirdWeekly,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.violet,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
    } else {
        // One row: only the enabled groups get cells, so no blank placeholders.
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            if (showGemini) {
                QuotaCell(
                    label = geminiFiveHourLabel,
                    bucket = fiveHour,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.glow,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(cellGap))
                QuotaCell(
                    label = geminiWeeklyLabel,
                    bucket = weekly,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.glow,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
            if (showGemini && show3p) Spacer(GlanceModifier.width(gap.dp))
            if (show3p) {
                QuotaCell(
                    label = thirdFiveHourLabel,
                    bucket = thirdFiveHour,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.violet,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(cellGap))
                QuotaCell(
                    label = thirdWeeklyLabel,
                    bucket = thirdWeekly,
                    config = config,
                    colors = colors,
                    showCountdown = showCountdown,
                    accent = colors.violet,
                    now = now,
                    barWidthDp = cellWidth,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
    }
}

/** ZCode status chip plus the optional remaining-balance bar. */
@Composable
fun ZCodeAtom(
    zcode: ZCodeStatus?,
    config: WidgetConfig,
    colors: AgColors,
    showInlineRefresh: Boolean,
    contentWidthDp: Float,
    trailingContext: ContextSummary? = null,
) {
    val (text, accent) = zcodeStatus(zcode, colors)

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                text = text,
                accent = accent,
                colors = colors,
                fontScale = config.fontScale,
            )
            // Classic 4×2 kept a compact context reading on this same line; it
            // only appears when the CONTEXT block itself is switched off.
            if (trailingContext != null) {
                Spacer(GlanceModifier.defaultWeight())
                ContextTrailingReadout(
                    ctx = trailingContext,
                    colors = colors,
                    fontScale = config.fontScale,
                )
            }
            if (showInlineRefresh) {
                Spacer(GlanceModifier.defaultWeight())
                InlineRefreshButton(colors = colors, fontScale = config.fontScale)
            }
        }
        val remaining = zcode?.remainingUnits
        val total = zcode?.totalUnits
        if (config.option(WidgetBlockId.ZCODE, "show_balance_bar", true) &&
            remaining != null && total != null && total > 0L
        ) {
            Spacer(GlanceModifier.height(blockGap(config.density).dp))
            Bar(
                fraction = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                fillColor = colors.glow,
                trackColor = colors.track,
                heightDp = 4,
                widthDp = contentWidthDp,
            )
        }
    }
}

/**
 * Native bar: a track plate and a fill plate with the 2dp token radius.
 *
 * The fill is a dp width (`fraction × track`), not a 240×8 bitmap stretched 4.2×,
 * so round caps stay round at any widget width. Glance 1.2 has no fractional
 * `fillMaxWidth`, hence the computed width.
 */
@Composable
private fun Bar(
    fraction: Float,
    fillColor: Color,
    trackColor: Color,
    heightDp: Int,
    widthDp: Float,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(ColorProvider(trackColor))
            .cornerRadius(BAR_RADIUS.dp),
    ) {
        if (clamped > 0.01f) {
            Box(
                modifier = GlanceModifier
                    .width((widthDp * clamped).dp)
                    .fillMaxHeight()
                    .background(ColorProvider(fillColor))
                    .cornerRadius(BAR_RADIUS.dp),
            ) {}
        }
    }
}

@Composable
private fun CompactChip(colors: AgColors, fontScale: Float) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(colors.badge))
            .cornerRadius(CHIP_RADIUS.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "/compact",
            style = TextStyle(
                color = ColorProvider(colors.rose),
                fontSize = scaledSp(9f, fontScale, colors).sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Status chip. Modern themes use a neutral backdrop with a semantic dot (the
 * label is always `text`, so a tinted fill can never eat the contrast); Classic
 * keeps the author's translucent colour chip without the dot.
 */
@Composable
private fun StatusChip(
    text: String,
    accent: Color,
    colors: AgColors,
    fontScale: Float,
) {
    if (colors.isClassic) {
        Box(
            modifier = GlanceModifier
                .background(ColorProvider(accent.copy(alpha = 0.20f)))
                .cornerRadius(CHIP_RADIUS.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = fit(text, 26),
                style = TextStyle(
                    color = ColorProvider(colors.text),
                    fontSize = scaledSp(9.5f, fontScale, colors).sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
        return
    }
    Row(
        modifier = GlanceModifier
            .background(ColorProvider(colors.badge))
            .cornerRadius(CHIP_RADIUS.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .background(ColorProvider(accent))
                .cornerRadius(3.dp),
        ) {}
        Spacer(GlanceModifier.width(5.dp))
        Text(
            text = fit(text, 26),
            style = TextStyle(
                color = ColorProvider(colors.text),
                fontSize = scaledSp(11f, fontScale, colors).sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Classic's compact context reading (`ЧАТ 223K 43%` + level dot), the one the
 * author drew on the same line as the ZCode chip.
 */
@Composable
private fun ContextTrailingReadout(
    ctx: ContextSummary,
    colors: AgColors,
    fontScale: Float,
) {
    val tokens = ctx.tokens ?: return
    val levelColor = when (ctx.level) {
        "red" -> colors.rose
        "yellow" -> colors.amber
        "green" -> colors.green
        "unknown" -> colors.muted
        else -> colors.glow
    }
    val prefix = if (ctx.active) "ЧАТ" else "ПК"
    val pct = ctx.pct?.takeIf { it.isFinite() }?.let { " ${it.roundToInt()}%" }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$prefix ${formatK(tokens)}$pct",
            style = TextStyle(
                color = ColorProvider(colors.muted),
                fontSize = scaledSp(9.5f, fontScale, colors).sp,
                fontFamily = FontFamily.Monospace,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(4.dp))
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .background(ColorProvider(levelColor))
                .cornerRadius(3.dp),
        ) {}
    }
}

@Composable
private fun QuotaCell(
    label: String,
    bucket: QuotaBucket?,
    config: WidgetConfig,
    colors: AgColors,
    showCountdown: Boolean,
    accent: Color,
    now: Long,
    barWidthDp: Float,
    modifier: GlanceModifier = GlanceModifier,
) {
    val frac = bucket?.remainingFraction?.toFloat()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0f, 1f)
    val pct = frac?.let { (it * 100).roundToInt() }
    // Healthy buckets follow the group accent; warning tones only at the end.
    val valueColor: Color = when {
        bucket == null -> colors.muted
        frac != null && frac < 0.08f -> colors.rose
        frac != null && frac < 0.15f -> colors.amber
        else -> accent
    }

    Column(modifier = modifier) {
        // Classic keeps the author's 8.5 / 10 / 8 sp campaign scale; modern
        // themes sit on the 11 / 12 / 10 sp app scale.
        val labelSize = if (colors.isClassic) 8.5f else 11f
        val valueSize = if (colors.isClassic) 10f else 12f
        val resetSize = if (colors.isClassic) 8f else 10f
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = ColorProvider(colors.muted),
                    fontSize = scaledSp(labelSize, config.fontScale, colors).sp,
                    fontFamily = if (colors.isClassic) FontFamily.Monospace else FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = if (pct == null) "—" else "$pct%",
                style = TextStyle(
                    color = ColorProvider(valueColor),
                    fontSize = scaledSp(valueSize, config.fontScale, colors).sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(3.dp))
        Bar(frac ?: 0f, valueColor, colors.track, 3, barWidthDp)
        // A missing reset time hides the line instead of printing a lone dash.
        val reset = if (showCountdown) {
            formatCountdownShort(bucket?.resetTime, now)?.takeIf { it != "—" }
        } else {
            null
        }
        if (reset != null) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = reset,
                style = TextStyle(
                    color = ColorProvider(colors.muted),
                    fontSize = scaledSp(resetSize, config.fontScale, colors).sp,
                    fontFamily = FontFamily.Monospace,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * One ZCode vocabulary for the widget: available / absent / not connected /
 * check failed. Technical detail (HTTP codes, parser messages) never reaches the
 * home screen; the balance case adds the plan and the remaining units.
 */
private fun zcodeStatus(z: ZCodeStatus?, colors: AgColors): Pair<String, Color> {
    if (z == null) return "ZCode: нет данных" to colors.muted
    val remaining = z.remainingUnits
    val total = z.totalUnits
    return when {
        z.isCouponUnknown -> when (z.errorKind) {
            "auth_expired" -> "ZCode: нужен вход" to colors.amber
            "network" -> "ZCode: нет связи" to colors.rose
            else -> "ZCode: ошибка проверки" to colors.rose
        }
        z.networkError -> "ZCode: нет связи" to colors.rose
        z.method == "disabled" -> "ZCode: не подключён" to colors.muted
        z.isCouponVisible -> "ZCode: ваучер доступен" to colors.glow
        remaining != null && (remaining > 0 || (total != null && total > 0)) ->
            "${z.planName.ifBlank { "ZCode" }} ${formatUnits(remaining)}" to colors.glow
        else -> "ZCode: нет ваучера" to colors.muted
    }
}

private fun formatTimeShort(iso: String?): String? {
    val instant = parseInstant(iso) ?: return null
    return DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
