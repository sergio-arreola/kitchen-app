package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.domain.time.DomainInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class Slice13BackupSnapshotTest {
    @Test
    fun `transactional backup snapshot includes recipe image and checked shopping state`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val recipes = SqlDelightRecipeRepository(database)
            val kitchen = SqlDelightKitchenRepository(database)
            val shoppingLists = SqlDelightShoppingListRepository(database)
            val ingredient = dev.kitchenapp.core.domain.ingredient.Ingredient(name = "Synthetic flour")
            kitchen.save(KitchenSnapshot(ingredients = listOf(ingredient)))
            val image = RecipeImage(byteArrayOf(1, 2, 3), "image/png")
            val recipe = Recipe(
                name = "Synthetic bread",
                ingredients = emptyList(),
                directionsText = "Bake.",
                categories = listOf("Synthetic"),
                sourceUrl = "https://example.invalid/synthetic",
                imageSourceUrl = "https://example.invalid/synthetic.png",
            )
            recipes.saveWithImage(recipe, image)
            shoppingLists.saveCurrent(
                ShoppingList(
                    createdAt = DomainInstant(Instant.parse("2026-11-10T12:00:00Z")),
                    items = listOf(
                        ShoppingListItem(
                            ingredientId = ingredient.id,
                            ingredientName = ingredient.name,
                            quantity = null,
                            unit = null,
                            sourceRecipeIds = setOf(recipe.id),
                            checked = true,
                        ),
                    ),
                ),
            )

            val snapshot = SqlDelightKitchenBackupSnapshotRepository(database).load()

            assertEquals(recipe, snapshot.recipes.single().recipe)
            assertEquals(image, snapshot.recipes.single().image)
            assertEquals(listOf("Synthetic"), snapshot.categories)
            assertEquals(ingredient, snapshot.kitchen.ingredients.single())
            assertTrue(snapshot.shoppingList?.items?.single()?.checked == true)
            assertEquals(setOf(recipe.id), snapshot.shoppingList?.items?.single()?.sourceRecipeIds)
            assertNotNull(snapshot.shoppingList)
        }
    }
}
