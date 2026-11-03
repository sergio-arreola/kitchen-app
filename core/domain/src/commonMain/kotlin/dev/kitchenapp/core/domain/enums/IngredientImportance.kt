package dev.kitchenapp.core.domain.enums

/**
 * Importance level for an ingredient within a recipe.
 *
 * Indicates how critical the ingredient is to the recipe's success.
 */
enum class IngredientImportance(val value: Int) {
    /** The recipe fails without this ingredient. */
    CRITICAL(0),

    /** Significantly affects quality but has workable substitutes. */
    IMPORTANT(1),

    /** Adds value but is not essential. */
    OPTIONAL(2),

    /** Easily replaced by another ingredient. */
    SUBSTITUTABLE(3);

    companion object {
        fun fromValue(value: Int): IngredientImportance? = entries.find { it.value == value }
    }
}

/** True when the recipe depends on an ingredient at this level. */
fun IngredientImportance.isRequired(): Boolean =
    this == IngredientImportance.CRITICAL || this == IngredientImportance.IMPORTANT

/** True when substitution is acceptable for this level. */
fun IngredientImportance.isSubstitutable(): Boolean = this == IngredientImportance.SUBSTITUTABLE
