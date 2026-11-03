package dev.kitchenapp.data.importers.ingredient

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode

/**
 * Structured result of parsing one raw ingredient line. Lossless: a line
 * that resists parsing keeps its full text in [name] with quantity/unit null.
 */
data class ParsedIngredient(
    /** Ingredient name; never blank. */
    val name: String,
    /** Parsed quantity; null when the line has none. */
    val quantity: Rational? = null,
    /** Upper bound of a quantity range; otherwise null. */
    val quantityHigh: Rational? = null,
    /** Recognized unit; null when none. */
    val unit: UnitCode? = null,
    /** Trailing clause after a comma ("2 tbsp butter, melted" → "melted"). */
    val preparation: String? = null,
    /** Importance; defaults to [IngredientImportance.CRITICAL]. */
    val importance: IngredientImportance = IngredientImportance.CRITICAL,
    /** Approximation level derived from the line's phrasing. */
    val approximateLevel: ApproximateLevel = ApproximateLevel.EXACT,
)