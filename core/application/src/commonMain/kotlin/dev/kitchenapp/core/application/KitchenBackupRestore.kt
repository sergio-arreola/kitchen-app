package dev.kitchenapp.core.application

/** The source archive identity and validated content being previewed for restore. */
data class KitchenBackupRestoreRequest(
    val snapshot: KitchenBackupSnapshot,
    val archiveFingerprint: String,
    val snapshotFingerprint: String,
) {
    init {
        require(archiveFingerprint.matches(SHA256_PATTERN)) {
            "Backup archive fingerprint must be a SHA-256 hex value"
        }
        require(snapshotFingerprint.matches(SHA256_PATTERN)) {
            "Backup snapshot fingerprint must be a SHA-256 hex value"
        }
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class BackupRestoreRecordCounts(
    val recipes: Int,
    val categories: Int,
    val ingredients: Int,
    val locations: Int,
    val inventoryLots: Int,
    val images: Int,
    val shoppingListItems: Int,
    val cookingEvents: Int,
    val leftovers: Int,
) {
    companion object {
        fun from(snapshot: KitchenBackupSnapshot) = BackupRestoreRecordCounts(
            recipes = snapshot.recipes.size,
            categories = snapshot.categories.size,
            ingredients = snapshot.kitchen.ingredients.size,
            locations = snapshot.kitchen.inventory.locations.size,
            inventoryLots = snapshot.kitchen.inventory.lots.size,
            images = snapshot.recipes.count { it.image != null },
            shoppingListItems = snapshot.shoppingList?.items?.size ?: 0,
            cookingEvents = snapshot.cookingHistory.size,
            leftovers = snapshot.leftovers.size,
        )
    }
}

/** Preview for an explicitly confirmed all-or-nothing local data replacement. */
data class KitchenBackupRestorePreview(
    val archiveFingerprint: String,
    val snapshotFingerprint: String,
    val backupCounts: BackupRestoreRecordCounts,
    val currentCounts: BackupRestoreRecordCounts,
    val legacyInventoryTransactionCount: Long,
    val hasActiveCookingSession: Boolean,
    val historicalUndoUnavailableCount: Int,
    val currentStateFingerprint: String,
) {
    init {
        require(archiveFingerprint.matches(SHA256_PATTERN))
        require(snapshotFingerprint.matches(SHA256_PATTERN))
        require(currentStateFingerprint.matches(SHA256_PATTERN))
        require(legacyInventoryTransactionCount >= 0)
        require(historicalUndoUnavailableCount >= 0)
    }

    val canApply: Boolean
        get() = !hasActiveCookingSession

    val warnings: List<String>
        get() = buildList {
            add("This restore replaces all local backup-managed data.")
            if (legacyInventoryTransactionCount > 0) {
                add("$legacyInventoryTransactionCount legacy inventory transaction records are not in the backup and will be cleared.")
            }
            if (hasActiveCookingSession) {
                add("End the active cooking session before restoring.")
            }
            if (historicalUndoUnavailableCount > 0) {
                add("Undo may be unavailable for $historicalUndoUnavailableCount cook events because a prior inventory location is absent from the backup.")
            }
        }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

enum class KitchenBackupRestoreDecision {
    REPLACE_ALL_LOCAL_DATA,
}

interface KitchenBackupSnapshotFingerprinter {
    fun fingerprint(snapshot: KitchenBackupSnapshot): String
}

/** Persistence boundary for previewing and atomically replacing local backup-managed data. */
interface KitchenBackupRestoreRepository {
    fun preview(request: KitchenBackupRestoreRequest): KitchenBackupRestorePreview

    fun replaceAll(
        request: KitchenBackupRestoreRequest,
        preview: KitchenBackupRestorePreview,
        decision: KitchenBackupRestoreDecision,
    )
}

class PreviewKitchenBackupRestore(
    private val repository: KitchenBackupRestoreRepository,
) {
    fun preview(request: KitchenBackupRestoreRequest): KitchenBackupRestorePreview =
        repository.preview(request)
}

class ApplyKitchenBackupRestore(
    private val repository: KitchenBackupRestoreRepository,
) {
    fun apply(
        request: KitchenBackupRestoreRequest,
        preview: KitchenBackupRestorePreview,
        decision: KitchenBackupRestoreDecision,
    ) {
        require(preview.archiveFingerprint == request.archiveFingerprint) {
            "Restore preview belongs to a different backup"
        }
        require(preview.snapshotFingerprint == request.snapshotFingerprint) {
            "Backup content changed after the restore preview"
        }
        require(preview.backupCounts == BackupRestoreRecordCounts.from(request.snapshot)) {
            "Backup contents changed after preview"
        }
        require(decision == KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA) {
            "Choose whether to replace all local backup-managed data"
        }
        check(preview.canApply) {
            "End the active cooking session before restoring a backup"
        }
        repository.replaceAll(request, preview, decision)
    }
}
