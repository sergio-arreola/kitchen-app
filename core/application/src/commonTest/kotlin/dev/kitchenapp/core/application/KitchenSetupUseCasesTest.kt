package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KitchenSetupUseCasesTest {
    @Test
    fun `inventory edits load and persist a complete updated kitchen snapshot`() {
        val ingredient = Ingredient(name = "Rice")
        val pantry = KitchenLocation(name = "Pantry")
        val original = KitchenSnapshot(ingredients = listOf(ingredient))
        val repository = FakeKitchenRepository(original)
        val useCase = EditInventory(repository)

        assertEquals(original, useCase.get())

        val updated = useCase.update { state ->
            state.copy(
                inventory = KitchenInventory(locations = listOf(pantry)),
                availableEquipment = setOf("Rice cooker"),
            )
        }

        assertEquals(updated, repository.snapshot)
        assertEquals(listOf(pantry), repository.snapshot.inventory.locations)
    }

    @Test
    fun `kitchen configuration normalizes equipment and preserves other kitchen state`() {
        val ingredient = Ingredient(name = "Peanut")
        val pantry = KitchenLocation(name = "Pantry")
        val original = KitchenSnapshot(
            ingredients = listOf(ingredient),
            inventory = KitchenInventory(locations = listOf(pantry)),
        )
        val repository = FakeKitchenRepository(original)

        val updated = ConfigureKitchen(repository).update(
            equipment = setOf(" Oven ", "oven", "Stand mixer"),
            rules = HouseholdIngredientRules(hardExclusions = setOf(ingredient.id)),
        )

        assertEquals(setOf("Oven", "Stand mixer"), updated.availableEquipment)
        assertEquals(setOf(ingredient.id), updated.householdRules.hardExclusions)
        assertEquals(original.ingredients, updated.ingredients)
        assertEquals(original.inventory, updated.inventory)
    }

    @Test
    fun `configuration stores preferences without making them hard exclusions`() {
        val ingredient = Ingredient(name = "Mushroom")
        val repository = FakeKitchenRepository(KitchenSnapshot(ingredients = listOf(ingredient)))

        val updated = ConfigureKitchen(repository).update(
            equipment = emptySet(),
            rules = HouseholdIngredientRules(
                preferences = listOf(IngredientPreference(ingredient.id, score = 2)),
            ),
        )

        assertEquals(listOf(IngredientPreference(ingredient.id, score = 2)), updated.householdRules.preferences)
        assertEquals(emptySet(), updated.householdRules.hardExclusions)
    }

    @Test
    fun `unused canonical ingredients can be deleted`() {
        val ingredient = Ingredient(name = "Unused spice")
        val kitchen = FakeKitchenRepository(KitchenSnapshot(ingredients = listOf(ingredient)))

        assertTrue(DeleteCanonicalIngredient(FakeRecipes(emptyList()), kitchen).delete(ingredient.id))
        assertEquals(emptyList(), kitchen.snapshot.ingredients)
    }

    @Test
    fun `canonical ingredients used by recipes cannot be deleted`() {
        val ingredient = Ingredient(name = "Rice")
        val recipe = Recipe(
            name = "Rice bowl",
            ingredients = listOf(RecipeIngredient(ingredientId = ingredient.id, rawLine = "rice")),
            directionsText = "Cook rice.",
        )
        val kitchen = FakeKitchenRepository(KitchenSnapshot(ingredients = listOf(ingredient)))

        assertFailsWith<IllegalArgumentException> {
            DeleteCanonicalIngredient(FakeRecipes(listOf(recipe)), kitchen).delete(ingredient.id)
        }
        assertEquals(listOf(ingredient), kitchen.snapshot.ingredients)
    }

    private class FakeRecipes(private val values: List<Recipe>) : RecipeRepository {
        override fun get(id: RecipeId): Recipe? = values.firstOrNull { it.id == id }
        override fun getAll(): List<Recipe> = values
        override fun save(recipe: Recipe) = Unit
        override fun delete(id: RecipeId) = Unit
        override fun getCategories(): List<String> = values.flatMap { it.categories }.distinct()
        override fun createCategory(name: String) = Unit
        override fun renameCategory(oldName: String, newName: String) = Unit
        override fun removeCategory(name: String) = Unit
    }

    private class FakeKitchenRepository(var snapshot: KitchenSnapshot) : KitchenRepository {
        override fun load(): KitchenSnapshot = snapshot

        override fun save(snapshot: KitchenSnapshot) {
            this.snapshot = snapshot
        }
    }
}
