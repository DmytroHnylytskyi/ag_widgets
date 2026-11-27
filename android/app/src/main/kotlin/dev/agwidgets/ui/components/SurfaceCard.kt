package dev.agwidgets.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agwidgets.BuildConfig
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

/** One widget placed on the launcher, as shown in the Settings surface list. */
internal data class InstalledWidgetInfo(
    val appWidgetId: Int,
    val presetLabel: String,
    val themeLabel: String,
)

@Composable
internal fun SurfaceCard(
    installedWidgets: List<InstalledWidgetInfo> = emptyList(),
    onPinWidget: () -> Unit,
    onConfigureWidget: (Int) -> Unit = {},
) {
    val colors = AgTheme.colors
    GlassCard {
        SectionHeader(
            serial = "04 / SURFACE",
            title = if (installedWidgets.isEmpty()) {
                "Виджет"
            } else {
                "Виджет · ${installedWidgets.size}"
            },
            chipText = if (installedWidgets.isEmpty()) "Не добавлен" else "На экране",
            chipColor = if (installedWidgets.isEmpty()) colors.muted else colors.violet,
        )
        if (installedWidgets.isEmpty()) {
            Text(
                // The `context` flavour has no ZCode data path, so its copy must
                // not promise a ZCode status the widget can never show.
                text = if (BuildConfig.ZCODE_ENABLED) {
                    "Шкалы 5 ч и недели, обратный отсчёт, статус ZCode и контекст ПК. " +
                        "Уведомления о сбросе приходят без компьютера."
                } else {
                    "Шкалы 5 ч и недели, обратный отсчёт и контекст ПК. " +
                        "Уведомления о сбросе приходят без компьютера."
                },
                style = AgType.body,
                color = colors.secondary,
            )
        } else {
            installedWidgets.forEach { widget ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "#${widget.appWidgetId} · ${widget.presetLabel}",
                            style = AgType.body,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = widget.themeLabel,
                            style = AgType.caption,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { onConfigureWidget(widget.appWidgetId) },
                        shape = RoundedCornerShape(AgDim.ControlRadius),
                        modifier = Modifier.height(AgDim.MinTouch),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                    ) {
                        Text(text = "Настроить", style = AgType.body)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onPinWidget,
            modifier = Modifier
                .fillMaxWidth()
                .height(AgDim.MinTouch),
            shape = RoundedCornerShape(AgDim.ControlRadius),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
        ) {
            Text(text = "Добавить виджет на экран", style = AgType.body)
        }
    }
}
