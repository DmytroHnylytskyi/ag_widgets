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
import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.i18n.AppLanguage
import dev.agwidgets.ui.i18n.LocalStrings
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgThemeMode
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.ui.theme.normalizeThemeMode

/** One selectable theme chip: stored id and its preview dot. */
private data class ThemeChoice(
    val id: String,
    val dotColor: Color,
    val dotEndColor: Color? = null,
)

private val themeChoices = listOf(
    // The "Auto" preview shows both outcomes: dark graphite half / light paper half.
    ThemeChoice(AgThemeMode.SYSTEM, Color(0xFF1B1E23), Color(0xFFFAF9F6)),
    ThemeChoice(AgThemeMode.GRAPHITE, Color(0xFF1B1E23)),
    ThemeChoice(AgThemeMode.PAPER, Color(0xFFFAF9F6)),
    ThemeChoice(AgThemeMode.OLED, Color(0xFF000000)),
    ThemeChoice(AgThemeMode.GLASS, Color(0xFF7DD3FC)),
    ThemeChoice(AgThemeMode.CLASSIC, Color(0xFFFFE9B8)),
)

/**
 * Human label for a theme id.
 *
 * The theme names are proper product names and stay in Latin; only the "auto"
 * option is translated.
 */
private fun themeLabel(id: String, s: AgStrings): String = when (normalizeThemeMode(id)) {
    AgThemeMode.SYSTEM -> s.setThemeAuto
    AgThemeMode.GRAPHITE -> "Graphite"
    AgThemeMode.PAPER -> "Paper"
    AgThemeMode.OLED -> "OLED"
    AgThemeMode.GLASS -> "Glass"
    AgThemeMode.CLASSIC -> "Classic"
    else -> s.setThemeAuto
}

/** Human label for a language mode chip. */
private fun languageLabel(mode: String, s: AgStrings): String = when (AppLanguage.normalize(mode)) {
    AppLanguage.RU -> s.languageRu
    AppLanguage.EN -> s.languageEn
    else -> s.languageAuto
}

/** Pinned Settings title row: 48dp back target plus the screen name. */
@Composable
internal fun SettingsTopBar(onBack: () -> Unit) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgIconButton(
            icon = UiIcons.ArrowBack,
            contentDescription = s.commonBack,
            onClick = onBack,
        )
        Spacer(Modifier.width(AgDim.ControlGap))
        Text(
            text = s.setTitle,
            style = AgType.display.copy(fontSize = AgType.title.fontSize, lineHeight = AgType.title.lineHeight),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Settings surface: theme and language pickers first, then whatever
 * configuration cards the caller passes in (channel, ZCode access, widget,
 * diagnostics).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    themeMode: String,
    onSelectTheme: (String) -> Unit,
    language: String,
    onSelectLanguage: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    val selected = normalizeThemeMode(themeMode)
    val selectedLanguage = AppLanguage.normalize(language)
    val languageOptions = listOf(
        AppLanguage.AUTO to s.languageAuto,
        AppLanguage.RU to s.languageRu,
        AppLanguage.EN to s.languageEn,
    )

    Column(verticalArrangement = Arrangement.spacedBy(AgDim.CardGap)) {
        GlassCard {
            SectionHeader(
                serial = s.serialTheme,
                title = s.setThemeTitle,
                chipText = themeLabel(selected, s),
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
                    val label = themeLabel(choice.id, s)
                    ThemeChoiceButton(
                        choice = choice,
                        label = label,
                        selected = choice.id == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectTheme(choice.id) },
                    )
                }
            }
        }

        // Compact language card: same chip geometry as the theme row, so the two
        // pickers read as one family of choices.
        GlassCard {
            SectionHeader(
                serial = s.serialSystem,
                title = s.setLanguageTitle,
                chipText = languageLabel(selectedLanguage, s),
                chipColor = colors.glow,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
                verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
                maxItemsInEachRow = 3,
            ) {
                languageOptions.forEach { (mode, label) ->
                    LanguageChoiceButton(
                        label = label,
                        selected = mode == selectedLanguage,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectLanguage(mode) },
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
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
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
                contentDescription = if (selected) {
                    s.fill(s.setThemeA11ySelected, "theme" to label)
                } else {
                    s.fill(s.setThemeA11y, "theme" to label)
                }
            }
            .padding(horizontal = AgDim.ControlGap),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeDot(choice)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = AgType.caption,
            color = if (selected) colors.accentInk else colors.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Language chip: 48dp target, accent border and ink for the active language. */
@Composable
private fun LanguageChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    val shape = RoundedCornerShape(AgDim.ControlRadius)
    Box(
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
            .semantics { contentDescription = "${s.setLanguageAria}, $label" }
            .padding(horizontal = AgDim.ControlGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
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
