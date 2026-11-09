package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class Slice12ShoppingPersistenceTest {
    @Test
    fun `migration from version six creates an empty current shopping list store`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            KitchenDatabase.Schema.migrate(driver, oldVersion = 0, newVersion = 6)
            KitchenDatabase.Schema.migrate(driver, oldVersion = 6, newVersion = KitchenDatabase.Schema.version)
            val repository = SqlDelightShoppingListRepository(KitchenDatabase(driver))

            assertNull(repository.getCurrent())
        }
    }

    @Test
    fun `shopping list round trips provenance unknown quantities and checked state`() {
        val (driver, database) = newDatabase()
        driver.use {
            val recipeA = RecipeId.generate()
            val recipeB = RecipeId.generate()
            val list = ShoppingList(
                createdAt = DomainInstant(Instant.parse("2026-11-08T10:15:00Z")),
                items = listOf(
                    ShoppingListItem(
                        ingredientId = IngredientId.generate(),
                        ingredientName = "Flour",
                        quantity = Rational.of(3, 2),
                        unit = UnitCode.CUP,
                        sourceRecipeIds = setOf(recipeA, recipeB),
                    ),
                    ShoppingListItem(
                        ingredientId = IngredientId.generate(),
                        ingredientName = "Fresh basil",
                        quantity = null,
                        unit = null,
                        sourceRecipeIds = setOf(recipeB),
                    ),
                ),
            )
            val repository = SqlDelightShoppingListRepository(database)

            repository.saveCurrent(list)

            assertEquals(list, repository.getCurrent())
            assertTrue(repository.setItemChecked(list.items.first().id, true))
            assertFalse(repository.setItemChecked(dev.kitchenapp.core.domain.identifiers.ShoppingListItemId.generate(), true))
            assertEquals(list.copy(items = list.items.mapIndexed { index, item ->
                if (index == 0) item.copy(checked = true) else item
            }), repository.getCurrent())

            repository.clearCurrent()

            assertNull(repository.getCurrent())
        }
    }

    private fun newDatabase(): Pair<JdbcSqliteDriver, KitchenDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return driver to KitchenDatabaseFactory.open(driver)
    }
}
