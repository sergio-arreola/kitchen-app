package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.identifiers.RecipeId

/** Source-independent saved recipe. */
public data class Recipe(
    public val id: RecipeId = RecipeId.generate(),
    public val name: String,
    public val servings: String? = null,
    public val ingredients: List<RecipeIngredient>,
    public val directionsText: String,
    public val notes: String? = null,
    public val categories: List<String> = emptyList(),
    public val sourceName: String? = null,
    public val sourceUrl: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Recipe name cannot be blank" }
        require(ingredients.map { it.id }.distinct().size == ingredients.size) {
            "Recipe contains duplicate ingredient row ids"
        }
    }
}
