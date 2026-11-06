package dev.kitchenapp.core.domain.identifiers

import kotlin.uuid.Uuid

/** Stable identity for a canonical ingredient. */
@JvmInline
value class IngredientId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): IngredientId = IngredientId(Uuid.random())
    }
}

/** Stable identity for a canonical recipe. */
@JvmInline
value class RecipeId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): RecipeId = RecipeId(Uuid.random())
    }
}

/** Stable identity for one ingredient row within a recipe. */
@JvmInline
value class RecipeIngredientId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): RecipeIngredientId = RecipeIngredientId(Uuid.random())
    }
}

/** Stable identity for an inventory lot. */
@JvmInline
value class InventoryLotId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): InventoryLotId = InventoryLotId(Uuid.random())
    }
}

/** Stable identity for a user-configured kitchen location. */
@JvmInline
value class KitchenLocationId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): KitchenLocationId = KitchenLocationId(Uuid.random())
    }
}
