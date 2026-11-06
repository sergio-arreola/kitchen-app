package dev.kitchenapp.core.engine.matching

import dev.kitchenapp.core.domain.household.EquipmentEvaluation
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.evaluateEquipment
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.inventory.FreshnessPolicy
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.quantity.QuantityEvaluation
import dev.kitchenapp.core.domain.quantity.QuantityEvaluator
import dev.kitchenapp.core.domain.quantity.ReadinessStatus
import dev.kitchenapp.core.domain.quantity.evaluateReadiness
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.time.DomainLocalDate

/** Structured filters for meal recommendations. */
data class MealQuery(
    val mealTypes: Set<String> = emptySet(),
    val availableTimeMinutes: Int? = null,
    val servings: Int? = null,
    val categories: Set<String> = emptySet(),
    val requiredIngredients: Set<IngredientId> = emptySet(),
) {
    init {
        require(availableTimeMinutes == null || availableTimeMinutes >= 0)
        require(servings == null || servings > 0)
        require(mealTypes.none(String::isBlank))
        require(categories.none(String::isBlank))
    }
}

/** Matching metadata absent from [Recipe]. */
data class RecipeMatchMetadata(
    val mealTypes: Set<String> = emptySet(),
    val estimatedMinutes: Int? = null,
    val servings: Int? = null,
    val equipmentRequirements: List<EquipmentRequirement> = emptyList(),
) {
    init {
        require(estimatedMinutes == null || estimatedMinutes >= 0)
        require(servings == null || servings > 0)
    }
}

enum class CandidateStatus { INCLUDED, EXCLUDED }

enum class MatchExclusion {
    HARD_INGREDIENT_EXCLUSION,
    EQUIPMENT_UNAVAILABLE,
    MEAL_TYPE_MISMATCH,
    TIME_LIMIT,
    TIME_UNKNOWN,
    SERVINGS_MISMATCH,
    SERVINGS_UNKNOWN,
    CATEGORY_MISMATCH,
    REQUIRED_INGREDIENT_MISSING,
}

enum class FreshnessStatus { UNDATED, FRESH, USE_SOON, PAST_EFFECTIVE_DATE }

data class IngredientExplanation(
    val requirement: RecipeIngredient,
    val quantity: QuantityEvaluation,
    val freshness: List<FreshnessExplanation>,
)

data class FreshnessExplanation(
    val lot: InventoryLot,
    val status: FreshnessStatus,
    val effectiveUseBy: DomainLocalDate?,
)

data class ScoreBreakdown(
    val readiness: Int,
    val ingredientCoverage: Int,
    val useSoonBonus: Int,
    val preferencePenalty: Int,
    val equipmentWarningPenalty: Int,
) {
    val total: Int
        get() = readiness + ingredientCoverage + useSoonBonus -
            preferencePenalty - equipmentWarningPenalty
}

/** Decision and explanations for one recipe. */
data class RecipeMatch(
    val recipe: Recipe,
    val status: CandidateStatus,
    val exclusions: List<MatchExclusion>,
    val readiness: ReadinessStatus,
    val ingredients: List<IngredientExplanation>,
    val equipment: EquipmentEvaluation,
    val score: ScoreBreakdown,
)

/** Explicit state keeps matching deterministic and side-effect free. */
data class MealMatchInput(
    val recipes: List<Recipe>,
    val inventory: KitchenInventory,
    val availableEquipment: Set<String> = emptySet(),
    val householdRules: HouseholdIngredientRules = HouseholdIngredientRules(),
    val query: MealQuery = MealQuery(),
    val metadata: Map<RecipeId, RecipeMatchMetadata> = emptyMap(),
    val freshnessPolicies: Map<IngredientId, FreshnessPolicy> = emptyMap(),
    val asOfDate: DomainLocalDate,
    val useSoonWindowDays: Int = 3,
) {
    init {
        require(useSoonWindowDays >= 0)
        require(recipes.map { it.id }.distinct().size == recipes.size) { "Recipe IDs must be unique" }
    }
}

class RecipeMatcher(
    private val quantityEvaluator: QuantityEvaluator = QuantityEvaluator(),
    private val ranker: RecipeRanker = RecipeRanker(),
) {
    fun match(input: MealMatchInput): List<RecipeMatch> = input.recipes
        .map { recipe -> evaluate(recipe, input) }
        .sortedWith(
            compareByDescending<RecipeMatch> { it.status == CandidateStatus.INCLUDED }
                .thenByDescending { it.score.total }
                .thenBy { it.recipe.name.lowercase() }
                .thenBy { it.recipe.id.toString() },
        )

    private fun evaluate(recipe: Recipe, input: MealMatchInput): RecipeMatch {
        val metadata = input.metadata[recipe.id] ?: RecipeMatchMetadata()
        val recipeIngredientIds = recipe.ingredients.map { it.ingredientId }.toSet()
        val rules = input.householdRules.evaluate(recipeIngredientIds)
        val equipment = evaluateEquipment(metadata.equipmentRequirements, input.availableEquipment)
        val exclusions = buildList {
            if (!rules.isAllowed) add(MatchExclusion.HARD_INGREDIENT_EXCLUSION)
            if (!equipment.isAllowed) add(MatchExclusion.EQUIPMENT_UNAVAILABLE)
            val query = input.query
            if (query.mealTypes.isNotEmpty() && !metadata.mealTypes.matchesAny(query.mealTypes)) {
                add(MatchExclusion.MEAL_TYPE_MISMATCH)
            }
            if (query.availableTimeMinutes != null) {
                when {
                    metadata.estimatedMinutes == null -> add(MatchExclusion.TIME_UNKNOWN)
                    metadata.estimatedMinutes > query.availableTimeMinutes -> add(MatchExclusion.TIME_LIMIT)
                }
            }
            if (query.servings != null) {
                when {
                    metadata.servings == null -> add(MatchExclusion.SERVINGS_UNKNOWN)
                    metadata.servings < query.servings -> add(MatchExclusion.SERVINGS_MISMATCH)
                }
            }
            if (query.categories.isNotEmpty() && !recipe.categories.toSet().matchesAny(query.categories)) {
                add(MatchExclusion.CATEGORY_MISMATCH)
            }
            if (!recipeIngredientIds.containsAll(query.requiredIngredients)) {
                add(MatchExclusion.REQUIRED_INGREDIENT_MISSING)
            }
        }

        val explanations = recipe.ingredients.map { requirement ->
            val quantity = quantityEvaluator.evaluate(requirement, input.inventory.lots)
            val freshness = input.inventory.lots
                .filter { it.ingredientId == requirement.ingredientId }
                .map { lot -> freshnessExplanation(lot, requirement.ingredientId, input) }
            IngredientExplanation(requirement, quantity, freshness)
        }
        val readiness = evaluateReadiness(explanations.map { it.quantity }).status
        val score = ranker.rank(explanations, readiness, rules.softPreferencePenalty, equipment)
        return RecipeMatch(
            recipe = recipe,
            status = if (exclusions.isEmpty()) CandidateStatus.INCLUDED else CandidateStatus.EXCLUDED,
            exclusions = exclusions,
            readiness = readiness,
            ingredients = explanations,
            equipment = equipment,
            score = score,
        )
    }

    private fun freshnessExplanation(
        lot: InventoryLot,
        ingredientId: IngredientId,
        input: MealMatchInput,
    ): FreshnessExplanation {
        val policy = input.freshnessPolicies[ingredientId] ?: FreshnessPolicy()
        val guidance = policy.guidanceFor(lot)
        val effective = guidance.effectiveUseBy
        val status = when {
            effective == null -> FreshnessStatus.UNDATED
            effective.isBefore(input.asOfDate) -> FreshnessStatus.PAST_EFFECTIVE_DATE
            effective.isOnOrBefore(input.asOfDate.plusDays(input.useSoonWindowDays)) ->
                FreshnessStatus.USE_SOON
            else -> FreshnessStatus.FRESH
        }
        return FreshnessExplanation(lot, status, effective)
    }

    private fun Set<String>.matchesAny(queryValues: Set<String>): Boolean {
        val normalized = map { it.trim().lowercase() }.toSet()
        return queryValues.any { it.trim().lowercase() in normalized }
    }
}
