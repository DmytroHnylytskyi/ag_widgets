package dev.agwidgets.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agwidgets.data.AgStatus
import dev.agwidgets.data.QuotaBucket
import dev.agwidgets.ui.formatCountdownShort
import dev.agwidgets.ui.i18n.LocalStrings
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.ui.updatedLabel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dashboard block 2 — Antigravity quotas.
 *
 * Semantics: this card shows what is **left** (bar shrinks as the quota is
 * spent), the opposite of the Context card. Gemini bars use `glow`, Claude/GPT
 * bars use `violet`, and warning tones appear only when the remainder drops
 * below 15%. Modern themes render four rows; `Classic` keeps the author's
 * orbital rings 1:1 (with the three technical fixes).
 */
@Composable
internal fun HeroCard(status: AgStatus?, nowMs: Long) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    val buckets = status?.buckets.orEmpty()
    // 3p-* belongs to CLAUDE/GPT only; never duplicate it in the GEMINI slots.
    val geminiBuckets = buckets.filterNot { it.id.startsWith("3p-") }
    val fiveHour = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "5h" }
        ?: geminiBuckets.firstOrNull { it.kind == "5h" }
    val weekly = geminiBuckets.firstOrNull { it.id.startsWith("gemini-") && it.kind == "weekly" }
        ?: geminiBuckets.firstOrNull { it.kind == "weekly" }
    val thirdBuckets = buckets.filter { it.id.startsWith("3p-") }
    val thirdFiveHour = thirdBuckets.firstOrNull { it.kind == "5h" }
    val thirdWeekly = thirdBuckets.firstOrNull { it.kind == "weekly" }
    val updated = status?.let { updatedLabel(it.pcUpdatedAt ?: it.updatedAt, nowMs, s) } ?: "—"

    GlassCard {
        if (colors.isClassic) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.serialLimits,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 1.3.sp,
                    color = colors.muted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = updated,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    lineHeight = 12.sp,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
            Text(
                text = s.labelLimits,
                style = AgType.title,
                color = colors.text,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuotaRing(s.dashLimitsClassicGemini5h, fiveHour, nowMs, accent = colors.glow, modifier = Modifier.weight(1f))
                QuotaRing(s.dashLimitsClassicGeminiWeekly, weekly, nowMs, accent = colors.glow, modifier = Modifier.weight(1f))
            }
            if (thirdFiveHour != null || thirdWeekly != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuotaRing(
                        s.dashLimitsClassicGpt5h,
                        thirdFiveHour,
                        nowMs,
                        accent = colors.pink,
                        modifier = Modifier.weight(1f),
                    )
                    QuotaRing(
                        s.dashLimitsClassicGptWeekly,
                        thirdWeekly,
                        nowMs,
                        accent = colors.pink,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.dashLimitsRemainingTitle,
                        style = AgType.section,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    status?.planTier?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = AgType.caption,
                            color = colors.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = updated,
                    style = AgType.caption,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
            QuotaRow(s.dashLimitsGemini5h, fiveHour, nowMs, accent = colors.glow)
            QuotaRow(s.dashLimitsGeminiWeekly, weekly, nowMs, accent = colors.glow)
            QuotaRow(s.dashLimitsThird5h, thirdFiveHour, nowMs, accent = colors.violet)
            QuotaRow(s.dashLimitsThirdWeekly, thirdWeekly, nowMs, accent = colors.violet)
        }
    }
}

/** One modern quota line: label + reset time on the left, big % on the right, bar below. */
@Composable
private fun QuotaRow(label: String, bucket: QuotaBucket?, nowMs: Long, accent: Color) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    // A missing or non-finite fraction must render as "—", never as 0% or NaN.
    val rawFraction = bucket?.remainingFraction
    val known = rawFraction != null && rawFraction.isFinite()
    val target = rawFraction?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat()
    // Calm brand accent while healthy; warn/risk only at the very end of the quota.
    val color = when {
        target == null -> colors.muted
        target < 0.08f -> colors.rose
        target < 0.15f -> colors.amber
        else -> accent
    }
    val reset = formatCountdownShort(bucket?.resetTime, nowMs, s)?.takeIf { it != "—" }

    Column(verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = AgType.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // No reset scheduled => no lone "—" line at all.
                if (reset != null) {
                    Text(
                        text = s.fill(s.dashLimitsResetIn, "time" to reset),
                        style = AgType.caption,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(AgDim.ControlGap))
            Text(
                text = if (known && target != null) "${(target * 100).roundToInt()}%" else s.statusNoDataLower,
                style = AgType.title,
                color = if (known) colors.text else colors.muted,
                maxLines = 1,
            )
        }
        QuotaBar(fraction = target, color = color)
    }
}

@Composable
private fun QuotaBar(fraction: Float?, color: Color) {
    val shape = RoundedCornerShape(AgDim.BarRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(AgTheme.colors.track),
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(shape)
                    .background(color),
            )
        }
    }
}

/** Original Classic ring with the dashed orbit, reticle and sweeping arc. */
@Composable
private fun QuotaRing(
    label: String,
    bucket: QuotaBucket?,
    nowMs: Long,
    accent: Color = AgTheme.colors.glow,
    modifier: Modifier = Modifier,
) {
    // A missing or non-finite fraction must render as "—", never as 0% or NaN.
    val fraction = bucket?.remainingFraction?.takeIf { it.isFinite() }
    val known = fraction != null
    val target = fraction?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 700),
        label = "quota",
    )
    val color = when {
        !known -> AgTheme.colors.muted
        target < 0.2f -> AgTheme.colors.rose
        target < 0.45f -> AgTheme.colors.amber
        else -> accent
    }
    val ag = AgTheme.colors
    // Classic Light needs `secondary` to clear AA on a white card (S2-020).
    val resetColor = if (ag.isDark) ag.muted else ag.secondary

    Box(
        modifier = modifier.padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(108.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 2.5.dp.toPx()
                val radius = size.minDimension * 0.36f
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 6.dp.toPx()), 0f)
                drawCircle(
                    color = ag.border,
                    radius = size.minDimension * 0.47f,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                )
                drawLine(
                    color = ag.grid,
                    start = Offset(center.x - radius - 8.dp.toPx(), center.y),
                    end = Offset(center.x + radius + 8.dp.toPx(), center.y),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = ag.grid,
                    start = Offset(center.x, center.y - radius - 8.dp.toPx()),
                    end = Offset(center.x, center.y + radius + 8.dp.toPx()),
                    strokeWidth = 1f,
                )
                drawCircle(color = ag.track, radius = radius, style = Stroke(width = stroke))
                if (known && animated > 0.004f) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    val angle = (-90.0 + 360.0 * animated) * PI / 180.0
                    drawCircle(
                        color = color,
                        radius = 3.dp.toPx(),
                        center = Offset(
                            center.x + radius * cos(angle).toFloat(),
                            center.y + radius * sin(angle).toFloat(),
                        ),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (known) "${(target * 100).roundToInt()}%" else "—",
                    color = if (known) ag.text else ag.muted,
                    style = AgType.title,
                    maxLines = 1,
                )
                Text(
                    // Inside the ring the label has a hard width budget: the
                    // short form never crosses the arc (S2-021).
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 0.4.sp,
                    color = ag.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.widthIn(max = 70.dp),
                )
                // A full quota has no running timer yet: hide the line instead
                // of printing "нет данных" inside a 100% ring.
                formatCountdownShort(bucket?.resetTime, nowMs, LocalStrings.current)?.takeIf { it != "—" }?.let { reset ->
                    Text(
                        text = reset,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = resetColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
