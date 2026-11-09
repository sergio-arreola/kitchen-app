package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.ShoppingListRepository
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.ShoppingListItemId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Persists one current shopping list and its checked state locally. */
class SqlDelightShoppingListRepository(
    private val database: KitchenDatabase,
) : ShoppingListRepository {
    override fun getCurrent(): ShoppingList? {
        val createdAt = database.recipeQueries.selectCurrentShoppingList().executeAsOneOrNull()
            ?.let { DomainInstant(Instant.parse(it)) } ?: return null
        val recipeIdsByItem = database.recipeQueries.selectCurrentShoppingListItemRecipes()
            .executeAsList()
            .groupBy({ it.shoppingItemId }, { RecipeId(Uuid.parse(it.recipeId)) })
        val items = database.recipeQueries.selectCurrentShoppingListItems().executeAsList().map { row ->
            ShoppingListItem(
                id = ShoppingListItemId(Uuid.parse(row.id)),
                ingredientId = IngredientId(Uuid.parse(row.ingredientId)),
                ingredientName = row.ingredientName,
                quantity = row.quantityNumerator?.let {
                    Rational.of(it, requireNotNull(row.quantityDenominator))
                },
                unit = row.unitCode?.let { code ->
                    UnitCode.fromCode(code) ?: error("Unknown persisted unit code: $code")
                },
                sourceRecipeIds = recipeIdsByItem[row.id].orEmpty().toSet(),
                checked = row.checked != 0L,
            )
        }
        return ShoppingList(createdAt, items)
    }

    override fun saveCurrent(list: ShoppingList) {
        database.transaction {
            val queries = database.recipeQueries
            queries.upsertCurrentShoppingList(list.createdAt.toString())
            queries.deleteCurrentShoppingListItemRecipes()
            queries.deleteCurrentShoppingListItems()
            list.items.forEach { item ->
                queries.insertCurrentShoppingListItem(
                    item.id.toString(),
                    item.ingredientId.toString(),
                    item.ingredientName,
                    item.quantity?.numerator,
                    item.quantity?.denominator,
                    item.unit?.code,
                    if (item.checked) 1L else 0L,
                )
                item.sourceRecipeIds.sortedBy(RecipeId::toString).forEach { recipeId ->
                    queries.insertShoppingListItemRecipe(item.id.toString(), recipeId.toString())
                }
            }
        }
    }

    override fun setItemChecked(itemId: ShoppingListItemId, checked: Boolean): Boolean {
        val queries = database.recipeQueries
        val id = itemId.toString()
        if (queries.selectCurrentShoppingListItemExists(id).executeAsOneOrNull() == null) return false
        queries.setCurrentShoppingListItemChecked(if (checked) 1L else 0L, id)
        return true
    }

    override fun clearCurrent() {
        database.recipeQueries.deleteCurrentShoppingList()
    }
}
