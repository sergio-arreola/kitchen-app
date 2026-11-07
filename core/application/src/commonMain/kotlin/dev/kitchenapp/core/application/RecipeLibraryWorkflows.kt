package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.ingredient.IngredientResolution
import dev.kitchenapp.core.domain.ingredient.IngredientResolver
import dev.kitchenapp.core.domain.recipe.CanonicalRecipeDraft
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.identifiers.RecipeId

sealed interface RecipeImportRequest {
    data class PaprikaArchive(val source: String) : RecipeImportRequest

    data class Url(val url: String) : RecipeImportRequest

    data class Manual(
        val name: String,
        val servings: String? = null,
        val ingredientLines: List<String> = emptyList(),
        val directionsText: String = "",
        val notes: String? = null,
        val categories: List<String> = emptyList(),
        val sourceUrl: String? = null,
    ) : RecipeImportRequest
}

enum class RecipeImportOutcome {
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    NEEDS_REVIEW,
    FAILED,
    SKIPPED,
}

data class RecipeImportCandidate(
    val sourceKey: String,
    val outcome: RecipeImportOutcome,
    val draft: CanonicalRecipeDraft? = null,
    val warnings: List<String> = emptyList(),
    val reason: String? = null,
    val suggestedName: String? = null,
    /** Existing recipe ID or the source key of an earlier record in the batch. */
    val duplicateOf: String? = null,
)

/** Source adapters return every record, preserving failures and skipped entries. */
interface RecipeImportGateway {
    suspend fun preview(
        request: RecipeImportRequest,
        ingredients: List<Ingredient>,
        existingRecipes: List<Recipe>,
    ): List<RecipeImportCandidate>
}

class PreviewRecipeImport(
    private val gateway: RecipeImportGateway,
    private val recipes: RecipeRepository,
    private val kitchen: KitchenRepository,
) {
    suspend fun preview(request: RecipeImportRequest): List<RecipeImportCandidate> =
        gateway.preview(request, kitchen.load().ingredients, recipes.getAll())
}

class GetRecipe(private val recipes: RecipeRepository) {
    fun get(id: RecipeId): Recipe? = recipes.get(id)
}

/** Adds a canonical ingredient without creating an inventory lot. */
class AddCanonicalIngredient(private val kitchen: KitchenRepository) {
    fun add(name: String, aliases: Set<String> = emptySet()): Ingredient =
        addAll(listOf(name), aliasesByName = mapOf(name to aliases)).single()

    /** Resolves reviewed names and stores any new canonical entries in one kitchen snapshot update. */
    fun addAll(
        names: List<String>,
        aliasesByName: Map<String, Set<String>> = emptyMap(),
    ): List<Ingredient> {
        val snapshot = kitchen.load()
        var catalog = snapshot.ingredients
        val resolved = names.map { input ->
            val name = input.trim()
            require(name.isNotEmpty()) { "Ingredient name cannot be blank" }
            when (val resolution = IngredientResolver(catalog).resolve(name)) {
                is IngredientResolution.Resolved -> resolution.ingredient
                is IngredientResolution.NeedsReview -> error("Choose one of the matching canonical ingredients")
                is IngredientResolution.Unresolved -> {
                    val ingredient = Ingredient(
                        name = name,
                        aliases = aliasesByName[name].orEmpty().map(String::trim).toSet(),
                    )
                    catalog = catalog + ingredient
                    ingredient
                }
            }
        }
        if (catalog != snapshot.ingredients) kitchen.save(snapshot.copy(ingredients = catalog))
        return resolved
    }
}

data class RecipeLibraryUseCases(
    val searchRecipes: SearchRecipes,
    val getRecipe: GetRecipe,
    val saveReviewedRecipe: SaveReviewedRecipe,
    val addCanonicalIngredient: AddCanonicalIngredient,
    val previewRecipeImport: PreviewRecipeImport,
)
