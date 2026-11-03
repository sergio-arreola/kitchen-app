package dev.kitchenapp.core.domain.ingredient

import dev.kitchenapp.core.domain.identifiers.IngredientId

/** Source-independent canonical ingredient and its explicitly curated aliases. */
public data class Ingredient(
    public val id: IngredientId = IngredientId.generate(),
    public val name: String,
    public val aliases: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "Ingredient name cannot be blank" }
        require(aliases.none { it.isBlank() }) { "Ingredient aliases cannot be blank" }
    }

    public val normalizedName: String
        get() = IngredientNameNormalizer.normalize(name)
}
