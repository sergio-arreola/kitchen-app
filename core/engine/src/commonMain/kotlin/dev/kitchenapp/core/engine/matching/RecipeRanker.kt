package dev.kitchenapp.core.engine.matching

import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.enums.isRequired
import dev.kitchenapp.core.domain.household.EquipmentEvaluation
import dev.kitchenapp.core.domain.quantity.QuantityStatus
import dev.kitchenapp.core.domain.quantity.ReadinessStatus

/** Score: readiness (100/50/0) + coverage (0–20; available=2, uncertain=1) + use-soon bonus (importance 1–4, capped at 20) − preference scores − 5 per equipment warning. */
class RecipeRanker {
    fun rank(
        ingredients: List<IngredientExplanation>,
        readiness: ReadinessStatus,
        preferencePenalty: Int,
        equipment: EquipmentEvaluation,
    ): ScoreBreakdown {
        val readinessPoints = when (readiness) {
            ReadinessStatus.READY -> 100
            ReadinessStatus.NEEDS_CONFIRMATION -> 50
            ReadinessStatus.NOT_READY -> 0
        }
        val required = ingredients.filter { it.requirement.importance.isRequired() }
        val coverage = if (required.isEmpty()) 20 else {
            val coveredTwice = required.sumOf {
                when (it.quantity.status) {
                    QuantityStatus.AVAILABLE -> 2
                    QuantityStatus.UNCERTAIN -> 1
                    else -> 0
                }
            }
            coveredTwice * 10 / required.size
        }
        val useSoon = ingredients
            .filter { it.freshness.any { freshness -> freshness.status == FreshnessStatus.USE_SOON } }
            .distinctBy { it.requirement.ingredientId }
            .sumOf { importanceWeight(it.requirement.importance) }
            .coerceAtMost(20)
        return ScoreBreakdown(
            readiness = readinessPoints,
            ingredientCoverage = coverage,
            useSoonBonus = useSoon,
            preferencePenalty = preferencePenalty,
            equipmentWarningPenalty = equipment.warningCount * 5,
        )
    }

    private fun importanceWeight(importance: IngredientImportance): Int = when (importance) {
        IngredientImportance.CRITICAL -> 4
        IngredientImportance.IMPORTANT -> 3
        IngredientImportance.SUBSTITUTABLE -> 2
        IngredientImportance.OPTIONAL -> 1
    }
}
