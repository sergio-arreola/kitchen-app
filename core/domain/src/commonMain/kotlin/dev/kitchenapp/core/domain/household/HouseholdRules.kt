package dev.kitchenapp.core.domain.household

import dev.kitchenapp.core.domain.identifiers.IngredientId

enum class PreferenceStrength { SOFT }

enum class HouseholdIngredientRuleKind { ALLERGEN, AVOID, DISLIKE }

data class IngredientPreference(
    val ingredientId: IngredientId,
    val strength: PreferenceStrength = PreferenceStrength.SOFT,
    val score: Int = 1,
) {
    init {
        require(score > 0) { "Preference score must be positive" }
    }
}

/** Ingredient exclusions always block; preferences can only add a ranking penalty. */
data class HouseholdIngredientRules(
    val hardExclusions: Set<IngredientId> = emptySet(),
    val preferences: List<IngredientPreference> = emptyList(),
    val allergens: Set<IngredientId> = emptySet(),
) {
    init {
        require(preferences.groupBy { it.ingredientId }.values.all { group -> group.size == 1 }) {
            "Each ingredient can have at most one preference"
        }
        require(preferences.none { it.ingredientId in hardExclusions || it.ingredientId in allergens }) {
            "An allergen or excluded ingredient cannot also be a preference"
        }
        require(allergens.intersect(hardExclusions).isEmpty()) {
            "An ingredient cannot be both an allergen and an avoid rule"
        }
    }

    fun evaluate(ingredients: Set<IngredientId>): IngredientRuleEvaluation {
        val excluded = ingredients.intersect(hardExclusions + allergens)
        val matchedPreferences = preferences.filter { it.ingredientId in ingredients }
        return IngredientRuleEvaluation(
            isAllowed = excluded.isEmpty(),
            excludedIngredients = excluded,
            softPreferencePenalty = matchedPreferences.sumOf { it.score },
            dislikedIngredients = matchedPreferences.map { it.ingredientId }.toSet(),
        )
    }
}

data class IngredientRuleEvaluation(
    val isAllowed: Boolean,
    val excludedIngredients: Set<IngredientId>,
    val softPreferencePenalty: Int,
    val dislikedIngredients: Set<IngredientId>,
)

enum class EquipmentRequirementKind { REQUIRED, PREFERRED, SUBSTITUTABLE, IRRELEVANT }

data class EquipmentRequirement(
    val equipment: String,
    val kind: EquipmentRequirementKind,
    val acceptableSubstitutes: Set<String> = emptySet(),
) {
    init {
        require(equipment.isNotBlank()) { "Equipment name cannot be blank" }
        require(acceptableSubstitutes.none { it.isBlank() }) { "Equipment substitute cannot be blank" }
        require(kind == EquipmentRequirementKind.SUBSTITUTABLE || acceptableSubstitutes.isEmpty()) {
            "Only substitutable requirements can declare substitutes"
        }
        require(acceptableSubstitutes.none { it.equals(equipment, ignoreCase = true) }) {
            "An item cannot be its own substitute"
        }
    }
}

enum class EquipmentStatus { SATISFIED, WARNING, BLOCKED, IRRELEVANT }

data class EquipmentReason(
    val requested: String,
    val status: EquipmentStatus,
    val satisfiedBy: String? = null,
)

data class EquipmentEvaluation(
    val isAllowed: Boolean,
    val warningCount: Int,
    val reasons: List<EquipmentReason>,
)

fun evaluateEquipment(
    requirements: List<EquipmentRequirement>,
    availableEquipment: Set<String>,
): EquipmentEvaluation {
    val available = availableEquipment.map { it.trim().lowercase() }.toSet()
    val reasons = requirements.map { requirement ->
        val requestedAvailable = requirement.equipment.trim().lowercase() in available
        val substitute = requirement.acceptableSubstitutes
            .sortedWith(compareBy({ it.trim().lowercase() }, { it }))
            .firstOrNull { it.trim().lowercase() in available }
        when (requirement.kind) {
            EquipmentRequirementKind.IRRELEVANT ->
                EquipmentReason(requirement.equipment, EquipmentStatus.IRRELEVANT)
            EquipmentRequirementKind.REQUIRED ->
                EquipmentReason(
                    requirement.equipment,
                    if (requestedAvailable) EquipmentStatus.SATISFIED else EquipmentStatus.BLOCKED,
                    requirement.equipment.takeIf { requestedAvailable },
                )
            EquipmentRequirementKind.PREFERRED ->
                EquipmentReason(
                    requirement.equipment,
                    if (requestedAvailable) EquipmentStatus.SATISFIED else EquipmentStatus.WARNING,
                    requirement.equipment.takeIf { requestedAvailable },
                )
            EquipmentRequirementKind.SUBSTITUTABLE ->
                when {
                    requestedAvailable -> EquipmentReason(requirement.equipment, EquipmentStatus.SATISFIED, requirement.equipment)
                    substitute != null -> EquipmentReason(requirement.equipment, EquipmentStatus.SATISFIED, substitute)
                    else -> EquipmentReason(requirement.equipment, EquipmentStatus.BLOCKED)
                }
        }
    }
    return EquipmentEvaluation(
        isAllowed = reasons.none { it.status == EquipmentStatus.BLOCKED },
        warningCount = reasons.count { it.status == EquipmentStatus.WARNING },
        reasons = reasons,
    )
}
