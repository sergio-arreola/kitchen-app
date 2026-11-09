package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.CompleteCooking
import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.GetSuggestions
import dev.kitchenapp.core.application.SuggestionOptions
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.application.StartCooking
import dev.kitchenapp.core.application.UndoCooking
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.quantity.ReadinessStatus
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Slice10CookingPersistenceTest {
    @Test
    fun `completion persists leftover inventory and undo atomically restores pre-cook stock`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val ingredient = Ingredient(name = "Egg")
            val leftoverIngredient = Ingredient(name = "Cooked egg")
            val location = KitchenLocation(name = "Fridge")
            val lot = InventoryLot(
                ingredientId = ingredient.id,
                locationId = location.id,
                amount = InventoryAmount.Exact(Rational.of(5), UnitCode.EGG),
            )
            val kitchen = SqlDelightKitchenRepository(database)
            kitchen.save(
                KitchenSnapshot(
                    ingredients = listOf(ingredient, leftoverIngredient),
                    inventory = KitchenInventory(listOf(location), listOf(lot)),
                ),
            )
            val recipe = Recipe(
                name = "Egg toast",
                ingredients = listOf(
                    RecipeIngredient(
                        ingredientId = ingredient.id,
                        quantity = Rational.of(2),
                        unit = UnitCode.EGG,
                        rawLine = "2 eggs",
                    ),
                ),
                directionsText = "Cook eggs.",
            )
            val recipes = SqlDelightRecipeRepository(database)
            recipes.save(recipe)
            val leftoverRecipe = Recipe(
                name = "Leftover bowl",
                ingredients = listOf(
                    RecipeIngredient(
                        ingredientId = leftoverIngredient.id,
                        quantity = Rational.of(1),
                        unit = UnitCode.SERVING,
                        rawLine = "1 serving cooked egg",
                    ),
                ),
                directionsText = "Serve leftovers.",
            )
            recipes.save(leftoverRecipe)
            val cooking = SqlDelightCookingRepository(database)
            val sessions = SqlDelightActiveCookingSessionRepository(database)
            val event = CompleteCooking(kitchen, cooking).complete(
                session = StartCooking(sessions).start(recipe, servings = 2),
                cookedOn = today,
                consumed = listOf(ConsumedInventory(lot.id, Rational.of(2), UnitCode.EGG)),
                leftovers = listOf(
                    PreparedLeftover(
                        ingredientId = leftoverIngredient.id,
                        name = "Cooked egg",
                        quantity = Rational.of(1),
                        unit = UnitCode.SERVING,
                        locationId = location.id,
                        preparedOn = today,
                    ),
                ),
            )

            val reloaded = SqlDelightKitchenRepository(database).load()
            assertEquals(Rational.of(3), (reloaded.inventory.lots.single { it.id == lot.id }.amount as InventoryAmount.Exact).quantity)
            assertEquals(2, reloaded.inventory.lots.size)
            assertEquals(1, database.recipeQueries.selectLeftovers().executeAsList().size)
            assertNull(sessions.getActive())
            val suggestion = GetSuggestions(recipes, SqlDelightKitchenRepository(database))
                .get(SuggestionOptions(asOfDate = today))
                .single { it.recipe.id == leftoverRecipe.id }
            assertEquals(ReadinessStatus.READY, suggestion.readiness)

            val restored = UndoCooking(cooking).undo(event.id, today)
            assertEquals(listOf(lot), restored?.inventory?.lots)
            assertEquals(listOf(lot), SqlDelightKitchenRepository(database).load().inventory.lots)
            assertEquals(0, database.recipeQueries.selectLeftovers().executeAsList().size)
            assertNull(UndoCooking(cooking).undo(event.id, today))
        }
    }

    @Test
    fun `failed cooking transaction leaves stock and cook history unchanged`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val ingredient = Ingredient(name = "Rice")
            val location = KitchenLocation(name = "Pantry")
            val lot = InventoryLot(
                ingredientId = ingredient.id,
                locationId = location.id,
                amount = InventoryAmount.Exact(Rational.of(4), UnitCode.CUP),
            )
            val original = KitchenSnapshot(
                ingredients = listOf(ingredient),
                inventory = KitchenInventory(listOf(location), listOf(lot)),
            )
            val kitchen = SqlDelightKitchenRepository(database)
            kitchen.save(original)
            val recipe = Recipe(
                name = "Rice",
                ingredients = listOf(
                    RecipeIngredient(ingredientId = ingredient.id, quantity = Rational.of(1), unit = UnitCode.CUP, rawLine = "1 cup rice"),
                ),
                directionsText = "Cook rice.",
            )
            SqlDelightRecipeRepository(database).save(recipe)
            val event = CookEvent("failed-event", recipe.id, null, today, emptyList())
            val cooking = SqlDelightCookingRepository(database)

            kotlin.test.assertFails {
                cooking.complete(
                    event = event,
                    previousLots = listOf(lot, lot),
                    updatedSnapshot = original.copy(inventory = KitchenInventory(listOf(location))),
                    leftovers = emptyList(),
                )
            }

            assertEquals(original, kitchen.load())
            assertNull(database.recipeQueries.selectCookEventExists(event.id).executeAsOneOrNull())
        }
    }

    private companion object {
        val today = DomainLocalDate.of(2026, 11, 6)
    }
}
