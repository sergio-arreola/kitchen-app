package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.Recipe

/** Persistence boundary for canonical recipes. */
interface RecipeRepository {
    fun get(id: RecipeId): Recipe?
    fun getAll(): List<Recipe>
    fun save(recipe: Recipe)
    fun delete(id: RecipeId)
}
