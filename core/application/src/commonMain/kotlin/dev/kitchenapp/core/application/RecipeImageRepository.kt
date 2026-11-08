package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.RecipeImage

/** Persistence boundary for the locally retained source image attached to a recipe. */
interface RecipeImageRepository {
    fun getRecipeImage(recipeId: RecipeId): RecipeImage?
}

class GetRecipeImage(private val images: RecipeImageRepository) {
    fun get(recipeId: RecipeId): RecipeImage? = images.getRecipeImage(recipeId)
}
