package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.CompleteCooking
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.DeleteRecipe
import dev.kitchenapp.core.application.KitchenBackupSnapshot
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.application.StartCooking
import dev.kitchenapp.core.application.UndoCooking
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RecipeDeletionHistoryPersistenceTest {
    @Test
    fun `recipe deletion preserves detached history leftovers shopping provenance and undo`() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val database = KitchenDatabaseFactory.open(driver)
            val fixture = seedHistory(database)

            verifyDeleteAndUndo(database, fixture)
        }
    }

    @Test
    fun `migration from version eight preserves cook children before detaching recipe history`() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            KitchenDatabase.Schema.migrate(driver, oldVersion = 0, newVersion = 8)
            val database = KitchenDatabase(driver)
            val fixture = seedHistory(database)
            assertTrue(KitchenDatabase.Schema.version > 8, "Detached cook history requires a forward migration")

            KitchenDatabase.Schema.migrate(driver, oldVersion = 8, newVersion = KitchenDatabase.Schema.version)
            database.recipeQueries.enableForeignKeys()

            assertEquals(fixture.beforeDeletion, SqlDelightKitchenBackupSnapshotRepository(database).load())
            verifyDeleteAndUndo(database, fixture)
        }
    }

    private fun verifyDeleteAndUndo(database: KitchenDatabase, fixture: Fixture) {
        val recipes = SqlDelightRecipeRepository(database)
        val cooking = SqlDelightCookingRepository(database)
        val shopping = SqlDelightShoppingListRepository(database)
        assertTrue(DeleteRecipe(recipes).delete(fixture.recipe.id))
        assertFalse(DeleteRecipe(recipes).delete(fixture.recipe.id))
        assertNull(recipes.get(fixture.recipe.id))
        assertNull(recipes.getRecipeImage(fixture.recipe.id))
        assertTrue(database.recipeQueries.selectRecipeIngredients(fixture.recipe.id.toString()).executeAsList().isEmpty())
        assertEquals(fixture.beforeDeletion.cookingHistory, cooking.getHistory())
        assertEquals(fixture.beforeDeletion.shoppingList, shopping.getCurrent())
        assertEquals(
            fixture.beforeDeletion.copy(recipes = emptyList()),
            SqlDelightKitchenBackupSnapshotRepository(database).load(),
        )

        val undoneOn = today.plusDays(3)
        val restored = requireNotNull(UndoCooking(cooking).undo(fixture.completed.id, undoneOn))

        assertEquals(listOf(fixture.originalLot), restored.inventory.lots)
        val history = cooking.getHistory().associateBy { it.event.id }
        val completed = requireNotNull(history[fixture.completed.id])
        val alreadyUndone = requireNotNull(history[fixture.alreadyUndone.id])
        assertEquals(fixture.recipe.id, completed.event.recipeId)
        assertEquals(fixture.completed.consumed, completed.event.consumed)
        assertEquals(listOf(fixture.originalLot), completed.inventoryBefore)
        assertEquals(undoneOn, completed.undoneOn)
        assertEquals(today.plusDays(1), alreadyUndone.undoneOn)
        assertEquals(fixture.alreadyUndone.consumed, alreadyUndone.event.consumed)
        assertEquals(fixture.beforeDeletion.shoppingList, shopping.getCurrent())
        val snapshot = SqlDelightKitchenBackupSnapshotRepository(database).load()
        assertTrue(snapshot.recipes.isEmpty())
        assertTrue(snapshot.leftovers.isEmpty())
        assertEquals(setOf(fixture.recipe.id), snapshot.shoppingList?.items?.single()?.sourceRecipeIds)
    }

    private fun seedHistory(database: KitchenDatabase): Fixture {
        val ingredient = Ingredient(name = "Deletion fixture egg")
        val leftoverIngredient = Ingredient(name = "Deletion fixture cooked egg")
        val location = KitchenLocation(name = "Deletion fixture refrigerator")
        val originalLot = InventoryLot(
            ingredientId = ingredient.id,
            locationId = location.id,
            amount = InventoryAmount.Exact(Rational.of(5), UnitCode.EGG),
        )
        val kitchen = SqlDelightKitchenRepository(database)
        kitchen.save(
            KitchenSnapshot(
                ingredients = listOf(ingredient, leftoverIngredient),
                inventory = KitchenInventory(listOf(location), listOf(originalLot)),
            ),
        )
        val recipe = Recipe(
            name = "Deletion fixture eggs",
            ingredients = listOf(RecipeIngredient(ingredientId = ingredient.id, quantity = Rational.of(2), unit = UnitCode.EGG, rawLine = "2 eggs")),
            directionsText = "Cook the self-authored fixture eggs.",
            categories = listOf("Deletion fixture"),
        )
        SqlDelightRecipeRepository(database).saveWithImage(recipe, RecipeImage(byteArrayOf(1, 2, 3), "image/png"))
        val cooking = SqlDelightCookingRepository(database)
        val sessions = SqlDelightActiveCookingSessionRepository(database)
        val complete = CompleteCooking(kitchen, cooking)
        val completed = complete.complete(
            session = StartCooking(sessions).start(recipe, servings = 2),
            cookedOn = today,
            consumed = listOf(ConsumedInventory(originalLot.id, Rational.of(2), UnitCode.EGG)),
            leftovers = listOf(
                PreparedLeftover(
                    ingredientId = leftoverIngredient.id,
                    name = "Deletion fixture leftovers",
                    quantity = Rational.of(1),
                    unit = UnitCode.SERVING,
                    locationId = location.id,
                    preparedOn = today,
                    useBy = today.plusDays(2),
                ),
            ),
            note = "Completed history must survive recipe deletion.",
        )
        val alreadyUndone = complete.complete(
            session = StartCooking(sessions).start(recipe, servings = 1),
            cookedOn = today.plusDays(1),
            consumed = listOf(ConsumedInventory(originalLot.id, Rational.of(1), UnitCode.EGG)),
            note = "Already-undone history must survive recipe deletion.",
        )
        requireNotNull(UndoCooking(cooking).undo(alreadyUndone.id, today.plusDays(1)))
        val current = kitchen.load()
        kitchen.save(current.copy(inventory = current.inventory.copy(lots = current.inventory.lots.filterNot { it.id == originalLot.id })))
        SqlDelightShoppingListRepository(database).saveCurrent(
            ShoppingList(
                createdAt = DomainInstant(Instant.parse("2026-11-14T12:00:00Z")),
                items = listOf(
                    ShoppingListItem(
                        ingredientId = ingredient.id,
                        ingredientName = ingredient.name,
                        quantity = Rational.of(2),
                        unit = UnitCode.EGG,
                        sourceRecipeIds = setOf(recipe.id),
                        checked = true,
                    ),
                ),
            ),
        )
        return Fixture(recipe, originalLot, completed, alreadyUndone, SqlDelightKitchenBackupSnapshotRepository(database).load())
    }

    private data class Fixture(
        val recipe: Recipe,
        val originalLot: InventoryLot,
        val completed: CookEvent,
        val alreadyUndone: CookEvent,
        val beforeDeletion: KitchenBackupSnapshot,
    )

    private companion object {
        val today = DomainLocalDate.of(2026, 11, 14)
    }
}
