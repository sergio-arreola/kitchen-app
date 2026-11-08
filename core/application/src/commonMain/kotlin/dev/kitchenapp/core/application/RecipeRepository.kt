package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.Recipe

/** Persistence boundary for canonical recipes. */
interface RecipeRepository {
    fun get(id: RecipeId): Recipe?
    fun getAll(): List<Recipe>
    fun save(recipe: Recipe)
    fun saveWithImage(recipe: Recipe, image: dev.kitchenapp.core.domain.recipe.RecipeImage?) {
        save(recipe)
    }
    fun delete(id: RecipeId)
    fun getCategories(): List<String>
    fun createCategory(name: String)
    fun renameCategory(oldName: String, newName: String)
    fun removeCategory(name: String)
}
