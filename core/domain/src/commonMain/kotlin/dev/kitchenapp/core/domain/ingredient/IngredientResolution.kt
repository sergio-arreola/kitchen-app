package dev.kitchenapp.core.domain.ingredient

/** Outcome of mapping imported ingredient text to the canonical ingredient set. */
public sealed interface IngredientResolution {
    public val input: String

    /** A unique canonical match. */
    public data class Resolved(
        override val input: String,
        public val ingredient: Ingredient,
    ) : IngredientResolution

    /** No canonical ingredient matched; the text remains available for review. */
    public data class Unresolved(
        override val input: String,
        public val normalizedInput: String,
    ) : IngredientResolution

    /** Multiple canonical ingredients matched; a person must choose. */
    public data class NeedsReview(
        override val input: String,
        public val candidates: List<Ingredient>,
    ) : IngredientResolution
}
