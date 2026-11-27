package dev.agwidgets.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.agwidgets.BuildConfig
import dev.agwidgets.MainActivity
import dev.agwidgets.R
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.StatusStore
import dev.agwidgets.ui.parseInstant
import dev.agwidgets.ui.theme.AgColors
import dev.agwidgets.ui.theme.ResolvedAgTheme
import dev.agwidgets.ui.theme.agColorsFor
import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.work.performRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class RefreshActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // Rapid taps must not stack overlapping network passes.
        if (!refreshInFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        try {
            // Hard cap keeps the launcher's action window happy even if a host
            // ignores the short per-request timeouts.
            withTimeoutOrNull(8_500L) {
                withContext(Dispatchers.IO) {
                    performRefresh(appContext, timeoutMs = 4_000, updateWidget = false)
                }
            }
        } finally {
            runCatching { LimitsWidget().updateAll(appContext) }
            refreshInFlight.set(false)
        }
    }

    private companion object {
        private val refreshInFlight = AtomicBoolean(false)
    }
}

/** Widget size buckets: which blocks survive at which height. */
internal enum class WidgetLayoutMode { COMPACT, STANDARD, EXPANDED }

/**
 * Height buckets that decide how many blocks survive.
 *
 * Calibrated against real launcher cells, not against the tallest layout the
 * builder can produce: a 4×2 widget on this device is ~110–155 dp tall, so the
 * old `165 dp` COMPACT threshold demoted every 4×2 to a 2-block stub.
 *  - `< 95 dp`      → COMPACT   (4×1 / 2×1: two blocks, one quota row)
 *  - `95..185 dp`   → STANDARD  (4×2: three blocks, 2×2 quota grid)
 *  - `> 185 dp`     → EXPANDED  (4×3+: every enabled block)
 */
internal fun widgetLayoutMode(heightDp: Float): WidgetLayoutMode = when {
    heightDp < 95f -> WidgetLayoutMode.COMPACT
    heightDp <= 185f -> WidgetLayoutMode.STANDARD
    else -> WidgetLayoutMode.EXPANDED
}

/** Data older than two refresh periods (30 min) is still shown, but flagged. */
private const val STALE_AFTER_MINUTES = 30L

/** Translucency floor so a 0% widget does not turn unreadable text into soup. */
private const val MIN_SURFACE_ALPHA = 0.15f

class LimitsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A widget created before the config-store landed has no stored id;
        // -1 simply resolves to the default config.
        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrDefault(-1)
        val config = WidgetConfigStore.get(context, appWidgetId)
        val resolved = WidgetConfigStore.resolveWidgetTheme(context, config)
        val status = StatusStore.getMergedStatus(context)
        val strings = StatusStore.resolveStrings(context)
        provideContent {
            WidgetContent(status = status, resolved = resolved, config = config, strings = strings)
        }
    }
}

/**
 * Background art for a resolved theme: calm flat surfaces for the modern
 * themes, the author's glass layers for `classic`.
 */
private fun widgetBackground(resolved: ResolvedAgTheme): Int = when (resolved.id) {
    "graphite" -> R.drawable.widget_bg_graphite
    "paper" -> R.drawable.widget_bg_paper
    "oled" -> R.drawable.widget_bg_oled
    "glass_dark", "classic_dark" -> R.drawable.glass_bg
    else -> R.drawable.glass_bg_light
}

@Composable
private fun WidgetContent(
    status: AgStatus?,
    resolved: ResolvedAgTheme,
    config: WidgetConfig,
    strings: AgStrings,
) {
    val size = LocalSize.current
    val heightDp = size.height.value
    val widthDp = size.width.value
    val mode = widgetLayoutMode(heightDp)
    val isCompactHeight = mode == WidgetLayoutMode.COMPACT
    val isNarrow = widthDp < 220f
    // Horizontal padding and the vertical rhythm are two separate scales: the
    // old shared `16 dp` step was used as the gap between every block too.
    val horizontalPadding = if (isCompactHeight) 8 else densityPadding(config.density)
    val verticalPadding = if (isCompactHeight) 6 else densityVerticalPadding(config.density)
    val gap = if (isCompactHeight) 4 else blockGap(config.density)
    val contentWidthDp = (widthDp - 2 * horizontalPadding).coerceAtLeast(24f)

    // Colours are always fixed ARGB values: `system` was already resolved against
    // the current night mode, so every palette is concrete by this point.
    val baseColors = agColorsFor(resolved)
    val accentColor: Color = config.customAccent?.let { raw ->
        runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrNull()
    } ?: baseColors.glow
    val colors: AgColors = if (accentColor == baseColors.glow) {
        baseColors
    } else {
        baseColors.copy(glow = accentColor, violet = accentColor)
    }

    val opacity = config.opacity.coerceIn(0f, 1f)
    val cornerRadius = config.cornerRadiusDp.dp

    // Classic keeps the author's glass art as the base plate; every other theme
    // is a flat surface. Translucency is one continuous slider value in both
    // cases — no `opacity == 0.95 && radius == 10` special case.
    val useArtBackdrop = colors.isClassic
    val surfaceAlpha = if (useArtBackdrop) 1f else opacity.coerceAtLeast(MIN_SURFACE_ALPHA)

    val rootModifier = GlanceModifier
        .fillMaxSize()
        .clickable(actionStartActivity<MainActivity>())
    val surfaceModifier = if (useArtBackdrop) {
        rootModifier.cornerRadius(cornerRadius)
    } else {
        rootModifier
            .background(ColorProvider(colors.surface.copy(alpha = surfaceAlpha)))
            .cornerRadius(cornerRadius)
    }

    Box(modifier = surfaceModifier) {
        if (useArtBackdrop) {
            Image(
                provider = ImageProvider(widgetBackground(resolved)),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize().cornerRadius(cornerRadius)
            )
            // Smooth veil instead of a hard cut at 90%: at the default 95%
            // opacity the author's `glass_bg` renders untouched (veil == 0) and
            // only a deliberate reduction of the slider dims it.
            val veil = ((0.95f - opacity) / 0.95f * 0.85f).coerceIn(0f, 0.85f)
            if (veil > 0.02f) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(colors.background.copy(alpha = veil)))
                        .cornerRadius(cornerRadius)
                ) {}
            }
        }

        // Empty state: one message and one action, never a skeleton of dashes.
        if (status == null) {
            Box(
                modifier = GlanceModifier.fillMaxSize().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = strings.watomsNoDataPrompt,
                        style = TextStyle(
                            color = ColorProvider(colors.secondary),
                            fontSize = scaledSp(11f, config.fontScale, colors).sp,
                        ),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    InlineRefreshButton(colors = colors, fontScale = config.fontScale, strings = strings)
                }
            }
            return@Box
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(
                    start = horizontalPadding.dp,
                    end = horizontalPadding.dp,
                    top = verticalPadding.dp,
                    bottom = verticalPadding.dp,
                ),
            verticalAlignment = Alignment.Top
        ) {
            val headerEnabled = config.isEnabled(WidgetBlockId.HEADER)
            val enabledBlocks = visibleBlocks(config, mode, zcodeEnabled = BuildConfig.ZCODE_ENABLED)
            val inlineHost = enabledBlocks.firstOrNull()?.id ?: config.firstEnabled()?.id
            val stale = isStale(status)
            // Classic 4×2 has no CONTEXT block; the author kept its compact
            // reading on the ZCode line instead of leaving the row half empty.
            val trailingContext = status.contextSummary
                ?.takeIf { colors.isClassic && !config.isEnabled(WidgetBlockId.CONTEXT) }

            enabledBlocks.forEachIndexed { index, block ->
                val isLast = index == enabledBlocks.lastIndex
                if (index > 0) {
                    // Anchor the closing ZCode row to the bottom edge: without
                    // the weight spacer it floated between the 2nd and 3rd grid
                    // rows of a 4×3 cell with an empty third below it.
                    if (isLast && block.id == WidgetBlockId.ZCODE) {
                        Spacer(GlanceModifier.defaultWeight())
                    }
                    Spacer(GlanceModifier.height(gap.dp))
                }
                // Presets without a header (context_compact / zcode_only) host the
                // single inline ⟳ in their first enabled block instead.
                val showInlineRefresh = !headerEnabled && block.id == inlineHost
                when (block.id) {
                    WidgetBlockId.HEADER -> HeaderFreshnessAtom(
                        status = status,
                        config = config,
                        colors = colors,
                        compact = isCompactHeight,
                        stale = stale,
                        strings = strings,
                    )
                    WidgetBlockId.CONTEXT -> ContextBarAtom(
                        ctx = status.contextSummary,
                        config = config,
                        colors = colors,
                        showInlineRefresh = showInlineRefresh,
                        barWidthDp = contentWidthDp,
                        strings = strings,
                    )
                    WidgetBlockId.ZCODE -> ZCodeAtom(
                        zcode = status.zcode,
                        config = config,
                        colors = colors,
                        showInlineRefresh = showInlineRefresh,
                        contentWidthDp = contentWidthDp,
                        trailingContext = trailingContext,
                        strings = strings,
                    )
                    WidgetBlockId.QUOTAS -> {
                        if (showInlineRefresh) {
                            Row(
                                modifier = GlanceModifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = GlanceModifier.defaultWeight()) {
                                    QuotasAtom(
                                        status = status,
                                        config = config,
                                        colors = colors,
                                        isCompactHeight = isCompactHeight || isNarrow,
                                        contentWidthDp = contentWidthDp - 56f,
                                        strings = strings,
                                    )
                                }
                                InlineRefreshButton(
                                    colors = colors,
                                    fontScale = config.fontScale,
                                    strings = strings,
                                )
                            }
                        } else {
                            QuotasAtom(
                                status = status,
                                config = config,
                                colors = colors,
                                isCompactHeight = isCompactHeight || isNarrow,
                                contentWidthDp = contentWidthDp,
                                strings = strings,
                            )
                        }
                    }
                }
            }

            // Every block disabled: keep the widget actionable instead of empty.
            if (enabledBlocks.isEmpty()) {
                InlineRefreshButton(colors = colors, fontScale = config.fontScale, strings = strings)
            }

            // A failing collector is a state, not a silent blank card.
            status.antigravityError?.takeIf { it.isNotBlank() }?.let { error ->
                Spacer(GlanceModifier.height(gap.dp))
                Text(
                    text = fit(strings.fill(strings.watomsCollectError, "error" to error), 44),
                    style = TextStyle(
                        color = ColorProvider(colors.amber),
                        fontSize = scaledSp(9f, config.fontScale, colors).sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Blocks that survive the current height bucket.
 *
 * Compact widgets used to be clipped by the launcher; now the tail of the list
 * is dropped by priority so what remains is laid out at full size.
 */
internal fun visibleBlocks(
    config: WidgetConfig,
    mode: WidgetLayoutMode,
    zcodeEnabled: Boolean = true,
): List<WidgetBlockConfig> {
    // The `context` flavour has no ZCode data path: drop the block before the
    // height-bucket limit so it never consumes a slot or renders the atom.
    val enabled = config.blocks
        .filter { it.enabled }
        .filter { zcodeEnabled || it.id != WidgetBlockId.ZCODE }
    val limit = when (mode) {
        WidgetLayoutMode.COMPACT -> 2
        WidgetLayoutMode.STANDARD -> 3
        WidgetLayoutMode.EXPANDED -> enabled.size
    }
    return enabled.take(limit.coerceAtLeast(1))
}

internal fun isStale(status: AgStatus, nowMs: Long = System.currentTimeMillis()): Boolean {
    val instant = parseInstant(status.pcUpdatedAt ?: status.updatedAt) ?: return false
    val minutes = Duration.between(instant, Instant.ofEpochMilli(nowMs)).toMinutes()
    return minutes > STALE_AFTER_MINUTES
}
