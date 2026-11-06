package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Slice9PersistenceTest {
    @Test
    fun `initial migration upgrades the previous empty schema`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            KitchenDatabase.Schema.migrate(
                driver = driver,
                oldVersion = 1,
                newVersion = KitchenDatabase.Schema.version,
            )
            val database = KitchenDatabase(driver)

            assertTrue(database.recipeQueries.selectAllRecipes().executeAsList().isEmpty())
            assertTrue(database.recipeQueries.selectAllIngredients().executeAsList().isEmpty())
            assertTrue(database.recipeQueries.selectKitchenLocations().executeAsList().isEmpty())
        }
    }

    @Test
    fun `recipes round trip and updates are observable`() {
        val (driver, database) = newDatabase()
        driver.use {
            val ingredient = Ingredient(name = "Tomato")
            SqlDelightKitchenRepository(database).save(KitchenSnapshot(ingredients = listOf(ingredient)))
            val recipe = Recipe(
                id = RecipeId.generate(),
                name = "Tomato toast",
                servings = "2",
                ingredients = listOf(
                    RecipeIngredient(
                        ingredientId = ingredient.id,
                        quantity = Rational.of(1, 2),
                        quantityHigh = Rational.of(3, 4),
                        unit = UnitCode.CUP,
                        preparation = "diced",
                        importance = IngredientImportance.IMPORTANT,
                        approximateLevel = ApproximateLevel.PRECISE,
                        rawLine = "1/2 to 3/4 cup diced tomato",
                    ),
                ),
                directionsText = "Toast bread and add tomato.",
                notes = "Serve warm.",
                categories = listOf("Lunch", "Quick"),
                sourceName = "Family recipe",
                sourceUrl = "https://example.test/tomato-toast",
            )
            val recipes = SqlDelightRecipeRepository(database)

            recipes.save(recipe)

            assertEquals(recipe, recipes.get(recipe.id))
            assertEquals(listOf(recipe), recipes.getAll())

            val updated = recipe.copy(notes = "Add basil before serving.", categories = listOf("Dinner"))
            recipes.save(updated)

            assertEquals(updated, recipes.get(recipe.id))
            assertEquals(listOf(updated), recipes.getAll())

            recipes.delete(recipe.id)
            assertNull(recipes.get(recipe.id))
            assertTrue(recipes.getAll().isEmpty())
        }
    }

    @Test
    fun `kitchen snapshot round trips and saved changes are observable`() {
        val (driver, database) = newDatabase()
        driver.use {
            val ingredient = Ingredient(name = "Rice", aliases = setOf("long grain"))
            val location = KitchenLocation(name = "Pantry")
            val snapshot = KitchenSnapshot(
                ingredients = listOf(ingredient),
                inventory = KitchenInventory(
                    locations = listOf(location),
                    lots = listOf(
                        InventoryLot(
                            ingredientId = ingredient.id,
                            locationId = location.id,
                            amount = InventoryAmount.Exact(Rational.of(3, 2), UnitCode.CUP),
                            purchasedOn = DomainLocalDate.of(2026, 11, 1),
                            openedOn = DomainLocalDate.of(2026, 11, 2),
                            manufacturerUseBy = DomainLocalDate.of(2027, 1, 1),
                            userUseBy = DomainLocalDate.of(2026, 12, 15),
                        ),
                    ),
                ),
                availableEquipment = setOf("Rice cooker", "Pot"),
                householdRules = HouseholdIngredientRules(
                    preferences = listOf(IngredientPreference(ingredient.id, score = 3)),
                ),
            )
            val repository = SqlDelightKitchenRepository(database)

            repository.save(snapshot)

            assertEquals(snapshot, repository.load())

            val updated = snapshot.copy(
                availableEquipment = setOf("Dutch oven"),
                ingredients = listOf(ingredient.copy(aliases = setOf("white rice"))),
            )
            repository.save(updated)

            assertEquals(updated, repository.load())
        }
    }

    @Test
    fun `failed kitchen save rolls back every record change`() {
        val (driver, database) = newDatabase()
        driver.use {
            val ingredient = Ingredient(name = "Rice", aliases = setOf("long grain"))
            val location = KitchenLocation(name = "Pantry")
            val lot = InventoryLot(
                ingredientId = ingredient.id,
                locationId = location.id,
                amount = InventoryAmount.Exact(Rational.of(2), UnitCode.CUP),
            )
            val original = KitchenSnapshot(
                ingredients = listOf(ingredient),
                inventory = KitchenInventory(locations = listOf(location), lots = listOf(lot)),
                availableEquipment = setOf("Pot"),
            )
            val repository = SqlDelightKitchenRepository(database)
            repository.save(original)

            val invalid = original.copy(
                ingredients = listOf(ingredient.copy(name = "Renamed rice", aliases = setOf("changed"))),
                inventory = KitchenInventory(
                    locations = listOf(location),
                    lots = listOf(lot.copy(ingredientId = IngredientId.generate())),
                ),
                availableEquipment = setOf("Oven"),
            )

            assertFails { repository.save(invalid) }
            assertEquals(original, repository.load())
        }
    }

    private fun newDatabase(): Pair<JdbcSqliteDriver, KitchenDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return driver to KitchenDatabaseFactory.open(driver)
    }
}
