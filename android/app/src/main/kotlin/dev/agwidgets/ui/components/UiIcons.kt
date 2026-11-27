package dev.agwidgets.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One line-icon set for the whole app: 24dp grid, 1.75dp stroke, round caps.
 *
 * Glyphs (`⟳`, `⚙`, `←`, `↑`, `↓`) are gone: they came from three different
 * font families with three optical weights and changed shape with the system
 * font. Everything here is a local `ImageVector`, so no icon dependency is
 * added and the tint follows the palette.
 */
internal object UiIcons {
    val Refresh: ImageVector by lazy { refresh("Refresh", clockwise = true) }
    val RotateCcw: ImageVector by lazy { refresh("RotateCcw", clockwise = false) }
    val Settings: ImageVector by lazy { gear("Settings") }
    val ArrowBack: ImageVector by lazy { arrowBack("ArrowBack") }
    val ArrowUp: ImageVector by lazy { chevron("ArrowUp") { pointsUp() } }
    val ArrowDown: ImageVector by lazy { chevron("ArrowDown") { pointsDown() } }
    val AlertCircle: ImageVector by lazy { alertCircle("AlertCircle") }
    val Check: ImageVector by lazy { check("Check") }
    val Close: ImageVector by lazy { closeIcon("Close") }
    val QrCode: ImageVector by lazy { qrCode("QrCode") }
}

private const val STROKE = 1.75f
private const val SIZE = 24f

private fun lineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = SIZE,
        viewportHeight = SIZE,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            block()
        }
    }.build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
}

/** Circular arrow: a 270° arc plus a chevron at the open end. */
private fun refresh(name: String, clockwise: Boolean): ImageVector = lineIcon(name) {
    val radius = 7.5f
    val centre = 12f
    val startX = centre + radius
    val endX = centre
    val endY = centre - radius
    moveTo(startX, centre)
    arcTo(
        horizontalEllipseRadius = radius,
        verticalEllipseRadius = radius,
        theta = 0f,
        isMoreThanHalf = true,
        isPositiveArc = clockwise,
        x1 = endX,
        y1 = endY,
    )
    // Chevron follows the sweep direction so the glyph reads as rotation.
    if (clockwise) {
        moveTo(endX - 2.7f, endY - 2.4f)
        lineTo(endX + 0.2f, endY)
        lineTo(endX - 2.7f, endY + 2.4f)
    } else {
        moveTo(endX + 2.7f, endY - 2.4f)
        lineTo(endX - 0.2f, endY)
        lineTo(endX + 2.7f, endY + 2.4f)
    }
}

/** Cog: an eight-tooth outline plus the hub hole. */
private fun gear(name: String): ImageVector = lineIcon(name) {
    val centre = 12f
    val outer = 9.2f
    val inner = 6.9f
    val teeth = 8
    val step = 2 * PI / (teeth * 2)
    for (index in 0 until teeth * 2) {
        val angle = -PI / 2 + index * step
        val radius = if (index % 2 == 0) outer else inner
        val x = centre + (cos(angle) * radius).toFloat()
        val y = centre + (sin(angle) * radius).toFloat()
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
    circle(centre, centre, 2.6f)
}

private fun arrowBack(name: String): ImageVector = lineIcon(name) {
    moveTo(20f, 12f)
    lineTo(4.5f, 12f)
    moveTo(10.5f, 5.8f)
    lineTo(4.3f, 12f)
    lineTo(10.5f, 18.2f)
}

private fun chevron(name: String, block: PathBuilder.() -> Unit): ImageVector = lineIcon(name) {
    block()
}

private fun PathBuilder.pointsUp() {
    moveTo(12f, 19.5f)
    lineTo(12f, 4.8f)
    moveTo(5.8f, 11f)
    lineTo(12f, 4.6f)
    lineTo(18.2f, 11f)
}

private fun PathBuilder.pointsDown() {
    moveTo(12f, 4.5f)
    lineTo(12f, 19.2f)
    moveTo(5.8f, 13f)
    lineTo(12f, 19.4f)
    lineTo(18.2f, 13f)
}

private fun alertCircle(name: String): ImageVector = lineIcon(name) {
    circle(12f, 12f, 8.4f)
    moveTo(12f, 7.6f)
    lineTo(12f, 12.6f)
    moveTo(12f, 16.2f)
    lineTo(12f, 16.4f)
}

private fun check(name: String): ImageVector = lineIcon(name) {
    moveTo(5f, 12.6f)
    lineTo(9.6f, 17.2f)
    lineTo(19f, 7.2f)
}

private fun closeIcon(name: String): ImageVector = lineIcon(name) {
    moveTo(6.2f, 6.2f)
    lineTo(17.8f, 17.8f)
    moveTo(17.8f, 6.2f)
    lineTo(6.2f, 17.8f)
}

/**
 * QR glyph: three corner "eyes" plus a couple of modules in the free corner.
 * All strokes, no fills, so it matches the rest of the set at 18dp.
 */
private fun qrCode(name: String): ImageVector = lineIcon(name) {
    qrEye(4.25f, 4.25f)
    qrEye(14.25f, 4.25f)
    qrEye(4.25f, 14.25f)
    // Bottom-right module cluster: a small square plus a tick-like dot.
    moveTo(15f, 15f)
    lineTo(18.5f, 15f)
    lineTo(18.5f, 18.5f)
    lineTo(15f, 18.5f)
    close()
    moveTo(15f, 20f)
    lineTo(15f, 20.1f)
}

/** One 5.5dp QR "eye" (corner square), drawn as an outline. */
private fun PathBuilder.qrEye(left: Float, top: Float) {
    val side = 5.5f
    moveTo(left, top)
    lineTo(left + side, top)
    lineTo(left + side, top + side)
    lineTo(left, top + side)
    close()
}
