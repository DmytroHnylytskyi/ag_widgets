package dev.agwidgets.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agwidgets.data.ContextSummary
import dev.agwidgets.ui.contextLevel
import dev.agwidgets.ui.formatK
import dev.agwidgets.ui.formatWindow
import dev.agwidgets.ui.prettyModel
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.ui.updatedLabel
import kotlin.math.roundToInt

/**
 * Dashboard block 1 — context window pressure.
 *
 * The bar grows from empty to full as the window fills ("занято"), which is the
 * opposite of the Limits card ("остаток"); both cards say so in words. The
 * thresholds wrap instead of colliding at font_scale 1.3.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextCard(context: ContextSummary?, updatedAt: String?, nowMs: Long) {
    val colors = AgTheme.colors
    val tokens = context?.tokens
    val window = context?.window
    val pct = context?.pct?.takeIf { it.isFinite() }
        ?: if (tokens != null && window != null && window > 0) {
            tokens.toDouble() / window * 100.0
        } else {
            null
        }
    val level = contextLevel(pct, context?.level, context?.yellowPct, context?.redPct)
    val levelColor = when (level) {
        "red" -> colors.rose
        "yellow" -> colors.amber
        "green" -> colors.green
        else -> colors.muted
    }
    val chipText = when (level) {
        "red" -> "Пора /compact"
        "yellow" -> "Внимание"
        "green" -> "Ок"
        else -> "Ожидание"
    }
    val yellowPct = context?.yellowPct?.takeIf { it.isFinite() } ?: 40.0
    val redPct = context?.redPct?.takeIf { it.isFinite() } ?: 65.0
    val warnTokens = context?.warnTokens
    val riskTokens = context?.riskTokens

    GlassCard {
        SectionHeader(
            serial = "01 / CONTEXT",
            title = "Контекст",
            chipText = chipText,
            chipColor = levelColor,
        )

        if (tokens == null) {
            Text(
                text = if (context?.active == true) {
                    "Текущий чат · ожидание ответа"
                } else {
                    "Нет данных с ПК — настройте канал ntfy в настройках"
                },
                style = AgType.body,
                color = colors.secondary,
            )
        } else {
            Text(
                text = context.model?.let { prettyModel(it) } ?: "Текущий чат",
                style = AgType.section,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = formatK(tokens),
                    style = AgType.display,
                    color = colors.text,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                if (window != null) {
                    Spacer(Modifier.width(AgDim.ControlGap))
                    Text(
                        text = "из ${formatWindow(window)}",
                        style = AgType.body,
                        color = colors.secondary,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                if (pct != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        // "занято" vs "осталось": the two cards can no longer be
                        // read as the same gauge.
                        text = "${pct.roundToInt()}% занято",
                        style = AgType.title,
                        color = levelColor,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (pct != null) {
                ContextBar(
                    fraction = (pct / 100.0).toFloat().coerceIn(0f, 1f),
                    color = levelColor,
                )
            }
            if (warnTokens != null && riskTokens != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Надёжно до ${formatK(warnTokens)} (${yellowPct.roundToInt()}%)",
                        style = AgType.caption,
                        color = colors.muted,
                        maxLines = 2,
                    )
                    Text(
                        text = "Риск после ${formatK(riskTokens)} (${redPct.roundToInt()}%)",
                        style = AgType.caption,
                        color = colors.muted,
                        maxLines = 2,
                    )
                }
            }
        }

        if (updatedAt != null) {
            val label = updatedLabel(updatedAt, nowMs)
            if (label != "—") {
                Text(
                    text = label,
                    style = AgType.caption,
                    color = colors.muted,
                )
            }
        }
    }
}

@Composable
private fun ContextBar(fraction: Float, color: Color) {
    val shape = RoundedCornerShape(AgDim.BarRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(AgTheme.colors.track),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(shape)
                    .background(color),
            )
        }
    }
}
