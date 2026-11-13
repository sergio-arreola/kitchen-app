package dev.kitchenapp.data.backup

import dev.kitchenapp.core.application.CookHistoryEntry
import dev.kitchenapp.core.application.KitchenBackupArchiveEncoder
import dev.kitchenapp.core.application.KitchenBackupLeftover
import dev.kitchenapp.core.application.KitchenBackupSnapshot
import dev.kitchenapp.core.application.KitchenBackupSnapshotFingerprinter
import dev.kitchenapp.core.domain.time.DomainInstant
import kotlin.time.Instant
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.recipe.RecipeIngredient
import dev.kitchenapp.core.domain.shopping.ShoppingList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encodes a complete local snapshot as a versioned ZIP archive with separately stored image assets. */
class KitchenBackupArchiveExporter : KitchenBackupArchiveEncoder, KitchenBackupSnapshotFingerprinter {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    override fun fingerprint(snapshot: KitchenBackupSnapshot): String =
        sha256BackupBytes(encode(snapshot, FINGERPRINT_TIMESTAMP))

    override fun encode(snapshot: KitchenBackupSnapshot, exportedAt: DomainInstant): ByteArray {
        val images = snapshot.recipes.mapNotNull { entry ->
            entry.image?.let { image ->
                val path = "images/${entry.recipe.id}.${imageExtension(image.mediaType)}"
                path to image.bytes
            }
        }.sortedBy { it.first }
        require(images.size <= MAX_BACKUP_ENTRY_COUNT - 2) {
            "Backup has too many images to encode"
        }
        images.forEach { (path, bytes) ->
            require(bytes.size in 1..MAX_BACKUP_IMAGE_BYTES) {
                "Backup image exceeds the maximum allowed size: $path"
            }
            require(imageMediaTypeFromBytes(bytes) == mediaTypeForPath(path)) {
                "Backup image bytes do not match the declared image type: $path"
            }
        }
        val assetMetadata = images.map { (path, bytes) ->
            BackupAsset(path, bytes.size.toLong(), sha256BackupBytes(bytes))
        }
        val payloadBytes = json.encodeToString(snapshot.toDocument(images.map { it.first }.toSet()))
            .encodeToByteArray()
        require(payloadBytes.size in 1..MAX_BACKUP_PAYLOAD_BYTES) {
            "Backup data document exceeds the maximum allowed size"
        }
        val manifestBytes = json.encodeToString(
            BackupManifest(
                exportedAt = exportedAt.toString(),
                payloadSizeBytes = payloadBytes.size.toLong(),
                payloadSha256 = sha256BackupBytes(payloadBytes),
                assets = assetMetadata,
            ),
        ).encodeToByteArray()
        require(manifestBytes.size <= MAX_BACKUP_MANIFEST_BYTES) {
            "Backup manifest exceeds the maximum allowed size"
        }
        val entries = buildList {
            add(BackupArchiveEntry(MANIFEST_PATH, manifestBytes))
            add(BackupArchiveEntry(PAYLOAD_PATH, payloadBytes))
            images.forEach { (path, bytes) -> add(BackupArchiveEntry(path, bytes)) }
        }
        val uncompressedSize = entries.sumOf { it.bytes.size.toLong() }
        require(uncompressedSize <= MAX_BACKUP_TOTAL_UNCOMPRESSED_BYTES) {
            "Backup contents exceed the maximum allowed uncompressed size"
        }
        require(uncompressedSize <= MAX_BACKUP_ARCHIVE_BYTES - BACKUP_ARCHIVE_OVERHEAD_RESERVE) {
            "Backup contents leave insufficient room for archive metadata"
        }
        val archive = zipBackupArchive(entries)
        require(archive.size <= MAX_BACKUP_ARCHIVE_BYTES) {
            "Encoded backup archive exceeds the maximum allowed size"
        }
        return archive
    }

    private fun mediaTypeForPath(path: String): String = when (path.substringAfterLast('.')) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> error("Unsupported backup image path: $path")
    }

    private fun imageExtension(mediaType: String): String = when (mediaType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> error("Unsupported backup image type: $mediaType")
    }

    private fun KitchenBackupSnapshot.toDocument(imagePaths: Set<String>) = BackupDocument(
        recipes = recipes.sortedBy { it.recipe.id.toString() }
            .map { entry -> entry.recipe.toBackup(imagePaths) },
        categories = categories.sortedBy { it.lowercase() },
        ingredients = kitchen.ingredients.sortedBy { it.id.toString() }.map { ingredient ->
            BackupIngredient(ingredient.id.toString(), ingredient.name, ingredient.aliases.sorted())
        },
        locations = kitchen.inventory.locations.sortedBy { it.id.toString() }.map { location ->
            BackupLocation(location.id.toString(), location.name)
        },
        inventoryLots = kitchen.inventory.lots.sortedBy { it.id.toString() }.map { it.toBackup() },
        availableEquipment = kitchen.availableEquipment.sortedWith(compareBy({ it.lowercase() }, { it })),
        hardExclusions = kitchen.householdRules.hardExclusions.map { it.toString() }.sorted(),
        allergens = kitchen.householdRules.allergens.map { it.toString() }.sorted(),
        preferences = kitchen.householdRules.preferences
            .sortedBy { it.ingredientId.toString() }
            .map { BackupIngredientPreference(it.ingredientId.toString(), it.strength.name, it.score) },
        shoppingList = shoppingList?.toBackup(),
        cookingHistory = cookingHistory.sortedWith(
            compareByDescending<CookHistoryEntry> { it.event.cookedOn.toString() }.thenBy { it.event.id },
        ).map { entry ->
            val event = entry.event
            BackupCookEvent(
                id = event.id,
                recipeId = event.recipeId.toString(),
                servings = event.servings,
                cookedOn = event.cookedOn.toString(),
                note = event.note,
                leftoverFollowUpPending = entry.leftoverFollowUpPending,
                leftoverReminderClaimed = entry.leftoverReminderClaimed,
                undoneOn = entry.undoneOn?.toString(),
                hasLeftovers = entry.hasLeftovers,
                inventoryBefore = entry.inventoryBefore.map { it.toBackup() },
                consumed = event.consumed.map { item ->
                    BackupConsumedInventory(
                        lotId = item.lotId.toString(),
                        quantity = item.quantity?.toBackup(),
                        unitCode = item.unit?.code,
                    )
                },
            )
        },
        leftovers = leftovers.sortedWith(
            compareByDescending<KitchenBackupLeftover> { it.preparedOn.toString() }
                .thenBy { it.id.toString() },
        ).map { leftover ->
            BackupLeftover(
                id = leftover.id.toString(),
                cookEventId = leftover.cookEventId,
                ingredientId = leftover.ingredientId?.toString(),
                name = leftover.name,
                quantity = leftover.quantity?.toBackup(),
                unitCode = leftover.unit?.code,
                preparedOn = leftover.preparedOn.toString(),
                useBy = leftover.useBy?.toString(),
            )
        },
    )

    private fun Recipe.toBackup(imagePaths: Set<String>) = BackupRecipe(
        id = id.toString(),
        name = name,
        servings = servings,
        ingredients = ingredients.map { it.toBackup() },
        directionsText = directionsText,
        notes = notes,
        categories = categories,
        sourceName = sourceName,
        sourceUrl = sourceUrl,
        prepTimeSeconds = prepTimeSeconds,
        cookTimeSeconds = cookTimeSeconds,
        chillingTimeSeconds = chillingTimeSeconds,
        totalTimeSeconds = totalTimeSeconds,
        imageSourceUrl = imageSourceUrl,
        importedAt = importedAt?.toString(),
        mealTypes = mealTypes,
        equipmentRequirements = equipmentRequirements.map { requirement ->
            BackupEquipmentRequirement(
                equipment = requirement.equipment,
                kind = requirement.kind.name,
                acceptableSubstitutes = requirement.acceptableSubstitutes.sorted(),
            )
        },
        imageAssetPath = "images/$id.jpg".takeIf { imagePaths.contains(it) }
            ?: listOf("png", "webp", "gif").firstNotNullOfOrNull { extension ->
                "images/$id.$extension".takeIf(imagePaths::contains)
            },
    )

    private fun RecipeIngredient.toBackup() = BackupRecipeIngredient(
        id = id.toString(),
        ingredientId = ingredientId.toString(),
        quantity = quantity?.toBackup(),
        quantityHigh = quantityHigh?.toBackup(),
        unitCode = unit?.code,
        preparation = preparation,
        importance = importance.name,
        approximateLevel = approximateLevel.name,
        rawLine = rawLine,
    )

    private fun InventoryLot.toBackup(): BackupInventoryLot {
        val amount = amount.toBackupAmount()
        return BackupInventoryLot(
            id = id.toString(),
            ingredientId = ingredientId.toString(),
            locationId = locationId.toString(),
            amountKind = amount.kind,
            quantity = amount.quantity,
            unitCode = amount.unitCode,
            approximateLevel = amount.approximateLevel,
            purchasedOn = purchasedOn?.toString(),
            openedOn = openedOn?.toString(),
            frozenOn = frozenOn?.toString(),
            manufacturerUseBy = manufacturerUseBy?.toString(),
            userUseBy = userUseBy?.toString(),
        )
    }

    private fun InventoryAmount.toBackupAmount() = when (this) {
        InventoryAmount.PresenceOnly -> BackupAmount("PRESENCE_ONLY", null, null, null)
        is InventoryAmount.Approximate -> BackupAmount(
            "APPROXIMATE",
            quantity.toBackup(),
            unit?.code,
            level.name,
        )
        is InventoryAmount.Exact -> BackupAmount(
            "EXACT",
            quantity.toBackup(),
            unit?.code,
            "EXACT",
        )
    }

    private fun ShoppingList.toBackup() = BackupShoppingList(
        createdAt = createdAt.toString(),
        items = items.map { item ->
            BackupShoppingItem(
                id = item.id.toString(),
                ingredientId = item.ingredientId.toString(),
                ingredientName = item.ingredientName,
                quantity = item.quantity?.toBackup(),
                unitCode = item.unit?.code,
                sourceRecipeIds = item.sourceRecipeIds.map { it.toString() }.sorted(),
                checked = item.checked,
            )
        },
    )

    private fun dev.kitchenapp.core.domain.math.Rational.toBackup() =
        BackupRational(numerator, denominator)

    private data class BackupAmount(
        val kind: String,
        val quantity: BackupRational?,
        val unitCode: String?,
        val approximateLevel: String?,
    )

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val PAYLOAD_PATH = "data.json"
        val FINGERPRINT_TIMESTAMP = DomainInstant(Instant.parse("2000-01-01T00:00:00Z"))
    }
}

@Serializable
data class BackupManifest(
    val formatId: String = FORMAT_ID,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAt: String,
    val payloadPath: String = "data.json",
    val payloadSizeBytes: Long,
    val payloadSha256: String,
    val assets: List<BackupAsset>,
) {
    companion object {
        const val FORMAT_ID = "dev.kitchenapp.backup"
        const val CURRENT_FORMAT_VERSION = 1
    }
}

@Serializable
data class BackupAsset(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class BackupDocument(
    val recipes: List<BackupRecipe>,
    val categories: List<String>,
    val ingredients: List<BackupIngredient>,
    val locations: List<BackupLocation>,
    val inventoryLots: List<BackupInventoryLot>,
    val availableEquipment: List<String>,
    val hardExclusions: List<String>,
    val allergens: List<String>,
    val preferences: List<BackupIngredientPreference>,
    val shoppingList: BackupShoppingList?,
    val cookingHistory: List<BackupCookEvent>,
    val leftovers: List<BackupLeftover>,
)

@Serializable
data class BackupRecipe(
    val id: String,
    val name: String,
    val servings: String?,
    val ingredients: List<BackupRecipeIngredient>,
    val directionsText: String,
    val notes: String?,
    val categories: List<String>,
    val sourceName: String?,
    val sourceUrl: String?,
    val prepTimeSeconds: Long?,
    val cookTimeSeconds: Long?,
    val chillingTimeSeconds: Long?,
    val totalTimeSeconds: Long?,
    val imageSourceUrl: String?,
    val importedAt: String?,
    val mealTypes: List<String>,
    val equipmentRequirements: List<BackupEquipmentRequirement>,
    val imageAssetPath: String?,
)

@Serializable
data class BackupRecipeIngredient(
    val id: String,
    val ingredientId: String,
    val quantity: BackupRational?,
    val quantityHigh: BackupRational?,
    val unitCode: String?,
    val preparation: String?,
    val importance: String,
    val approximateLevel: String,
    val rawLine: String,
)

@Serializable
data class BackupEquipmentRequirement(
    val equipment: String,
    val kind: String,
    val acceptableSubstitutes: List<String>,
)

@Serializable
data class BackupRational(val numerator: Long, val denominator: Long)

@Serializable
data class BackupIngredient(val id: String, val name: String, val aliases: List<String>)

@Serializable
data class BackupLocation(val id: String, val name: String)

@Serializable
data class BackupInventoryLot(
    val id: String,
    val ingredientId: String,
    val locationId: String,
    val amountKind: String,
    val quantity: BackupRational?,
    val unitCode: String?,
    val approximateLevel: String?,
    val purchasedOn: String?,
    val openedOn: String?,
    val frozenOn: String?,
    val manufacturerUseBy: String?,
    val userUseBy: String?,
)

@Serializable
data class BackupIngredientPreference(val ingredientId: String, val strength: String, val score: Int)

@Serializable
data class BackupShoppingList(val createdAt: String, val items: List<BackupShoppingItem>)

@Serializable
data class BackupShoppingItem(
    val id: String,
    val ingredientId: String,
    val ingredientName: String,
    val quantity: BackupRational?,
    val unitCode: String?,
    val sourceRecipeIds: List<String>,
    val checked: Boolean,
)

@Serializable
data class BackupCookEvent(
    val id: String,
    val recipeId: String,
    val servings: Int?,
    val cookedOn: String,
    val note: String?,
    val leftoverFollowUpPending: Boolean,
    val leftoverReminderClaimed: Boolean,
    val undoneOn: String?,
    val hasLeftovers: Boolean,
    val inventoryBefore: List<BackupInventoryLot>,
    val consumed: List<BackupConsumedInventory>,
)

@Serializable
data class BackupConsumedInventory(
    val lotId: String,
    val quantity: BackupRational?,
    val unitCode: String?,
)

@Serializable
data class BackupLeftover(
    val id: String,
    val cookEventId: String,
    val ingredientId: String?,
    val name: String,
    val quantity: BackupRational?,
    val unitCode: String?,
    val preparedOn: String,
    val useBy: String?,
)
