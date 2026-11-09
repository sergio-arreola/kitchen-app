package dev.kitchenapp.core.domain.shopping

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.ShoppingListItemId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.units.UnitCode

/** The locally saved shopping list currently being used. */
data class ShoppingList(
    val createdAt: DomainInstant,
    val items: List<ShoppingListItem>,
) {
    init {
        require(items.map { it.id }.distinct().size == items.size) {
            "Shopping list item IDs must be unique"
        }
    }
}

/** A missing ingredient and its provenance, with an optional checked state. */
data class ShoppingListItem(
    val id: ShoppingListItemId = ShoppingListItemId.generate(),
    val ingredientId: IngredientId,
    val ingredientName: String,
    val quantity: Rational?,
    val unit: UnitCode?,
    val sourceRecipeIds: Set<RecipeId>,
    val checked: Boolean = false,
) {
    init {
        require(ingredientName.isNotBlank()) { "Ingredient name cannot be blank" }
        require(quantity == null || quantity.isPositive()) { "Shopping quantity must be positive" }
        require(sourceRecipeIds.isNotEmpty()) { "Shopping item must have recipe provenance" }
    }
}
