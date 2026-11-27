package dev.agwidgets.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agwidgets.R
import dev.agwidgets.ui.components.GlassCard
import dev.agwidgets.ui.components.SectionHeader
import dev.agwidgets.ui.components.UiIcons
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

/** First-run choice: ZCode-only or connected to a PC with Antigravity. */
@Composable
internal fun OnboardingScreen(
    zcodeAvailable: Boolean,
    onChoosePc: () -> Unit,
    onScanQr: () -> Unit,
    onChooseZcode: () -> Unit,
) {
    val colors = AgTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AgDim.Page, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(AgDim.CardGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Modern themes use the monochrome mark tinted by the palette;
            // Classic keeps the author's launcher artwork 1:1.
            if (colors.isClassic) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_mark),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_brand_mark),
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = AgType.title,
                    color = colors.text,
                )
                if (colors.isClassic) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "ANTIGRAVITY · 001",
                        style = AgType.caption.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.1.sp,
                        ),
                        color = colors.muted,
                    )
                }
            }
        }

        Text(
            text = "Как вы будете пользоваться?",
            style = AgType.title,
            color = colors.text,
        )
        Text(
            text = if (zcodeAvailable) {
                "Режим можно сменить позже в разделе «Диагностика»."
            } else {
                "AG Context работает вместе с коллектором на компьютере."
            },
            style = AgType.body,
            color = colors.secondary,
        )

        if (zcodeAvailable) {
            GlassCard {
                SectionHeader(
                    serial = "01 / ZCODE",
                    title = "Только ZCode",
                    chipText = "Без ПК",
                    chipColor = colors.glow,
                )
                Text(
                    text = "Ваучеры на 300–500M токенов и баланс. Компьютер не нужен: " +
                        "приложение само проверяет ваучеры.",
                    style = AgType.body,
                    color = colors.secondary,
                )
                Button(
                    onClick = onChooseZcode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AgDim.MinTouch),
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.glow,
                        contentColor = colors.onGlow,
                    ),
                ) {
                    Text(text = "Выбрать ZCode", style = AgType.body)
                }
            }
        }

        GlassCard {
            SectionHeader(
                serial = if (zcodeAvailable) "02 / PC" else "01 / PC",
                title = "С компьютером",
                chipText = "Antigravity",
                chipColor = colors.violet,
            )
            Text(
                text = if (zcodeAvailable) {
                    "Контекст открытого чата Antigravity, лимиты 5 ч и недели и ZCode. " +
                        "Нужен компьютер с установленным коллектором."
                } else {
                    "Контекст открытого чата Antigravity и лимиты 5 ч и недели. " +
                        "Нужен компьютер с установленным коллектором."
                },
                style = AgType.body,
                color = colors.secondary,
            )
            // The QR scan is the primary path: it sets the mode *and* the whole
            // channel in one step. "Выбрать ПК" stays as the manual fallback.
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
                Text(text = "Сканировать QR-код с ПК", style = AgType.body)
            }
            OutlinedButton(
                onClick = onChoosePc,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AgDim.MinTouch),
                shape = RoundedCornerShape(AgDim.ControlRadius),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
            ) {
                Text(text = "Выбрать ПК", style = AgType.body)
            }
        }

        Text(
            text = if (zcodeAvailable) {
                "AG Widgets · Antigravity + ZCode"
            } else {
                "AG Context · только Antigravity"
            },
            style = if (colors.isClassic) {
                AgType.caption.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                )
            } else {
                AgType.caption
            },
            color = if (colors.isClassic) colors.muted.copy(alpha = 0.7f) else colors.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
