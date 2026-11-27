package dev.agwidgets.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

@Composable
internal fun DiagnosticsCard(
    rawJson: String?,
    lastError: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChangeMode: (() -> Unit)? = null,
) {
    val colors = AgTheme.colors
    val classic = colors.isClassic
    GlassCard {
        SectionHeader(
            serial = "05 / SYSTEM",
            title = "Диагностика",
            trailing = {
                AgGhostButton(
                    text = if (expanded) "Скрыть" else "Показать JSON",
                    onClick = onToggle,
                )
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
                // A raw dump can be hundreds of lines: it gets its own bounded,
                // scrollable pane instead of stretching the whole page.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(AgDim.ControlRadius))
                        .background(colors.badge)
                        .border(1.dp, colors.border, RoundedCornerShape(AgDim.ControlRadius))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(AgDim.ControlGap),
                ) {
                    Text(
                        text = rawJson ?: "Данных пока нет — нажмите «Обновить»",
                        style = AgType.caption.copy(fontFamily = FontFamily.Monospace),
                        color = if (classic) colors.muted else colors.secondary,
                        softWrap = false,
                        maxLines = 400,
                    )
                }
                lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = "Последняя ошибка: $error",
                        style = AgType.body,
                        color = colors.amber,
                    )
                }
            }
        }
        if (onChangeMode != null) {
            // v1 invariant: the mode can always be reset from Diagnostika.
            AgGhostButton(
                text = if (classic) "СМЕНИТЬ РЕЖИМ" else "Сменить режим",
                onClick = onChangeMode,
                contentColor = colors.secondary,
            )
        }
    }
}
