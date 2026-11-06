package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode

/** A canonical ingredient reference and quantity within a saved recipe. */
data class RecipeIngredient(
    val id: RecipeIngredientId = RecipeIngredientId.generate(),
    val ingredientId: IngredientId,
    val quantity: Rational? = null,
    val quantityHigh: Rational? = null,
    val unit: UnitCode? = null,
    val preparation: String? = null,
    val importance: IngredientImportance = IngredientImportance.CRITICAL,
    val approximateLevel: ApproximateLevel = ApproximateLevel.EXACT,
    /** Original imported line retained for traceability. */
    val rawLine: String,
) {
    init {
        require(rawLine.isNotBlank()) { "Raw ingredient line cannot be blank" }
        require(quantityHigh == null || quantity != null) {
            "An upper quantity bound requires a lower quantity"
        }
        require(quantityHigh == null || quantityHigh >= quantity!!) {
            "Upper quantity bound cannot be less than lower quantity"
        }
    }
}
