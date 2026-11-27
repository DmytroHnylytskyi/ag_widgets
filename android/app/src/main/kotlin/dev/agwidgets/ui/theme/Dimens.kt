package dev.agwidgets.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The 4dp grid every modern surface is built on.
 *
 * Dashboard, Settings and the widget builder all use [Page] so the content edge
 * does not jump between screens, and every interactive element is at least
 * [MinTouch] tall/wide with [ControlGap] between neighbours.
 */
object AgDim {
    /** Page padding, identical on every screen. */
    val Page = 16.dp

    /** Card inner padding: 12dp vertical / 16dp horizontal. */
    val CardPadV = 12.dp
    val CardPadH = 16.dp

    /** Gap between cards. */
    val CardGap = 12.dp

    /** Gap between stacked controls inside a card. */
    val ControlGap = 8.dp

    /** Minimum touch target for anything clickable. */
    val MinTouch = 48.dp

    /** Visible heights inside a 48dp touch target. */
    val ChipHeight = 36.dp
    val ButtonHeight = 44.dp
    val CtaHeight = 48.dp

    /** Radius scale: bar < chip < control < card < panel. */
    val BarRadius = 2.dp
    val ChipRadius = 6.dp
    val ControlRadius = 8.dp
    val CardRadius = 12.dp
    val PanelRadius = 16.dp

    /** Soft scrim drawn under a pinned bar / above a pinned action. */
    val Scrim = 24.dp
}
