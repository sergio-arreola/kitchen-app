package dev.kitchenapp.core.domain.ingredient

import dev.kitchenapp.core.domain.identifiers.IngredientId

/** Source-independent canonical ingredient and its explicitly curated aliases. */
data class Ingredient(
    val id: IngredientId = IngredientId.generate(),
    val name: String,
    val aliases: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "Ingredient name cannot be blank" }
        require(aliases.none { it.isBlank() }) { "Ingredient aliases cannot be blank" }
    }

    val normalizedName: String
        get() = IngredientNameNormalizer.normalize(name)
}
