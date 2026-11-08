package dev.kitchenapp.core.domain.recipe

private val leadingServes = Regex("(?i)^serves?\\s+")
private val trailingServing = Regex("(?i)\\s+servings?$")
private val repeatedWhitespace = Regex("\\s+")

/** Collapses equivalent source yield values while retaining the clearest supplied text. */
fun normalizeRecipeServings(values: Iterable<String>): String? {
    val normalizedByKey = linkedMapOf<String, String>()
    values.flatMap { it.split(',') }
        .map { it.trim().replace(repeatedWhitespace, " ") }
        .filter(String::isNotBlank)
        .forEach { raw ->
            val value = raw.replace(leadingServes, "").trim()
            val key = value.replace(trailingServing, "").trim().lowercase()
            val existing = normalizedByKey[key]
            if (existing == null || (!existing.endsWithServingUnit() && value.endsWithServingUnit())) {
                normalizedByKey[key] = value
            }
        }
    return normalizedByKey.values.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** Presents old and new recipe values without duplicating a servings label. */
fun formatRecipeServings(value: String?): String? {
    val normalized = value?.let { normalizeRecipeServings(listOf(it)) } ?: return null
    return if (normalized.containsServingUnit()) "Servings: $normalized" else "Serves $normalized"
}

private fun String.endsWithServingUnit(): Boolean = trailingServing.containsMatchIn(this)

private fun String.containsServingUnit(): Boolean =
    Regex("(?i)\\bservings?\\b").containsMatchIn(this)
