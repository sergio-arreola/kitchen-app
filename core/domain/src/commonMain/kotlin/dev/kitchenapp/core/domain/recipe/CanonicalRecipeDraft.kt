package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.identifiers.SketchId
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode

/** Reviewable, source-independent recipe draft produced by an import adapter. */
data class CanonicalRecipeDraft(
    val id: SketchId = SketchId.generate(),
    val name: String,
    val servings: String? = null,
    val ingredients: List<CanonicalRecipeIngredient>,
    val directionsText: String,
    val notes: String? = null,
    val categories: List<String> = emptyList(),
    val sourceEntryName: String? = null,
    val externalId: String? = null,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val prepTimeSeconds: Long? = null,
    val cookTimeSeconds: Long? = null,
    val chillingTimeSeconds: Long? = null,
    val totalTimeSeconds: Long? = null,
    val imageSourceUrl: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Recipe name cannot be blank" }
    }
}

/**
 * Parsed ingredient data alongside the untouched input and explicit resolver
 * outcome, so import review never loses source wording.
 */
data class CanonicalRecipeIngredient(
    val rawLine: String,
    val parsedName: String,
    val quantity: Rational?,
    val quantityHigh: Rational?,
    val unit: UnitCode?,
    val preparation: String?,
    val importance: IngredientImportance,
    val approximateLevel: ApproximateLevel,
    val resolution: IngredientResolution,
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
