package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.KitchenBackupLeftover
import dev.kitchenapp.core.application.KitchenBackupSnapshot
import dev.kitchenapp.core.application.KitchenBackupSnapshotRepository
import dev.kitchenapp.core.application.RecipeBackupEntry
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate

/** Reads all persisted backup data under one SQLite read transaction. */
class SqlDelightKitchenBackupSnapshotRepository(
    private val database: KitchenDatabase,
) : KitchenBackupSnapshotRepository {
    override fun load(): KitchenBackupSnapshot {
        var snapshot: KitchenBackupSnapshot? = null
        database.transaction {
            val recipes = SqlDelightRecipeRepository(database)
            val kitchen = SqlDelightKitchenRepository(database)
            val recipeEntries = recipes.getAll().map { recipe ->
                RecipeBackupEntry(recipe, recipes.getRecipeImage(recipe.id))
            }
            val leftovers = database.recipeQueries.selectLeftovers().executeAsList().map { row ->
                KitchenBackupLeftover(
                    id = InventoryLotId(Uuid.parse(row.id)),
                    cookEventId = row.cookEventId,
                    ingredientId = row.ingredientId?.let { IngredientId(Uuid.parse(it)) },
                    name = row.name,
                    quantity = when {
                        row.quantityNumerator == null && row.quantityDenominator == null -> null
                        row.quantityNumerator != null && row.quantityDenominator != null ->
                            Rational.of(row.quantityNumerator, row.quantityDenominator)
                        else -> error("Persisted leftover has an incomplete quantity")
                    },
                    unit = row.unitCode?.let { code ->
                        UnitCode.fromCode(code) ?: error("Unknown persisted unit code: $code")
                    },
                    preparedOn = DomainLocalDate(LocalDate.parse(row.preparedOn)),
                    useBy = row.useBy?.let { DomainLocalDate(LocalDate.parse(it)) },
                )
            }
            snapshot = KitchenBackupSnapshot(
                recipes = recipeEntries,
                categories = recipes.getCategories(),
                kitchen = kitchen.load(),
                shoppingList = SqlDelightShoppingListRepository(database).getCurrent(),
                cookingHistory = SqlDelightCookingRepository(database, kitchen).getHistory(),
                leftovers = leftovers,
            )
        }
        return requireNotNull(snapshot)
    }
}
