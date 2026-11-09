package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.ShoppingListItemId
import dev.kitchenapp.core.domain.shopping.ShoppingList

/** Local persistence boundary for the current shopping list. */
interface ShoppingListRepository {
    fun getCurrent(): ShoppingList?
    fun saveCurrent(list: ShoppingList)
    fun setItemChecked(itemId: ShoppingListItemId, checked: Boolean): Boolean
    fun clearCurrent()
}
