package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.RecipeRepository
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe as DomainRecipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import dev.kitchenapp.data.database.generated.SelectRecipeById
import kotlin.uuid.Uuid

/** SQLDelight-backed canonical recipe storage. Ingredient rows must exist before saving recipes. */
class SqlDelightRecipeRepository(
    private val database: KitchenDatabase,
) : RecipeRepository {
    override fun get(id: RecipeId): DomainRecipe? =
        database.recipeQueries.selectRecipeById(id.toString()).executeAsOneOrNull()?.toDomainRecipe(database)

    override fun getAll(): List<DomainRecipe> =
        database.recipeQueries.selectAllRecipes().executeAsList()
            .mapNotNull { row -> get(RecipeId(Uuid.parse(row.id))) }

    override fun save(recipe: DomainRecipe) {
        database.transaction {
            val queries = database.recipeQueries
            val id = recipe.id.toString()
            if (queries.selectRecipeById(id).executeAsOneOrNull() == null) {
                queries.insertRecipe(
                    id,
                    recipe.name,
                    recipe.servings,
                    recipe.directionsText,
                    recipe.notes,
                    recipe.sourceName,
                    recipe.sourceUrl,
                )
            } else {
                queries.updateRecipe(
                    recipe.name,
                    recipe.servings,
                    recipe.directionsText,
                    recipe.notes,
                    recipe.sourceName,
                    recipe.sourceUrl,
                    id,
                )
            }

            queries.deleteRecipeCategories(id)
            recipe.categories.forEachIndexed { index, category ->
                queries.insertRecipeCategory(id, index.toLong(), category)
            }

            queries.deleteRecipeIngredients(id)
            recipe.ingredients.forEachIndexed { index, ingredient ->
                queries.insertRecipeIngredient(
                    ingredient.id.toString(),
                    id,
                    index.toLong(),
                    ingredient.ingredientId.toString(),
                    ingredient.quantity?.numerator,
                    ingredient.quantity?.denominator,
                    ingredient.quantityHigh?.numerator,
                    ingredient.quantityHigh?.denominator,
                    ingredient.unit?.code,
                    ingredient.preparation,
                    ingredient.importance.name,
                    ingredient.approximateLevel.name,
                    ingredient.rawLine,
                )
            }
        }
    }

    override fun delete(id: RecipeId) {
        database.recipeQueries.deleteRecipe(id.toString())
    }

    private fun SelectRecipeById.toDomainRecipe(database: KitchenDatabase): DomainRecipe {
        val ingredients = database.recipeQueries.selectRecipeIngredients(id).executeAsList().map { row ->
            RecipeIngredient(
                id = RecipeIngredientId(Uuid.parse(row.id)),
                ingredientId = IngredientId(Uuid.parse(row.ingredientId)),
                quantity = row.quantityNumerator?.let { Rational.of(it, requireNotNull(row.quantityDenominator)) },
                quantityHigh = row.quantityHighNumerator?.let { Rational.of(it, requireNotNull(row.quantityHighDenominator)) },
                unit = row.unitCode?.let { code ->
                    UnitCode.fromCode(code) ?: error("Unknown persisted unit code: $code")
                },
                preparation = row.preparation,
                importance = enumValueOf<IngredientImportance>(row.importance),
                approximateLevel = enumValueOf<ApproximateLevel>(row.approximateLevel),
                rawLine = row.rawLine,
            )
        }
        return DomainRecipe(
            id = RecipeId(Uuid.parse(id)),
            name = name,
            servings = servings,
            ingredients = ingredients,
            directionsText = directionsText,
            notes = notes,
            categories = database.recipeQueries.selectRecipeCategories(id).executeAsList(),
            sourceName = sourceName,
            sourceUrl = sourceUrl,
        )
    }
}
