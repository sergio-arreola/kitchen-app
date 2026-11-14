package dev.kitchenapp.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.ApplyKitchenBackupRestore
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.CookHistoryEntry
import dev.kitchenapp.core.application.KitchenBackupLeftover
import dev.kitchenapp.core.application.KitchenBackupRestoreDecision
import dev.kitchenapp.core.application.KitchenBackupSnapshot
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreviewKitchenBackupRestore
import dev.kitchenapp.core.application.UndoCooking
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.KitchenDatabaseFactory
import dev.kitchenapp.data.database.SqlDelightCookingRepository
import dev.kitchenapp.data.database.SqlDelightKitchenBackupRestoreRepository
import dev.kitchenapp.data.database.SqlDelightKitchenBackupSnapshotRepository
import dev.kitchenapp.data.database.SqlDelightShoppingListRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DetachedRecipeHistoryBackupTest {
    private val exporter = KitchenBackupArchiveExporter()
    private val validator = KitchenBackupArchiveValidator()
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `detached recipe history validates restores and retains undo without a recipe row`() {
        val snapshot = detachedSnapshot()
        val validated = validator.validate(exporter.encode(snapshot, exportedAt))
        val request = validated.toRestoreRequest()
        assertTrue(request.snapshot.recipes.isEmpty())
        assertEquals(deletedRecipe, request.snapshot.cookingHistory.first().event.recipeId)
        assertEquals(setOf(deletedRecipe), request.snapshot.shoppingList?.items?.single()?.sourceRecipeIds)
        assertEquals(exporter.fingerprint(snapshot), exporter.fingerprint(request.snapshot))

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val database = KitchenDatabaseFactory.open(driver)
            val restore = SqlDelightKitchenBackupRestoreRepository(database, fingerprinter = exporter)
            val preview = PreviewKitchenBackupRestore(restore).preview(request)
            assertEquals(0, preview.backupCounts.recipes)
            ApplyKitchenBackupRestore(restore).apply(request, preview, KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA)
            val restored = SqlDelightKitchenBackupSnapshotRepository(database).load()

            assertTrue(restored.recipes.isEmpty())
            assertEquals(exporter.fingerprint(snapshot), exporter.fingerprint(restored))
            assertEquals(snapshot.cookingHistory.associateBy { it.event.id }, restored.cookingHistory.associateBy { it.event.id })
            assertEquals(snapshot.leftovers, restored.leftovers)
            assertEquals(snapshot.shoppingList, restored.shoppingList)
            assertEquals(listOf(leftoverLotId), restored.kitchen.inventory.lots.map { it.id })

            val cooking = SqlDelightCookingRepository(database)
            val undoDate = today.plusDays(3)
            val undone = requireNotNull(UndoCooking(cooking).undo(COMPLETED_EVENT_ID, undoDate))
            assertEquals(listOf(originalLot()), undone.inventory.lots)
            val history = cooking.getHistory().associateBy { it.event.id }
            val completed = requireNotNull(history[COMPLETED_EVENT_ID])
            assertEquals(deletedRecipe, completed.event.recipeId)
            assertEquals(snapshot.cookingHistory.single { it.event.id == COMPLETED_EVENT_ID }.event.consumed, completed.event.consumed)
            assertEquals(undoDate, completed.undoneOn)
            assertEquals(today.plusDays(1), history[UNDONE_EVENT_ID]?.undoneOn)
            assertEquals(snapshot.shoppingList, SqlDelightShoppingListRepository(database).getCurrent())
            val afterUndo = SqlDelightKitchenBackupSnapshotRepository(database).load()
            assertTrue(afterUndo.leftovers.isEmpty())
            assertTrue(validator.validate(exporter.encode(afterUndo, exportedAt)).toSnapshot().recipes.isEmpty())
        }
    }

    @Test
    fun `detached historical recipe IDs must still be valid UUIDs`() {
        val document = validator.validate(exporter.encode(detachedSnapshot(), exportedAt)).document
        val broken = document.copy(
            cookingHistory = document.cookingHistory.map {
                if (it.id == COMPLETED_EVENT_ID) it.copy(recipeId = "invalid-recipe-id") else it
            },
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(archive(broken)) }

        assertTrue(error.message.orEmpty().contains("recipe ID", ignoreCase = true))
    }

    @Test
    fun `detached recipe history does not permit leftovers without their cook event`() {
        val document = validator.validate(exporter.encode(detachedSnapshot(), exportedAt)).document
        val broken = document.copy(leftovers = document.leftovers.map { it.copy(cookEventId = "missing-cook-event") })

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(archive(broken)) }

        assertTrue(error.message.orEmpty().contains("missing cook event"))
    }

    private fun archive(document: BackupDocument): ByteArray {
        val payload = json.encodeToString(document).encodeToByteArray()
        val manifest = BackupManifest(
            exportedAt = exportedAt.toString(),
            payloadSizeBytes = payload.size.toLong(),
            payloadSha256 = sha256BackupBytes(payload),
            assets = emptyList(),
        )
        return zipBackupArchive(listOf(
            BackupArchiveEntry("manifest.json", json.encodeToString(manifest).encodeToByteArray()),
            BackupArchiveEntry("data.json", payload),
        ))
    }

    private fun detachedSnapshot(): KitchenBackupSnapshot {
        val raw = Ingredient(rawIngredientId, "Detached fixture egg")
        val prepared = Ingredient(preparedIngredientId, "Detached fixture cooked egg")
        val completed = CookEvent(
            id = COMPLETED_EVENT_ID,
            recipeId = deletedRecipe,
            servings = 2,
            cookedOn = today,
            consumed = listOf(ConsumedInventory(stockLotId, Rational.of(2), UnitCode.EGG)),
            note = "Detached completed history",
        )
        val undone = CookEvent(
            id = UNDONE_EVENT_ID,
            recipeId = deletedRecipe,
            servings = 1,
            cookedOn = today.plusDays(1),
            consumed = listOf(ConsumedInventory(stockLotId, Rational.of(1), UnitCode.EGG)),
            note = "Detached already-undone history",
        )
        return KitchenBackupSnapshot(
            recipes = emptyList(),
            categories = listOf("Historical fixture"),
            kitchen = KitchenSnapshot(
                ingredients = listOf(raw, prepared),
                inventory = KitchenInventory(
                    locations = listOf(KitchenLocation(locationId, "Detached fixture refrigerator")),
                    lots = listOf(InventoryLot(
                        id = leftoverLotId,
                        ingredientId = preparedIngredientId,
                        locationId = locationId,
                        amount = InventoryAmount.Exact(Rational.of(1), UnitCode.SERVING),
                        purchasedOn = today,
                        userUseBy = today.plusDays(2),
                    )),
                ),
            ),
            shoppingList = ShoppingList(
                createdAt = exportedAt,
                items = listOf(ShoppingListItem(
                    ingredientId = rawIngredientId,
                    ingredientName = raw.name,
                    quantity = Rational.of(2),
                    unit = UnitCode.EGG,
                    sourceRecipeIds = setOf(deletedRecipe),
                    checked = true,
                )),
            ),
            cookingHistory = listOf(
                CookHistoryEntry(
                    event = undone,
                    leftoverFollowUpPending = false,
                    hasLeftovers = false,
                    undoneOn = today.plusDays(1),
                    inventoryBefore = listOf(originalLot().copy(amount = InventoryAmount.Exact(Rational.of(3), UnitCode.EGG))),
                ),
                CookHistoryEntry(
                    event = completed,
                    leftoverFollowUpPending = false,
                    hasLeftovers = true,
                    leftoverReminderClaimed = true,
                    inventoryBefore = listOf(originalLot()),
                ),
            ),
            leftovers = listOf(KitchenBackupLeftover(
                id = leftoverLotId,
                cookEventId = COMPLETED_EVENT_ID,
                ingredientId = preparedIngredientId,
                name = "Detached fixture leftovers",
                quantity = Rational.of(1),
                unit = UnitCode.SERVING,
                preparedOn = today,
                useBy = today.plusDays(2),
            )),
        )
    }

    private fun originalLot() = InventoryLot(
        id = stockLotId,
        ingredientId = rawIngredientId,
        locationId = locationId,
        amount = InventoryAmount.Exact(Rational.of(5), UnitCode.EGG),
    )

    private companion object {
        const val COMPLETED_EVENT_ID = "detached-completed-cook"
        const val UNDONE_EVENT_ID = "detached-undone-cook"
        val today = DomainLocalDate.of(2026, 11, 14)
        val exportedAt = DomainInstant(Instant.parse("2026-11-14T12:00:00Z"))
        val deletedRecipe = RecipeId(uuid(1))
        val rawIngredientId = IngredientId(uuid(2))
        val preparedIngredientId = IngredientId(uuid(3))
        val locationId = KitchenLocationId(uuid(4))
        val stockLotId = InventoryLotId(uuid(5))
        val leftoverLotId = InventoryLotId(uuid(6))
        fun uuid(value: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-${(14000 + value).toString().padStart(12, '0')}")
    }
}
