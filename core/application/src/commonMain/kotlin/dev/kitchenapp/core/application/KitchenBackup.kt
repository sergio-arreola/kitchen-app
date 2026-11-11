package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode

/** One transactionally consistent view of the user-owned data included in a backup. */
data class KitchenBackupSnapshot(
    val recipes: List<RecipeBackupEntry>,
    val categories: List<String>,
    val kitchen: KitchenSnapshot,
    val shoppingList: ShoppingList?,
    val cookingHistory: List<CookHistoryEntry>,
    val leftovers: List<KitchenBackupLeftover>,
) {
    init {
        require(recipes.map { it.recipe.id }.distinct().size == recipes.size) {
            "Backup recipe IDs must be unique"
        }
        require(categories.none(String::isBlank)) { "Backup categories cannot be blank" }
        require(leftovers.map { it.id }.distinct().size == leftovers.size) {
            "Backup leftover IDs must be unique"
        }
    }
}

data class RecipeBackupEntry(
    val recipe: Recipe,
    val image: RecipeImage?,
)

data class KitchenBackupLeftover(
    val id: InventoryLotId,
    val cookEventId: String,
    val ingredientId: IngredientId,
    val name: String,
    val quantity: Rational,
    val unit: UnitCode?,
    val preparedOn: DomainLocalDate,
    val useBy: DomainLocalDate?,
)

/** Reads all backup state from one local persistence snapshot. */
interface KitchenBackupSnapshotRepository {
    fun load(): KitchenBackupSnapshot
}

interface KitchenBackupArchiveEncoder {
    fun encode(snapshot: KitchenBackupSnapshot, exportedAt: DomainInstant): ByteArray
}

class ExportKitchenBackup(
    private val snapshots: KitchenBackupSnapshotRepository,
    private val encoder: KitchenBackupArchiveEncoder,
) {
    fun export(exportedAt: DomainInstant): ByteArray =
        encoder.encode(snapshots.load(), exportedAt)
}
