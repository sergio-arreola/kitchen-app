package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.BackupRestoreRecordCounts
import dev.kitchenapp.core.application.KitchenBackupRestoreDecision
import dev.kitchenapp.core.application.KitchenBackupRestorePreview
import dev.kitchenapp.core.application.KitchenBackupRestoreRepository
import dev.kitchenapp.core.application.KitchenBackupRestoreRequest
import dev.kitchenapp.core.application.KitchenBackupSnapshotFingerprinter
import dev.kitchenapp.core.application.KitchenBackupSnapshotRepository
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.data.database.generated.KitchenDatabase

/** Replaces all backup-managed local data within one SQLite transaction. */
class SqlDelightKitchenBackupRestoreRepository(
    private val database: KitchenDatabase,
    private val snapshots: KitchenBackupSnapshotRepository = SqlDelightKitchenBackupSnapshotRepository(database),
    private val fingerprinter: KitchenBackupSnapshotFingerprinter,
) : KitchenBackupRestoreRepository {
    override fun preview(request: KitchenBackupRestoreRequest): KitchenBackupRestorePreview {
        require(fingerprinter.fingerprint(request.snapshot) == request.snapshotFingerprint) {
            "Backup snapshot changed after archive validation"
        }
        var preview: KitchenBackupRestorePreview? = null
        database.transaction {
            val current = snapshots.load()
            val activeCookingSession = database.recipeQueries.selectActiveCookingSession()
                .executeAsOneOrNull() != null
            val incomingLocationIds = request.snapshot.kitchen.inventory.locations.map { it.id }.toSet()
            preview = KitchenBackupRestorePreview(
                archiveFingerprint = request.archiveFingerprint,
                snapshotFingerprint = request.snapshotFingerprint,
                backupCounts = BackupRestoreRecordCounts.from(request.snapshot),
                currentCounts = BackupRestoreRecordCounts.from(current),
                legacyInventoryTransactionCount = database.recipeQueries
                    .selectLegacyInventoryTransactionCount().executeAsOne(),
                hasActiveCookingSession = activeCookingSession,
                historicalUndoUnavailableCount = request.snapshot.cookingHistory.count { history ->
                    history.undoneOn == null && history.inventoryBefore.any { it.locationId !in incomingLocationIds }
                },
                currentStateFingerprint = fingerprinter.fingerprint(current),
            )
        }
        return requireNotNull(preview)
    }

    override fun replaceAll(
        request: KitchenBackupRestoreRequest,
        preview: KitchenBackupRestorePreview,
        decision: KitchenBackupRestoreDecision,
    ) {
        require(decision == KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA) {
            "Restore requires explicit confirmation to replace all local data"
        }
        require(preview.archiveFingerprint == request.archiveFingerprint) {
            "Restore preview belongs to a different backup"
        }
        require(preview.backupCounts == BackupRestoreRecordCounts.from(request.snapshot)) {
            "Backup content changed after the restore preview"
        }
        require(preview.snapshotFingerprint == request.snapshotFingerprint) {
            "Restore preview belongs to different backup content"
        }
        require(fingerprinter.fingerprint(request.snapshot) == request.snapshotFingerprint) {
            "Backup snapshot changed after the restore preview"
        }

        database.transaction {
            val queries = database.recipeQueries
            check(queries.selectActiveCookingSession().executeAsOneOrNull() == null) {
                "End the active cooking session before restoring a backup"
            }
            val current = snapshots.load()
            val legacyCount = queries.selectLegacyInventoryTransactionCount().executeAsOne()
            if (
                BackupRestoreRecordCounts.from(current) != preview.currentCounts ||
                legacyCount != preview.legacyInventoryTransactionCount ||
                fingerprinter.fingerprint(current) != preview.currentStateFingerprint
            ) {
                error("Kitchen data changed after the restore preview. Create a new preview before restoring.")
            }

            check(fingerprinter.fingerprint(request.snapshot) == request.snapshotFingerprint) {
                "Backup snapshot changed while restore was being applied"
            }

            val recipes = SqlDelightRecipeRepository(database)
            val kitchen = SqlDelightKitchenRepository(database)
            val shoppingLists = SqlDelightShoppingListRepository(database)

            queries.deleteCurrentShoppingList()
            queries.deleteAllCookEventsForBackupRestore()
            queries.deleteAllRecipesForBackupRestore()
            queries.deleteLegacyInventoryTransactionsForBackupRestore()
            queries.deleteAllRecipeCategoryNamesForBackupRestore()
            kitchen.save(request.snapshot.kitchen)

            request.snapshot.recipes.forEach { entry ->
                recipes.saveWithImage(entry.recipe, entry.image)
            }
            request.snapshot.categories.forEach(queries::insertRecipeCategoryName)

            request.snapshot.cookingHistory.forEach { history ->
                val event = history.event
                queries.insertRestoredCookEvent(
                    event.id,
                    event.recipeId.toString(),
                    event.cookedOn.toString(),
                    event.servings?.toString(),
                    event.note,
                    history.leftoverFollowUpPending.toLong(),
                    history.leftoverReminderClaimed.toLong(),
                )
                event.consumed.forEach { consumed ->
                    queries.insertCookConsumption(
                        event.id,
                        consumed.lotId.toString(),
                        consumed.quantity?.numerator,
                        consumed.quantity?.denominator,
                        consumed.unit?.code,
                    )
                }
                history.inventoryBefore.forEach { lot ->
                    insertLotBeforeCooking(event.id, lot)
                }
                history.undoneOn?.let { undoneOn ->
                    queries.insertCookEventUndo(event.id, undoneOn.toString())
                }
            }
            request.snapshot.leftovers.forEach { leftover ->
                queries.insertCookLeftover(
                    leftover.id.toString(),
                    leftover.cookEventId,
                    leftover.ingredientId?.toString(),
                    leftover.name,
                    leftover.quantity?.numerator,
                    leftover.quantity?.denominator,
                    leftover.unit?.code,
                    leftover.preparedOn.toString(),
                    leftover.useBy?.toString(),
                )
            }
            request.snapshot.shoppingList?.let(shoppingLists::saveCurrent)
        }
    }

    private fun insertLotBeforeCooking(eventId: String, lot: InventoryLot) {
        val (kind, quantity, unitCode, approximateLevel) = lot.amount.toColumns()
        database.recipeQueries.insertLotBeforeCooking(
            eventId,
            lot.id.toString(),
            lot.ingredientId.toString(),
            lot.locationId.toString(),
            kind,
            quantity?.numerator,
            quantity?.denominator,
            unitCode,
            approximateLevel,
            lot.purchasedOn?.toString(),
            lot.openedOn?.toString(),
            lot.frozenOn?.toString(),
            lot.manufacturerUseBy?.toString(),
            lot.userUseBy?.toString(),
        )
    }

    private fun InventoryAmount.toColumns(): AmountColumns = when (this) {
        InventoryAmount.PresenceOnly -> AmountColumns("PRESENCE_ONLY", null, null, null)
        is InventoryAmount.Approximate -> AmountColumns("APPROXIMATE", quantity, unit?.code, level.name)
        is InventoryAmount.Exact -> AmountColumns("EXACT", quantity, unit?.code, ApproximateLevel.EXACT.name)
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L

    private data class AmountColumns(
        val kind: String,
        val quantity: Rational?,
        val unitCode: String?,
        val approximateLevel: String?,
    )
}
