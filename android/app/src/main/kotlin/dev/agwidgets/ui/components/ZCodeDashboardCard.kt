package dev.agwidgets.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agwidgets.data.ZCodeStatus
import dev.agwidgets.ui.formatDateShort
import dev.agwidgets.ui.formatUnits
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.ui.theme.onAccentFor
import kotlin.math.roundToInt

/**
 * Dashboard block 3 — compact ZCode status.
 *
 * Read-only: OAuth login and a link to the Settings screen, never a JWT field.
 * One status carrier (the chip) and one primary action per screen; the chip
 * never claims "no coupon" while the state is unknown.
 */
@Composable
internal fun ZCodeDashboardCard(
    zcode: ZCodeStatus?,
    isPending: Boolean,
    message: String,
    onLogin: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = AgTheme.colors
    val (chipText, chipColor) = zcodeDashboardChip(zcode)
    val remaining = zcode?.remainingUnits
    val total = zcode?.totalUnits
    val totalSafe = total?.takeIf { it > 0L }
    val planName = zcode?.planName?.takeIf { it.isNotBlank() }
    val showLogin = zcode == null ||
        zcode.method == "disabled" ||
        zcode.authOk == false ||
        (zcode.isCouponUnknown && zcode.errorKind == "auth_expired")

    GlassCard {
        SectionHeader(
            serial = "03 / ACCESS",
            title = "ZCode",
            chipText = chipText,
            chipColor = chipColor,
        )
        if (planName != null) {
            Text(
                text = planName,
                style = AgType.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Only real state changes are echoed here; "monitoring is on" is the
        // chip's job (it used to be said three times in one card).
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = AgType.body,
                color = colors.secondary,
            )
        }
        if (remaining != null) {
            Column(verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (total != null) {
                            "Осталось ${formatUnits(remaining)} из ${formatUnits(total)}"
                        } else {
                            "Осталось ${formatUnits(remaining)}"
                        },
                        style = AgType.body,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (totalSafe != null) {
                        Text(
                            text = "${(remaining.toDouble() / totalSafe * 100).roundToInt()}%",
                            style = AgType.title,
                            color = colors.green,
                            maxLines = 1,
                        )
                    }
                }
                if (totalSafe != null) {
                    ZCodeBar((remaining.toDouble() / totalSafe).toFloat().coerceIn(0f, 1f))
                }
                zcode.endsAt?.let {
                    Text(
                        text = "Действует до ${formatDateShort(it)}",
                        style = AgType.caption,
                        color = colors.muted,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLogin) {
                Button(
                    onClick = onLogin,
                    enabled = !isPending,
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    modifier = Modifier.height(AgDim.MinTouch),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.violet,
                        // `onGlow` is tuned for the cream glow, not for violet:
                        // Classic Dark measured 3.75:1 with it.
                        contentColor = onAccentFor(colors.violet),
                    ),
                ) {
                    Text(
                        text = if (isPending) "Ожидание входа…" else "Войти в Z.ai",
                        style = AgType.body,
                    )
                }
            }
            AgGhostButton(text = "Настроить", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun ZCodeBar(fraction: Float) {
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
                    .background(AgTheme.colors.glow),
            )
        }
    }
}

/** Tri-state chip: "unknown" never degrades into "no coupon". */
@Composable
private fun zcodeDashboardChip(zcode: ZCodeStatus?): Pair<String, Color> {
    val colors = AgTheme.colors
    if (zcode == null) return "Нет данных" to colors.muted
    if (zcode.isCouponUnknown) {
        return when (zcode.errorKind) {
            "auth_expired" -> "Нужен вход" to colors.amber
            "rate_limited" -> "Лимит запросов" to colors.amber
            "network" -> "Нет связи" to colors.rose
            "server_error", "api_changed", "parse_failed" -> "Ошибка проверки" to colors.rose
            else -> "Неизвестно" to colors.muted
        }
    }
    if (zcode.networkError) return "Нет связи" to colors.rose
    if (zcode.authOk == false) return "Нужен вход" to colors.amber
    if (zcode.method == "disabled") return "Не подключён" to colors.muted
    if (zcode.isCouponVisible) return "Ваучер" to colors.glow
    val remaining = zcode.remainingUnits
    if (remaining != null && (remaining > 0L || (zcode.totalUnits ?: 0L) > 0L)) {
        return "Баланс" to colors.green
    }
    if (zcode.couponState == ZCodeStatus.COUPON_ABSENT) return "Нет ваучера" to colors.muted
    return "Нет данных" to colors.muted
}
