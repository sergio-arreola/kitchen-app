package dev.kitchenapp.core.engine.matching

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.quantity.QuantityStatus
import dev.kitchenapp.core.domain.quantity.UnitConverter
import dev.kitchenapp.core.domain.units.UnitCode

/** Unconvertible units stay on separate shopping lines. */
data class ShoppingLine(
    val ingredientId: IngredientId,
    val quantity: Rational?,
    val unit: UnitCode?,
    val recipeIds: Set<RecipeId>,
)

class ShoppingAggregator(
    private val converter: UnitConverter = UnitConverter(),
) {
    /** Aggregates missing items from eligible recipes; unknown amounts stay null. */
    fun aggregate(selectedRecipes: List<RecipeMatch>): List<ShoppingLine> {
        val groups = mutableListOf<MutableLine>()
        selectedRecipes
            .filter { it.status == CandidateStatus.INCLUDED }
            .sortedWith(compareBy({ it.recipe.name.lowercase() }, { it.recipe.id.toString() }))
            .forEach { match ->
                match.ingredients.forEach ingredient@{ explanation ->
                    val reason = explanation.quantity.reason
                    if (explanation.quantity.status != QuantityStatus.MISSING &&
                        explanation.quantity.status != QuantityStatus.INSUFFICIENT
                    ) return@ingredient
                    val amount = remainingAmount(reason.required, reason.knownAvailable)
                    val group = groups.firstOrNull {
                        it.ingredientId == explanation.requirement.ingredientId &&
                            unitsCompatible(it.unit, explanation.requirement.unit, it.ingredientId)
                    } ?: MutableLine(
                        ingredientId = explanation.requirement.ingredientId,
                        unit = explanation.requirement.unit,
                    ).also(groups::add)
                    group.add(amount, explanation.requirement.unit, match.recipe.id)
                }
            }
        return groups
            .map(MutableLine::toShoppingLine)
            .sortedWith(compareBy({ it.ingredientId.toString() }, { it.unit?.code.orEmpty() }))
    }

    private fun remainingAmount(required: Rational?, available: Rational?): Rational? {
        val needed = required ?: return null
        // QuantityReason stores known stock in the recipe requirement unit.
        return (needed - (available ?: Rational.of(0))).takeIf { it.isPositive() }
    }

    private fun unitsCompatible(from: UnitCode?, to: UnitCode?, ingredientId: IngredientId): Boolean =
        when {
            from == to -> true
            from == null || to == null -> false
            else -> converter.convert(Rational.of(1), from, to, ingredientId) != null
        }

    private inner class MutableLine(
        val ingredientId: IngredientId,
        val unit: UnitCode?,
    ) {
        private var total: Rational? = Rational.of(0)
        private var hasUnknownAmount: Boolean = false
        private val recipes = linkedSetOf<RecipeId>()

        fun add(amount: Rational?, fromUnit: UnitCode?, recipeId: RecipeId) {
            recipes += recipeId
            if (amount == null) {
                hasUnknownAmount = true
                return
            }
            val converted = when {
                fromUnit == unit -> amount
                fromUnit == null || unit == null -> null
                else -> converter.convert(amount, fromUnit, unit, ingredientId)
            }
            if (converted == null) {
                hasUnknownAmount = true
            } else {
                total = total?.plus(converted)
            }
        }

        fun toShoppingLine(): ShoppingLine = ShoppingLine(
            ingredientId = ingredientId,
            quantity = total.takeUnless { hasUnknownAmount },
            unit = unit,
            recipeIds = recipes.toSet(),
        )
    }
}
