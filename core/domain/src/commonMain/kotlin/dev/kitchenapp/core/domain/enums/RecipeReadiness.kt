package dev.kitchenapp.core.domain.enums

/**
 * Readiness of a recipe with respect to current inventory.
 *
 * Answers "can this recipe be cooked now?" given tracked stock.
 */
enum class RecipeReadiness(val value: Int) {
    /** At least one required ingredient is missing entirely. */
    MISSING_INGREDIENTS(0),

    /** All required ingredients exist, but stock is insufficient. */
    INSUFFICIENT_STOCK(1),

    /** All required ingredients are available in sufficient quantity. */
    READY(2);

    companion object {
        fun fromValue(value: Int): RecipeReadiness? = entries.find { it.value == value }
    }
}
