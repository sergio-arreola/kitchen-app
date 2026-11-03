package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.identifiers.SketchId
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode

/** Reviewable, source-independent recipe draft produced by an import adapter. */
public data class CanonicalRecipeDraft(
    public val id: SketchId = SketchId.generate(),
    public val name: String,
    public val servings: String? = null,
    public val ingredients: List<CanonicalRecipeIngredient>,
    public val directionsText: String,
    public val notes: String? = null,
    public val categories: List<String> = emptyList(),
    public val sourceEntryName: String? = null,
    public val externalId: String? = null,
    public val sourceName: String? = null,
    public val sourceUrl: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Recipe name cannot be blank" }
    }
}

/**
 * Parsed ingredient data alongside the untouched input and explicit resolver
 * outcome, so import review never loses source wording.
 */
public data class CanonicalRecipeIngredient(
    public val rawLine: String,
    public val parsedName: String,
    public val quantity: Rational?,
    public val quantityHigh: Rational?,
    public val unit: UnitCode?,
    public val preparation: String?,
    public val importance: IngredientImportance,
    public val approximateLevel: ApproximateLevel,
    public val resolution: IngredientResolution,
) {
    init {
        require(rawLine.isNotBlank()) { "Raw ingredient line cannot be blank" }
        require(parsedName.isNotBlank()) { "Parsed ingredient name cannot be blank" }
        require(quantityHigh == null || quantity != null) {
            "An upper quantity bound requires a lower quantity"
        }
        require(quantityHigh == null || quantityHigh >= quantity!!) {
            "Upper quantity bound cannot be less than lower quantity"
        }
    }
}
