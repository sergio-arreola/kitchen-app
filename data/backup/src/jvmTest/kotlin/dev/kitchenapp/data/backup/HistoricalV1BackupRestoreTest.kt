package dev.kitchenapp.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.kitchenapp.core.application.ApplyKitchenBackupRestore
import dev.kitchenapp.core.application.KitchenBackupRestoreDecision
import dev.kitchenapp.core.application.PreviewKitchenBackupRestore
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.data.database.KitchenDatabaseFactory
import dev.kitchenapp.data.database.SqlDelightKitchenBackupRestoreRepository
import dev.kitchenapp.data.database.SqlDelightKitchenBackupSnapshotRepository
import dev.kitchenapp.data.database.SqlDelightRecipeRepository
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoricalV1BackupRestoreTest {
    @Test
    fun `13_1 archive validates and restores populated state`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/historical-v1-13-1.kitchenbackup"))
            .use { it.readBytes() }
        assertEquals(
            "214ce178f11d531538799dd6de40cfb4230ec79bc4c06142c210bfff2cf8c657",
            sha256BackupBytes(bytes),
        )

        val validated = KitchenBackupArchiveValidator().validate(bytes)
        assertEquals(1, validated.manifest.formatVersion)
        assertEquals("2026-01-04T12:00:00Z", validated.manifest.exportedAt)
        val request = ValidatedKitchenBackupArchiveDecoder().decode(bytes)
        val expected = request.snapshot
        val recipe = expected.recipes.single()
        assertEquals("Synthetic bread", recipe.recipe.name)
        assertEquals("https://example.invalid/recipe", recipe.recipe.sourceUrl)
        assertEquals("https://example.invalid/image.png", recipe.recipe.imageSourceUrl)
        assertEquals(300L, recipe.recipe.prepTimeSeconds)
        assertEquals(900L, recipe.recipe.cookTimeSeconds)
        assertEquals(1200L, recipe.recipe.totalTimeSeconds)
        val image = assertNotNull(recipe.image)
        assertEquals("image/png", image.mediaType)
        val decodedImage = assertNotNull(ImageIO.read(ByteArrayInputStream(image.bytes)))
        assertEquals(1, decodedImage.width)
        assertEquals(1, decodedImage.height)
        assertEquals(listOf("Synthetic"), expected.categories)
        assertEquals(2, expected.kitchen.ingredients.size)
        assertEquals(1, expected.kitchen.inventory.lots.size)
        assertEquals(setOf("Synthetic oven"), expected.kitchen.availableEquipment)
        assertEquals(1, expected.kitchen.householdRules.allergens.size)
        val shoppingItem = assertNotNull(expected.shoppingList).items.single()
        assertTrue(shoppingItem.checked)
        assertEquals(setOf(recipe.recipe.id), shoppingItem.sourceRecipeIds)
        assertEquals("synthetic-event", expected.cookingHistory.single().event.id)
        assertEquals("synthetic-event", expected.leftovers.single().cookEventId)
        assertEquals(Rational.of(2), expected.leftovers.single().quantity)
        assertNotNull(expected.leftovers.single().ingredientId)

        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val database = KitchenDatabaseFactory.open(driver)
            val recipeRepository = SqlDelightRecipeRepository(database)
            val previous = Recipe(name = "Previous synthetic recipe", ingredients = emptyList(), directionsText = "")
            recipeRepository.save(previous)
            val restoreRepository = SqlDelightKitchenBackupRestoreRepository(
                database = database,
                fingerprinter = KitchenBackupArchiveExporter(),
            )
            val preview = PreviewKitchenBackupRestore(restoreRepository).preview(request)
            assertEquals(1, preview.currentCounts.recipes)
            assertEquals(1, preview.backupCounts.recipes)
            assertTrue(preview.canApply)

            ApplyKitchenBackupRestore(restoreRepository).apply(
                request,
                preview,
                KitchenBackupRestoreDecision.REPLACE_ALL_LOCAL_DATA,
            )

            assertNull(recipeRepository.get(previous.id))
            assertEquals(expected, SqlDelightKitchenBackupSnapshotRepository(database).load())
        }
    }
}
