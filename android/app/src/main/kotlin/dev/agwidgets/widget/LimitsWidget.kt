package dev.agwidgets.widget

import android.content.Context
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
import androidx.glance.color.ColorProvider as dayNightColorProvider
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
 * anything above 215 is EXPANDED.
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
        val themeMode = StatusStore.getThemeMode(context)
        provideContent {
            WidgetContent(
                status = status,
                themeMode = themeMode,
                dark = StatusStore.isDarkTheme(context)
            )
        }
    }
}

/**
 * Resolves a color for the configured theme mode. For "system" both day and night colors are
 * handed to Glance so the launcher can repaint the widget when the OS theme changes.
 */
private fun themed(light: Color, darkColor: Color, mode: String): ColorProvider = when (mode) {
    "dark" -> ColorProvider(darkColor)
    "light" -> ColorProvider(light)
    else -> dayNightColorProvider(day = light, night = darkColor)
}

@Composable
private fun WidgetContent(status: AgStatus?, themeMode: String, dark: Boolean) {
    val size = LocalSize.current
    val mode = widgetLayoutMode(size.height.value)
    val fg = themed(Color(0xFF09090B), Color.White, themeMode)
    val muted = themed(Color(0xFF52525B), Color(0xB8FFFFFF), themeMode)
    val subtle = themed(Color(0xFF6B6B76), Color(0x8AFFFFFF), themeMode)
    val accent = themed(Color(0xFF7C3AED), Color(0xFFFFE9B8), themeMode)
    val accentArgb = if (dark) Color(0xFFFFE9B8) else Color(0xFF7C3AED)
    val thirdAccent = themed(Color(0xFFBE185D), Color(0xFFF472B6), themeMode)
    val thirdAccentArgb = if (dark) Color(0xFFF472B6) else Color(0xFFBE185D)
    val divider = themed(Color(0x1F09090B), Color(0x1FFFFFFF), themeMode)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Image(
            provider = ImageProvider(if (dark) R.drawable.glass_bg else R.drawable.glass_bg_light),
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
                Text(
                    text = "Antigravity" + (status?.planTier?.let { " · $it" } ?: ""),
                    style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
                RefreshIcon(themeMode = themeMode, tint = subtle)
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
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        QuotaCell(
                            label = "GEMINI 5H",
                            bucket = fiveHour,
                            muted = muted,
                            subtle = subtle,
                            accent = accent,
                            accentArgb = accentArgb,
                            dark = dark,
                            themeMode = themeMode,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.width(7.dp))
                        QuotaCell(
                            label = "GEMINI WK",
                            bucket = weekly,
                            muted = muted,
                            subtle = subtle,
                            accent = accent,
                            accentArgb = accentArgb,
                            dark = dark,
                            themeMode = themeMode,
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        QuotaCell(
                            label = "CLAUDE/GPT 5H",
                            bucket = thirdFiveHour,
                            muted = muted,
                            subtle = subtle,
                            accent = thirdAccent,
                            accentArgb = thirdAccentArgb,
                            dark = dark,
                            themeMode = themeMode,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.width(7.dp))
                        QuotaCell(
                            label = "CLAUDE/GPT WK",
                            bucket = thirdWeekly,
                            muted = muted,
                            subtle = subtle,
                            accent = thirdAccent,
                            accentArgb = thirdAccentArgb,
                            dark = dark,
                            themeMode = themeMode,
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
                                accentArgb = accentArgb,
                                dark = dark
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
                        val (zText, zBg) = zcodeChip(status.zcode, themeMode)
                        WidgetChip(zText, zBg, fg)
                    }

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    contextChip(status.contextSummary, themeMode)?.let { (text, color) ->
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
private fun RefreshIcon(themeMode: String, tint: ColorProvider) {
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
                .background(themed(Color(0x0F09090B), Color(0x1FFFFFFF), themeMode))
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
    accentArgb: Color,
    dark: Boolean
) {
    val tokens = ctx.tokens ?: return
    val window = ctx.window
    val pct = ctx.pct ?: window?.takeIf { it > 0 }?.let { tokens * 100.0 / it }
    val fraction = (pct?.div(100.0) ?: 0.0).toFloat().coerceIn(0f, 1f)
    val barColor: Color = when (ctx.level) {
        "red" -> if (dark) Color(0xFFFF4D6D) else Color(0xFFBE123C)
        "yellow" -> if (dark) Color(0xFFF5A623) else Color(0xFFB45309)
        "unknown" -> if (dark) Color(0xFF8F8F9A) else Color(0xFF6B6B76)
        "green" -> if (dark) Color(0xFF4EF2D2) else Color(0xFF047857)
        else -> accentArgb
    }
    val trackArgb = if (dark) Color(0x1FFFFFFF) else Color(0x1F09090B)

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
                BarRenderer.renderBar(fraction, barColor.toArgb(), trackArgb.toArgb())
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
    muted: ColorProvider,
    subtle: ColorProvider,
    accent: ColorProvider,
    accentArgb: Color,
    dark: Boolean,
    themeMode: String,
    modifier: GlanceModifier = GlanceModifier
) {
    val frac = bucket?.remainingFraction?.toFloat()?.coerceIn(0f, 1f)
    val pct = frac?.let { (it * 100).roundToInt() }
    // Text color follows the accent for a healthy bucket and switches to warning tones below it.
    val color: ColorProvider = when {
        bucket == null -> muted
        frac != null && frac < 0.2f -> themed(Color(0xFFBE123C), Color(0xFFFF4D6D), themeMode)
        frac != null && frac < 0.45f -> themed(Color(0xFFB45309), Color(0xFFF5A623), themeMode)
        else -> accent
    }
    // Bitmap bars need plain ARGB values, so they follow the resolved dark flag, not a ColorProvider.
    val barColor: Color = when {
        bucket == null -> if (dark) Color(0xFF8F8F9A) else Color(0xFF6B6B76)
        frac != null && frac < 0.2f -> if (dark) Color(0xFFFF4D6D) else Color(0xFFBE123C)
        frac != null && frac < 0.45f -> if (dark) Color(0xFFF5A623) else Color(0xFFB45309)
        else -> accentArgb
    }
    val trackArgb = if (dark) Color(0x1FFFFFFF) else Color(0x1F09090B)

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
            provider = ImageProvider(BarRenderer.renderBar(frac ?: 0f, barColor.toArgb(), trackArgb.toArgb())),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height(3.dp)
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = formatCountdownShort(bucket?.resetTime, System.currentTimeMillis()) ?: "—",
            style = TextStyle(
                color = subtle,
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

private fun zcodeChip(z: ZCodeStatus?, themeMode: String): Pair<String, ColorProvider> {
    val neutral = themed(Color(0x0F09090B), Color(0x26FFFFFF), themeMode)
    val error = themed(Color(0x24BE123C), Color(0x45FF4D6D), themeMode)
    val warn = themed(Color(0x24B45309), Color(0x45F5A623), themeMode)
    val coupon = themed(Color(0x1F7C3AED), Color(0x66FFE9B8), themeMode)
    val glm = themed(Color(0x1F7C3AED), Color(0x33B026FF), themeMode)
    return when {
        z == null -> "ZCode: нет данных" to neutral
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
        z.remainingUnits != null && (z.remainingUnits > 0 || (z.totalUnits != null && z.totalUnits > 0)) ->
            "${z.planName.ifBlank { "ZCode" }} ${formatUnits(z.remainingUnits)}" to glm
        !z.running && z.method != "api" -> "ZCode не запущен" to neutral
        else -> "ZCode: нет ваучера" to neutral
    }
}

private fun contextChip(ctx: ContextSummary?, themeMode: String): Pair<String, ColorProvider>? {
    val tokens = ctx?.tokens ?: return null
    // "unknown" must stay neutral grey instead of falling through to green.
    val color = when (ctx.level) {
        "red" -> themed(Color(0xFFBE123C), Color(0xFFFF4D6D), themeMode)
        "yellow" -> themed(Color(0xFFB45309), Color(0xFFF5A623), themeMode)
        "green" -> themed(Color(0xFF047857), Color(0xFF4EF2D2), themeMode)
        else -> themed(Color(0xFF6B6B76), Color(0xFF8F8F9A), themeMode)
    }
    val pct = ctx.pct?.let { " ${it.roundToInt()}%" } ?: ""
    val prefix = if (ctx.active) "ЧАТ" else "ПК"
    return "$prefix ${formatK(tokens)}$pct" to color
}

private fun formatTimeShort(iso: String?): String? {
    val instant = parseInstant(iso) ?: return null
    return DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
