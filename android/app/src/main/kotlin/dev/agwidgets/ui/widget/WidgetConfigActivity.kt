package dev.agwidgets.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import dev.agwidgets.BuildConfig
import dev.agwidgets.data.StatusStore
import dev.agwidgets.ui.components.AgGhostButton
import dev.agwidgets.ui.components.AgIconButton
import dev.agwidgets.ui.components.GlassCard
import dev.agwidgets.ui.components.UiIcons
import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.i18n.AppLanguage
import dev.agwidgets.ui.i18n.LocalStrings
import dev.agwidgets.ui.i18n.stringsFor
import dev.agwidgets.ui.theme.AGWidgetsTheme
import dev.agwidgets.ui.theme.AgDim
import dev.agwidgets.ui.theme.AgThemeMode
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType
import dev.agwidgets.widget.LimitsWidget
import dev.agwidgets.widget.WidgetBlockConfig
import dev.agwidgets.widget.WidgetBlockId
import dev.agwidgets.widget.WidgetConfig
import dev.agwidgets.widget.WidgetConfigCodec
import dev.agwidgets.widget.WidgetConfigStore
import dev.agwidgets.widget.WidgetDensity
import dev.agwidgets.widget.WidgetPresets
import dev.agwidgets.widget.WidgetThemes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Per-widget configuration screen started by the launcher with
 * [AppWidgetManager.EXTRA_APPWIDGET_ID].
 *
 * It keeps a pinned live preview, edits a [WidgetConfig] and, on save, refreshes
 * exactly the configured Glance instance (falling back to `updateAll` when the
 * launcher does not hand out a glance id).
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Until the user confirms, the widget must keep its previous settings.
        setResult(RESULT_CANCELED)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        val appContext = applicationContext
        setContent {
            val strings = stringsFor(AppLanguage.resolve(StatusStore.getLanguage(appContext)))
            var config by remember { mutableStateOf(WidgetConfigStore.get(appContext, appWidgetId)) }
            var saving by remember { mutableStateOf(false) }
            // `theme = "system"` inherits the *app* theme in the preview, so the
            // chrome around it must resolve through the very same helper.
            val chromeMode = remember(config.theme) { chromeThemeMode(appContext, config) }
            CompositionLocalProvider(LocalStrings provides strings) {
                AGWidgetsTheme(themeMode = chromeMode) {
                    WidgetConfigScreen(
                        config = config,
                        saving = saving,
                        onChange = { config = it },
                        onSave = {
                            if (saving) return@WidgetConfigScreen
                            saving = true
                            saveAndRefresh(appWidgetId, config)
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun saveAndRefresh(appWidgetId: Int, config: WidgetConfig) {
        WidgetConfigStore.save(this, appWidgetId, config)
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@WidgetConfigActivity)
            val refreshed = runCatching {
                LimitsWidget().update(
                    this@WidgetConfigActivity,
                    manager.getGlanceIdBy(appWidgetId),
                )
            }.isSuccess
            if (!refreshed) {
                runCatching { LimitsWidget().updateAll(this@WidgetConfigActivity) }
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    config: WidgetConfig,
    saving: Boolean,
    onChange: (WidgetConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
    ) {
        // Pinned preview: stays visible while the builder below scrolls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(horizontal = AgDim.Page, vertical = AgDim.ControlGap),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.wconfTitle,
                    style = AgType.title,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AgGhostButton(text = s.commonCancel, onClick = onCancel)
            }
            Spacer(Modifier.height(AgDim.ControlGap))
            WidgetLivePreview(config = config, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(thickness = 1.dp, color = colors.border)

        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = AgDim.Page)
                    .padding(top = AgDim.CardGap + AgDim.Scrim, bottom = AgDim.CardGap),
                verticalArrangement = Arrangement.spacedBy(AgDim.CardGap),
            ) {
                PresetsSection(config = config, onChange = onChange)
                BlocksSection(config = config, onChange = onChange)
                StyleSection(config = config, onChange = onChange)
                JsonSection(config = config, onChange = onChange)
                ResetAction(onReset = { onChange(WidgetConfig()) })
            }
            // Soft scrim: the scrolled cards fade out instead of being cut by a
            // hard line right under the preview.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AgDim.Scrim)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.background, colors.background.copy(alpha = 0f)),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AgDim.Scrim)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.background.copy(alpha = 0f), colors.background),
                        ),
                    ),
            )
        }

        HorizontalDivider(thickness = 1.dp, color = colors.border)

        // Pinned primary action.
        Surface(color = colors.background) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AgDim.Page, vertical = AgDim.CardGap),
            ) {
                Button(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AgDim.CtaHeight),
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.glow,
                        contentColor = colors.onGlow,
                    ),
                ) {
                    Text(
                        text = if (saving) s.wconfSaving else s.wconfSave,
                        style = AgType.body,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard {
        Text(
            text = title,
            style = AgType.section,
            color = AgTheme.colors.text,
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsSection(config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val s = LocalStrings.current
    ConfigSection(title = s.wconfPresets) {
        // FlowRow instead of a clipped horizontal strip: every preset label
        // stays whole at 320dp and at font_scale 1.3.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
            verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
        ) {
            WidgetPresets.all
                // The `context` flavour has no ZCode data path: a "Только ZCode"
                // preset there would build an empty widget.
                .filter { BuildConfig.ZCODE_ENABLED || it.id != WidgetPresets.zcodeOnly.id }
                .forEach { preset ->
                    SelectChip(
                        label = presetLabel(preset.id, s),
                        // Compare normalised configs: a stored order/options pair must
                        // not un-highlight the preset the user just opened.
                        selected = WidgetConfigCodec.normalize(preset.config) ==
                            WidgetConfigCodec.normalize(config),
                        onClick = { onChange(preset.config) },
                    )
                }
        }
    }
}

@Composable
private fun BlocksSection(config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val s = LocalStrings.current
    ConfigSection(title = s.wconfBlocks) {
        // The `context` flavour has no ZCode block: hide its row instead of
        // offering a switch that can never render, and reorder between adjacent
        // visible rows so arrows never swap with an invisible block.
        val visible = config.blocks.withIndex()
            .filter { BuildConfig.ZCODE_ENABLED || it.value.id != WidgetBlockId.ZCODE }
        visible.forEachIndexed { position, indexed ->
            val index = indexed.index
            val block = indexed.value
            // Flat sections with a hairline: a bordered card inside a GlassCard
            // is a card in a card.
            if (position > 0) {
                HorizontalDivider(thickness = 1.dp, color = AgTheme.colors.border)
            }
            BlockRow(
                block = block,
                title = blockTitle(block.id, s),
                canMoveUp = position > 0,
                canMoveDown = position < visible.lastIndex,
                onMoveUp = { onChange(config.moveBlock(index, visible[position - 1].index)) },
                onMoveDown = { onChange(config.moveBlock(index, visible[position + 1].index)) },
                onToggle = { enabled ->
                    onChange(config.updateBlock(block.id) { it.copy(enabled = enabled) })
                },
                onOption = { key, value ->
                    onChange(
                        config.updateBlock(block.id) {
                            it.copy(options = it.options + (key to value))
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun BlockRow(
    block: WidgetBlockConfig,
    title: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOption: (String, Boolean) -> Unit,
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 48dp row = 48dp touch target for the two arrows and the switch.
                .heightIn(min = AgDim.MinTouch)
                // The whole row is the switch: TalkBack always announces the
                // block name instead of a bare "переключатель, вкл.".
                .toggleable(
                    value = block.enabled,
                    role = Role.Switch,
                    onValueChange = onToggle,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = AgType.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 8dp between every pair of touch targets, never 0dp.
            AgIconButton(
                icon = UiIcons.ArrowUp,
                contentDescription = s.fill(s.wconfMoveUp, "title" to title),
                onClick = onMoveUp,
                enabled = canMoveUp,
            )
            Spacer(Modifier.width(AgDim.ControlGap))
            AgIconButton(
                icon = UiIcons.ArrowDown,
                contentDescription = s.fill(s.wconfMoveDown, "title" to title),
                onClick = onMoveDown,
                enabled = canMoveDown,
            )
            Spacer(Modifier.width(AgDim.ControlGap))
            Switch(
                checked = block.enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onGlow,
                    checkedTrackColor = colors.glow,
                    uncheckedThumbColor = colors.secondary,
                    uncheckedTrackColor = colors.track,
                    uncheckedBorderColor = colors.fieldBorder,
                ),
            )
        }
        when (block.id) {
            WidgetBlockId.CONTEXT -> OptionRow(
                label = s.wconfOptionCompactChip,
                checked = block.options["show_compact_chip"] ?: true,
                enabled = block.enabled,
                onCheckedChange = { onOption("show_compact_chip", it) },
            )
            WidgetBlockId.QUOTAS -> {
                OptionRow("Gemini", block.options["show_gemini"] ?: true, block.enabled) {
                    onOption("show_gemini", it)
                }
                OptionRow("Claude / GPT", block.options["show_3p"] ?: true, block.enabled) {
                    onOption("show_3p", it)
                }
                OptionRow(s.wconfOptionResetTimer, block.options["show_countdown"] ?: true, block.enabled) {
                    onOption("show_countdown", it)
                }
            }
            WidgetBlockId.ZCODE -> OptionRow(
                label = s.wconfOptionBalanceBar,
                checked = block.options["show_balance_bar"] ?: true,
                enabled = block.enabled,
                onCheckedChange = { onOption("show_balance_bar", it) },
            )
            WidgetBlockId.HEADER -> Unit
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = AgTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AgDim.MinTouch)
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(start = AgDim.ControlGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AgType.body,
            color = colors.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(AgDim.ControlGap))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onGlow,
                checkedTrackColor = colors.glow,
                uncheckedThumbColor = colors.secondary,
                uncheckedTrackColor = colors.track,
                uncheckedBorderColor = colors.fieldBorder,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleSection(config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    val selectedTheme = WidgetThemes.normalize(config.theme)
    val selectedAccent = WidgetConfigCodec.normalizeHexAccent(config.customAccent)
    ConfigSection(title = s.wconfStyle) {
        Text(text = s.labelTheme, style = AgType.body, color = colors.secondary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
            verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
        ) {
            WidgetThemes.all.forEach { theme ->
                SelectChip(
                    label = themeLabel(theme, s),
                    selected = theme == selectedTheme,
                    onClick = { onChange(config.copy(theme = theme)) },
                )
            }
        }
        Text(text = s.wconfAccent, style = AgType.body, color = colors.secondary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
            verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
        ) {
            AccentDot(
                color = null,
                selected = selectedAccent == null,
                label = s.wconfAccentDefault,
                onClick = { onChange(config.copy(customAccent = null)) },
            )
            accentChoices.forEach { hex ->
                AccentDot(
                    color = Color(android.graphics.Color.parseColor(hex)),
                    selected = selectedAccent == hex,
                    label = s.fill(s.wconfAccentHex, "hex" to hex),
                    onClick = { onChange(config.copy(customAccent = hex)) },
                )
            }
        }
        LabeledSlider(
            title = s.wconfOpacity,
            valueLabel = "${(config.opacity * 100).roundToInt()}%",
            value = config.opacity,
            valueRange = 0f..1f,
            onValueChange = {
                val snapped = WidgetConfigCodec.clampStep(
                    it.toDouble(),
                    WidgetConfigCodec.MIN_OPACITY.toDouble(),
                    WidgetConfigCodec.MAX_OPACITY.toDouble(),
                    WidgetConfigCodec.DEFAULT_OPACITY.toDouble(),
                ).toFloat()
                onChange(config.copy(opacity = snapped))
            },
        )
        LabeledSlider(
            title = s.wconfCornerRadius,
            valueLabel = "${config.cornerRadiusDp} dp",
            value = config.cornerRadiusDp.toFloat(),
            valueRange = 0f..24f,
            onValueChange = {
                onChange(config.copy(cornerRadiusDp = WidgetConfigCodec.clampCornerRadius(it.toDouble())))
            },
        )
        LabeledSlider(
            title = s.wconfFontScale,
            valueLabel = "${(config.fontScale * 100).roundToInt()}%",
            value = config.fontScale,
            valueRange = 0.85f..1.4f,
            onValueChange = {
                val snapped = WidgetConfigCodec.clampStep(
                    it.toDouble(),
                    WidgetConfigCodec.MIN_FONT_SCALE.toDouble(),
                    WidgetConfigCodec.MAX_FONT_SCALE.toDouble(),
                    WidgetConfigCodec.DEFAULT_FONT_SCALE.toDouble(),
                ).toFloat()
                onChange(config.copy(fontScale = snapped))
            },
        )
        Text(text = s.wconfDensity, style = AgType.body, color = colors.secondary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
            verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap),
        ) {
            WidgetDensity.all.forEach { density ->
                SelectChip(
                    label = densityLabel(density, s),
                    selected = config.density == density,
                    onClick = { onChange(config.copy(density = density)) },
                )
            }
        }
    }
}

/**
 * One selection chip: selected state is a tint plus an accent border, not a
 * 1.04:1 fill. The touch target is 48dp while the visible chip stays 36dp —
 * `FilterChip` reported a 27.7dp clickable area on this device.
 */
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AgTheme.colors
    val shape = RoundedCornerShape(AgDim.ControlRadius)
    Box(
        modifier = Modifier
            .heightIn(min = AgDim.MinTouch)
            .clip(shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = AgDim.ChipHeight)
                .clip(shape)
                .background(if (selected) colors.glow.copy(alpha = 0.16f) else colors.surface)
                .border(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) colors.glow else colors.border,
                    shape = shape,
                )
                .padding(horizontal = 12.dp),
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
}

@Composable
private fun AccentDot(color: Color?, selected: Boolean, label: String, onClick: () -> Unit) {
    val colors = AgTheme.colors
    Box(
        modifier = Modifier
            .size(AgDim.MinTouch)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (selected) colors.glow.copy(alpha = 0.22f) else Color.Transparent)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.glow else colors.fieldBorder,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (color == null) 20.dp else 18.dp)
                    .clip(CircleShape)
                    .background(color ?: colors.surface)
                    .border(1.dp, colors.border, CircleShape),
            )
            if (color == null) {
                // `7.sp` inside an 18dp circle was unreadable; a crisp vector reset
                // glyph says "theme default" without sub-11sp type.
                androidx.compose.material3.Icon(
                    imageVector = UiIcons.RotateCcw,
                    contentDescription = null,
                    tint = colors.secondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Slider drawn by hand: a 6dp track, a 20dp round thumb and — critically — a
 * real **48dp** interaction node.
 *
 * The Material `Slider` of this BOM reported 44.1dp in `uiautomator` (and drew a
 * 16dp pill track with a vertical M3 handle), which is below the HIG minimum and
 * off-contract for this app. The accessibility node keeps the `SeekBar` class and
 * a `setProgress` action, so TalkBack still treats it as a range control.
 */
@Composable
private fun LabeledSlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val colors = AgTheme.colors
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumbSize = 20.dp
    val trackHeight = 6.dp
    var widthPx by remember { mutableStateOf(0f) }
    // `pointerInput` keeps its initial coroutine across recompositions; wrap the
    // callback so dragging a slider never overwrites other edits with a stale config.
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = AgType.body,
                color = colors.secondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = AgType.body,
                color = colors.text,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AgDim.MinTouch)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(span, valueRange.start) {
                    val thumbPx = thumbSize.toPx()
                    fun emit(x: Float) {
                        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
                        val next = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                        latestOnValueChange(valueRange.start + next * span)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        emit(down.position.x)
                        drag(down.id) { change ->
                            change.consume()
                            emit(change.position.x)
                        }
                    }
                }
                .semantics {
                    contentDescription = title
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, 0)
                    setProgress { target ->
                        latestOnValueChange(
                            target.coerceIn(valueRange.start, valueRange.endInclusive),
                        )
                        true
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.track),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.glow),
            )
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (fraction * (widthPx - thumbSize.toPx())).roundToInt().coerceAtLeast(0),
                            0,
                        )
                    }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(colors.glow)
                    .border(2.dp, colors.card, CircleShape),
            )
        }
    }
}

@Composable
private fun ResetAction(onReset: () -> Unit) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    var confirmReset by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Destructive action: never primary, visually separated from the save CTA.
        Spacer(Modifier.height(AgDim.ControlGap))
        AgGhostButton(
            text = s.wconfResetAction,
            onClick = { confirmReset = true },
            modifier = Modifier.fillMaxWidth(),
            contentColor = colors.rose,
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = colors.card,
            titleContentColor = colors.text,
            textContentColor = colors.secondary,
            shape = RoundedCornerShape(AgDim.PanelRadius),
            title = { Text(text = s.wconfResetTitle, style = AgType.title) },
            text = {
                Text(
                    text = s.wconfResetBody,
                    style = AgType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) {
                    Text(text = s.commonReset, style = AgType.body, color = colors.rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(text = s.commonCancel, style = AgType.body, color = colors.secondary)
                }
            },
        )
    }
}

@Composable
private fun JsonSection(config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val context = LocalContext.current
    val colors = AgTheme.colors
    val s = LocalStrings.current
    var importOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }

    ConfigSection(title = s.wconfExportImport) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
            ChipButton(
                text = s.wconfExportJson,
                modifier = Modifier.weight(1f),
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            "AG Widgets",
                            WidgetConfigCodec.exportJson(config),
                        ),
                    )
                    Toast.makeText(context, s.wconfJsonCopied, Toast.LENGTH_SHORT).show()
                },
            )
            ChipButton(
                text = s.wconfImportJson,
                modifier = Modifier.weight(1f),
                onClick = { importOpen = true },
            )
        }
    }

    if (importOpen) {
        AlertDialog(
            onDismissRequest = { importOpen = false },
            containerColor = colors.card,
            titleContentColor = colors.text,
            textContentColor = colors.secondary,
            shape = RoundedCornerShape(AgDim.PanelRadius),
            title = { Text(text = s.wconfImportJson, style = AgType.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AgDim.ControlGap)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        placeholder = {
                            Text(
                                text = s.wconfImportPlaceholder,
                                style = AgType.caption.copy(fontFamily = FontFamily.Monospace),
                                color = colors.muted,
                            )
                        },
                        // M3 hides the placeholder until focus: the format example
                        // has to be visible up front, so it lives in the hint line.
                        supportingText = {
                            Text(
                                text = s.wconfImportHint,
                                style = AgType.caption.copy(fontFamily = FontFamily.Monospace),
                                color = colors.muted,
                            )
                        },
                        label = { Text(s.wconfConfigLabel, style = AgType.caption) },
                        textStyle = AgType.caption.copy(fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(AgDim.ControlRadius),
                        isError = importError,
                        singleLine = false,
                        maxLines = 12,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedBorderColor = colors.glow,
                            unfocusedBorderColor = colors.fieldBorder,
                            focusedLabelColor = colors.accentInk,
                            unfocusedLabelColor = colors.secondary,
                            cursorColor = colors.glow,
                        ),
                    )
                    if (importError) {
                        Text(
                            text = s.wconfImportInvalid,
                            style = AgType.caption,
                            color = colors.rose,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val imported = WidgetConfigCodec.importJson(importText)
                        if (imported == null) {
                            importError = true
                        } else {
                            onChange(imported)
                            importOpen = false
                        }
                    },
                    shape = RoundedCornerShape(AgDim.ControlRadius),
                    modifier = Modifier.height(AgDim.MinTouch),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.glow,
                        contentColor = colors.onGlow,
                    ),
                ) {
                    Text(text = s.commonApply, style = AgType.body)
                }
            },
            dismissButton = {
                TextButton(onClick = { importOpen = false }) {
                    Text(text = s.commonCancel, style = AgType.body, color = colors.secondary)
                }
            },
        )
    }
}

@Composable
private fun ChipButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AgTheme.colors
    val shape = RoundedCornerShape(AgDim.ControlRadius)
    Box(
        modifier = modifier
            .heightIn(min = AgDim.MinTouch)
            .clip(shape)
            .border(1.dp, colors.fieldBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = AgDim.ControlGap, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AgType.body,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun WidgetConfig.updateBlock(
    id: WidgetBlockId,
    transform: (WidgetBlockConfig) -> WidgetBlockConfig,
): WidgetConfig = copy(blocks = blocks.map { if (it.id == id) transform(it) else it })

private fun WidgetConfig.moveBlock(from: Int, to: Int): WidgetConfig {
    if (from == to || from !in blocks.indices || to !in blocks.indices) return this
    val reordered = blocks.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    return copy(blocks = reordered)
}

private fun blockTitle(id: WidgetBlockId, s: AgStrings): String = when (id) {
    WidgetBlockId.HEADER -> s.wconfBlockHeader
    WidgetBlockId.CONTEXT -> s.labelContext
    WidgetBlockId.QUOTAS -> s.labelLimits
    WidgetBlockId.ZCODE -> s.labelZcode
}

/**
 * Mode for the builder chrome: the very palette the pinned preview resolves to.
 *
 * `WidgetStore.resolveWidgetTheme` already turned `system` into a concrete
 * palette; mapping the concrete id back to a selectable mode keeps the chrome
 * and the preview on one theme even when the app itself runs OLED.
 */
private fun chromeThemeMode(context: android.content.Context, config: WidgetConfig): String {
    val resolved = WidgetConfigStore.resolveWidgetTheme(context, config)
    return when (resolved.id) {
        "glass_dark", "glass_light" -> AgThemeMode.GLASS
        "classic_dark", "classic_light" -> AgThemeMode.CLASSIC
        else -> resolved.id
    }
}

private fun themeLabel(theme: String, s: AgStrings): String = when (theme) {
    WidgetThemes.SYSTEM -> s.setThemeAuto
    WidgetThemes.GRAPHITE -> "Graphite"
    WidgetThemes.PAPER -> "Paper"
    WidgetThemes.OLED -> "OLED"
    WidgetThemes.GLASS -> "Glass"
    WidgetThemes.CLASSIC -> "Classic"
    else -> s.setThemeAuto
}

private fun densityLabel(density: WidgetDensity, s: AgStrings): String = when (density) {
    WidgetDensity.COMPACT -> s.wconfDensityCompact
    WidgetDensity.COMFORTABLE -> s.wconfDensityComfortable
    WidgetDensity.SPACIOUS -> s.wconfDensitySpacious
}

/** Localised preset name; ids are stored in the widget config, labels are not. */
private fun presetLabel(id: String, s: AgStrings): String = when (id) {
    WidgetPresets.dashboard.id -> s.wconfPresetDashboard
    WidgetPresets.classic.id -> s.wconfPresetClassic
    WidgetPresets.contextCompact.id -> s.wconfPresetContext
    WidgetPresets.detailed.id -> s.wconfPresetDetailed
    WidgetPresets.zcodeOnly.id -> s.wconfPresetZcodeOnly
    else -> s.wconfPresetCustom
}

private val accentChoices = listOf(
    "#60a5fa",
    "#34d399",
    "#a78bfa",
    "#f59e0b",
    "#f43f5e",
    "#38bdf8",
)
