package dev.kitchenapp.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class KitchenBackupArchiveValidatorTest {
    private val validator = KitchenBackupArchiveValidator()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `valid v1 archive returns payload and image assets`() {
        val archive = archive(
            document = emptyDocument(),
            assets = emptyList(),
            imageEntries = emptyList(),
        )

        val validated = validator.validate(archive)

        assertEquals(BackupManifest.CURRENT_FORMAT_VERSION, validated.manifest.formatVersion)
        assertEquals(emptyDocument(), validated.document)
        assertTrue(validated.imageAssets.isEmpty())
    }

    @Test
    fun `valid recipe image reference returns bytes and inferred media type`() {
        val recipeId = uuid()
        val path = "images/$recipeId.png"
        val imageBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val document = emptyDocument().copy(recipes = listOf(recipe(recipeId, imageAssetPath = path)))
        val archive = archive(
            document = document,
            assets = listOf(BackupAsset(path, imageBytes.size.toLong(), sha256BackupBytes(imageBytes))),
            imageEntries = listOf(BackupArchiveEntry(path, imageBytes)),
        )

        val image = validator.validate(archive).imageAssets.getValue(path)

        assertEquals("image/png", image.mediaType)
        assertTrue(image.bytes.contentEquals(imageBytes))
    }

    @Test
    fun `rejects non zip bytes as corrupt archive`() {
        val error = assertFailsWith<BackupArchiveValidationException> {
            validator.validate(byteArrayOf(1, 2, 3, 4))
        }

        assertTrue(error.message.orEmpty().contains("corrupt or unreadable"))
    }

    @Test
    fun `rejects manifests missing required defaulted fields`() {
        listOf("formatId", "formatVersion").forEach { field ->
            val entries = unzipBackupArchive(validArchive()).toMutableList()
            val manifestIndex = entries.indexOfFirst { it.path == MANIFEST_PATH }
            val manifestObject = json.parseToJsonElement(entries[manifestIndex].bytes.decodeToString()).jsonObject
                .toMutableMap()
            manifestObject.remove(field)
            entries[manifestIndex] = entries[manifestIndex].copy(bytes = json.encodeToString(
                kotlinx.serialization.json.JsonObject(manifestObject),
            ).encodeToByteArray())

            val error = assertFailsWith<BackupArchiveValidationException> {
                validator.validate(zip(*entries.toTypedArray()))
            }

            assertTrue(error.message.orEmpty().contains(field))
        }
    }

    @Test
    fun `rejects unsupported manifest versions`() {
        val (manifest, payload) = validManifestAndPayload()
        val bytes = zip(
            BackupArchiveEntry(MANIFEST_PATH, json.encodeToString(manifest.copy(formatVersion = 2)).encodeToByteArray()),
            BackupArchiveEntry(PAYLOAD_PATH, payload),
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(bytes) }

        assertTrue(error.message.orEmpty().contains("version 2"))
    }

    @Test
    fun `rejects duplicate archive entry paths`() {
        val (manifest, payload) = validManifestAndPayload()
        val manifestBytes = json.encodeToString(manifest).encodeToByteArray()
        val entries = listOf(
            BackupArchiveEntry(MANIFEST_PATH, manifestBytes),
            BackupArchiveEntry(PAYLOAD_PATH, payload),
            BackupArchiveEntry(PAYLOAD_PATH, payload),
        )

        val error = assertFailsWith<BackupArchiveValidationException> {
            validator.validateEntries(entries)
        }

        assertTrue(error.message.orEmpty().contains("duplicate path"))
    }

    @Test
    fun `rejects unsafe and unexpected paths`() {
        val (manifest, payload) = validManifestAndPayload()
        val bytes = zip(
            BackupArchiveEntry(MANIFEST_PATH, json.encodeToString(manifest).encodeToByteArray()),
            BackupArchiveEntry(PAYLOAD_PATH, payload),
            BackupArchiveEntry("../outside.json", byteArrayOf(1)),
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(bytes) }

        assertTrue(error.message.orEmpty().contains("unsafe path"))
    }

    @Test
    fun `rejects payload checksum mismatch`() {
        val valid = validArchive()
        val entries = unzipBackupArchive(valid).toMutableList()
        val index = entries.indexOfFirst { it.path == PAYLOAD_PATH }
        val changedPayload = entries[index].bytes.copyOf()
        changedPayload[0] = (changedPayload[0].toInt() xor 1).toByte()
        entries[index] = entries[index].copy(bytes = changedPayload)

        val error = assertFailsWith<BackupArchiveValidationException> {
            validator.validate(zip(*entries.toTypedArray()))
        }

        assertTrue(error.message.orEmpty().contains("checksum does not match"))
    }

    @Test
    fun `rejects declared image missing from archive`() {
        val recipeId = uuid()
        val path = "images/$recipeId.jpg"
        val imageBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val document = emptyDocument().copy(recipes = listOf(recipe(recipeId, imageAssetPath = path)))
        val archive = archive(
            document = document,
            assets = listOf(BackupAsset(path, imageBytes.size.toLong(), sha256BackupBytes(imageBytes))),
            imageEntries = emptyList(),
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(archive) }

        assertTrue(error.message.orEmpty().contains("missing an entry"))
    }

    @Test
    fun `rejects image bytes that do not match the declared type`() {
        val recipeId = uuid()
        val path = "images/$recipeId.png"
        val jpegBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val document = emptyDocument().copy(recipes = listOf(recipe(recipeId, imageAssetPath = path)))
        val archive = archive(
            document = document,
            assets = listOf(BackupAsset(path, jpegBytes.size.toLong(), sha256BackupBytes(jpegBytes))),
            imageEntries = listOf(BackupArchiveEntry(path, jpegBytes)),
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(archive) }

        assertTrue(error.message.orEmpty().contains("do not match the declared image type"))
    }

    @Test
    fun `rejects image not referenced by a recipe`() {
        val path = "images/${uuid()}.jpg"
        val imageBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val archive = archive(
            document = emptyDocument(),
            assets = listOf(BackupAsset(path, imageBytes.size.toLong(), sha256BackupBytes(imageBytes))),
            imageEntries = listOf(BackupArchiveEntry(path, imageBytes)),
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(archive) }

        assertTrue(error.message.orEmpty().contains("unreferenced image"))
    }

    @Test
    fun `accepts case distinct aliases and substitutes with historical non foreign key references`() {
        val recipeId = uuid()
        val ingredientId = uuid()
        val shoppingIngredientId = uuid()
        val oldLotIngredientId = uuid()
        val oldLocationId = uuid()
        val recipe = recipe(
            recipeId,
            ingredients = listOf(
                BackupRecipeIngredient(
                    id = uuid(),
                    ingredientId = ingredientId,
                    quantity = null,
                    quantityHigh = null,
                    unitCode = null,
                    preparation = null,
                    importance = "CRITICAL",
                    approximateLevel = "EXACT",
                    rawLine = "synthetic",
                ),
            ),
        ).copy(
            equipmentRequirements = listOf(
                BackupEquipmentRequirement(
                    equipment = "Oven",
                    kind = "SUBSTITUTABLE",
                    acceptableSubstitutes = listOf("Air fryer", "air fryer"),
                ),
            ),
        )
        val document = emptyDocument().copy(
            recipes = listOf(recipe),
            ingredients = listOf(BackupIngredient(ingredientId, "Flour", listOf("Meal", "meal"))),
            shoppingList = BackupShoppingList(
                "2026-11-10T12:00:00Z",
                listOf(
                    BackupShoppingItem(
                        id = uuid(),
                        ingredientId = shoppingIngredientId,
                        ingredientName = "Removed ingredient",
                        quantity = null,
                        unitCode = null,
                        sourceRecipeIds = listOf(uuid()),
                        checked = true,
                    ),
                ),
            ),
            cookingHistory = listOf(
                BackupCookEvent(
                    id = "historical-event",
                    recipeId = recipeId,
                    servings = null,
                    cookedOn = "2026-11-10",
                    note = null,
                    leftoverFollowUpPending = false,
                    leftoverReminderClaimed = false,
                    undoneOn = null,
                    hasLeftovers = false,
                    inventoryBefore = listOf(
                        BackupInventoryLot(
                            id = uuid(),
                            ingredientId = oldLotIngredientId,
                            locationId = oldLocationId,
                            amountKind = "PRESENCE_ONLY",
                            quantity = null,
                            unitCode = null,
                            approximateLevel = null,
                            purchasedOn = null,
                            openedOn = null,
                            frozenOn = null,
                            manufacturerUseBy = null,
                            userUseBy = null,
                        ),
                    ),
                    consumed = listOf(BackupConsumedInventory(uuid(), null, null)),
                ),
            ),
        )

        val validated = validator.validate(archive(document, emptyList(), emptyList()))

        assertEquals(document, validated.document)
    }

    @Test
    fun `rejects duplicate equipment requirements ignoring case`() {
        val recipe = recipe(uuid()).copy(
            equipmentRequirements = listOf(
                BackupEquipmentRequirement("Oven", "REQUIRED", emptyList()),
                BackupEquipmentRequirement("oven", "REQUIRED", emptyList()),
            ),
        )
        val error = assertFailsWith<BackupArchiveValidationException> {
            validator.validate(archive(emptyDocument().copy(recipes = listOf(recipe)), emptyList(), emptyList()))
        }

        assertTrue(error.message.orEmpty().contains("duplicate equipment requirement"))
    }

    @Test
    fun `rejects malformed recipe identifier`() {
        val archive = archive(
            document = emptyDocument().copy(recipes = listOf(recipe("not-a-uuid"))),
            assets = emptyList(),
            imageEntries = emptyList(),
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(archive) }

        assertTrue(error.message.orEmpty().contains("invalid recipe ID"))
    }

    @Test
    fun `rejects a recipe ingredient with a broken canonical ingredient reference`() {
        val row = BackupRecipeIngredient(
            id = uuid(),
            ingredientId = uuid(),
            quantity = null,
            quantityHigh = null,
            unitCode = null,
            preparation = null,
            importance = "CRITICAL",
            approximateLevel = "EXACT",
            rawLine = "a synthetic ingredient",
        )
        val archive = archive(
            document = emptyDocument().copy(recipes = listOf(recipe(uuid(), ingredients = listOf(row)))),
            assets = emptyList(),
            imageEntries = emptyList(),
        )

        val error = assertFailsWith<BackupArchiveValidationException> { validator.validate(archive) }

        assertTrue(error.message.orEmpty().contains("missing ingredient"))
    }

    private fun validArchive(): ByteArray = archive(
        document = emptyDocument(),
        assets = emptyList(),
        imageEntries = emptyList(),
    )

    private fun validManifestAndPayload(): Pair<BackupManifest, ByteArray> {
        val payload = json.encodeToString(emptyDocument()).encodeToByteArray()
        return BackupManifest(
            exportedAt = "2026-11-11T12:00:00Z",
            payloadSizeBytes = payload.size.toLong(),
            payloadSha256 = sha256BackupBytes(payload),
            assets = emptyList(),
        ) to payload
    }

    private fun archive(
        document: BackupDocument,
        assets: List<BackupAsset>,
        imageEntries: List<BackupArchiveEntry>,
    ): ByteArray {
        val payload = json.encodeToString(document).encodeToByteArray()
        val manifest = BackupManifest(
            exportedAt = "2026-11-11T12:00:00Z",
            payloadSizeBytes = payload.size.toLong(),
            payloadSha256 = sha256BackupBytes(payload),
            assets = assets,
        )
        return zip(
            BackupArchiveEntry(MANIFEST_PATH, json.encodeToString(manifest).encodeToByteArray()),
            BackupArchiveEntry(PAYLOAD_PATH, payload),
            *imageEntries.toTypedArray(),
        )
    }

    private fun emptyDocument() = BackupDocument(
        recipes = emptyList(),
        categories = emptyList(),
        ingredients = emptyList(),
        locations = emptyList(),
        inventoryLots = emptyList(),
        availableEquipment = emptyList(),
        hardExclusions = emptyList(),
        allergens = emptyList(),
        preferences = emptyList(),
        shoppingList = null,
        cookingHistory = emptyList(),
        leftovers = emptyList(),
    )

    private fun recipe(
        id: String,
        ingredients: List<BackupRecipeIngredient> = emptyList(),
        imageAssetPath: String? = null,
    ) = BackupRecipe(
        id = id,
        name = "Synthetic",
        servings = null,
        ingredients = ingredients,
        directionsText = "",
        notes = null,
        categories = emptyList(),
        sourceName = null,
        sourceUrl = null,
        prepTimeSeconds = null,
        cookTimeSeconds = null,
        chillingTimeSeconds = null,
        totalTimeSeconds = null,
        imageSourceUrl = null,
        importedAt = null,
        mealTypes = emptyList(),
        equipmentRequirements = emptyList(),
        imageAssetPath = imageAssetPath,
    )

    private fun zip(vararg entries: BackupArchiveEntry): ByteArray =
        zipBackupArchive(entries.toList())

    private fun uuid(): String = Uuid.random().toString()

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val PAYLOAD_PATH = "data.json"
    }
}
