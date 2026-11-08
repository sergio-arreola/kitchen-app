package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Slice10WorkflowsTest {
    @Test
    fun `browsing and starting a recipe never change inventory`() {
        val ingredientId = IngredientId.generate()
        val recipe = Recipe(
            name = "Egg toast",
            ingredients = listOf(
                RecipeIngredient(ingredientId = ingredientId, quantity = Rational.of(2), unit = UnitCode.EGG, rawLine = "2 eggs"),
            ),
            directionsText = "Cook eggs.",
        )
        val location = KitchenLocation(name = "Fridge")
        val lot = InventoryLot(
            ingredientId = ingredientId,
            locationId = location.id,
            amount = InventoryAmount.Exact(Rational.of(3), UnitCode.EGG),
        )
        val kitchen = FakeKitchen(KitchenSnapshot(inventory = KitchenInventory(listOf(location), listOf(lot))))
        val recipes = FakeRecipes(listOf(recipe))

        assertEquals(listOf(recipe), SearchRecipes(recipes).search("egg"))
        assertEquals(1, GetSuggestions(recipes, kitchen).get(SuggestionOptions(asOfDate = today)).size)
        val session = StartCooking().start(recipe, servings = 2)

        assertEquals(recipe, session.recipe)
        assertEquals(listOf(lot), kitchen.load().inventory.lots)
    }

    @Test
    fun `completion records actual consumption and undo restores stock`() {
        val ingredientId = IngredientId.generate()
        val recipe = Recipe(
            name = "Egg toast",
            ingredients = listOf(
                RecipeIngredient(ingredientId = ingredientId, quantity = Rational.of(2), unit = UnitCode.EGG, rawLine = "2 eggs"),
            ),
            directionsText = "Cook eggs.",
        )
        val location = KitchenLocation(name = "Fridge")
        val lot = InventoryLot(
            ingredientId = ingredientId,
            locationId = location.id,
            amount = InventoryAmount.Exact(Rational.of(5), UnitCode.EGG),
        )
        val kitchen = FakeKitchen(KitchenSnapshot(inventory = KitchenInventory(listOf(location), listOf(lot))))
        val cooking = FakeCooking(kitchen)
        val session = StartCooking().start(recipe, servings = 2)

        val event = CompleteCooking(kitchen, cooking).complete(
            session = session,
            cookedOn = today,
            consumed = listOf(ConsumedInventory(lot.id, Rational.of(2), UnitCode.EGG)),
        )

        assertEquals(listOf(lot.copy(amount = InventoryAmount.Exact(Rational.of(3), UnitCode.EGG))), kitchen.load().inventory.lots)
        assertEquals(event, cooking.lastEvent)
        assertNotNull(UndoCooking(cooking).undo(event.id, today))
        assertEquals(listOf(lot), kitchen.load().inventory.lots)
        assertNull(UndoCooking(cooking).undo(event.id, today))
    }

    private class FakeKitchen(var snapshot: KitchenSnapshot) : KitchenRepository {
        override fun load() = snapshot
        override fun save(snapshot: KitchenSnapshot) {
            this.snapshot = snapshot
        }
    }

    private class FakeRecipes(private val values: List<Recipe>) : RecipeRepository {
        override fun get(id: dev.kitchenapp.core.domain.identifiers.RecipeId) = values.firstOrNull { it.id == id }
        override fun getAll() = values
        override fun save(recipe: Recipe) = Unit
        override fun delete(id: dev.kitchenapp.core.domain.identifiers.RecipeId) = Unit
        override fun getCategories() = values.flatMap { it.categories }.distinct()
        override fun createCategory(name: String) = Unit
        override fun renameCategory(oldName: String, newName: String) = Unit
        override fun removeCategory(name: String) = Unit
    }

    private class FakeCooking(private val kitchen: FakeKitchen) : CookingRepository {
        var lastEvent: CookEvent? = null
        private var before: List<InventoryLot> = emptyList()

        override fun complete(
            event: CookEvent,
            previousLots: List<InventoryLot>,
            updatedSnapshot: KitchenSnapshot,
            leftovers: List<PreparedLeftover>,
        ) {
            before = previousLots
            kitchen.save(updatedSnapshot)
            lastEvent = event
        }

        override fun undo(eventId: String, undoneOn: DomainLocalDate): KitchenSnapshot? {
            if (lastEvent?.id != eventId) return null
            val existing = kitchen.load()
            val restored = existing.copy(
                inventory = KitchenInventory(
                    existing.inventory.locations,
                    existing.inventory.lots.filterNot { it.id in before.map(InventoryLot::id) } + before,
                ),
            )
            kitchen.save(restored)
            lastEvent = null
            return restored
        }
    }

    private companion object {
        val today = DomainLocalDate.of(2026, 11, 6)
    }
}
