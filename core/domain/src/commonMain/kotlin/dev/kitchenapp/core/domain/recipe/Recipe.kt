package dev.kitchenapp.core.domain.recipe

import dev.kitchenapp.core.domain.identifiers.RecipeId

/** Source-independent saved recipe. */
data class Recipe(
    val id: RecipeId = RecipeId.generate(),
    val name: String,
    val servings: String? = null,
    val ingredients: List<RecipeIngredient>,
    val directionsText: String,
    val notes: String? = null,
    val categories: List<String> = emptyList(),
    val sourceName: String? = null,
    val sourceUrl: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Recipe name cannot be blank" }
        require(ingredients.map { it.id }.distinct().size == ingredients.size) {
            "Recipe contains duplicate ingredient row ids"
        }
    }
}
