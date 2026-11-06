package dev.kitchenapp.core.domain.ingredient

/** Outcome of mapping imported ingredient text to the canonical ingredient set. */
sealed interface IngredientResolution {
    val input: String

    /** A unique canonical match. */
    data class Resolved(
        override val input: String,
        val ingredient: Ingredient,
    ) : IngredientResolution

    /** No canonical ingredient matched; the text remains available for review. */
    data class Unresolved(
        override val input: String,
        val normalizedInput: String,
    ) : IngredientResolution

    /** Multiple canonical ingredients matched; a person must choose. */
    data class NeedsReview(
        override val input: String,
        val candidates: List<Ingredient>,
    ) : IngredientResolution
}
