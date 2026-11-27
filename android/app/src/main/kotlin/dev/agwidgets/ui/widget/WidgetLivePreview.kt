package dev.agwidgets.ui.widget

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agwidgets.BuildConfig
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.QuotaBucket
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeStatus
import dev.agwidgets.ui.components.UiIcons
import dev.agwidgets.ui.contextLevel
import dev.agwidgets.ui.formatCountdownShort
import dev.agwidgets.ui.formatK
import dev.agwidgets.ui.formatUnits
import dev.agwidgets.ui.formatWindow
import dev.agwidgets.ui.parseInstant
import dev.agwidgets.ui.prettyModel
import dev.agwidgets.ui.theme.AgColors
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.agColorsFor
import dev.agwidgets.ui.theme.compositeOver
import dev.agwidgets.widget.WidgetBlockConfig
import dev.agwidgets.widget.WidgetBlockId
import dev.agwidgets.widget.WidgetConfig
import dev.agwidgets.widget.WidgetConfigCodec
import dev.agwidgets.widget.WidgetConfigStore
import dev.agwidgets.widget.WidgetDensity
import dev.agwidgets.widget.WidgetPresets
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compose imitation of the home-screen widget.
 *
 * Honest WYSIWYG: the frame is drawn from the active preset's cell size
 * (`previewCols × previewRows`), the inner radius is clamped against the frame,
 * and blocks that cannot fit the cell budget are **counted** in a `+N скрыто`
 * chip instead of being silently squeezed to a sub-pixel row.
 *
 * The preview is intentionally not Glance: it lives in a normal Activity, can be
 * re-measured on every slider move and keeps the real widget contract
 * (`WidgetConfig` + `WidgetConfigStore`) as its only source of truth.
 */
@Composable
fun WidgetLivePreview(config: WidgetConfig, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = remember(context, config.theme) {
        agColorsFor(WidgetConfigStore.resolveWidgetTheme(context, config))
    }
    val customAccent = parseAccent(config.customAccent)
    val glow = customAccent ?: colors.glow
    val violet = customAccent ?: colors.violet
    val status = remember(context) { StatusStore.getMergedStatus(context) }
    val state = remember(status) { buildPreviewState(status) }
    val scale = config.fontScale.coerceIn(0.85f, 1.4f)
    // Every `sp` in the preview also follows the system font scale. Planning the
    // fit against `config.fontScale` alone is what cut the lower quota row at
    // font_scale 1.3.
    val effectiveScale = scale * LocalDensity.current.fontScale
    val innerPadding = when (config.density) {
        WidgetDensity.COMPACT -> 8.dp
        WidgetDensity.COMFORTABLE -> 10.dp
        WidgetDensity.SPACIOUS -> 14.dp
    }
    // Same vertical rhythm as the real Glance body: 4 / 6 / 8 dp, never 16.
    val blockSpacing = when (config.density) {
        WidgetDensity.COMPACT -> 4.dp
        WidgetDensity.COMFORTABLE -> 6.dp
        WidgetDensity.SPACIOUS -> 8.dp
    }
    val cells = remember(config) { previewCells(config) }
    val frameRadius = max(16, config.cornerRadiusDp + 10)
    // Formula innerRadius = outerRadius - padding, never larger.
    val framePadding = 10
    val innerRadius = config.cornerRadiusDp.coerceAtMost((frameRadius - framePadding).coerceAtLeast(0))
    val frameShape = RoundedCornerShape(frameRadius.dp)
    val innerShape = RoundedCornerShape(innerRadius.dp)
    val opacity = config.opacity.coerceIn(0f, 1f)
    // A transparent card still needs readable text in the preview.
    val surfaceAlpha = max(opacity, 0.12f)
    val cardColor = colors.surface.copy(alpha = surfaceAlpha)
        .compositeOver(colors.background)

    // `BoxWithConstraints` subcomposes during measurement, so the hidden count is
    // published through state instead of a local variable: the note under the
    // frame is composed before the inner content is measured.
    var hiddenBlocks by remember { mutableStateOf(0) }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(cells.first / cells.second.toFloat())
                // Neutral backdrop: the wallpaper is not ours to tint. The grid is
                // the author's Classic device and appears only for that theme.
                .clip(frameShape)
                .background(colors.background)
                .border(1.dp, colors.border, frameShape),
        ) {
            if (colors.isClassic) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val step = 22.dp.toPx()
                    var x = step
                    while (x < size.width) {
                        drawLine(colors.grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += step
                    }
                    var y = step
                    while (y < size.height) {
                        drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(framePadding.dp),
            ) {
                val budget = maxHeight - innerPadding * 2
                val fit = remember(config, budget, effectiveScale) {
                    fitBlocks(
                        config = config,
                        blocks = config.blocks.filter { it.enabled },
                        budgetDp = budget.value,
                        scale = effectiveScale,
                        spacingDp = blockSpacing.value,
                        maxBlocks = previewMaxBlocks(cells.second),
                    )
                }
                // Presets without a header (Контекст 4×1 / Только ZCode) host the
                // single inline ⟳ inside their first visible block, exactly like
                // the Glance body does.
                val inlineHost = fit.visible.firstOrNull()?.id
                val headerEnabled = config.isEnabled(WidgetBlockId.HEADER)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(innerShape)
                        .background(cardColor)
                        .border(
                            width = 1.dp,
                            color = if (opacity < 0.15f) colors.fieldBorder else colors.border,
                            shape = innerShape,
                        )
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.spacedBy(blockSpacing),
                ) {
                    // Material's `bodyLarge` (lineHeight 24sp) is inherited by every
                    // `Text` here otherwise, which tripled the height of a 7.5sp
                    // label and pushed the last block out of the frame. The widget
                    // itself uses natural line heights.
                    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
                        fit.visible.forEachIndexed { index, block ->
                            // Same anchor as the Glance body: the closing ZCode row
                            // sits on the bottom edge, never in the middle of slack.
                            if (index == fit.visible.lastIndex &&
                                fit.visible.size > 1 &&
                                block.id == WidgetBlockId.ZCODE
                            ) {
                                Spacer(Modifier.weight(1f))
                            }
                            val showInlineRefresh = !headerEnabled && block.id == inlineHost
                            when (block.id) {
                                WidgetBlockId.HEADER -> PreviewHeader(colors, glow, state, scale)
                                WidgetBlockId.CONTEXT -> PreviewContext(
                                    colors = colors,
                                    glow = glow,
                                    state = state,
                                    scale = scale,
                                    showCompactChip = config.option(
                                        WidgetBlockId.CONTEXT,
                                        "show_compact_chip",
                                        true,
                                    ),
                                    showInlineRefresh = showInlineRefresh,
                                )
                                WidgetBlockId.QUOTAS -> PreviewQuotas(
                                    colors = colors,
                                    glow = glow,
                                    violet = violet,
                                    state = state,
                                    scale = scale,
                                    showGemini = config.option(WidgetBlockId.QUOTAS, "show_gemini", true),
                                    showThirdParty = config.option(WidgetBlockId.QUOTAS, "show_3p", true),
                                    showCountdown = config.option(WidgetBlockId.QUOTAS, "show_countdown", true),
                                    showInlineRefresh = showInlineRefresh,
                                )
                                WidgetBlockId.ZCODE -> if (BuildConfig.ZCODE_ENABLED) {
                                    PreviewZCode(
                                        colors = colors,
                                        violet = violet,
                                        state = state,
                                        scale = scale,
                                        showBalanceBar = config.option(
                                            WidgetBlockId.ZCODE,
                                            "show_balance_bar",
                                            true,
                                        ),
                                        showInlineRefresh = showInlineRefresh,
                                    )
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(fit.hiddenCount) { hiddenBlocks = fit.hiddenCount }
            }

            if (opacity < 0.15f) {
                // Dashed frame keeps a fully transparent widget visible as an area.
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRoundRect(
                        color = colors.fieldBorder,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(frameRadius.dp.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                            ),
                        ),
                    )
                }
            }
        }
    // The "does not fit" note lives outside the frame: an overlay badge used to
    // cover the last visible row.
    if (hiddenBlocks > 0) {
        Spacer(Modifier.height(AgDim.ControlGap))
        HiddenBadge(hidden = hiddenBlocks, colors = colors)
    }
    }
}

/** Cells of the active preset, or a content-based estimate for a custom config. */
private fun previewCells(config: WidgetConfig): Pair<Int, Int> {
    val normalized = WidgetConfigCodec.normalize(config)
    WidgetPresets.all.firstOrNull { preset ->
        // A "Только ZCode" preset cannot be the active one without ZCode: its
        // stored config falls through to the content-based estimate below.
        (BuildConfig.ZCODE_ENABLED || preset.id != WidgetPresets.zcodeOnly.id) &&
            WidgetConfigCodec.normalize(preset.config) == normalized
    }?.let { return it.previewCols to it.previewRows }

    val enabled = normalized.blocks
        .filter { it.enabled }
        .filter { BuildConfig.ZCODE_ENABLED || it.id != WidgetBlockId.ZCODE }
        .map { it.id }
    val cols = if (enabled.size == 1 && enabled.first() == WidgetBlockId.ZCODE) 2 else 4
    val weight = enabled.sumOf { blockWeight(it) }
    val rows = ceil(weight / 2.25).toInt()
        .let { if (normalized.density == WidgetDensity.SPACIOUS) it + 1 else it }
        .coerceIn(1, 4)
    return cols to rows
}

private fun blockWeight(id: WidgetBlockId): Double = when (id) {
    WidgetBlockId.HEADER -> 0.5
    WidgetBlockId.CONTEXT -> 1.0
    WidgetBlockId.ZCODE -> 1.0
    WidgetBlockId.QUOTAS -> 2.0
}

private data class PreviewFit(val visible: List<WidgetBlockConfig>, val hiddenCount: Int)

/**
 * How many blocks a widget of [rows] cells can host: the Glance widget uses the
 * same priority cut (`visibleBlocks`), so preview and home screen agree.
 */
private fun previewMaxBlocks(rows: Int): Int = when {
    rows <= 1 -> 2
    rows == 2 -> 3
    else -> 4
}

/**
 * Distributes enabled blocks over the cell budget, in the user's order.
 *
 * Heights are measured against the rendered atoms (header line, context pair,
 * two quota rows, ZCode chip) times the effective type scale — config font scale
 * multiplied by the system one. Whatever does not fit is counted, never
 * compressed: a 0.7dp row is worse than an honest "+1 скрыто".
 */
private fun fitBlocks(
    config: WidgetConfig,
    blocks: List<WidgetBlockConfig>,
    budgetDp: Float,
    scale: Float,
    spacingDp: Float,
    maxBlocks: Int,
    zcodeEnabled: Boolean = BuildConfig.ZCODE_ENABLED,
): PreviewFit {
    // Same cut as the Glance body (`visibleBlocks`): the `context` flavour has
    // no ZCode data path, so the block must not take a slot or reach the frame.
    val available = blocks.filter { zcodeEnabled || it.id != WidgetBlockId.ZCODE }
    if (available.isEmpty()) return PreviewFit(emptyList(), 0)
    val quotaRows = when {
        config.option(WidgetBlockId.QUOTAS, "show_gemini", true) &&
            config.option(WidgetBlockId.QUOTAS, "show_3p", true) -> 2
        config.option(WidgetBlockId.QUOTAS, "show_gemini", true) ||
            config.option(WidgetBlockId.QUOTAS, "show_3p", true) -> 1
        else -> 0
    }

    // Measured heights of the atoms below at scale 1.0, multiplied by the
    // effective type scale. Text lines dominate every atom, so the growth is
    // linear in `scale`; the plan is a slight over-estimate on purpose — a block
    // that is counted in "+N не помещается" is honest, a clipped row is not.
    fun heightOf(id: WidgetBlockId): Float = when (id) {
        WidgetBlockId.HEADER -> 17f * scale
        WidgetBlockId.CONTEXT -> 26f * scale
        WidgetBlockId.ZCODE -> 16f * scale
        WidgetBlockId.QUOTAS -> if (quotaRows == 0) {
            0f
        } else {
            quotaRows * 27f * scale + (quotaRows - 1) * 4f
        }
    }

    val visible = ArrayList<WidgetBlockConfig>(available.size)
    var used = 0f
    available.forEachIndexed { index, block ->
        if (visible.size >= maxBlocks) return@forEachIndexed
        val needed = heightOf(block.id) + if (index == 0) 0f else spacingDp
        // Always show the first block so the preview is never an empty box; after
        // that, stop at the first block that does not fit. Skipping a block and
        // continuing would drop the primary one and keep a secondary.
        if (visible.isNotEmpty() && used + needed > budgetDp) return@forEachIndexed
        visible.add(block)
        used += needed
    }
    return PreviewFit(visible, available.size - visible.size)
}

@Composable
private fun HiddenBadge(hidden: Int, colors: AgColors, modifier: Modifier = Modifier) {
    val nouns = if (hidden == 1) "блок не помещается" else "блока не помещается"
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(AgDim.ChipRadius)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(colors.badge)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = "+$hidden $nouns в этой сетке",
                style = AgTypeScale.caption,
                color = colors.secondary,
                maxLines = 1,
            )
        }
    }
}

/** Preview atoms stay one notch smaller than the app scale. */
private object AgTypeScale {
    val caption = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
    )
}

@Composable
private fun PreviewHeader(colors: AgColors, glow: Color, state: PreviewState, scale: Float) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(glow),
        )
        Spacer(Modifier.width(4.dp))
        if (colors.isClassic) {
            Text(
                text = "AGW / 01",
                fontFamily = FontFamily.Monospace,
                fontSize = scaled(8f, scale),
                color = glow,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = "AG Widgets",
            fontSize = scaled(11f, scale),
            fontWeight = FontWeight.Bold,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = state.time ?: "—",
            fontFamily = FontFamily.Monospace,
            fontSize = scaled(8f, scale),
            color = colors.muted,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.badge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = UiIcons.Refresh,
                contentDescription = null,
                tint = colors.secondary,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun PreviewContext(
    colors: AgColors,
    glow: Color,
    state: PreviewState,
    scale: Float,
    showCompactChip: Boolean,
    showInlineRefresh: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Контекст",
                fontSize = scaled(7.5f, scale),
                color = colors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = state.model,
                fontSize = scaled(10f, scale),
                fontWeight = FontWeight.Bold,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = state.usage,
                fontFamily = FontFamily.Monospace,
                fontSize = scaled(8f, scale),
                color = colors.secondary,
                maxLines = 1,
            )
            if (showInlineRefresh) PreviewRefreshIcon(colors)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PreviewBar(
                fraction = (state.contextPct ?: 0) / 100f,
                color = levelColor(state.contextLevel, colors, glow),
                track = colors.track,
                modifier = Modifier.weight(1f),
                height = 3.dp,
            )
            // The home screen only offers `/compact` on a red window; at 43%
            // yellow the preview used to invent a chip Glance never draws.
            if (showCompactChip && state.contextLevel == "red") {
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AgDim.ChipRadius))
                        .background(colors.badge)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "/compact",
                        fontFamily = FontFamily.Monospace,
                        fontSize = scaled(7f, scale),
                        color = colors.secondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Compact inline ⟳ for presets that carry no header block at all. */
@Composable
private fun PreviewRefreshIcon(colors: AgColors) {
    Spacer(Modifier.width(4.dp))
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.badge),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = UiIcons.Refresh,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(10.dp),
        )
    }
}

@Composable
private fun PreviewQuotas(
    colors: AgColors,
    glow: Color,
    violet: Color,
    state: PreviewState,
    scale: Float,
    showGemini: Boolean,
    showThirdParty: Boolean,
    showCountdown: Boolean,
    showInlineRefresh: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showGemini) {
                PreviewQuotaRow(state.gemini, glow, colors, scale, showCountdown)
            }
            if (showThirdParty) {
                PreviewQuotaRow(state.thirdParty, violet, colors, scale, showCountdown)
            }
        }
        if (showInlineRefresh) PreviewRefreshIcon(colors)
    }
}

@Composable
private fun PreviewQuotaRow(
    quotas: List<PreviewQuota>,
    accent: Color,
    colors: AgColors,
    scale: Float,
    showCountdown: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        quotas.forEachIndexed { index, quota ->
            if (index > 0) Spacer(Modifier.width(6.dp))
            PreviewQuotaCell(quota, accent, colors, scale, showCountdown, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreviewQuotaCell(
    quota: PreviewQuota,
    accent: Color,
    colors: AgColors,
    scale: Float,
    showCountdown: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = quota.pct?.let { (it / 100f).coerceIn(0f, 1f) }
    val valueColor = when {
        fraction == null -> colors.muted
        fraction < 0.08f -> colors.rose
        fraction < 0.15f -> colors.amber
        else -> accent
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = quota.label,
                fontSize = scaled(7.5f, scale),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = quota.pct?.let { "$it%" } ?: "—",
                fontSize = scaled(9f, scale),
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
            )
        }
        PreviewBar(
            fraction = fraction ?: 0f,
            color = valueColor,
            track = colors.track,
            height = 3.dp,
        )
        if (showCountdown && quota.reset != null) {
            Text(
                text = quota.reset,
                fontFamily = FontFamily.Monospace,
                fontSize = scaled(7f, scale),
                color = colors.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * ZCode row, drawn exactly like the Glance chip: neutral backdrop with a
 * semantic dot in modern themes, the author's translucent colour chip in
 * Classic. The balance bar only exists when there is a balance to show.
 */
@Composable
private fun PreviewZCode(
    colors: AgColors,
    violet: Color,
    state: PreviewState,
    scale: Float,
    showBalanceBar: Boolean,
    showInlineRefresh: Boolean,
) {
    val accent = when (state.zcodeTone) {
        "rose" -> colors.rose
        "amber" -> colors.amber
        "glow" -> violet
        else -> colors.muted
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (colors.isClassic) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AgDim.ChipRadius))
                    .background(accent.copy(alpha = 0.20f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = state.zcodeLabel,
                    fontSize = scaled(8f, scale),
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AgDim.ChipRadius))
                    .background(colors.badge)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = state.zcodeLabel,
                    fontSize = scaled(8f, scale),
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // No balance -> no track: an empty grey pill read as "0 remaining".
        if (showBalanceBar && state.zcodeFraction != null) {
            Spacer(Modifier.width(6.dp))
            PreviewBar(
                fraction = state.zcodeFraction,
                color = accent,
                track = colors.track,
                modifier = Modifier.weight(1f),
                height = 4.dp,
            )
        } else if (showInlineRefresh) {
            Spacer(Modifier.weight(1f))
        }
        if (showInlineRefresh) PreviewRefreshIcon(colors)
    }
}

@Composable
private fun PreviewBar(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // 2dp token radius, exactly like the Glance `Bar`.
            .clip(RoundedCornerShape(AgDim.BarRadius))
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color),
        )
    }
}

private fun scaled(size: Float, scale: Float) = (size * scale).sp

private fun parseAccent(raw: String?): Color? = raw?.let { hex ->
    runCatching { AndroidColor.parseColor(hex) }.getOrNull()?.let { Color(it) }
}

private fun levelColor(level: String?, colors: AgColors, glow: Color): Color = when (level) {
    "red" -> colors.rose
    "yellow" -> colors.amber
    "green" -> colors.green
    "unknown" -> colors.muted
    else -> glow
}

private data class PreviewQuota(val label: String, val pct: Int?, val reset: String?)

private data class PreviewState(
    val time: String?,
    val model: String,
    val usage: String,
    val contextPct: Int?,
    val contextLevel: String?,
    val gemini: List<PreviewQuota>,
    val thirdParty: List<PreviewQuota>,
    val zcodeLabel: String,
    /** Semantic dot colour key: `muted` / `glow` / `amber` / `rose`. */
    val zcodeTone: String,
    val zcodeFraction: Float?,
)

/** Shown when the phone has no cached status yet. */
private val demoPreview = PreviewState(
    time = "12:40",
    model = "Gemini 4",
    usage = "312K / 512K (61%)",
    contextPct = 61,
    contextLevel = "yellow",
    gemini = listOf(
        PreviewQuota("Gemini 5 ч", 74, "2ч 40м"),
        PreviewQuota("Gemini 7 дн", 82, "4д 2ч"),
    ),
    thirdParty = listOf(
        PreviewQuota("Claude / GPT 5 ч", 41, "1ч 15м"),
        PreviewQuota("Claude / GPT 7 дн", 63, "3д 8ч"),
    ),
    zcodeLabel = "GLM 320M",
    zcodeTone = "glow",
    zcodeFraction = 0.64f,
)

private fun buildPreviewState(status: AgStatus?): PreviewState {
    if (status == null) return demoPreview
    val ctx = status.contextSummary
    val tokens = ctx?.tokens
    val window = ctx?.window?.takeIf { it > 0 }
    val pct = (ctx?.pct ?: if (tokens != null && window != null) tokens * 100.0 / window else null)
        ?.takeIf { it.isFinite() }
    val usage = if (tokens == null) {
        "—"
    } else {
        buildString {
            append(formatK(tokens))
            append(" / ")
            append(window?.let { formatWindow(it) } ?: "—")
            pct?.let { append(" (").append(it.roundToInt()).append("%)") }
        }
    }
    val geminiBuckets = status.buckets.filter { !it.id.startsWith("3p-") }
    val thirdBuckets = status.buckets.filter { it.id.startsWith("3p-") }
    val fiveHour = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "5h" }
        ?: geminiBuckets.firstOrNull { it.kind == "5h" }
    val weekly = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "weekly" }
        ?: geminiBuckets.firstOrNull { it.kind == "weekly" }
    val thirdFiveHour = thirdBuckets.firstOrNull { it.kind == "5h" }
    val thirdWeekly = thirdBuckets.firstOrNull { it.kind == "weekly" }
    val now = System.currentTimeMillis()
    return PreviewState(
        time = formatPreviewTime(status.pcUpdatedAt ?: status.updatedAt),
        model = prettyModel(ctx?.model.orEmpty().ifBlank { "—" }),
        usage = usage,
        contextPct = pct?.roundToInt(),
        contextLevel = contextLevel(pct, ctx?.level, ctx?.yellowPct, ctx?.redPct),
        gemini = listOf(
            quotaPreview("Gemini 5 ч", fiveHour, now),
            quotaPreview("Gemini 7 дн", weekly, now),
        ),
        thirdParty = listOf(
            quotaPreview("Claude / GPT 5 ч", thirdFiveHour, now),
            quotaPreview("Claude / GPT 7 дн", thirdWeekly, now),
        ),
        zcodeLabel = zcodeLabel(status.zcode),
        zcodeTone = zcodeTone(status.zcode),
        zcodeFraction = zcodeFraction(status.zcode),
    )
}

private fun quotaPreview(label: String, bucket: QuotaBucket?, now: Long): PreviewQuota {
    val fraction = bucket?.remainingFraction?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    return PreviewQuota(
        label = label,
        pct = fraction?.let { (it * 100).roundToInt() },
        reset = formatCountdownShort(bucket?.resetTime, now)?.takeIf { it != "—" },
    )
}

/**
 * Same four-state vocabulary as the Glance chip, so the builder never promises a
 * label the home screen will not draw.
 */
private fun zcodeLabel(zcode: ZCodeStatus?): String {
    if (zcode == null) return "ZCode: нет данных"
    val remaining = zcode.remainingUnits
    return when {
        zcode.isCouponUnknown -> when (zcode.errorKind) {
            "auth_expired" -> "ZCode: нужен вход"
            "network" -> "ZCode: нет связи"
            else -> "ZCode: ошибка проверки"
        }
        zcode.networkError -> "ZCode: нет связи"
        zcode.method == "disabled" -> "ZCode: не подключён"
        zcode.isCouponVisible -> "ZCode: ваучер доступен"
        remaining != null && (remaining > 0 || (zcode.totalUnits ?: 0L) > 0L) ->
            "${zcode.planName.ifBlank { "ZCode" }} ${formatUnits(remaining)}"
        else -> "ZCode: нет ваучера"
    }
}

private fun zcodeTone(zcode: ZCodeStatus?): String = when {
    zcode == null -> "muted"
    zcode.isCouponUnknown && zcode.errorKind == "auth_expired" -> "amber"
    zcode.isCouponUnknown -> "rose"
    zcode.networkError -> "rose"
    zcode.method == "disabled" -> "muted"
    zcode.isCouponVisible -> "glow"
    (zcode.remainingUnits ?: 0L) > 0L || (zcode.totalUnits ?: 0L) > 0L -> "glow"
    else -> "muted"
}

private fun zcodeFraction(zcode: ZCodeStatus?): Float? {
    if (zcode == null) return null
    val remaining = zcode.remainingUnits ?: return null
    val total = zcode.totalUnits ?: return null
    if (total <= 0) return null
    return (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private fun formatPreviewTime(iso: String?): String? {
    val instant = parseInstant(iso) ?: return null
    return DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
