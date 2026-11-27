package dev.agwidgets.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agwidgets.data.E2eCrypto
import dev.agwidgets.ui.i18n.LocalStrings
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

/**
 * ntfy channel settings: the one primary action (scan the PC's QR code), the
 * refresh, and a collapsed "manual" section with the raw fields.
 *
 * Pairing itself is confirmed by the caller: this card only collects the code
 * and validates the server field before anything is persisted. The paste-code
 * dialog lives at the screen root so it also works during onboarding.
 */
@Composable
internal fun ChannelCard(
    topic: String,
    onTopicChange: (String) -> Unit,
    server: String,
    onServerChange: (String) -> Unit,
    hasE2eKey: Boolean,
    isRefreshing: Boolean,
    statusMessage: String,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
    onScanQr: () -> Unit,
    onPasteCode: () -> Unit,
    onResetE2eKey: () -> Unit,
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    var manualExpanded by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    GlassCard {
        // A key without a topic is not a working channel: E2E only counts as
        // configured when there is something to talk to.
        val channelConfigured = topic.isNotBlank()
        SectionHeader(
            serial = s.serialChannel,
            title = s.brandAntigravity,
            chipText = when {
                channelConfigured && hasE2eKey -> "E2E"
                channelConfigured -> "ntfy"
                else -> s.chNotConfigured
            },
            chipColor = when {
                channelConfigured && hasE2eKey -> colors.green
                channelConfigured -> colors.glow
                else -> colors.muted
            },
        )

        if (channelConfigured) {
            val summary = s.fill(
                if (hasE2eKey) s.chSummaryE2e else s.chSummaryPlain,
                "topic" to E2eCrypto.maskTopic(topic),
            )
            val serverSuffix = if (server.isNotBlank() && server != E2eCrypto.DEFAULT_NTFY_SERVER) {
                " · $server"
            } else {
                ""
            }
            Text(
                text = summary + serverSuffix,
                style = AgType.caption,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = s.chEmptyHint,
                style = AgType.body,
                color = colors.secondary,
            )
        }

        Button(
            onClick = onScanQr,
            modifier = Modifier
                .fillMaxWidth()
                .height(AgDim.MinTouch),
            shape = RoundedCornerShape(AgDim.ControlRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.glow,
                contentColor = colors.onGlow,
            ),
        ) {
            Icon(
                imageVector = UiIcons.QrCode,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = s.chScanQr, style = AgType.body)
        }

        if (channelConfigured) {
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                shape = RoundedCornerShape(AgDim.ControlRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AgDim.MinTouch),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.glow.copy(alpha = 0.16f),
                    contentColor = colors.accentInk,
                ),
            ) {
                Text(
                    text = if (isRefreshing) s.chRefreshing else s.commonRefresh,
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

        AgGhostButton(
            text = if (manualExpanded) s.chManualHide else s.chManualShow,
            onClick = { manualExpanded = !manualExpanded },
            icon = if (manualExpanded) UiIcons.ArrowUp else UiIcons.ArrowDown,
        )

        if (manualExpanded) {
            OutlinedTextField(
                value = topic,
                onValueChange = onTopicChange,
                singleLine = true,
                label = { Text(s.chTopicLabel, style = AgType.caption) },
                textStyle = AgType.body,
                shape = RoundedCornerShape(AgDim.ControlRadius),
                colors = glassFieldColors(colors.glow),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = server,
                onValueChange = onServerChange,
                singleLine = true,
                label = { Text(s.chServerLabel, style = AgType.caption) },
                placeholder = { Text("https://ntfy.sh", style = AgType.caption) },
                textStyle = AgType.body,
                shape = RoundedCornerShape(AgDim.ControlRadius),
                colors = glassFieldColors(colors.glow),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = s.chServerHint,
                style = AgType.caption,
                color = colors.muted,
            )
            AgGhostButton(text = s.commonSave, onClick = onSave)
            AgGhostButton(text = s.chPastePairing, onClick = onPasteCode)
            if (hasE2eKey) {
                AgGhostButton(
                    text = s.chResetE2eKey,
                    onClick = { showResetConfirm = true },
                    contentColor = colors.rose,
                )
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            shape = RoundedCornerShape(AgDim.Page),
            containerColor = colors.card,
            titleContentColor = colors.text,
            textContentColor = colors.secondary,
            title = { Text(s.chResetE2eTitle, style = AgType.title) },
            text = {
                Text(
                    text = s.chResetE2eBody,
                    style = AgType.body,
                    color = colors.secondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        onResetE2eKey()
                    },
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    modifier = Modifier.height(AgDim.MinTouch),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.riskStrong,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(s.commonReset, style = AgType.body)
                }
            },
            dismissButton = {
                AgGhostButton(text = s.commonCancel, onClick = { showResetConfirm = false })
            },
        )
    }
}
