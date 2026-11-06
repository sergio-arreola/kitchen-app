package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.inventory.FreshnessPolicy
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.engine.matching.MealMatchInput
import dev.kitchenapp.core.engine.matching.MealQuery
import dev.kitchenapp.core.engine.matching.RecipeMatch
import dev.kitchenapp.core.engine.matching.RecipeMatchMetadata
import dev.kitchenapp.core.engine.matching.RecipeMatcher
import dev.kitchenapp.core.engine.matching.ShoppingAggregator
import dev.kitchenapp.core.engine.matching.ShoppingLine

data class SuggestionOptions(
    val query: MealQuery = MealQuery(),
    val metadata: Map<RecipeId, RecipeMatchMetadata> = emptyMap(),
    val freshnessPolicies: Map<IngredientId, FreshnessPolicy> = emptyMap(),
    val asOfDate: DomainLocalDate,
)

/** Read-only recipe browse and search. */
class SearchRecipes(private val recipes: RecipeRepository) {
    fun search(query: String): List<Recipe> {
        val term = query.trim()
        return recipes.getAll().filter { term.isEmpty() || it.name.contains(term, ignoreCase = true) }
    }
}

/** Suggestions are computed from a loaded snapshot and never mutate it. */
class GetSuggestions(
    private val recipes: RecipeRepository,
    private val kitchen: KitchenRepository,
    private val matcher: RecipeMatcher = RecipeMatcher(),
) {
    fun get(options: SuggestionOptions): List<RecipeMatch> {
        val snapshot = kitchen.load()
        return matcher.match(
            MealMatchInput(
                recipes = recipes.getAll(),
                inventory = snapshot.inventory,
                availableEquipment = snapshot.availableEquipment,
                householdRules = snapshot.householdRules,
                query = options.query,
                metadata = options.metadata,
                freshnessPolicies = options.freshnessPolicies,
                asOfDate = options.asOfDate,
            ),
        )
    }
}

/** Persist one selected recipe set as a shopping list using the existing deterministic aggregator. */
class CreateShoppingList(
    private val aggregator: ShoppingAggregator = ShoppingAggregator(),
) {
    fun create(selectedRecipes: List<RecipeMatch>): List<ShoppingLine> =
        aggregator.aggregate(selectedRecipes)
}

/** Replaces editable inventory while preserving repository validation and atomic persistence. */
class EditInventory(private val kitchen: KitchenRepository) {
    fun update(transform: (KitchenSnapshot) -> KitchenSnapshot): KitchenSnapshot {
        val updated = transform(kitchen.load())
        kitchen.save(updated)
        return updated
    }
}

/** Saves equipment and household rules as one kitchen snapshot update. */
class ConfigureKitchen(private val kitchen: KitchenRepository) {
    fun update(
        equipment: Set<String>,
        rules: HouseholdIngredientRules,
    ): KitchenSnapshot {
        val updated = kitchen.load().copy(availableEquipment = equipment, householdRules = rules)
        kitchen.save(updated)
        return updated
    }
}

/** Stores a recipe after import review has resolved every ingredient to kitchen identities. */
class SaveReviewedRecipe(
    private val recipes: RecipeRepository,
    private val kitchen: KitchenRepository,
) {
    fun save(recipe: Recipe): Recipe {
        val knownIngredients = kitchen.load().ingredients.map { it.id }.toSet()
        require(recipe.ingredients.all { it.ingredientId in knownIngredients }) {
            "Review every recipe ingredient against the kitchen before saving"
        }
        recipes.save(recipe)
        return recipe
    }
}
