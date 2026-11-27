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
import dev.agwidgets.ui.i18n.LocalStrings
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
    val s = LocalStrings.current
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
            serial = s.serialAccess,
            title = s.labelZcode,
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
                            s.fill(
                                s.dashZcodeRemainingOf,
                                "remaining" to formatUnits(remaining),
                                "total" to formatUnits(total),
                            )
                        } else {
                            s.fill(s.dashZcodeRemainingOnly, "remaining" to formatUnits(remaining))
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
                        text = s.fill(s.dashZcodeValidUntil, "date" to formatDateShort(it)),
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
                        text = if (isPending) s.dashZcodeWaitingLogin else s.dashZcodeSignIn,
                        style = AgType.body,
                    )
                }
            }
            AgGhostButton(text = s.commonConfigure, onClick = onOpenSettings)
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
    val s = LocalStrings.current
    if (zcode == null) return s.statusNoData to colors.muted
    if (zcode.isCouponUnknown) {
        return when (zcode.errorKind) {
            "auth_expired" -> s.statusLoginRequired to colors.amber
            "rate_limited" -> s.statusRateLimited to colors.amber
            "network" -> s.statusNoConnection to colors.rose
            "server_error", "api_changed", "parse_failed" -> s.statusCheckFailed to colors.rose
            else -> s.statusUnknown to colors.muted
        }
    }
    if (zcode.networkError) return s.statusNoConnection to colors.rose
    if (zcode.authOk == false) return s.statusLoginRequired to colors.amber
    if (zcode.method == "disabled") return s.statusNotConnected to colors.muted
    if (zcode.isCouponVisible) return s.statusCoupon to colors.glow
    val remaining = zcode.remainingUnits
    if (remaining != null && (remaining > 0L || (zcode.totalUnits ?: 0L) > 0L)) {
        return s.statusBalance to colors.green
    }
    if (zcode.couponState == ZCodeStatus.COUPON_ABSENT) return s.statusNoCoupon to colors.muted
    return s.statusNoData to colors.muted
}
