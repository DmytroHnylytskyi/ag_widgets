package dev.agwidgets.ui

import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.i18n.RuStrings
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Parse an ISO timestamp produced by the collector (offset or UTC). */
internal fun parseInstant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (_: Exception) {
        try {
            Instant.parse(iso)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * `agy /usage` reports untouched 100% buckets with the epoch-zero sentinel
 * `1970-01-01T00:00:00Z`. Such a timestamp means "no reset scheduled", never
 * "resets now".
 */
private fun isEpochZero(instant: Instant?): Boolean =
    instant != null && instant.epochSecond <= 0L

internal fun statusAgeMinutes(iso: String?, nowMs: Long): Long? {
    val instant = parseInstant(iso) ?: return null
    return ((nowMs - instant.toEpochMilli()) / 60_000).coerceAtLeast(0)
}

internal fun updatedLabel(
    iso: String?,
    nowMs: Long,
    strings: AgStrings = RuStrings,
): String {
    val age = statusAgeMinutes(iso, nowMs) ?: return "—"
    return strings.updatedLabel(age)
}

private fun countdownText(totalSeconds: Long, strings: AgStrings): String {
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    return strings.countdown(days, hours, minutes)
}

internal fun formatCountdown(
    iso: String?,
    nowMs: Long,
    strings: AgStrings = RuStrings,
): String {
    val target = parseInstant(iso) ?: return ""
    if (isEpochZero(target)) return ""
    val totalSeconds = Duration.between(Instant.ofEpochMilli(nowMs), target).seconds
    if (totalSeconds <= 0) return strings.resetNow
    return countdownText(totalSeconds, strings)
}

internal fun formatCountdownShort(
    iso: String?,
    nowMs: Long,
    strings: AgStrings = RuStrings,
): String? {
    val target = parseInstant(iso) ?: return null
    if (isEpochZero(target)) return "—"
    val totalSeconds = Duration.between(Instant.ofEpochMilli(nowMs), target).seconds
    if (totalSeconds <= 0) return strings.resetNow
    return countdownText(totalSeconds, strings)
}

internal fun formatDateShort(iso: String): String {
    val instant = parseInstant(iso) ?: return "—"
    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    return String.format(Locale.US, "%02d.%02d", date.dayOfMonth, date.monthValue)
}

/** One decimal place with a trailing `.0` removed; always a dot (Locale.US). */
private fun oneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

/**
 * Context-window size: raw token counts below 1M (e.g. `524_288 -> "512K"`),
 * megabytes above it (e.g. `2_097_152 -> "2M"`).
 */
internal fun formatWindow(size: Int): String = when {
    size <= 0 -> "—"
    size == 1_048_576 || size == 1_000_000 -> "1M"
    size >= 1_000_000 -> oneDecimal(size / 1_048_576.0) + "M"
    size >= 1_024 -> "${(size / 1024.0).roundToInt()}K"
    else -> size.toString()
}

internal fun formatK(value: Int): String = when {
    value >= 999_500 -> oneDecimal(value / 1_000_000.0) + "M"
    value >= 1_000 -> "${(value / 1_000.0).roundToInt()}K"
    else -> value.toString()
}

internal fun formatUnits(value: Long): String = when {
    value >= 999_500_000L -> oneDecimal(value / 1_000_000_000.0) + "B"
    value >= 999_500L -> oneDecimal(value / 1_000_000.0) + "M"
    value >= 1_000L -> "${(value / 1_000.0).roundToInt()}K"
    else -> value.toString()
}

/** "claude-opus-5-5-high" -> "Claude Opus 5.5". */
internal fun prettyModel(raw: String): String {
    val efforts = setOf("low", "medium", "high", "xhigh", "max", "minimal")
    val parts = raw.split('-').filter { it.isNotBlank() }
    val trimmed = if (parts.size > 1 && parts.last().lowercase() in efforts) parts.dropLast(1) else parts
    val base = trimmed.joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    return Regex("""\b(\d) (\d)\b""").replace(base) { match -> "${match.groupValues[1]}.${match.groupValues[2]}" }
}

/**
 * Resolves the context level without ever turning an unknown wire level into
 * green: an explicit `green|yellow|red|unknown` wins, otherwise the thresholds
 * from the wire (falling back to 40/65) decide.
 */
internal fun contextLevel(
    pct: Double?,
    level: String?,
    yellowPct: Double? = null,
    redPct: Double? = null,
): String? {
    if (level == "green" || level == "yellow" || level == "red" || level == "unknown") return level
    if (pct == null || !pct.isFinite()) return null
    var yellow = yellowPct?.takeIf { it.isFinite() } ?: 40.0
    var red = redPct?.takeIf { it.isFinite() } ?: 65.0
    if (red <= yellow) {
        yellow = 40.0
        red = 65.0
    }
    return when {
        pct < yellow -> "green"
        pct < red -> "yellow"
        else -> "red"
    }
}
