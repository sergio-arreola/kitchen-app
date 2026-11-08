package dev.kitchenapp.data.importers

import kotlin.math.roundToLong

private val isoDuration = Regex(
    """^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""",
    RegexOption.IGNORE_CASE,
)
private val humanDurationPart = Regex(
    """(\d+(?:\.\d+)?)\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""",
    RegexOption.IGNORE_CASE,
)
private val humanRange = Regex(
    """\d+(?:\.\d+)?\s*(?:-|to)\s*\d+(?:\.\d+)?\s*(?:days?|hours?|hrs?|minutes?|mins?|seconds?|secs?)""",
    RegexOption.IGNORE_CASE,
)

/** Parses exact Schema.org durations and human time labels into whole seconds. */
fun parseRecipeTimeSeconds(value: String?): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    isoDuration.matchEntire(text)?.let { match ->
        val components = match.groupValues.drop(1).map(String::toDoubleOrNull)
        if (components.all { it == null }) return null
        val seconds = (components[0] ?: 0.0) * 86_400 +
            (components[1] ?: 0.0) * 3_600 +
            (components[2] ?: 0.0) * 60 +
            (components[3] ?: 0.0)
        return seconds.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE }?.roundToLong()
    }
    if (text.startsWith('P', ignoreCase = true) || humanRange.containsMatchIn(text)) return null

    val matches = humanDurationPart.findAll(text).toList()
    if (matches.isEmpty()) return null
    val totalSeconds = matches.sumOf { match ->
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val multiplier = when {
            unit.startsWith("d") -> 86_400.0
            unit.startsWith("h") -> 3_600.0
            unit.startsWith("m") -> 60.0
            else -> 1.0
        }
        amount * multiplier
    }
    return totalSeconds.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE }?.roundToLong()
}
