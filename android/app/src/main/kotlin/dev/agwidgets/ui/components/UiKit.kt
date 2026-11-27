package dev.agwidgets.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

/**
 * Card surface.
 *
 * `Classic` keeps the author's glass panel with a hard 5dp offset shadow and a
 * 10dp corner. Every modern theme renders a calm utility surface on the 4dp
 * grid: 12dp corners, a 1dp hairline, 12/16dp padding. Glass themes add the
 * inner top highlight that makes the pane read as a pane.
 */
@Composable
internal fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = AgTheme.colors
    if (colors.isClassic) {
        val shape = RoundedCornerShape(10.dp)
        Box(modifier = Modifier.padding(end = 5.dp, bottom = 5.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(5.dp, 5.dp)
                    .clip(shape)
                    .background(colors.cardShadow),
            )
            Column(
                modifier = Modifier
                    .clip(shape)
                    .background(colors.card)
                    .border(1.dp, colors.border, shape)
                    .padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                content = content,
            )
        }
    } else {
        val shape = RoundedCornerShape(colors.cardCornerDp.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.border, shape),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AgDim.CardPadH,
                    vertical = AgDim.CardPadV,
                ),
                verticalArrangement = Arrangement.spacedBy(AgDim.CardGap),
                content = content,
            )
            if (colors.isGlass) {
                // Inner top hairline: the one cue that separates glass from a
                // flat surface without a shadow.
                Canvas(modifier = Modifier.matchParentSize()) {
                    val inset = AgDim.CardPadH.toPx()
                    drawLine(
                        color = colors.innerHighlight,
                        start = Offset(inset, 1f),
                        end = Offset(size.width - inset, 1f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }
    }
}

/**
 * Card header, used by **every** card (including diagnostics).
 *
 * Classic keeps the monospace serial (`02 / CHANNEL`) above the title; modern
 * themes drop the technical code and show one readable title row with the
 * status chip and an optional trailing action.
 */
@Composable
internal fun SectionHeader(
    serial: String,
    title: String,
    chipText: String? = null,
    chipColor: Color = AgTheme.colors.muted,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AgTheme.colors
    if (colors.isClassic) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = serial,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 1.3.sp,
                    color = colors.muted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (chipText != null) StatusChip(chipText, chipColor)
                trailing?.invoke()
            }
            Text(
                text = title,
                style = AgType.title,
                color = colors.text,
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = AgType.section,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (chipText != null) {
                Spacer(Modifier.width(AgDim.ControlGap))
                StatusChip(chipText, chipColor)
            }
            trailing?.invoke()
        }
    }
}

/**
 * State chip.
 *
 * Modern themes never paint the label in the colour they fill with: the label
 * is normal `text` on a neutral `badge` backdrop, the colour lives in a 6dp dot
 * and the hairline. Classic keeps the original tinted chip.
 */
@Composable
internal fun StatusChip(text: String, color: Color) {
    val colors = AgTheme.colors
    if (colors.isClassic) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, color.copy(alpha = 0.45f), shape)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                // Classic keeps the author's mono-caps; modern themes stay in
                // sentence case.
                text = text.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
            )
        }
    } else {
        val shape = RoundedCornerShape(AgDim.ChipRadius)
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 24.dp)
                .clip(shape)
                .background(colors.badge)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = AgType.caption,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun glassFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AgTheme.colors.text,
    unfocusedTextColor = AgTheme.colors.text,
    focusedBorderColor = accent,
    unfocusedBorderColor = AgTheme.colors.fieldBorder,
    focusedLabelColor = accent,
    unfocusedLabelColor = AgTheme.colors.secondary,
    cursorColor = accent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)

/**
 * The one icon button: 48dp touch target, 40dp visible container, 20dp glyph.
 *
 * Every header/back/arrow action goes through it so no screen invents its own
 * 42dp box or drops a 16sp glyph inside a 48dp frame.
 */
@Composable
internal fun AgIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = AgTheme.colors
    val shape = RoundedCornerShape(AgDim.ControlRadius)
    Box(
        modifier = modifier
            .size(AgDim.MinTouch)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(if (colors.isClassic) colors.card else colors.badge)
                .border(1.dp, colors.border, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) colors.text else colors.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Borderless action aligned to the card content edge: no `TextButton` inset,
 * 48dp tall, 8dp corners.
 */
@Composable
internal fun AgGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    // Destructive actions pass `colors.rose`: a ghost button must not read as
    // the same affordance as "Отмена".
    contentColor: Color? = null,
) {
    val colors = AgTheme.colors
    val ink = contentColor ?: colors.accentInk
    val shape = RoundedCornerShape(AgDim.ControlRadius)
    Row(
        modifier = modifier
            // Explicit height, not `defaultMinSize`: the accessibility node must
            // report a real 48dp target, not the 35dp text box inside padding.
            .height(AgDim.MinTouch)
            .clip(shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(AgDim.ControlGap))
        }
        Text(
            text = text,
            style = AgType.body.copy(fontWeight = FontWeight.Medium),
            color = ink,
            maxLines = 1,
        )
    }
}

@Composable
internal fun RefreshButton(isRefreshing: Boolean, onClick: () -> Unit) {
    AgIconButton(
        icon = UiIcons.Refresh,
        contentDescription = if (isRefreshing) "Обновление идёт" else "Обновить данные",
        onClick = onClick,
        enabled = !isRefreshing,
    )
}

/** Opens the separate Settings screen; 48dp touch target. */
@Composable
internal fun SettingsButton(onClick: () -> Unit) {
    AgIconButton(
        icon = UiIcons.Settings,
        contentDescription = "Настройки",
        onClick = onClick,
    )
}
