package dev.kitchenapp.core.domain.identifiers

import kotlin.uuid.Uuid

/** Stable identity for one item in the current shopping list. */
@JvmInline
value class ShoppingListItemId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): ShoppingListItemId = ShoppingListItemId(Uuid.random())
    }
}
