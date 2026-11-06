package dev.kitchenapp.core.domain.quantity

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.enums.isRequired
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.units.UnitCode

enum class QuantityStatus {
    AVAILABLE,
    INSUFFICIENT,
    MISSING,
    UNCERTAIN,
    NOT_REQUIRED,
}

data class QuantityReason(
    val status: QuantityStatus,
    val message: String,
    val required: Rational? = null,
    val knownAvailable: Rational? = null,
    val unit: UnitCode? = null,
)

data class QuantityEvaluation(
    val ingredientId: IngredientId,
    val importance: IngredientImportance,
    val reason: QuantityReason,
) {
    val status: QuantityStatus get() = reason.status
}

/** Evaluates one recipe line against all lots for its canonical ingredient. */
class QuantityEvaluator(
    private val converter: UnitConverter = UnitConverter(),
) {
    fun evaluate(
        requirement: RecipeIngredient,
        lots: List<InventoryLot>,
    ): QuantityEvaluation {
        val matching = lots.filter { it.ingredientId == requirement.ingredientId }
        if (!requirement.importance.isRequired()) {
            return result(requirement, QuantityStatus.NOT_REQUIRED, "Ingredient is optional.")
        }
        if (matching.isEmpty()) {
            return result(requirement, QuantityStatus.MISSING, "No inventory lot is recorded.")
        }
        val needed = requirement.quantity
        if (needed == null) {
            return result(requirement, QuantityStatus.AVAILABLE, "Ingredient is present; no amount is specified.")
        }

        var exact = Rational.of(0)
        var approximate = Rational.of(0)
        var hasPresenceOnly = false
        var hasUnconvertible = false
        matching.forEach { lot ->
            when (val amount = lot.amount) {
                InventoryAmount.PresenceOnly -> hasPresenceOnly = true
                is InventoryAmount.Exact -> {
                    val converted = convertAmount(amount.quantity, amount.unit, requirement.unit, requirement.ingredientId)
                    if (converted == null) hasUnconvertible = true else exact += converted
                }
                is InventoryAmount.Approximate -> {
                    val converted = convertAmount(amount.quantity, amount.unit, requirement.unit, requirement.ingredientId)
                    if (converted == null) hasUnconvertible = true else approximate += converted
                }
            }
        }

        val upperNeed = requirement.quantityHigh ?: needed
        if (
            exact >= upperNeed &&
            (requirement.quantityHigh != null || requirement.approximateLevel == ApproximateLevel.EXACT)
        ) {
            return result(requirement, QuantityStatus.AVAILABLE, "Exact stock covers the required amount.", upperNeed, exact)
        }
        if (
            !approximate.isZero() ||
            exact + approximate >= needed ||
            hasPresenceOnly ||
            hasUnconvertible ||
            requirement.quantityHigh != null ||
            requirement.approximateLevel != ApproximateLevel.EXACT
        ) {
            val known = exact + approximate
            return result(
                requirement,
                QuantityStatus.UNCERTAIN,
                "Recorded stock or required amount is uncertain; confirm the amount.",
                upperNeed,
                known.takeIf { !it.isZero() },
            )
        }
        if (exact.isZero() && approximate.isZero()) {
            return result(requirement, QuantityStatus.INSUFFICIENT, "No measurable compatible amount is recorded.", needed)
        }
        return result(requirement, QuantityStatus.INSUFFICIENT, "Exact stock is below the required amount.", needed, exact)
    }

    private fun convertAmount(
        quantity: Rational,
        from: UnitCode?,
        to: UnitCode?,
        ingredientId: IngredientId,
    ): Rational? = when {
        from == null && to == null -> quantity
        from != null && to != null -> converter.convert(quantity, from, to, ingredientId)
        else -> null
    }

    private fun result(
        requirement: RecipeIngredient,
        status: QuantityStatus,
        message: String,
        required: Rational? = requirement.quantity,
        available: Rational? = null,
    ) = QuantityEvaluation(
        ingredientId = requirement.ingredientId,
        importance = requirement.importance,
        reason = QuantityReason(status, message, required, available, requirement.unit),
    )
}

enum class ReadinessStatus {
    READY,
    NEEDS_CONFIRMATION,
    NOT_READY,
}

data class ReadinessReason(
    val ingredientId: IngredientId,
    val quantityReason: QuantityReason,
)

data class RecipeReadinessEvaluation(
    val status: ReadinessStatus,
    val reasons: List<ReadinessReason>,
)

fun evaluateReadiness(evaluations: List<QuantityEvaluation>): RecipeReadinessEvaluation {
    val required = evaluations.filter { it.status != QuantityStatus.NOT_REQUIRED }
    val status = when {
        required.any { it.status == QuantityStatus.MISSING || it.status == QuantityStatus.INSUFFICIENT } ->
            ReadinessStatus.NOT_READY
        required.any { it.status == QuantityStatus.UNCERTAIN } -> ReadinessStatus.NEEDS_CONFIRMATION
        else -> ReadinessStatus.READY
    }
    return RecipeReadinessEvaluation(
        status = status,
        reasons = required.filter { it.status != QuantityStatus.AVAILABLE }
            .map { ReadinessReason(it.ingredientId, it.reason) },
    )
}
