package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.ShoppingListItemId
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.engine.matching.RecipeMatch
import dev.kitchenapp.core.engine.matching.ShoppingAggregator
import kotlin.time.Clock

/** Creates and saves one offline shopping list from selected recipe matches. */
class CreateShoppingList(
    private val kitchen: KitchenRepository,
    private val shoppingLists: ShoppingListRepository,
    private val aggregator: ShoppingAggregator = ShoppingAggregator(),
    private val clock: Clock = Clock.System,
) {
    fun create(selectedRecipes: List<RecipeMatch>): ShoppingList {
        require(selectedRecipes.isNotEmpty()) { "Select at least one recipe" }
        val ingredientNames = kitchen.load().ingredients.associate { it.id to it.name }
        val items = aggregator.aggregate(selectedRecipes).map { line ->
            ShoppingListItem(
                id = ShoppingListItemId.generate(),
                ingredientId = line.ingredientId,
                ingredientName = ingredientNames[line.ingredientId]
                    ?: error("Shopping ingredient is missing from the kitchen catalog"),
                quantity = line.quantity,
                unit = line.unit,
                sourceRecipeIds = line.recipeIds,
            )
        }
        return ShoppingList(DomainInstant(clock.now()), items).also(shoppingLists::saveCurrent)
    }
}

class GetShoppingList(private val shoppingLists: ShoppingListRepository) {
    fun get(): ShoppingList? = shoppingLists.getCurrent()
}

class SetShoppingListItemChecked(private val shoppingLists: ShoppingListRepository) {
    fun update(itemId: ShoppingListItemId, checked: Boolean): Boolean =
        shoppingLists.setItemChecked(itemId, checked)
}

class ClearShoppingList(private val shoppingLists: ShoppingListRepository) {
    fun clear() = shoppingLists.clearCurrent()
}
