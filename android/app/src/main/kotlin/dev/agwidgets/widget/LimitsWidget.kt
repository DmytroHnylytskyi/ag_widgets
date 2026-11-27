package dev.agwidgets.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.agwidgets.BuildConfig
import dev.agwidgets.MainActivity
import dev.agwidgets.R
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.ContextSummary
import dev.agwidgets.data.QuotaBucket
import dev.agwidgets.data.StatusStore
import dev.agwidgets.data.ZCodeStatus
import dev.agwidgets.ui.formatCountdownShort
import dev.agwidgets.ui.formatK
import dev.agwidgets.ui.formatUnits
import dev.agwidgets.ui.formatWindow
import dev.agwidgets.ui.parseInstant
import dev.agwidgets.ui.prettyModel
import dev.agwidgets.ui.theme.AgColors
import dev.agwidgets.ui.theme.ResolvedAgTheme
import dev.agwidgets.ui.theme.agColorsFor
import dev.agwidgets.ui.theme.resolveAgTheme
import dev.agwidgets.work.performRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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

/** Widget height buckets used to adapt spacing and extra sections. */
internal enum class WidgetLayoutMode { COMPACT, STANDARD, EXPANDED }

/**
 * Height in dp below 165 is COMPACT, 165..215 inclusive is STANDARD and
 * anything above 215 is EXPANDED. Locked by `WidgetRefreshTest`.
 */
internal fun widgetLayoutMode(heightDp: Float): WidgetLayoutMode = when {
    heightDp < 165f -> WidgetLayoutMode.COMPACT
    heightDp <= 215f -> WidgetLayoutMode.STANDARD
    else -> WidgetLayoutMode.EXPANDED
}

class LimitsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val status = StatusStore.getMergedStatus(context)
        val systemDark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val resolved = resolveAgTheme(StatusStore.getThemeMode(context), systemDark)
        provideContent {
            WidgetContent(status = status, resolved = resolved)
        }
    }
}

/**
 * Background drawable for a resolved theme: calm flat surfaces for the modern
 * themes, the author's glass layers for `glass` and `classic`.
 */
private fun widgetBackground(resolved: ResolvedAgTheme): Int = when (resolved.id) {
    "graphite" -> R.drawable.widget_bg_graphite
    "paper" -> R.drawable.widget_bg_paper
    "oled" -> R.drawable.widget_bg_oled
    "glass_dark", "classic_dark" -> R.drawable.glass_bg
    else -> R.drawable.glass_bg_light
}

@Composable
private fun WidgetContent(status: AgStatus?, resolved: ResolvedAgTheme) {
    val size = LocalSize.current
    val mode = widgetLayoutMode(size.height.value)
    // Colours are always fixed ARGB values: `system` was already resolved against
    // the current night mode, so every palette is concrete by this point.
    val colors = agColorsFor(resolved)
    val fg = ColorProvider(colors.text)
    val muted = ColorProvider(colors.secondary)
    val subtle = ColorProvider(colors.muted)
    val accent = ColorProvider(colors.glow)
    val thirdAccent = ColorProvider(colors.pink)
    val divider = ColorProvider(colors.track)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Image(
            provider = ImageProvider(widgetBackground(resolved)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize()
        )

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(start = 15.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Header
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "●",
                    style = TextStyle(color = accent, fontSize = 9.sp)
                )
                Spacer(modifier = GlanceModifier.width(5.dp))
                // The author's `AGW / 01` code stays exclusively in Classic.
                if (resolved.isClassic) {
                    Text(
                        text = "AGW / 01",
                        style = TextStyle(
                            color = accent,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                    Spacer(modifier = GlanceModifier.width(7.dp))
                }
                Text(
                    text = "Antigravity" + (status?.planTier?.let { " · $it" } ?: ""),
                    style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
                RefreshIcon(tint = subtle, background = ColorProvider(colors.track))
                Spacer(modifier = GlanceModifier.width(5.dp))
                Text(
                    text = formatTimeShort(status?.pcUpdatedAt ?: status?.updatedAt) ?: "—",
                    style = TextStyle(
                        color = subtle,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1
                )
            }

            Spacer(modifier = GlanceModifier.height(7.dp))
            Spacer(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(divider)
            )
            Spacer(modifier = GlanceModifier.height(8.dp))

            if (status == null) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Нет данных · откройте приложение",
                        style = TextStyle(color = muted, fontSize = 12.sp),
                        maxLines = 1
                    )
                }
            } else {
                // Gemini slots never use 3p- buckets; CLAUDE/GPT slots use only 3p- buckets.
                val geminiBuckets = status.buckets.filter { !it.id.startsWith("3p-") }
                val fiveHour = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "5h" }
                    ?: geminiBuckets.firstOrNull { it.kind == "5h" }
                val weekly = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "weekly" }
                    ?: geminiBuckets.firstOrNull { it.kind == "weekly" }
                val thirdFiveHour = status.buckets.firstOrNull { it.id.startsWith("3p-") && it.kind == "5h" }
                val thirdWeekly = status.buckets.firstOrNull { it.id.startsWith("3p-") && it.kind == "weekly" }

                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    // Classic keeps the author's campaign vocabulary (`GEMINI 5H` / `WK`);
                    // the modern palettes use the app-scale wording.
                    val geminiFiveHourLabel = if (resolved.isClassic) "GEMINI 5H" else "Gemini 5 ч"
                    val geminiWeeklyLabel = if (resolved.isClassic) "GEMINI WK" else "Gemini 7 дн"
                    val thirdFiveHourLabel = if (resolved.isClassic) "CLAUDE/GPT 5H" else "Claude / GPT 5 ч"
                    val thirdWeeklyLabel = if (resolved.isClassic) "CLAUDE/GPT WK" else "Claude / GPT 7 дн"
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        QuotaCell(
                            label = geminiFiveHourLabel,
                            bucket = fiveHour,
                            colors = colors,
                            muted = muted,
                            accent = accent,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.width(7.dp))
                        QuotaCell(
                            label = geminiWeeklyLabel,
                            bucket = weekly,
                            colors = colors,
                            muted = muted,
                            accent = accent,
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        QuotaCell(
                            label = thirdFiveHourLabel,
                            bucket = thirdFiveHour,
                            colors = colors,
                            muted = muted,
                            accent = thirdAccent,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.width(7.dp))
                        QuotaCell(
                            label = thirdWeeklyLabel,
                            bucket = thirdWeekly,
                            colors = colors,
                            muted = muted,
                            accent = thirdAccent,
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                }

                // No weight-based filler: a flexible spacer turns into a visible
                // hole as soon as the host grants more height than we need.
                when (mode) {
                    WidgetLayoutMode.COMPACT ->
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    WidgetLayoutMode.STANDARD ->
                        Spacer(modifier = GlanceModifier.height(10.dp))
                    WidgetLayoutMode.EXPANDED -> {
                        val contextSummary = status.contextSummary
                        if (contextSummary?.tokens != null) {
                            ContextSection(
                                ctx = contextSummary,
                                fg = fg,
                                muted = muted,
                                subtle = subtle,
                                colors = colors
                            )
                        } else {
                            Spacer(modifier = GlanceModifier.height(10.dp))
                        }
                    }
                }

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (BuildConfig.ZCODE_ENABLED) {
                        val (zText, zBg) = zcodeChip(status.zcode, colors)
                        WidgetChip(zText, zBg, fg)
                    }

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    contextChip(status.contextSummary, colors)?.let { (text, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = text,
                                style = TextStyle(color = muted, fontSize = 9.5.sp),
                                maxLines = 1
                            )
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            Text(
                                text = "●",
                                style = TextStyle(color = color, fontSize = 9.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshIcon(tint: ColorProvider, background: ColorProvider) {
    // 42x40 tap target around a small visible pill: the pill alone is too small
    // to hit reliably, and the clickable modifier must live on the outer box.
    Box(
        modifier = GlanceModifier
            .clickable(actionRunCallback<RefreshActionCallback>())
            .width(42.dp)
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = GlanceModifier
                .width(26.dp)
                .height(24.dp)
                .background(background)
                .cornerRadius(9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⟳",
                style = TextStyle(
                    color = tint,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}

/** Expand-mode block: active chat model, token usage and its colour-coded bar. */
@Composable
private fun ContextSection(
    ctx: ContextSummary,
    fg: ColorProvider,
    muted: ColorProvider,
    subtle: ColorProvider,
    colors: AgColors
) {
    val tokens = ctx.tokens ?: return
    val window = ctx.window
    // NaN/Infinity must never reach the bitmap renderer or the percent label.
    val pct = (ctx.pct ?: window?.takeIf { it > 0 }?.let { tokens * 100.0 / it })
        ?.takeIf { it.isFinite() }
    val fraction = (pct?.div(100.0) ?: 0.0).toFloat().coerceIn(0f, 1f)
    val barColor: Color = when (ctx.level) {
        "red" -> colors.rose
        "yellow" -> colors.amber
        "unknown" -> colors.muted
        "green" -> colors.green
        else -> colors.glow
    }
    val trackArgb = colors.track.toArgb()

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONTEXT",
                style = TextStyle(
                    color = muted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = prettyModel(ctx.model.orEmpty().ifBlank { "—" }),
                style = TextStyle(color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = buildString {
                    append(formatK(tokens))
                    append(" / ")
                    append(window?.let { formatWindow(it) } ?: "—")
                    pct?.let { append(" (").append(it.roundToInt()).append("%)") }
                },
                style = TextStyle(
                    color = subtle,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 1
            )
        }
        Spacer(modifier = GlanceModifier.height(4.dp))
        Image(
            provider = ImageProvider(
                BarRenderer.renderBar(fraction, barColor.toArgb(), trackArgb)
            ),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height(3.dp)
        )
    }
}

@Composable
private fun QuotaCell(
    label: String,
    bucket: QuotaBucket?,
    colors: AgColors,
    muted: ColorProvider,
    accent: ColorProvider,
    modifier: GlanceModifier = GlanceModifier
) {
    val frac = bucket?.remainingFraction?.toFloat()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0f, 1f)
    val pct = frac?.let { (it * 100).roundToInt() }
    // Warning tones only at the very end of a window; healthy buckets keep the group accent.
    val color: ColorProvider = when {
        bucket == null -> muted
        frac != null && frac < 0.08f -> ColorProvider(colors.rose)
        frac != null && frac < 0.15f -> ColorProvider(colors.amber)
        else -> accent
    }
    // Bitmap bars need plain ARGB values, so they use the palette entries directly.
    val barColor: Color = when {
        bucket == null -> colors.muted
        frac != null && frac < 0.08f -> colors.rose
        frac != null && frac < 0.15f -> colors.amber
        else -> colors.glow
    }
    val trackArgb = colors.track.toArgb()

    Column(modifier = modifier) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = TextStyle(
                    color = muted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (pct == null) "—" else "$pct%",
                style = TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
        Spacer(modifier = GlanceModifier.height(2.dp))
        Image(
            provider = ImageProvider(BarRenderer.renderBar(frac ?: 0f, barColor.toArgb(), trackArgb)),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height(3.dp)
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = formatCountdownShort(bucket?.resetTime, System.currentTimeMillis()) ?: "—",
            style = TextStyle(
                // `muted` instead of `subtle`: the countdown sits on the surface and
                // needs the same contrast as the label, not the dimmest tone.
                color = muted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun WidgetChip(text: String, background: ColorProvider, color: ColorProvider) {
    Box(
        modifier = GlanceModifier
            .background(background)
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(color = color, fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
            maxLines = 1
        )
    }
}

/**
 * Chip backgrounds keep the original Classic alphas; modern palettes reuse their
 * semantic accent colours with the same translucency so contrast stays stable.
 */
private fun zcodeChip(z: ZCodeStatus?, colors: AgColors): Pair<String, ColorProvider> {
    val chipAlpha = if (colors.isDark) 0.27f else 0.14f
    val neutral = ColorProvider(colors.track)
    val error = ColorProvider(colors.rose.copy(alpha = chipAlpha))
    val warn = ColorProvider(colors.amber.copy(alpha = chipAlpha))
    val coupon = ColorProvider(colors.glow.copy(alpha = if (colors.isDark) 0.40f else 0.12f))
    val glm = ColorProvider(colors.violet.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
    if (z == null) return "ZCode: нет данных" to neutral
    // Local copies keep the nullable checks below readable and smart-cast safe.
    val remaining = z.remainingUnits
    val total = z.totalUnits
    return when {
        z.isCouponUnknown -> {
            val text = when (z.errorKind) {
                "network" -> "ZCode: нет связи"
                "auth_expired" -> "ZCode: нужен вход"
                "rate_limited" -> "ZCode: лимит 429"
                "server_error", "api_changed", "parse_failed" -> "ZCode: ошибка проверки"
                else -> "ZCode: неизвестно"
            }
            val bg = when (z.errorKind) {
                "network", "rate_limited", "server_error", "api_changed", "parse_failed" -> error
                else -> warn
            }
            text to bg
        }
        z.networkError -> "ZCode: нет связи" to error
        z.method == "api" && z.authOk == false -> "ZCode: нужен вход" to warn
        z.method == "disabled" -> "ZCode не подключён" to neutral
        z.isCouponVisible -> "Ваучер доступен!" to coupon
        remaining != null && (remaining > 0 || (total != null && total > 0)) ->
            "${z.planName.ifBlank { "ZCode" }} ${formatUnits(remaining)}" to glm
        !z.running && z.method != "api" -> "ZCode не запущен" to neutral
        else -> "ZCode: нет ваучера" to neutral
    }
}

private fun contextChip(ctx: ContextSummary?, colors: AgColors): Pair<String, ColorProvider>? {
    val tokens = ctx?.tokens ?: return null
    // "unknown" must stay neutral grey instead of falling through to green.
    val color = ColorProvider(
        when (ctx.level) {
            "red" -> colors.rose
            "yellow" -> colors.amber
            "green" -> colors.green
            else -> colors.muted
        }
    )
    val pct = ctx.pct?.takeIf { it.isFinite() }?.let { " ${it.roundToInt()}%" } ?: ""
    val prefix = if (ctx.active) "ЧАТ" else "ПК"
    return "$prefix ${formatK(tokens)}$pct" to color
}

private fun formatTimeShort(iso: String?): String? {
    val instant = parseInstant(iso) ?: return null
    return DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
