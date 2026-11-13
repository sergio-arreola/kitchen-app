package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.ActiveCookingSession
import dev.kitchenapp.core.application.ApplyKitchenBackupRestore
import dev.kitchenapp.core.application.KitchenBackupRestoreDecision
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.CookHistoryEntry
import dev.kitchenapp.core.application.KitchenBackupLeftover
import dev.kitchenapp.core.application.KitchenBackupRestoreRequest
import dev.kitchenapp.core.application.KitchenBackupSnapshot
import dev.kitchenapp.core.application.KitchenBackupSnapshotFingerprinter
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreviewKitchenBackupRestore
import dev.kitchenapp.core.application.RecipeBackupEntry
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.household.PreferenceStrength
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.ingredient.Ingredient
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class Slice13BackupRestoreTest {
    @Test
    fun replaceAllRestoresBackupManagedState() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val recipes = SqlDelightRecipeRepository(database)
            val kitchen = SqlDelightKitchenRepository(database)
            val shopping = SqlDelightShoppingListRepository(database)
            val oldIngredient = Ingredient(name = "Old ingredient")
            kitchen.save(KitchenSnapshot(ingredients = listOf(oldIngredient)))
            driver.execute(
                null,
                "INSERT INTO inventory_transaction(id, ingredient_id, occurred_on, transaction_kind) " +
                    "VALUES ('legacy-row', '${oldIngredient.id}', '2026-11-01', 'CONSUME')",
                0,
            )
            val oldRecipe = Recipe(name = "Old recipe", ingredients = emptyList(), directionsText = "Old.")
            recipes.save(oldRecipe)
            shopping.saveCurrent(
                ShoppingList(
                    createdAt = instant("2026-11-01T10:00:00Z"),
                    items = listOf(
                        ShoppingListItem(
                            ingredientId = oldIngredient.id,
                            ingredientName = oldIngredient.name,
                            quantity = null,
                            unit = null,
                            sourceRecipeIds = setOf(oldRecipe.id),
                            checked = false,
                        ),
                    ),
                ),
            )

            val incoming = backupSnapshot()
            val repo = restoreRepository(database)
            val request = request(incoming)
            val preview = PreviewKitchenBackupRestore(repo).preview(request)

            assertEquals(1, preview.currentCounts.recipes)
            assertEquals(1, preview.backupCounts.recipes)
            assertEquals(1L, preview.legacyInventoryTransactionCount)
            assertFalse(preview.hasActiveCookingSession)

            ApplyKitchenBackupRestore(repo).apply(
                request,
                preview,
                KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA,
            )

            val restored = SqlDelightKitchenBackupSnapshotRepository(database).load()
            assertEquals(incoming.recipes.single().recipe, restored.recipes.single().recipe)
            assertEquals(incoming.recipes.single().image, restored.recipes.single().image)
            assertEquals(incoming.categories, restored.categories)
            assertEquals(
                incoming.kitchen.ingredients.associateBy { it.id },
                restored.kitchen.ingredients.associateBy { it.id },
            )
            assertEquals(incoming.kitchen.householdRules, restored.kitchen.householdRules)
            assertEquals(
                incoming.kitchen.inventory.lots.associateBy { it.id },
                restored.kitchen.inventory.lots.associateBy { it.id },
            )
            assertEquals(incoming.shoppingList, restored.shoppingList)
            assertEquals(incoming.cookingHistory, restored.cookingHistory)
            assertEquals(incoming.leftovers, restored.leftovers)
            assertEquals(0L, database.recipeQueries.selectLegacyInventoryTransactionCount().executeAsOne())
            assertFalse(restored.recipes.any { it.recipe.id == oldRecipe.id })
            assertFalse(restored.kitchen.ingredients.any { it.id == oldIngredient.id })
        }
    }

    @Test
    fun activeCookingSessionBlocksRestoreWithoutChangingRecords() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val recipes = SqlDelightRecipeRepository(database)
            val currentRecipe = Recipe(name = "Current recipe", ingredients = emptyList(), directionsText = "")
            recipes.save(currentRecipe)
            val sessions = SqlDelightActiveCookingSessionRepository(database)
            sessions.start(ActiveCookingSession(sessionId = "active-session", recipeId = currentRecipe.id, servings = null))
            val repo = restoreRepository(database)
            val request = request(backupSnapshot())
            val preview = PreviewKitchenBackupRestore(repo).preview(request)

            assertTrue(preview.hasActiveCookingSession)
            assertFalse(preview.canApply)
            assertFailsWith<IllegalStateException> {
                ApplyKitchenBackupRestore(repo).apply(
                    request,
                    preview,
                    KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA,
                )
            }
            assertEquals(currentRecipe, recipes.get(currentRecipe.id))
            assertEquals("active-session", sessions.getActive()?.sessionId)
        }
    }

    @Test
    fun stalePreviewAndFailedTransactionPreserveCurrentRecords() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            val database = KitchenDatabaseFactory.open(driver)
            val recipes = SqlDelightRecipeRepository(database)
            val original = Recipe(name = "Original", ingredients = emptyList(), directionsText = "")
            recipes.save(original)
            val repo = restoreRepository(database)
            val request = request(backupSnapshot())
            val stalePreview = PreviewKitchenBackupRestore(repo).preview(request)
            recipes.save(original.copy(name = "Edited after preview"))

            assertFailsWith<IllegalStateException> {
                repo.replaceAll(request, stalePreview, KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA)
            }
            assertEquals("Edited after preview", recipes.get(original.id)?.name)

            val mutableRequest = request(backupSnapshot())
            val mutablePreview = PreviewKitchenBackupRestore(repo).preview(mutableRequest)
            val imageBytes = requireNotNull(mutableRequest.snapshot.recipes.single().image).bytes
            imageBytes[0] = (imageBytes[0].toInt() xor 1).toByte()
            assertFailsWith<IllegalArgumentException> {
                ApplyKitchenBackupRestore(repo).apply(
                    mutableRequest,
                    mutablePreview,
                    KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA,
                )
            }

            val broken = backupSnapshot().copy(
                recipes = listOf(
                    RecipeBackupEntry(
                        recipe = Recipe(
                            name = "Broken reference",
                            ingredients = listOf(
                                RecipeIngredient(
                                    id = RecipeIngredientId.generate(),
                                    ingredientId = IngredientId.generate(),
                                    rawLine = "missing",
                                ),
                            ),
                            directionsText = "",
                        ),
                        image = null,
                    ),
                ),
            )
            val brokenRequest = request(broken)
            val freshPreview = PreviewKitchenBackupRestore(repo).preview(brokenRequest)

            assertFailsWith<Exception> {
                repo.replaceAll(
                    brokenRequest,
                    freshPreview,
                    KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA,
                )
            }
            assertEquals("Edited after preview", recipes.get(original.id)?.name)
        }
    }

    private fun restoreRepository(database: KitchenDatabase) =
        SqlDelightKitchenBackupRestoreRepository(
            database = database,
            fingerprinter = SnapshotFingerprinter,
        )

    private fun request(snapshot: KitchenBackupSnapshot) = KitchenBackupRestoreRequest(
        snapshot = snapshot,
        archiveFingerprint = "a".repeat(64),
        snapshotFingerprint = SnapshotFingerprinter.fingerprint(snapshot),
    )

    private fun backupSnapshot(): KitchenBackupSnapshot {
        val flourId = IngredientId.generate()
        val milkId = IngredientId.generate()
        val locationId = KitchenLocationId.generate()
        val stockLotId = InventoryLotId.generate()
        val leftoverId = InventoryLotId.generate()
        val recipeId = RecipeId.generate()
        val today = DomainLocalDate(LocalDate.parse("2026-11-11"))
        val flour = Ingredient(flourId, "Backup flour", setOf("backup flour"))
        val milk = Ingredient(milkId, "Backup milk")
        val recipe = Recipe(
            id = recipeId,
            name = "Backup bread",
            servings = "4",
            ingredients = listOf(
                RecipeIngredient(
                    id = RecipeIngredientId.generate(),
                    ingredientId = flourId,
                    quantity = Rational.of(2),
                    unit = UnitCode.CUP,
                    importance = IngredientImportance.CRITICAL,
                    approximateLevel = ApproximateLevel.EXACT,
                    rawLine = "2 cups backup flour",
                ),
            ),
            directionsText = "Bake.",
            categories = listOf("Baking"),
            sourceUrl = "https://example.invalid/bread",
            prepTimeSeconds = 300,
            cookTimeSeconds = 900,
            totalTimeSeconds = 1200,
            mealTypes = listOf("Dinner"),
            equipmentRequirements = listOf(
                EquipmentRequirement("Oven", EquipmentRequirementKind.REQUIRED),
            ),
        )
        val stockLot = InventoryLot(
            id = stockLotId,
            ingredientId = flourId,
            locationId = locationId,
            amount = InventoryAmount.Exact(Rational.of(4), UnitCode.CUP),
            purchasedOn = today,
        )
        val leftoverLot = InventoryLot(
            id = leftoverId,
            ingredientId = milkId,
            locationId = locationId,
            amount = InventoryAmount.Exact(Rational.of(2), UnitCode.SERVING),
            purchasedOn = today,
        )
        val event = CookEvent(
            id = "backup-cook-event",
            recipeId = recipeId,
            servings = 4,
            cookedOn = today,
            consumed = listOf(ConsumedInventory(stockLotId, Rational.of(1), UnitCode.CUP)),
            note = "Synthetic history",
        )
        val undoneEvent = CookEvent(
            id = "backup-undone-event",
            recipeId = recipeId,
            servings = 2,
            cookedOn = DomainLocalDate(LocalDate.parse("2026-11-10")),
            consumed = emptyList(),
            note = "Synthetic undone event",
        )
        return KitchenBackupSnapshot(
            recipes = listOf(RecipeBackupEntry(recipe, RecipeImage(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                "image/png",
            ))),
            categories = listOf("Baking", "Unused category"),
            kitchen = KitchenSnapshot(
                ingredients = listOf(flour, milk),
                inventory = KitchenInventory(
                    locations = listOf(KitchenLocation(locationId, "Pantry")),
                    lots = listOf(stockLot, leftoverLot),
                ),
                availableEquipment = setOf("Oven"),
                householdRules = HouseholdIngredientRules(
                    hardExclusions = setOf(milkId),
                    preferences = listOf(IngredientPreference(flourId, PreferenceStrength.SOFT, score = 2)),
                ),
            ),
            shoppingList = ShoppingList(
                createdAt = instant("2026-11-11T09:00:00Z"),
                items = listOf(
                    ShoppingListItem(
                        ingredientId = flourId,
                        ingredientName = flour.name,
                        quantity = Rational.of(1),
                        unit = UnitCode.CUP,
                        sourceRecipeIds = setOf(recipeId),
                        checked = true,
                    ),
                ),
            ),
            cookingHistory = listOf(
                CookHistoryEntry(
                    event = event,
                    leftoverFollowUpPending = false,
                    hasLeftovers = true,
                    leftoverReminderClaimed = true,
                    inventoryBefore = listOf(stockLot),
                ),
                CookHistoryEntry(
                    event = undoneEvent,
                    leftoverFollowUpPending = false,
                    hasLeftovers = false,
                    undoneOn = today,
                    inventoryBefore = listOf(stockLot),
                ),
            ),
            leftovers = listOf(
                KitchenBackupLeftover(
                    id = leftoverId,
                    cookEventId = event.id,
                    ingredientId = milkId,
                    name = "Backup bread",
                    quantity = Rational.of(2),
                    unit = UnitCode.SERVING,
                    preparedOn = today,
                    useBy = today.plusDays(2),
                ),
            ),
        )
    }

    private fun instant(value: String) = DomainInstant(Instant.parse(value))

    private object SnapshotFingerprinter : KitchenBackupSnapshotFingerprinter {
        override fun fingerprint(snapshot: KitchenBackupSnapshot): String =
            snapshot.hashCode().toUInt().toString(16).padStart(8, '0').repeat(8)
    }
}
