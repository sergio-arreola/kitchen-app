package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.inventory.KitchenInventory

/** User-owned kitchen state stored atomically as one local snapshot. */
data class KitchenSnapshot(
    val ingredients: List<Ingredient> = emptyList(),
    val inventory: KitchenInventory = KitchenInventory(),
    val availableEquipment: Set<String> = emptySet(),
    val householdRules: HouseholdIngredientRules = HouseholdIngredientRules(),
) {
    init {
        require(ingredients.map { it.id }.distinct().size == ingredients.size) {
            "Ingredient IDs must be unique"
        }
        require(availableEquipment.none { it.isBlank() }) {
            "Available equipment names cannot be blank"
        }
    }
}

/** Persistence boundary for kitchen inventory, canonical ingredients, and household settings. */
interface KitchenRepository {
    fun load(): KitchenSnapshot
    fun save(snapshot: KitchenSnapshot)
}
