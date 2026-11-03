package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode

/** A canonical ingredient reference and quantity within a saved recipe. */
public data class RecipeIngredient(
    public val id: RecipeIngredientId = RecipeIngredientId.generate(),
    public val ingredientId: IngredientId,
    public val quantity: Rational? = null,
    public val quantityHigh: Rational? = null,
    public val unit: UnitCode? = null,
    public val preparation: String? = null,
    public val importance: IngredientImportance = IngredientImportance.CRITICAL,
    public val approximateLevel: ApproximateLevel = ApproximateLevel.EXACT,
    /** Original imported line retained for traceability. */
    public val rawLine: String,
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
