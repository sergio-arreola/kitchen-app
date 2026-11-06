package dev.kitchenapp.core.domain.quantity

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.core.domain.units.UnitDimension

/**
 * An explicitly reviewed conversion. The factor means target units per source
 * unit; ingredientId is required for conversions such as volume to mass.
 */
data class UnitConversionRule(
    val from: UnitCode,
    val to: UnitCode,
    val factor: Rational,
    val ingredientId: IngredientId? = null,
) {
    init {
        require(from != to) { "A conversion rule must change units" }
        require(!factor.isZero() && factor.isPositive()) { "Conversion factor must be positive" }
    }
}

/**
 * Deterministic unit conversion. Same-dimension conversions use exact declared
 * ratios; cross-dimension conversions require an ingredient-specific rule.
 * No universal volume-to-mass assumption is made.
 */
class UnitConverter(
    private val reviewedRules: List<UnitConversionRule> = emptyList(),
) {
    init {
        require(reviewedRules.distinct().size == reviewedRules.size) {
            "Conversion rules must be unique"
        }
    }

    fun convert(
        quantity: Rational,
        from: UnitCode,
        to: UnitCode,
        ingredientId: IngredientId? = null,
    ): Rational? {
        if (from == to) return quantity

        val explicit = findReviewedRule(from, to, ingredientId)
        if (explicit != null) return quantity * explicit.factor

        val reverse = findReviewedRule(to, from, ingredientId)
        if (reverse != null) return quantity / reverse.factor

        val fromRatio = unitsPerBase(from) ?: return null
        val toRatio = unitsPerBase(to) ?: return null
        if (from.dimension != to.dimension) return null
        return quantity * fromRatio / toRatio
    }

    private fun findReviewedRule(
        from: UnitCode,
        to: UnitCode,
        ingredientId: IngredientId?,
    ): UnitConversionRule? = reviewedRules
        .filter {
            it.from == from &&
                it.to == to &&
                (it.ingredientId == null || it.ingredientId == ingredientId)
        }
        .sortedByDescending { it.ingredientId != null }
        .firstOrNull()

    private fun unitsPerBase(unit: UnitCode): Rational? = when (unit) {
        UnitCode.GRAM -> Rational.of(1)
        UnitCode.KILOGRAM -> Rational.of(1000)
        UnitCode.OUNCE -> Rational.of(283495, 10000)
        UnitCode.POUND -> Rational.of(45359237, 100000)
        UnitCode.MILLILITER -> Rational.of(1)
        UnitCode.CUP -> Rational.of(240)
        UnitCode.TABLESPOON -> Rational.of(15)
        UnitCode.TEASPOON -> Rational.of(5)
        UnitCode.SECOND -> Rational.of(1)
        UnitCode.MINUTE -> Rational.of(60)
        UnitCode.HOUR -> Rational.of(3600)
        UnitCode.CALORIE -> Rational.of(1)
        UnitCode.KILOCALORIE -> Rational.of(1000)
        UnitCode.METER -> Rational.of(1)
        UnitCode.CENTIMETER -> Rational.of(1, 100)
        UnitCode.INCH -> Rational.of(254, 10000)
        UnitCode.FOOT -> Rational.of(3048, 10000)
        UnitCode.SQUARE_METER -> Rational.of(1)
        UnitCode.SQUARE_INCH -> Rational.of(64516, 100000000)
        UnitCode.PIECE,
        UnitCode.EGG,
        UnitCode.CLOVE,
        UnitCode.SERVING,
        UnitCode.CELSIUS,
        UnitCode.FAHRENHEIT,
        UnitCode.KELVIN,
        UnitCode.UNKNOWN -> null
    }.takeIf { unit.dimension != UnitDimension.UNKNOWN }
}
