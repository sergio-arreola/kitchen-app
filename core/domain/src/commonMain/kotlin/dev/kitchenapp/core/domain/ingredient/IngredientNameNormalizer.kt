package dev.kitchenapp.core.domain.ingredient

/**
 * Conservative, deterministic normalization for exact ingredient-name matching.
 *
 * It lowercases text, treats punctuation as word separators, and collapses
 * whitespace. It deliberately does not remove descriptive words, stem words,
 * or infer substitutions.
 */
public object IngredientNameNormalizer {
    public fun normalize(value: String): String {
        val normalized = buildString(value.length) {
            var previousWasSpace = true
            for (character in value.lowercase()) {
                if (character.isLetterOrDigit()) {
                    append(character)
                    previousWasSpace = false
                } else if (!previousWasSpace) {
                    append(' ')
                    previousWasSpace = true
                }
            }
        }
        return normalized.trim()
    }
}
