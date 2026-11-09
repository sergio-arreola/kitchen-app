package dev.kitchenapp.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Slice12RecipeMetadataPersistenceTest {
    @Test
    fun `migration adds empty metadata for previously saved recipes`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.use {
            KitchenDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 5)
            val oldDatabase = KitchenDatabase(driver)
            val recipe = recipe()
            oldDatabase.recipeQueries.insertRecipe(
                recipe.id.toString(),
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

            KitchenDatabase.Schema.migrate(driver, oldVersion = 5, newVersion = KitchenDatabase.Schema.version)
            val migrated = SqlDelightRecipeRepository(KitchenDatabase(driver)).get(recipe.id)

            assertEquals(emptyList(), migrated?.mealTypes)
            assertEquals(emptyList(), migrated?.equipmentRequirements)
        }
    }

    @Test
    fun `meal and equipment metadata round trip and cascade on recipe deletion`() {
        val (driver, database) = newDatabase()
        driver.use {
            val recipes = SqlDelightRecipeRepository(database)
            val original = recipe().copy(
                mealTypes = listOf("Dinner", "Comfort food"),
                equipmentRequirements = listOf(
                    EquipmentRequirement(
                        equipment = "Dutch oven",
                        kind = EquipmentRequirementKind.SUBSTITUTABLE,
                        acceptableSubstitutes = setOf("Heavy pot", "Cocotte"),
                    ),
                    EquipmentRequirement(
                        equipment = "Oven",
                        kind = EquipmentRequirementKind.PREFERRED,
                    ),
                ),
            )

            recipes.save(original)
            assertEquals(original, recipes.get(original.id))

            val updated = original.copy(
                mealTypes = listOf("Breakfast"),
                equipmentRequirements = listOf(
                    EquipmentRequirement("Skillet", EquipmentRequirementKind.REQUIRED),
                ),
            )
            recipes.save(updated)
            assertEquals(updated, recipes.get(updated.id))

            recipes.delete(updated.id)

            assertTrue(database.recipeQueries.selectRecipeMealTypes(updated.id.toString()).executeAsList().isEmpty())
            assertTrue(
                database.recipeQueries.selectRecipeEquipmentRequirements(updated.id.toString())
                    .executeAsList().isEmpty(),
            )
            assertTrue(
                database.recipeQueries.selectRecipeEquipmentSubstitutes(updated.id.toString())
                    .executeAsList().isEmpty(),
            )
        }
    }

    private fun recipe() = Recipe(
        name = "Tomato stew",
        ingredients = emptyList(),
        directionsText = "Simmer.",
    )

    private fun newDatabase(): Pair<JdbcSqliteDriver, KitchenDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return driver to KitchenDatabaseFactory.open(driver)
    }
}
