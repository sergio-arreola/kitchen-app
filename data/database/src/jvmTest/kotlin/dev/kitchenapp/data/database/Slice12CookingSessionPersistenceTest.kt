package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.ClaimCookingCheckIn
import dev.kitchenapp.core.application.CompleteCooking
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.DismissCookingReturnPrompt
import dev.kitchenapp.core.application.FirstReturnState
import dev.kitchenapp.core.application.MarkCookingReturned
import dev.kitchenapp.core.application.GetCookingHistory
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.application.RecordLeftoversForCookEvent
import dev.kitchenapp.core.application.ResumeCooking
import dev.kitchenapp.core.application.StartCooking
import dev.kitchenapp.core.application.UndoCooking
import dev.kitchenapp.core.domain.identifiers.IngredientId
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Slice12CookingSessionPersistenceTest {
    @Test
    fun `active cooking restores checklist and lifecycle claims without stale writes`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val ingredient = Ingredient(name = "Rice")
            val recipe = Recipe(
                name = "Rice bowl",
                ingredients = listOf(
                    RecipeIngredient(ingredientId = ingredient.id, rawLine = "Rice"),
                ),
                directionsText = "Cook rice.",
            )
            SqlDelightKitchenRepository(database).save(
                KitchenSnapshot(ingredients = listOf(ingredient)),
            )
            SqlDelightRecipeRepository(database).save(recipe)
            val sessions = SqlDelightActiveCookingSessionRepository(database)
            val started = StartCooking(sessions).start(recipe, servings = 2)

            assertEquals(started.persistedState(), SqlDelightActiveCookingSessionRepository(database).getActive())
            kotlin.test.assertFails { StartCooking(sessions).start(recipe) }
            sessions.setIngredientChecked(started.sessionId, recipe.ingredients.single().id, true)
            val generation = requireNotNull(sessions.markAway(started.sessionId))
            assertEquals(1L, generation)
            assertTrue(ClaimCookingCheckIn(sessions).claim(started.sessionId, generation))
            assertFalse(ClaimCookingCheckIn(sessions).claim(started.sessionId, generation))

            // Checklist writes preserve the newer generation and delivery claim.
            sessions.setIngredientChecked(started.sessionId, recipe.ingredients.single().id, false)
            val returned = requireNotNull(sessions.markReturned(started.sessionId))
            assertTrue(returned.showContinuePrompt)
            assertTrue(returned.resumeRequired)
            assertEquals(FirstReturnState.PROMPTED, returned.session.firstReturnState)
            assertTrue(returned.session.checkedIngredients.isEmpty())

            val resumed = ResumeCooking(sessions).resume(started.sessionId)
            assertFalse(resumed.resumeRequired)
            val nextGeneration = requireNotNull(sessions.markAway(started.sessionId))
            assertEquals(2L, nextGeneration)
            val secondReturn = requireNotNull(sessions.markReturned(started.sessionId))
            assertFalse(secondReturn.showContinuePrompt)
            assertFalse(secondReturn.resumeRequired)
        }
    }

    @Test
    fun `dismissed first return requires explicit resume and recipe deletion preserves session`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val ingredient = Ingredient(name = "Rice")
            val recipe = Recipe(
                name = "Rice bowl",
                ingredients = listOf(RecipeIngredient(ingredientId = ingredient.id, rawLine = "Rice")),
                directionsText = "Cook rice.",
            )
            SqlDelightKitchenRepository(database).save(KitchenSnapshot(ingredients = listOf(ingredient)))
            val recipes = SqlDelightRecipeRepository(database)
            recipes.save(recipe)
            val sessions = SqlDelightActiveCookingSessionRepository(database)
            val active = StartCooking(sessions).start(recipe)
            assertEquals(1L, sessions.markAway(active.sessionId))
            assertTrue(requireNotNull(MarkCookingReturned(sessions).markReturned(active.sessionId)).showContinuePrompt)

            val afterRecreation = requireNotNull(SqlDelightActiveCookingSessionRepository(database).getActive())
            assertEquals(FirstReturnState.PROMPTED, afterRecreation.firstReturnState)
            assertTrue(afterRecreation.resumeRequired)
            DismissCookingReturnPrompt(sessions).dismiss(active.sessionId)
            assertEquals(FirstReturnState.DISMISSED, sessions.getActive()?.firstReturnState)
            assertNull(sessions.markAway(active.sessionId))

            val failure = assertFailsWith<IllegalArgumentException> { recipes.delete(recipe.id) }
            assertEquals("Cannot delete a recipe while its cooking session is active", failure.message)
            assertNotNull(sessions.getActive())

            ResumeCooking(sessions).resume(active.sessionId)
            assertEquals(2L, sessions.markAway(active.sessionId))
        }
    }

    @Test
    fun `later leftovers remain manually available after reminder claim and undo removes them`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val ingredient = Ingredient(name = "Cooked rice")
            val location = KitchenLocation(name = "Fridge")
            val recipe = Recipe(
                name = "Rice bowl",
                ingredients = listOf(
                    RecipeIngredient(ingredientId = ingredient.id, rawLine = "Rice"),
                ),
                directionsText = "Cook rice.",
            )
            val kitchen = SqlDelightKitchenRepository(database)
            kitchen.save(KitchenSnapshot(ingredients = listOf(ingredient), inventory = KitchenInventory(listOf(location))))
            val recipes = SqlDelightRecipeRepository(database)
            recipes.save(recipe)
            val sessions = SqlDelightActiveCookingSessionRepository(database)
            val cooking = SqlDelightCookingRepository(database)
            val event = CompleteCooking(kitchen, cooking).complete(
                session = StartCooking(sessions).start(recipe),
                cookedOn = today,
                consumed = emptyList(),
                recordLeftoversLater = true,
            )

            val history = GetCookingHistory(cooking)
            assertTrue(history.get().single().leftoverFollowUpPending)
            assertTrue(cooking.claimLeftoverReminder(event.id))
            assertFalse(cooking.claimLeftoverReminder(event.id))
            assertTrue(history.get().single().leftoverFollowUpPending)

            val leftover = PreparedLeftover(
                ingredientId = ingredient.id,
                name = "Cooked rice",
                quantity = Rational.of(2),
                unit = UnitCode.CUP,
                locationId = location.id,
                preparedOn = today,
            )
            RecordLeftoversForCookEvent(kitchen, cooking).record(event.id, listOf(leftover))

            assertFalse(history.get().single().leftoverFollowUpPending)
            assertTrue(history.get().single().hasLeftovers)
            assertEquals(1, kitchen.load().inventory.lots.size)
            assertNotNull(UndoCooking(cooking).undo(event.id, today))
            assertTrue(kitchen.load().inventory.lots.isEmpty())
            assertFalse(history.get().single().leftoverFollowUpPending)
            kotlin.test.assertFails {
                RecordLeftoversForCookEvent(kitchen, cooking).record(event.id, listOf(leftover))
            }
        }
    }

    @Test
    fun `failed completion keeps the active session`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val ingredient = Ingredient(name = "Beans")
            val location = KitchenLocation(name = "Pantry")
            val lot = InventoryLot(
                ingredientId = ingredient.id,
                locationId = location.id,
                amount = InventoryAmount.Exact(Rational.of(2), UnitCode.CUP),
            )
            val original = KitchenSnapshot(
                ingredients = listOf(ingredient),
                inventory = KitchenInventory(listOf(location), listOf(lot)),
            )
            val kitchen = SqlDelightKitchenRepository(database)
            kitchen.save(original)
            val recipe = Recipe(
                name = "Beans",
                ingredients = listOf(RecipeIngredient(ingredientId = ingredient.id, rawLine = "Beans")),
                directionsText = "Cook beans.",
            )
            SqlDelightRecipeRepository(database).save(recipe)
            val sessions = SqlDelightActiveCookingSessionRepository(database)
            val active = StartCooking(sessions).start(recipe)
            val event = CookEvent("failed-event", recipe.id, null, today, emptyList())
            val cooking = SqlDelightCookingRepository(database)

            kotlin.test.assertFails {
                cooking.complete(
                    event = event,
                    previousLots = listOf(lot, lot),
                    updatedSnapshot = original.copy(inventory = KitchenInventory(listOf(location))),
                    leftovers = emptyList(),
                    sessionId = active.sessionId,
                )
            }

            assertNotNull(sessions.getActive())
            assertEquals(original, kitchen.load())
            assertNull(database.recipeQueries.selectCookEventExists(event.id).executeAsOneOrNull())
        }
    }

    private companion object {
        val today = DomainLocalDate.of(2026, 11, 9)
    }
}
