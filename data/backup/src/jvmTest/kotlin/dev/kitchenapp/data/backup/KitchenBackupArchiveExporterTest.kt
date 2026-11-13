package dev.kitchenapp.data.backup

import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.CookHistoryEntry
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.KitchenBackupLeftover
import dev.kitchenapp.core.application.KitchenBackupSnapshot
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.RecipeBackupEntry
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.household.EquipmentRequirement
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.household.PreferenceStrength
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.shopping.ShoppingList
import dev.kitchenapp.core.domain.shopping.ShoppingListItem
import dev.kitchenapp.core.domain.time.DomainInstant
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

class KitchenBackupArchiveExporterTest {
    @Test
    fun `archive round trips the versioned local snapshot and checked shopping state`() {
        val ingredientId = IngredientId.generate()
        val allergenId = IngredientId.generate()
        val lotId = InventoryLotId.generate()
        val locationId = KitchenLocationId.generate()
        val recipeId = dev.kitchenapp.core.domain.identifiers.RecipeId.generate()
        val eventDate = DomainLocalDate(LocalDate.parse("2026-11-09"))
        val recipe = Recipe(
            id = recipeId,
            name = "Synthetic bread",
            servings = "4 servings",
            ingredients = listOf(
                RecipeIngredient(
                    id = RecipeIngredientId.generate(),
                    ingredientId = ingredientId,
                    quantity = Rational.of(2),
                    unit = UnitCode.CUP,
                    preparation = "sifted",
                    importance = IngredientImportance.CRITICAL,
                    approximateLevel = ApproximateLevel.EXACT,
                    rawLine = "2 cups synthetic flour, sifted",
                ),
            ),
            directionsText = "Bake.",
            notes = "Synthetic test record",
            categories = listOf("Synthetic", "Baking"),
            sourceName = "Synthetic source",
            sourceUrl = "https://example.invalid/recipe",
            prepTimeSeconds = 300,
            cookTimeSeconds = 900,
            chillingTimeSeconds = 600,
            totalTimeSeconds = 1200,
            imageSourceUrl = "https://example.invalid/recipe.png",
            importedAt = DomainInstant(Instant.parse("2026-11-08T12:00:00Z")),
            mealTypes = listOf("Breakfast"),
            equipmentRequirements = listOf(
                EquipmentRequirement(
                    equipment = "Oven",
                    kind = EquipmentRequirementKind.SUBSTITUTABLE,
                    acceptableSubstitutes = setOf("Air fryer", "air fryer"),
                ),
            ),
        )
        val imageBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val flour = Ingredient(ingredientId, "Synthetic flour", setOf("Test flour", "test flour"))
        val milk = Ingredient(allergenId, "Synthetic milk")
        val lot = InventoryLot(
            id = lotId,
            ingredientId = ingredientId,
            locationId = locationId,
            amount = InventoryAmount.Exact(Rational.of(5), UnitCode.CUP),
            purchasedOn = eventDate,
            userUseBy = DomainLocalDate(LocalDate.parse("2026-11-20")),
        )
        val list = ShoppingList(
            createdAt = DomainInstant(Instant.parse("2026-11-10T12:00:00Z")),
            items = listOf(
                ShoppingListItem(
                    ingredientId = ingredientId,
                    ingredientName = flour.name,
                    quantity = Rational.of(1),
                    unit = UnitCode.CUP,
                    sourceRecipeIds = setOf(recipe.id),
                    checked = true,
                ),
            ),
        )
        val event = CookEvent(
            id = "synthetic-event",
            recipeId = recipe.id,
            servings = 4,
            cookedOn = eventDate,
            consumed = listOf(ConsumedInventory(lotId, Rational.of(1), UnitCode.CUP)),
            note = "Synthetic note",
        )
        val snapshot = KitchenBackupSnapshot(
            recipes = listOf(RecipeBackupEntry(recipe, RecipeImage(imageBytes, "image/png"))),
            categories = listOf("Baking", "Synthetic"),
            kitchen = KitchenSnapshot(
                ingredients = listOf(flour, milk),
                inventory = KitchenInventory(
                    locations = listOf(KitchenLocation(locationId, "Pantry")),
                    lots = listOf(lot),
                ),
                availableEquipment = setOf("Oven"),
                householdRules = HouseholdIngredientRules(
                    hardExclusions = setOf(allergenId),
                    preferences = listOf(
                        IngredientPreference(ingredientId, PreferenceStrength.SOFT, score = 2),
                    ),
                    allergens = emptySet(),
                ),
            ),
            shoppingList = list,
            cookingHistory = listOf(
                CookHistoryEntry(
                    event = event,
                    leftoverFollowUpPending = false,
                    hasLeftovers = true,
                    leftoverReminderClaimed = true,
                    undoneOn = null,
                    inventoryBefore = listOf(lot),
                ),
            ),
            leftovers = listOf(
                KitchenBackupLeftover(
                    id = InventoryLotId.generate(),
                    cookEventId = event.id,
                    ingredientId = null,
                    name = "Synthetic bread",
                    quantity = null,
                    unit = UnitCode.SERVING,
                    preparedOn = eventDate,
                    useBy = DomainLocalDate(LocalDate.parse("2026-11-12")),
                ),
            ),
        )

        val archive = KitchenBackupArchiveExporter().encode(
            snapshot,
            DomainInstant(Instant.parse("2026-11-10T12:00:00Z")),
        )
        val entries = readEntries(archive)
        val manifest = Json.decodeFromString<BackupManifest>(entries.getValue("manifest.json").decodeToString())
        val document = Json.decodeFromString<BackupDocument>(entries.getValue("data.json").decodeToString())
        val validated = KitchenBackupArchiveValidator().validate(archive)
        val restored = validated.toSnapshot()
        val restoreRequest = validated.toRestoreRequest()

        assertEquals(validated.archiveFingerprint, restoreRequest.archiveFingerprint)
        assertEquals(
            KitchenBackupArchiveExporter().fingerprint(restoreRequest.snapshot),
            restoreRequest.snapshotFingerprint,
        )

        assertEquals(BackupManifest.FORMAT_ID, manifest.formatId)
        assertEquals(recipe, restored.recipes.single().recipe)
        assertEquals(RecipeImage(imageBytes, "image/png"), restored.recipes.single().image)
        assertEquals(snapshot.shoppingList, restored.shoppingList)
        assertEquals(snapshot.cookingHistory, restored.cookingHistory)
        assertEquals(snapshot.leftovers, restored.leftovers)
        assertEquals(snapshot.kitchen.inventory.lots, restored.kitchen.inventory.lots)
        assertEquals(BackupManifest.CURRENT_FORMAT_VERSION, manifest.formatVersion)
        assertEquals(document, validated.document)
        val validatedImage = validated.imageAssets.getValue("images/${recipe.id}.png")
        assertContentEquals(imageBytes, validatedImage.bytes)
        validatedImage.bytes[0] = 0
        assertContentEquals(imageBytes, validatedImage.bytes)
        assertEquals("2026-11-10T12:00:00Z", manifest.exportedAt)
        assertEquals(entries.getValue("data.json").size.toLong(), manifest.payloadSizeBytes)
        assertEquals(sha256BackupBytes(entries.getValue("data.json")), manifest.payloadSha256)
        assertEquals("images/${recipe.id}.png", document.recipes.single().imageAssetPath)
        assertContentEquals(imageBytes, entries.getValue("images/${recipe.id}.png"))
        assertEquals(true, document.shoppingList?.items?.single()?.checked)
        assertEquals(recipe.id.toString(), document.shoppingList?.items?.single()?.sourceRecipeIds?.single())
        assertEquals(recipe.sourceUrl, document.recipes.single().sourceUrl)
        assertEquals(recipe.importedAt.toString(), document.recipes.single().importedAt)
        assertEquals(
            setOf("Test flour", "test flour"),
            document.ingredients.single { it.id == ingredientId.toString() }.aliases.toSet(),
        )
        assertEquals("EXACT", document.inventoryLots.single().amountKind)
        assertEquals(listOf(allergenId.toString()), document.hardExclusions)
        assertEquals(2, document.preferences.single().score)
        assertEquals(lotId.toString(), document.cookingHistory.single().inventoryBefore.single().id)
        assertEquals(event.id, document.leftovers.single().cookEventId)
        assertEquals(null, document.leftovers.single().ingredientId)
        assertEquals(null, document.leftovers.single().quantity)
        assertNotNull(manifest.assets.singleOrNull { it.path == "images/${recipe.id}.png" })
        assertTrue(manifest.assets.single().sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `export rejects an image beyond archive size limits`() {
        val recipe = Recipe(name = "Oversized image", ingredients = emptyList(), directionsText = "")
        val snapshot = KitchenBackupSnapshot(
            recipes = listOf(
                RecipeBackupEntry(
                    recipe,
                    RecipeImage(ByteArray(MAX_BACKUP_IMAGE_BYTES + 1), "image/png"),
                ),
            ),
            categories = emptyList(),
            kitchen = KitchenSnapshot(),
            shoppingList = null,
            cookingHistory = emptyList(),
            leftovers = emptyList(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            KitchenBackupArchiveExporter().encode(
                snapshot,
                DomainInstant(Instant.parse("2026-11-10T12:00:00Z")),
            )
        }

        assertTrue(error.message.orEmpty().contains("image exceeds the maximum allowed size"))
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> =
        buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                    zip.closeEntry()
                }
            }
        }
}
