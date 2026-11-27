package dev.agwidgets.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

@Composable
internal fun ChannelCard(
    topic: String,
    onTopicChange: (String) -> Unit,
    isRefreshing: Boolean,
    statusMessage: String,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = AgTheme.colors
    GlassCard {
        SectionHeader(
            serial = "02 / CHANNEL",
            title = "Antigravity",
            chipText = if (topic.isBlank()) "Не настроен" else "ntfy",
            chipColor = if (topic.isBlank()) colors.muted else colors.glow,
        )
        Text(
            text = "Квоты 5 ч и недели, контекст ПК и статус ZCode приходят с компьютера " +
                "через приватный ntfy-канал.",
            style = AgType.body,
            color = colors.secondary,
        )
        OutlinedTextField(
            value = topic,
            onValueChange = onTopicChange,
            singleLine = true,
            label = { Text("Тема ntfy", style = AgType.caption) },
            textStyle = AgType.body,
            shape = RoundedCornerShape(AgDim.ControlRadius),
            colors = glassFieldColors(colors.glow),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
        ) {
            // Field-local save is a quiet action; "Обновить" is tonal so the one
            // filled primary on the screen stays "Войти в Z.ai".
            AgGhostButton(text = "Сохранить", onClick = onSave)
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                shape = RoundedCornerShape(AgDim.ControlRadius),
                modifier = Modifier.weight(1f).height(AgDim.MinTouch),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.glow.copy(alpha = 0.16f),
                    contentColor = colors.accentInk,
                ),
            ) {
                Text(
                    text = if (isRefreshing) "Обновляю…" else "Обновить",
                    style = AgType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (statusMessage.isNotBlank()) {
            Text(
                text = statusMessage,
                style = AgType.body,
                color = colors.secondary,
            )
        }
    }
}
