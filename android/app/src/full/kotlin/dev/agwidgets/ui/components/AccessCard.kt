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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agwidgets.data.ZCodeStatus
import dev.agwidgets.ui.formatDateShort
import dev.agwidgets.ui.formatUnits
import dev.agwidgets.ui.i18n.LocalStrings
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.ui.theme.onAccentFor

@Composable
internal fun AccessCard(
    zcode: ZCodeStatus?,
    token: String,
    onTokenChange: (String) -> Unit,
    isPending: Boolean,
    message: String,
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onSaveToken: () -> Unit,
    onClearToken: () -> Unit,
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    val (chipText, chipColor) = zcodeChip(zcode)
    val planSuffix = zcode?.planName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
    var confirmClear by remember { mutableStateOf(false) }

    GlassCard {
        SectionHeader(
            serial = s.serialAccess,
            title = s.labelZcode + planSuffix,
            chipText = chipText,
            chipColor = chipColor,
        )
        // One description: the chip already states whether monitoring is live.
        Text(
            text = s.accDescription,
            style = AgType.body,
            color = colors.secondary,
        )
        if (zcode?.remainingUnits != null) {
            BalanceBar(zcode)
        }
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            singleLine = true,
            // Standard password masking: identity offset mapping, so the caret,
            // selection and backspace behave. `ShortTokenMask` used to reveal the
            // first 1–4 typed characters next to fake bullets.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
            ),
            label = { Text(s.accTokenLabel, style = AgType.caption) },
            textStyle = AgType.body,
            shape = RoundedCornerShape(AgDim.ControlRadius),
            colors = glassFieldColors(colors.violet),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onLogin,
                enabled = !isPending,
                modifier = Modifier
                    .weight(1f)
                    .height(AgDim.MinTouch),
                shape = RoundedCornerShape(AgDim.ControlRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.violet,
                    // Derived from the violet fill, not from `onGlow` (tuned for
                    // the cream glow): Classic Dark dropped to 3.75:1 before.
                    contentColor = onAccentFor(colors.violet),
                ),
            ) {
                Text(text = if (isPending) s.accWaiting else s.dashZcodeSignIn, style = AgType.body)
            }
            if (isPending) {
                OutlinedButton(
                    onClick = onCancelLogin,
                    modifier = Modifier
                        .weight(1f)
                        .height(AgDim.MinTouch),
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                ) {
                    Text(text = s.commonCancel, style = AgType.body)
                }
            }
        }
        // Secondary save action belongs to its own field, not to the page CTA row.
        AgGhostButton(text = s.accSaveToken, onClick = onSaveToken)
        if (token.isNotBlank()) {
            Spacer(Modifier.height(AgDim.ControlGap))
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AgDim.MinTouch),
                shape = RoundedCornerShape(AgDim.ControlRadius),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.rose),
            ) {
                Text(text = s.accClearToken, style = AgType.body)
            }
        }
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = AgType.body,
                color = colors.secondary,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = colors.card,
            titleContentColor = colors.text,
            textContentColor = colors.secondary,
            shape = RoundedCornerShape(AgDim.PanelRadius),
            title = { Text(text = s.accDeleteTitle, style = AgType.title) },
            text = {
                Text(
                    text = s.accDeleteBody,
                    style = AgType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearToken()
                }) {
                    Text(text = s.commonDelete, style = AgType.body, color = colors.rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(text = s.commonCancel, style = AgType.body, color = colors.secondary)
                }
            },
        )
    }
}

@Composable
private fun BalanceBar(zcode: ZCodeStatus) {
    val remaining = zcode.remainingUnits ?: return
    val total = zcode.totalUnits
    val colors = AgTheme.colors
    val classic = colors.isClassic
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (classic) {
                    if (total != null) {
                        s.fill(
                            s.accBalanceClassicTotal,
                            "remaining" to formatUnits(remaining),
                            "total" to formatUnits(total),
                        )
                    } else {
                        s.fill(s.accBalanceClassic, "remaining" to formatUnits(remaining))
                    }
                } else {
                    if (total != null) {
                        s.fill(
                            s.dashZcodeRemainingOf,
                            "remaining" to formatUnits(remaining),
                            "total" to formatUnits(total),
                        )
                    } else {
                        s.fill(s.dashZcodeRemainingOnly, "remaining" to formatUnits(remaining))
                    }
                },
                style = if (classic) {
                    AgType.caption.copy(letterSpacing = 0.5.sp, color = colors.text)
                } else {
                    AgType.body
                },
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            zcode.endsAt?.let {
                Spacer(Modifier.width(AgDim.ControlGap))
                Text(
                    text = if (classic) {
                        s.fill(s.accUntilClassic, "date" to formatDateShort(it))
                    } else {
                        s.fill(s.accUntil, "date" to formatDateShort(it))
                    },
                    style = AgType.caption,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }
        if (total != null && total > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(AgDim.BarRadius))
                    .background(colors.track),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((remaining.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(colors.glow),
                )
            }
        }
    }
}

@Composable
private fun zcodeChip(zcode: ZCodeStatus?): Pair<String, Color> {
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
    if (zcode.isCouponVisible || zcode.couponVisible) return s.statusCoupon to colors.glow
    if (zcode.running) return s.statusActive to colors.green
    return s.statusNotRunning to colors.muted
}
