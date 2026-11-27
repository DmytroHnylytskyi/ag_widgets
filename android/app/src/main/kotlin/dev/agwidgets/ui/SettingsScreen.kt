package dev.agwidgets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agwidgets.ui.components.AgIconButton
import dev.agwidgets.ui.components.GlassCard
import dev.agwidgets.ui.components.SectionHeader
import dev.agwidgets.ui.components.UiIcons
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgThemeMode
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.ui.theme.normalizeThemeMode

/** One selectable theme chip: stored id, human label and its preview dot. */
private data class ThemeChoice(
    val id: String,
    val label: String,
    val dotColor: Color,
    val dotEndColor: Color? = null,
)

private val themeChoices = listOf(
    // "Авто" previews both outcomes: dark graphite half / light paper half.
    ThemeChoice(AgThemeMode.SYSTEM, "Авто", Color(0xFF1B1E23), Color(0xFFFAF9F6)),
    ThemeChoice(AgThemeMode.GRAPHITE, "Graphite", Color(0xFF1B1E23)),
    ThemeChoice(AgThemeMode.PAPER, "Paper", Color(0xFFFAF9F6)),
    ThemeChoice(AgThemeMode.OLED, "OLED", Color(0xFF000000)),
    ThemeChoice(AgThemeMode.GLASS, "Glass", Color(0xFF7DD3FC)),
    ThemeChoice(AgThemeMode.CLASSIC, "Classic", Color(0xFFFFE9B8)),
)

private fun themeLabel(id: String): String =
    themeChoices.firstOrNull { it.id == id }?.label ?: "Авто"

/** Pinned Settings title row: 48dp back target plus the screen name. */
@Composable
internal fun SettingsTopBar(onBack: () -> Unit) {
    val colors = AgTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgIconButton(
            icon = UiIcons.ArrowBack,
            contentDescription = "Назад",
            onClick = onBack,
        )
        Spacer(Modifier.width(AgDim.ControlGap))
        Text(
            text = "Настройки",
            style = AgType.display.copy(fontSize = AgType.title.fontSize, lineHeight = AgType.title.lineHeight),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Settings surface: theme picker first, then whatever configuration cards the
 * caller passes in (channel, ZCode access, widget, diagnostics).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    themeMode: String,
    onSelectTheme: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = AgTheme.colors
    val selected = normalizeThemeMode(themeMode)

    Column(verticalArrangement = Arrangement.spacedBy(AgDim.CardGap)) {
        GlassCard {
            SectionHeader(
                serial = "01 / THEME",
                title = "Тема оформления",
                chipText = themeLabel(selected),
                chipColor = colors.glow,
            )
            // FlowRow: six chips wrap into two tidy rows instead of being cut
            // by the card edge at 320dp or at font_scale 1.3.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
                verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
                maxItemsInEachRow = 3,
            ) {
                themeChoices.forEach { choice ->
                    ThemeChoiceButton(
                        choice = choice,
                        selected = choice.id == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectTheme(choice.id) },
                    )
                }
            }
        }

        content()
    }
}

@Composable
private fun ThemeChoiceButton(
    choice: ThemeChoice,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AgTheme.colors
    val shape = RoundedCornerShape(AgDim.ControlRadius)
    Row(
        modifier = modifier
            .heightIn(min = AgDim.MinTouch)
            .clip(shape)
            .background(if (selected) colors.glow.copy(alpha = 0.16f) else colors.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.glow else colors.border,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Тема ${choice.label}" + if (selected) ", выбрана" else ""
            }
            .padding(horizontal = AgDim.ControlGap),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeDot(choice)
        Spacer(Modifier.width(6.dp))
        Text(
            text = choice.label,
            style = AgType.caption,
            color = if (selected) colors.accentInk else colors.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThemeDot(choice: ThemeChoice) {
    val shape = CircleShape
    val end = choice.dotEndColor
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(shape)
            .then(
                if (end != null) {
                    Modifier.background(Brush.horizontalGradient(listOf(choice.dotColor, end)), shape)
                } else {
                    Modifier.background(choice.dotColor, shape)
                },
            )
            .border(1.dp, AgTheme.colors.fieldBorder, shape),
    ) {}
}
