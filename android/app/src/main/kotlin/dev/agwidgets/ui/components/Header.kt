package dev.agwidgets.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agwidgets.R
import dev.agwidgets.data.AgStatus
import dev.agwidgets.ui.i18n.LocalStrings
import dev.agwidgets.ui.statusAgeMinutes
import dev.agwidgets.ui.theme.AgTheme
import dev.agwidgets.ui.theme.AgType

/**
 * Header: brand, freshness and the two actions.
 *
 * The mark is the monochrome vector tinted with the palette, so it is visible on
 * every light and dark theme (the launcher PNG carried its own shadow and
 * halo). Classic keeps its own row layout, but on two lines: the product name
 * is never truncated to `AG WIDG…` and the subtitle is not clipped mid-word.
 */
@Composable
internal fun Header(
    status: AgStatus?,
    nowMs: Long,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val age = statusAgeMinutes(status?.pcUpdatedAt ?: status?.updatedAt, nowMs)
    val classic = AgTheme.colors.isClassic
    val colorOf = AgTheme.colors
    val s = LocalStrings.current
    val (chipText, chipColor) = when {
        status == null -> (if (classic) s.statusClassicNoData else s.statusNoData) to colorOf.muted
        // An unknown age is not a status: the chip stays quiet instead of "—".
        age == null -> (if (classic) s.statusClassicNoData else s.statusNoData) to colorOf.muted
        age < 15 -> (if (classic) s.statusClassicLive else s.statusOnline) to colorOf.green
        age < 120 -> (if (classic) s.statusClassicQuiet else s.statusQuiet) to colorOf.amber
        else -> (if (classic) s.statusClassicStale else s.statusStale) to colorOf.muted
    }

    if (classic) {
        ClassicHeader(
            chipText = chipText,
            chipColor = chipColor,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )
    } else {
        ModernHeader(
            chipText = chipText,
            chipColor = chipColor,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun BrandMark(tint: Color, size: Int) {
    Icon(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size.dp),
    )
}

@Composable
private fun ModernHeader(
    chipText: String,
    chipColor: Color,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = AgTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(tint = colors.text, size = 28)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = LocalStrings.current.brandWidgets,
                style = AgType.title,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            StatusChip(chipText, chipColor)
        }
        Spacer(Modifier.width(8.dp))
        RefreshButton(isRefreshing = isRefreshing, onClick = onRefresh)
        Spacer(Modifier.width(8.dp))
        SettingsButton(onClick = onOpenSettings)
    }
}

/**
 * Classic brand row.
 *
 * The actions live on their own line so the wordmark gets the full width; the
 * author's cream/violet accent paints the mark, which is 1:1 with the original
 * artwork on the dark theme and still visible on the light one.
 */
@Composable
private fun ClassicHeader(
    chipText: String,
    chipColor: Color,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = AgTheme.colors
    val s = LocalStrings.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(tint = colors.glow, size = 32)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.brandWidgetsCaps,
                    style = AgType.title,
                    letterSpacing = (-0.2).sp,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = s.brandAntigravityZcode001,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    letterSpacing = 1.0.sp,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics {
                        contentDescription = s.dashHeaderSubtitleA11y
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(chipText, chipColor)
            Spacer(Modifier.weight(1f))
            RefreshButton(isRefreshing = isRefreshing, onClick = onRefresh)
            Spacer(Modifier.width(8.dp))
            SettingsButton(onClick = onOpenSettings)
        }    }
}
