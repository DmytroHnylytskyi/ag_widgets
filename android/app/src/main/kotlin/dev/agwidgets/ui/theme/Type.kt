package dev.agwidgets.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Five roles, three weights, tabular figures everywhere.
 *
 * Modern screens pick a role and never a raw `fontSize`; Classic keeps its
 * monospace campaign styling by wrapping these roles at the call site.
 * `tnum` stops the numbers from jumping while the dashboard refreshes.
 */
object AgType {
    /** Hero number (`253K`), screen titles. 26/32, -0.4sp. */
    val display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    )

    /** Screen heading, model name, percentages. 18/24. */
    val title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )

    /** Card and panel headings. 15/20. */
    val section = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    /** Body copy, labels, buttons. 13/18. */
    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )

    /** Metadata, timers, threshold captions, chips. 11/16. */
    val caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )
}

/** Material roles mapped onto [AgType] so stock components inherit the scale. */
val Typography = Typography(
    headlineSmall = AgType.display,
    titleLarge = AgType.title,
    titleMedium = AgType.section,
    titleSmall = AgType.section,
    bodyLarge = AgType.body,
    bodyMedium = AgType.body,
    bodySmall = AgType.caption,
    labelLarge = AgType.body.copy(fontWeight = FontWeight.Medium),
    labelMedium = AgType.caption,
    labelSmall = AgType.caption,
)
