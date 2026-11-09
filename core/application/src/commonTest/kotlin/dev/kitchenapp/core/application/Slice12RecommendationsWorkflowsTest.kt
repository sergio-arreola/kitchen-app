package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.quantity.ReadinessStatus
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.core.engine.matching.MealQuery
import dev.kitchenapp.core.engine.matching.MatchExclusion
import dev.kitchenapp.core.engine.matching.RecipeMatchMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Slice12RecommendationsWorkflowsTest {
    @Test
    fun `structured filters use recipe categories time and servings`() {
        val eggs = Ingredient(name = "Eggs")
        val eligible = recipe(
            name = "Breakfast eggs",
            ingredientId = eggs.id,
            servings = "4",
            totalTimeSeconds = 20 * 60L,
            categories = listOf("Breakfast"),
        )
        val tooSlow = recipe(
            name = "Slow eggs",
            ingredientId = eggs.id,
            servings = "4",
            totalTimeSeconds = 45 * 60L,
            categories = listOf("Breakfast"),
        )
        val kitchen = FakeKitchen(KitchenSnapshot(ingredients = listOf(eggs), inventory = KitchenInventory()))
        val recipes = FakeRecipes(listOf(eligible, tooSlow))
        val metadata = mapOf(
            eligible.id to RecipeMatchMetadata(estimatedMinutes = 20, servings = 4),
            tooSlow.id to RecipeMatchMetadata(estimatedMinutes = 45, servings = 4),
        )

        val matches = GetSuggestions(recipes, kitchen).get(
            SuggestionOptions(
                query = MealQuery(
                    availableTimeMinutes = 30,
                    servings = 2,
                    categories = setOf("breakfast"),
                    requiredIngredients = setOf(eggs.id),
                ),
                metadata = metadata,
                asOfDate = today,
            ),
        )

        assertEquals(eligible.id, matches.first().recipe.id)
        assertTrue(matches.first().exclusions.isEmpty())
        assertEquals(ReadinessStatus.NOT_READY, matches.first().readiness)
        assertTrue(matches.any { it.recipe.id == tooSlow.id && it.exclusions.isNotEmpty() })
    }

    @Test
    fun `meal type and equipment metadata produce matcher exclusions`() {
        val ingredient = Ingredient(name = "Tomatoes")
        val dinnerRecipe = recipe(
            name = "Dinner stew",
            ingredientId = ingredient.id,
            servings = "4",
            totalTimeSeconds = 30 * 60L,
        ).copy(
            mealTypes = listOf("Dinner"),
            equipmentRequirements = listOf(
                EquipmentRequirement("Dutch oven", EquipmentRequirementKind.REQUIRED),
            ),
        )
        val breakfastRecipe = recipe(
            name = "Breakfast tomatoes",
            ingredientId = ingredient.id,
            servings = "2",
            totalTimeSeconds = 10 * 60L,
        ).copy(mealTypes = listOf("Breakfast"))
        val kitchen = FakeKitchen(KitchenSnapshot(ingredients = listOf(ingredient), inventory = KitchenInventory()))
        val metadata = listOf(dinnerRecipe, breakfastRecipe).associate { item ->
            item.id to RecipeMatchMetadata(
                mealTypes = item.mealTypes.toSet(),
                equipmentRequirements = item.equipmentRequirements,
            )
        }

        val matches = GetSuggestions(FakeRecipes(listOf(dinnerRecipe, breakfastRecipe)), kitchen).get(
            SuggestionOptions(query = MealQuery(mealTypes = setOf("dinner")), metadata = metadata, asOfDate = today),
        )

        assertTrue(matches.single { it.recipe.id == dinnerRecipe.id }.exclusions.contains(MatchExclusion.EQUIPMENT_UNAVAILABLE))
        assertTrue(matches.single { it.recipe.id == breakfastRecipe.id }.exclusions.contains(MatchExclusion.MEAL_TYPE_MISMATCH))
    }

    @Test
    fun `shopping workflow saves missing items and delegates check off and clear`() {
        val eggs = Ingredient(name = "Eggs")
        val recipe = recipe(
            name = "Omelet",
            ingredientId = eggs.id,
            servings = "2",
            totalTimeSeconds = 10 * 60L,
        )
        val kitchen = FakeKitchen(KitchenSnapshot(ingredients = listOf(eggs), inventory = KitchenInventory()))
        val matches = GetSuggestions(FakeRecipes(listOf(recipe)), kitchen).get(
            SuggestionOptions(asOfDate = today),
        )
        val repository = FakeShoppingLists()
        val saved = CreateShoppingList(kitchen, repository).create(matches.filter { it.exclusions.isEmpty() })

        assertEquals("Eggs", saved.items.single().ingredientName)
        assertEquals(Rational.of(2), saved.items.single().quantity)
        assertEquals(setOf(recipe.id), saved.items.single().sourceRecipeIds)
        assertEquals(saved, repository.getCurrent())

        assertTrue(SetShoppingListItemChecked(repository).update(saved.items.single().id, true))
        assertTrue(assertNotNull(repository.getCurrent()).items.single().checked)
        assertTrue(SetShoppingListItemChecked(repository).update(saved.items.single().id, false))
        assertFalse(assertNotNull(repository.getCurrent()).items.single().checked)
        ClearShoppingList(repository).clear()
        assertEquals(null, GetShoppingList(repository).get())
    }

    private class FakeKitchen(var snapshot: KitchenSnapshot) : KitchenRepository {
        override fun load(): KitchenSnapshot = snapshot
        override fun save(snapshot: KitchenSnapshot) {
            this.snapshot = snapshot
        }
    }

    private class FakeRecipes(private val recipes: List<Recipe>) : RecipeRepository {
        override fun get(id: dev.kitchenapp.core.domain.identifiers.RecipeId) =
            recipes.firstOrNull { it.id == id }

        override fun getAll(): List<Recipe> = recipes
        override fun save(recipe: Recipe) = Unit
        override fun delete(id: dev.kitchenapp.core.domain.identifiers.RecipeId) = Unit
        override fun getCategories(): List<String> = recipes.flatMap(Recipe::categories).distinct()
        override fun createCategory(name: String) = Unit
        override fun renameCategory(oldName: String, newName: String) = Unit
        override fun removeCategory(name: String) = Unit
    }

    private class FakeShoppingLists : ShoppingListRepository {
        private var current: dev.kitchenapp.core.domain.shopping.ShoppingList? = null

        override fun getCurrent() = current
        override fun saveCurrent(list: dev.kitchenapp.core.domain.shopping.ShoppingList) {
            current = list
        }
        override fun setItemChecked(
            itemId: dev.kitchenapp.core.domain.identifiers.ShoppingListItemId,
            checked: Boolean,
        ): Boolean {
            val list = current ?: return false
            val item = list.items.firstOrNull { it.id == itemId } ?: return false
            current = list.copy(items = list.items.map { if (it.id == itemId) item.copy(checked = checked) else it })
            return true
        }
        override fun clearCurrent() {
            current = null
        }
    }

    private fun recipe(
        name: String,
        ingredientId: IngredientId,
        servings: String,
        totalTimeSeconds: Long,
        categories: List<String> = emptyList(),
    ) = Recipe(
        name = name,
        servings = servings,
        ingredients = listOf(
            RecipeIngredient(
                ingredientId = ingredientId,
                quantity = Rational.of(2),
                unit = UnitCode.EGG,
                rawLine = "2 eggs",
            ),
        ),
        directionsText = "Cook.",
        categories = categories,
        totalTimeSeconds = totalTimeSeconds,
    )

    private companion object {
        val today = DomainLocalDate.of(2026, 11, 8)
    }
}
