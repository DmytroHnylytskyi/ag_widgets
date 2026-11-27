package dev.agwidgets.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.agwidgets.BuildConfig
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

@Composable
internal fun SurfaceCard(onPinWidget: () -> Unit) {
    val colors = AgTheme.colors
    GlassCard {
        SectionHeader(
            serial = "04 / SURFACE",
            title = "Виджет",
            chipText = "Не добавлен",
            chipColor = colors.muted,
        )
        Text(
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
