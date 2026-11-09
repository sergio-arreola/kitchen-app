package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.RecipeImageRepository
import dev.kitchenapp.core.application.RecipeRepository
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe as DomainRecipe
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import dev.kitchenapp.data.database.generated.SelectRecipeById
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** SQLDelight-backed canonical recipe storage. Ingredient rows must exist before saving recipes. */
class SqlDelightRecipeRepository(
    private val database: KitchenDatabase,
) : RecipeRepository, RecipeImageRepository {
    override fun get(id: RecipeId): DomainRecipe? =
        database.recipeQueries.selectRecipeById(id.toString()).executeAsOneOrNull()?.toDomainRecipe(database)

    override fun getAll(): List<DomainRecipe> =
        database.recipeQueries.selectAllRecipes().executeAsList()
            .mapNotNull { row -> get(RecipeId(Uuid.parse(row.id))) }

    override fun save(recipe: DomainRecipe) = saveWithImage(recipe, image = null)

    override fun saveWithImage(recipe: DomainRecipe, image: RecipeImage?) {
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
                    recipe.prepTimeSeconds,
                    recipe.cookTimeSeconds,
                    recipe.chillingTimeSeconds,
                    recipe.totalTimeSeconds,
                    recipe.importedAt?.toString(),
                    recipe.imageSourceUrl,
                )
            } else {
                queries.updateRecipe(
                    recipe.name,
                    recipe.servings,
                    recipe.directionsText,
                    recipe.notes,
                    recipe.sourceName,
                    recipe.sourceUrl,
                    recipe.prepTimeSeconds,
                    recipe.cookTimeSeconds,
                    recipe.chillingTimeSeconds,
                    recipe.totalTimeSeconds,
                    recipe.importedAt?.toString(),
                    recipe.imageSourceUrl,
                    id,
                )
            }

            val categories = recipe.categories.map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(String::lowercase)
            categories.forEach(queries::insertRecipeCategoryName)
            queries.deleteRecipeCategories(id)
            categories.forEachIndexed { index, category ->
                queries.insertRecipeCategory(id, index.toLong(), category)
            }

            queries.deleteRecipeMealTypes(id)
            recipe.mealTypes.forEachIndexed { index, mealType ->
                queries.insertRecipeMealType(id, index.toLong(), mealType)
            }

            queries.deleteRecipeEquipmentSubstitutes(id)
            queries.deleteRecipeEquipmentRequirements(id)
            recipe.equipmentRequirements.forEachIndexed { index, requirement ->
                val position = index.toLong()
                queries.insertRecipeEquipmentRequirement(
                    id,
                    position,
                    requirement.equipment,
                    requirement.kind.name,
                )
                requirement.acceptableSubstitutes
                    .sortedWith(compareBy({ it.lowercase() }, { it }))
                    .forEachIndexed { substituteIndex, substitute ->
                        queries.insertRecipeEquipmentSubstitute(
                            id,
                            position,
                            substituteIndex.toLong(),
                            substitute,
                        )
                    }
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
            image?.let { queries.insertRecipeImage(id, it.bytes, it.mediaType) }
        }
    }

    override fun delete(id: RecipeId) {
        database.recipeQueries.deleteRecipe(id.toString())
    }

    override fun getRecipeImage(recipeId: RecipeId): RecipeImage? =
        database.recipeQueries.selectRecipeImageById(recipeId.toString()).executeAsOneOrNull()
            ?.let { RecipeImage(it.bytes, it.mediaType) }

    override fun getCategories(): List<String> =
        database.recipeQueries.selectAllRecipeCategoryNames().executeAsList()

    override fun createCategory(name: String) {
        database.recipeQueries.insertRecipeCategoryName(name.trim())
    }

    override fun renameCategory(oldName: String, newName: String) {
        val old = oldName.trim()
        val new = newName.trim()
        database.transaction {
            val queries = database.recipeQueries
            val categoryNames = queries.selectAllRecipeCategoryNames().executeAsList()
            val storedOld = categoryNames.firstOrNull { it.equals(old, ignoreCase = true) } ?: return@transaction
            val storedNew = categoryNames.firstOrNull { it.equals(new, ignoreCase = true) } ?: new
            if (storedOld.equals(storedNew, ignoreCase = true)) return@transaction

            updateRecipeCategories { categories ->
                categories.map { if (it.equals(storedOld, ignoreCase = true)) storedNew else it }
                    .distinctBy(String::lowercase)
            }
            queries.deleteRecipeCategoryName(storedOld)
            if (storedNew !in categoryNames) queries.insertRecipeCategoryName(storedNew)
        }
    }

    override fun removeCategory(name: String) {
        val category = name.trim()
        database.transaction {
            updateRecipeCategories { categories ->
                categories.filterNot { it.equals(category, ignoreCase = true) }
            }
            database.recipeQueries.deleteRecipeCategoryName(category)
        }
    }

    private fun updateRecipeCategories(transform: (List<String>) -> List<String>) {
        val queries = database.recipeQueries
        queries.selectAllRecipes().executeAsList().forEach { row ->
            val current = queries.selectRecipeCategories(row.id).executeAsList()
            val updated = transform(current)
            if (updated != current) {
                queries.deleteRecipeCategories(row.id)
                updated.forEachIndexed { index, category ->
                    queries.insertRecipeCategory(row.id, index.toLong(), category)
                }
            }
        }
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
        val queries = database.recipeQueries
        val mealTypes = queries.selectRecipeMealTypes(id).executeAsList()
        val substitutesByRequirement = queries.selectRecipeEquipmentSubstitutes(id).executeAsList()
            .groupBy({ it.requirementPosition }, { it.name })
        val equipmentRequirements = queries.selectRecipeEquipmentRequirements(id).executeAsList().map { row ->
            EquipmentRequirement(
                equipment = row.equipment,
                kind = enumValueOf<EquipmentRequirementKind>(row.kind),
                acceptableSubstitutes = substitutesByRequirement[row.position].orEmpty().toSet(),
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
            prepTimeSeconds = prepTimeSeconds,
            cookTimeSeconds = cookTimeSeconds,
            chillingTimeSeconds = chillingTimeSeconds,
            totalTimeSeconds = totalTimeSeconds,
            imageSourceUrl = imageSourceUrl,
            importedAt = importedAt?.let { DomainInstant(Instant.parse(it)) },
            mealTypes = mealTypes,
            equipmentRequirements = equipmentRequirements,
        )
    }
}
