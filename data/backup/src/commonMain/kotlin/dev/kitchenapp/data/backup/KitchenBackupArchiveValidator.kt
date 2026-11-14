package dev.kitchenapp.data.backup

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.household.EquipmentRequirementKind
import dev.kitchenapp.core.domain.household.PreferenceStrength
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A v1 archive after container, hash, schema, and reference validation. */
data class ValidatedKitchenBackup(
    val manifest: BackupManifest,
    val document: BackupDocument,
    val imageAssets: Map<String, ValidatedBackupImage>,
    val archiveFingerprint: String,
)

class ValidatedBackupImage(
    val path: String,
    val mediaType: String,
    bytes: ByteArray,
) {
    private val contents = bytes.copyOf()
    val bytes: ByteArray
        get() = contents.copyOf()
}

class BackupArchiveValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Validates a backup completely before exposing records to restore code. */
class KitchenBackupArchiveValidator {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun validate(bytes: ByteArray): ValidatedKitchenBackup {
        if (bytes.isEmpty()) invalid("Backup file is empty")
        if (bytes.size > MAX_BACKUP_ARCHIVE_BYTES) invalid("Backup file exceeds the maximum allowed size")

        val entries = try {
            unzipBackupArchive(bytes)
        } catch (error: BackupArchiveValidationException) {
            throw error
        } catch (error: Exception) {
            invalid("Backup archive is corrupt or unreadable", error)
        }
        return validateEntries(entries)
    }

    internal fun validateEntries(entries: List<BackupArchiveEntry>): ValidatedKitchenBackup {
        if (entries.size < 2) invalid("Backup archive must contain a manifest and data document")
        if (entries.size > MAX_BACKUP_ENTRY_COUNT) invalid("Backup archive contains too many entries")

        val entriesByPath = linkedMapOf<String, ByteArray>()
        entries.forEach { entry ->
            validateArchivePath(entry.path)
            if (entriesByPath.put(entry.path, entry.bytes) != null) {
                invalid("Backup archive contains a duplicate path: ${entry.path}")
            }
        }

        val manifestBytes = entriesByPath[MANIFEST_PATH]
            ?: invalid("Backup archive is missing manifest.json")
        if (manifestBytes.size > MAX_BACKUP_MANIFEST_BYTES) invalid("Backup manifest exceeds the maximum allowed size")
        val manifestObject = try {
            json.parseToJsonElement(manifestBytes.decodeToString()) as? JsonObject
                ?: invalid("Backup manifest must be a JSON object")
        } catch (error: SerializationException) {
            invalid("Backup manifest is malformed", error)
        } catch (error: IllegalArgumentException) {
            if (error is BackupArchiveValidationException) throw error
            invalid("Backup manifest is malformed", error)
        }
        val requiredManifestFields = setOf(
            "formatId",
            "formatVersion",
            "exportedAt",
            "payloadPath",
            "payloadSizeBytes",
            "payloadSha256",
            "assets",
        )
        val missingManifestFields = requiredManifestFields - manifestObject.keys
        if (missingManifestFields.isNotEmpty()) {
            invalid("Backup manifest is missing required field: ${missingManifestFields.first()}")
        }
        val manifest = try {
            json.decodeFromJsonElement<BackupManifest>(manifestObject)
        } catch (error: SerializationException) {
            invalid("Backup manifest is malformed", error)
        }

        if (manifest.formatId != BackupManifest.FORMAT_ID) {
            invalid("Backup format identifier is not supported")
        }
        if (manifest.formatVersion != BackupManifest.CURRENT_FORMAT_VERSION) {
            invalid("Backup format version ${manifest.formatVersion} is not supported")
        }
        if (manifest.payloadPath != PAYLOAD_PATH) {
            invalid("Backup manifest must refer to data.json")
        }
        if (manifest.exportedAt.isBlank() || runCatching { Instant.parse(manifest.exportedAt) }.isFailure) {
            invalid("Backup manifest has an invalid export timestamp")
        }

        val payloadBytes = entriesByPath[manifest.payloadPath]
            ?: invalid("Backup archive is missing data.json")
        if (manifest.payloadSizeBytes !in 1..MAX_BACKUP_PAYLOAD_BYTES.toLong()) {
            invalid("Backup data document has an invalid size")
        }
        if (payloadBytes.size.toLong() != manifest.payloadSizeBytes) {
            invalid("Backup data document size does not match the manifest")
        }
        validateSha256(manifest.payloadSha256, "data document")
        if (sha256BackupBytes(payloadBytes) != manifest.payloadSha256) {
            invalid("Backup data document checksum does not match the manifest")
        }

        if (manifest.assets.size > MAX_BACKUP_ENTRY_COUNT - 2) {
            invalid("Backup manifest lists too many image assets")
        }
        val declaredAssets = linkedMapOf<String, BackupAsset>()
        manifest.assets.forEach { asset ->
            validateImagePath(asset.path)
            if (declaredAssets.put(asset.path, asset) != null) {
                invalid("Backup manifest contains a duplicate image path: ${asset.path}")
            }
            if (asset.sizeBytes !in 1..MAX_BACKUP_IMAGE_BYTES.toLong()) {
                invalid("Backup image has an invalid size: ${asset.path}")
            }
            validateSha256(asset.sha256, "image ${asset.path}")
        }

        val expectedPaths = setOf(MANIFEST_PATH, PAYLOAD_PATH) + declaredAssets.keys
        if (entriesByPath.keys != expectedPaths) {
            val unexpected = entriesByPath.keys - expectedPaths
            if (unexpected.isNotEmpty()) {
                invalid("Backup archive contains an unexpected path: ${unexpected.first()}")
            }
            val missing = expectedPaths - entriesByPath.keys
            invalid("Backup archive is missing an entry: ${missing.first()}")
        }

        val images = declaredAssets.mapValues { (path, metadata) ->
            val imageBytes = entriesByPath.getValue(path)
            if (imageBytes.size.toLong() != metadata.sizeBytes) {
                invalid("Backup image size does not match the manifest: $path")
            }
            if (sha256BackupBytes(imageBytes) != metadata.sha256) {
                invalid("Backup image checksum does not match the manifest: $path")
            }
            val mediaType = mediaTypeForPath(path)
            if (imageMediaTypeFromBytes(imageBytes) != mediaType) {
                invalid("Backup image bytes do not match the declared image type: $path")
            }
            ValidatedBackupImage(path, mediaType, imageBytes)
        }

        val document = try {
            json.decodeFromString<BackupDocument>(payloadBytes.decodeToString())
        } catch (error: SerializationException) {
            invalid("Backup data document is malformed", error)
        } catch (error: IllegalArgumentException) {
            invalid("Backup data document is malformed", error)
        }
        validateDocument(document, images.keys)
        return ValidatedKitchenBackup(
            manifest = manifest,
            document = document,
            imageAssets = images,
            archiveFingerprint = manifestFingerprint(manifest),
        )
    }

    private fun validateDocument(document: BackupDocument, assetPaths: Set<String>) {
        uniqueIds(document.recipes.map { it.id }, "recipe")
        val ingredientIds = uniqueIds(document.ingredients.map { it.id }, "ingredient")
        val locationIds = uniqueIds(document.locations.map { it.id }, "location")
        uniqueIds(document.inventoryLots.map { it.id }, "inventory lot")

        uniqueNames(document.categories, "category")
        document.categories.forEach { nonBlank(it, "category name") }
        document.ingredients.forEach { ingredient ->
            parseUuid(ingredient.id, "ingredient ID")
            nonBlank(ingredient.name, "ingredient name")
            uniqueExactValues(ingredient.aliases, "ingredient alias for ${ingredient.name}")
        }

        val recipeAssetPaths = mutableSetOf<String>()
        val recipeIngredientIds = mutableSetOf<String>()
        document.recipes.forEach { recipe ->
            parseUuid(recipe.id, "recipe ID")
            nonBlank(recipe.name, "recipe name")
            requireNonNegative(recipe.prepTimeSeconds, "prep time")
            requireNonNegative(recipe.cookTimeSeconds, "cook time")
            requireNonNegative(recipe.chillingTimeSeconds, "chilling time")
            requireNonNegative(recipe.totalTimeSeconds, "total time")
            recipe.importedAt?.let { parseInstant(it, "recipe import time") }
            uniqueNames(recipe.categories, "category on recipe ${recipe.name}")
            recipe.categories.forEach { category ->
                nonBlank(category, "recipe category")
                if (document.categories.none { it.equals(category, ignoreCase = true) }) {
                    invalid("Recipe ${recipe.name} refers to an undeclared category")
                }
            }
            uniqueNames(recipe.mealTypes, "meal type on recipe ${recipe.name}")
            recipe.mealTypes.forEach { nonBlank(it, "recipe meal type") }
            recipe.ingredients.forEach { row ->
                parseUuid(row.id, "recipe ingredient ID")
                if (!recipeIngredientIds.add(row.id)) invalid("Backup contains a duplicate recipe ingredient ID")
                if (row.ingredientId !in ingredientIds) {
                    invalid("Recipe ${recipe.name} refers to a missing ingredient")
                }
                validateRational(row.quantity, "recipe ingredient quantity", allowZero = true)
                validateRational(row.quantityHigh, "recipe ingredient upper quantity", allowZero = true)
                if (row.quantityHigh != null && row.quantity == null) {
                    invalid("Recipe ingredient upper quantity has no lower quantity")
                }
                if (row.quantityHigh != null && compare(row.quantityHigh, requireNotNull(row.quantity)) < 0) {
                    invalid("Recipe ingredient upper quantity is below its lower quantity")
                }
                validateUnit(row.unitCode, "recipe ingredient unit")
                nonBlank(row.rawLine, "original recipe ingredient line")
                enumValue<IngredientImportance>(row.importance, "ingredient importance")
                enumValue<ApproximateLevel>(row.approximateLevel, "ingredient approximation")
            }
            uniqueNames(
                recipe.equipmentRequirements.map { it.equipment },
                "equipment requirement on recipe ${recipe.name}",
            )
            recipe.equipmentRequirements.forEach { requirement ->
                nonBlank(requirement.equipment, "equipment requirement")
                val kind = enumValue<EquipmentRequirementKind>(requirement.kind, "equipment requirement kind")
                uniqueExactValues(requirement.acceptableSubstitutes, "equipment substitute")
                requirement.acceptableSubstitutes.forEach { nonBlank(it, "equipment substitute") }
                if (kind != EquipmentRequirementKind.SUBSTITUTABLE && requirement.acceptableSubstitutes.isNotEmpty()) {
                    invalid("Only substitutable equipment requirements can declare substitutes")
                }
            }
            recipe.imageAssetPath?.let { imagePath ->
                validateImagePath(imagePath)
                if (imagePath !in assetPaths) invalid("Recipe ${recipe.name} refers to a missing image asset")
                if (!recipeAssetPaths.add(imagePath)) invalid("Two recipes refer to the same image asset")
                val recipeIdFromPath = imagePath.substringAfter("images/").substringBefore('.')
                if (recipeIdFromPath != recipe.id) {
                    invalid("Recipe image path does not match its recipe ID")
                }
            }
        }
        if (recipeAssetPaths != assetPaths) {
            val unreferenced = assetPaths - recipeAssetPaths
            if (unreferenced.isNotEmpty()) invalid("Backup contains an unreferenced image asset")
            invalid("Backup recipe image reference is missing from its image assets")
        }

        uniqueIds(document.locations.map { it.id }, "location")
        document.locations.forEach { location ->
            parseUuid(location.id, "location ID")
            nonBlank(location.name, "location name")
        }

        document.inventoryLots.forEach { lot ->
            parseUuid(lot.id, "inventory lot ID")
            if (lot.ingredientId !in ingredientIds) invalid("Inventory lot refers to a missing ingredient")
            if (lot.locationId !in locationIds) invalid("Inventory lot refers to a missing location")
            validateInventoryLot(lot)
        }

        uniqueNames(document.availableEquipment, "available equipment")
        document.availableEquipment.forEach { nonBlank(it, "available equipment") }
        uniqueIds(document.hardExclusions, "hard exclusion")
        uniqueIds(document.allergens, "allergen")
        document.hardExclusions.forEach {
            parseUuid(it, "hard exclusion ingredient ID")
            if (it !in ingredientIds) invalid("Hard exclusion refers to a missing ingredient")
        }
        document.allergens.forEach {
            parseUuid(it, "allergen ingredient ID")
            if (it !in ingredientIds) invalid("Allergen refers to a missing ingredient")
        }
        if (document.hardExclusions.intersect(document.allergens).isNotEmpty()) {
            invalid("An ingredient cannot be both an allergen and a hard exclusion")
        }
        uniqueIds(document.preferences.map { it.ingredientId }, "ingredient preference")
        document.preferences.forEach { preference ->
            parseUuid(preference.ingredientId, "preference ingredient ID")
            if (preference.ingredientId !in ingredientIds) invalid("Preference refers to a missing ingredient")
            if (preference.ingredientId in document.hardExclusions || preference.ingredientId in document.allergens) {
                invalid("An excluded ingredient cannot also have a preference")
            }
            enumValue<PreferenceStrength>(preference.strength, "preference strength")
            if (preference.score <= 0) invalid("Ingredient preference score must be positive")
        }

        document.shoppingList?.let { list ->
            parseInstant(list.createdAt, "shopping list creation time")
            uniqueIds(list.items.map { it.id }, "shopping list item")
            list.items.forEach { item ->
                parseUuid(item.id, "shopping list item ID")
                parseUuid(item.ingredientId, "shopping list ingredient ID")
                nonBlank(item.ingredientName, "shopping list ingredient name")
                validateRational(item.quantity, "shopping list quantity", allowZero = false)
                validateUnit(item.unitCode, "shopping list unit")
                if (item.sourceRecipeIds.isEmpty()) invalid("Shopping list item has no source recipes")
                uniqueIds(item.sourceRecipeIds, "shopping list source recipe")
                item.sourceRecipeIds.forEach { recipeId ->
                    parseUuid(recipeId, "shopping list source recipe ID")
                }
            }
        }

        val eventIds = uniqueNonBlank(document.cookingHistory.map { it.id }, "cook event")
        document.cookingHistory.forEach { event ->
            nonBlank(event.id, "cook event ID")
            parseUuid(event.recipeId, "cook event recipe ID")
            if (event.servings != null && event.servings <= 0) invalid("Cook event servings must be positive")
            parseDate(event.cookedOn, "cook date")
            event.undoneOn?.let { parseDate(it, "cook undo date") }
            if (event.leftoverFollowUpPending && event.hasLeftovers) {
                invalid("Cook event cannot await leftover follow-up and already contain leftovers")
            }
            uniqueIds(event.inventoryBefore.map { it.id }, "pre-cook inventory lot")
            event.inventoryBefore.forEach { lot ->
                parseUuid(lot.id, "pre-cook inventory lot ID")
                parseUuid(lot.ingredientId, "pre-cook inventory ingredient ID")
                parseUuid(lot.locationId, "pre-cook inventory location ID")
                validateInventoryLot(lot)
            }
            uniqueIds(event.consumed.map { it.lotId }, "consumed inventory lot")
            event.consumed.forEach { consumed ->
                parseUuid(consumed.lotId, "consumed inventory lot ID")
                validateRational(consumed.quantity, "consumed quantity", allowZero = false)
                validateUnit(consumed.unitCode, "consumed quantity unit")
            }
        }

        uniqueIds(document.leftovers.map { it.id }, "leftover")
        document.leftovers.forEach { leftover ->
            parseUuid(leftover.id, "leftover ID")
            nonBlank(leftover.cookEventId, "leftover cook event ID")
            if (leftover.cookEventId !in eventIds) invalid("Leftover refers to a missing cook event")
            leftover.ingredientId?.let { ingredientId ->
                parseUuid(ingredientId, "leftover ingredient ID")
                if (ingredientId !in ingredientIds) invalid("Leftover refers to a missing ingredient")
            }
            nonBlank(leftover.name, "leftover name")
            validateRational(leftover.quantity, "leftover quantity", allowZero = false)
            validateUnit(leftover.unitCode, "leftover unit")
            val preparedOn = parseDate(leftover.preparedOn, "leftover preparation date")
            leftover.useBy?.let { useBy ->
                val useByDate = parseDate(useBy, "leftover use-by date")
                if (useByDate.value < preparedOn.value) invalid("Leftover use-by date precedes preparation date")
            }
        }
        document.cookingHistory.forEach { event ->
            val recordedLeftovers = document.leftovers.any { it.cookEventId == event.id }
            if (event.hasLeftovers != recordedLeftovers) {
                invalid("Cook event leftover flag does not match its leftover records")
            }
        }
    }

    private fun validateInventoryLot(lot: BackupInventoryLot) {
        val kind = lot.amountKind
        val amount = lot.quantity
        when (kind) {
            "PRESENCE_ONLY" -> {
                if (amount != null || lot.unitCode != null || lot.approximateLevel != null) {
                    invalid("Presence-only inventory cannot contain quantity or unit fields")
                }
            }
            "EXACT" -> {
                if (amount == null || lot.approximateLevel != ApproximateLevel.EXACT.name) {
                    invalid("Exact inventory must include a quantity and EXACT level")
                }
                validateRational(amount, "inventory quantity", allowZero = true)
                validateUnit(lot.unitCode, "inventory unit")
            }
            "APPROXIMATE" -> {
                if (amount == null || lot.approximateLevel == null) {
                    invalid("Approximate inventory must include quantity and approximation level")
                }
                validateRational(amount, "inventory quantity", allowZero = true)
                validateUnit(lot.unitCode, "inventory unit")
                val level = enumValue<ApproximateLevel>(lot.approximateLevel, "inventory approximation")
                if (level == ApproximateLevel.EXACT) invalid("Approximate inventory cannot use EXACT level")
            }
            else -> invalid("Unknown inventory amount kind")
        }

        val purchased = lot.purchasedOn?.let { parseDate(it, "inventory purchase date") }
        val opened = lot.openedOn?.let { parseDate(it, "inventory opening date") }
        val frozen = lot.frozenOn?.let { parseDate(it, "inventory freezing date") }
        lot.manufacturerUseBy?.let { parseDate(it, "manufacturer use-by date") }
        lot.userUseBy?.let { parseDate(it, "user use-by date") }
        if (opened != null && purchased != null && opened.value < purchased.value) {
            invalid("Inventory opening date precedes purchase date")
        }
        val latestStart = opened ?: purchased
        if (frozen != null && latestStart != null && frozen.value < latestStart.value) {
            invalid("Inventory freezing date precedes purchase or opening date")
        }
    }

    private fun validateArchivePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') ||
            path.contains('\\') || path.contains('\u0000') || path.contains(':')
        ) {
            invalid("Backup archive contains an unsafe path")
        }
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            invalid("Backup archive contains an unsafe path")
        }
        when (path) {
            MANIFEST_PATH, PAYLOAD_PATH -> Unit
            else -> validateImagePath(path)
        }
    }

    private fun validateImagePath(path: String) {
        val prefix = "images/"
        if (!path.startsWith(prefix)) invalid("Backup image path is outside images/")
        val filename = path.removePrefix(prefix)
        if (filename.contains('/')) invalid("Backup image path must not contain nested folders")
        val id = filename.substringBeforeLast('.', missingDelimiterValue = "")
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (id.isBlank() || extension !in IMAGE_EXTENSIONS) invalid("Backup image path is invalid")
        val canonicalId = parseUuid(id, "image recipe ID").toString()
        if (id != canonicalId || path != "$prefix$canonicalId.$extension") {
            invalid("Backup image path is not normalized")
        }
    }

    private fun mediaTypeForPath(path: String): String = when (path.substringAfterLast('.')) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> invalid("Backup image type is unsupported")
    }

    private fun manifestFingerprint(manifest: BackupManifest): String {
        val canonical = buildString {
            append(manifest.formatId).append('\n')
            append(manifest.formatVersion).append('\n')
            append(manifest.exportedAt).append('\n')
            append(manifest.payloadPath).append('\n')
            append(manifest.payloadSizeBytes).append('\n')
            append(manifest.payloadSha256).append('\n')
            manifest.assets.sortedBy { it.path }.forEach { asset ->
                append(asset.path).append('\t').append(asset.sizeBytes).append('\t').append(asset.sha256).append('\n')
            }
        }
        return sha256BackupBytes(canonical.encodeToByteArray())
    }

    private fun fingerprintManifest(manifest: BackupManifest): String = sha256BackupBytes(
        buildString {
            append(manifest.formatId).append('\n')
            append(manifest.formatVersion).append('\n')
            append(manifest.exportedAt).append('\n')
            append(manifest.payloadPath).append('\n')
            append(manifest.payloadSizeBytes).append('\n')
            append(manifest.payloadSha256).append('\n')
            manifest.assets.sortedBy { it.path }.forEach { asset ->
                append(asset.path).append('\t').append(asset.sizeBytes).append('\t')
                    .append(asset.sha256).append('\n')
            }
        }.encodeToByteArray(),
    )

    private fun validateSha256(value: String, description: String) {
        if (!SHA256_PATTERN.matches(value)) invalid("Backup $description checksum is malformed")
    }

    private fun parseUuid(value: String, description: String): Uuid {
        val id = try {
            Uuid.parse(value)
        } catch (error: IllegalArgumentException) {
            invalid("Backup contains an invalid $description", error)
        }
        if (id.toString() != value) invalid("Backup $description is not normalized")
        return id
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, description: String): T =
        try {
            enumValueOf<T>(value)
        } catch (error: IllegalArgumentException) {
            invalid("Backup contains an unsupported $description", error)
        }

    private fun parseDate(value: String, description: String): DomainLocalDate =
        try {
            DomainLocalDate(LocalDate.parse(value))
        } catch (error: IllegalArgumentException) {
            invalid("Backup contains an invalid $description", error)
        }

    private fun parseInstant(value: String, description: String) {
        try {
            Instant.parse(value)
        } catch (error: IllegalArgumentException) {
            invalid("Backup contains an invalid $description", error)
        }
    }

    private fun validateRational(value: BackupRational?, description: String, allowZero: Boolean) {
        if (value == null) return
        if (value.denominator <= 0) invalid("Backup $description denominator must be positive")
        val rational = try {
            Rational.of(value.numerator, value.denominator)
        } catch (error: IllegalArgumentException) {
            invalid("Backup contains an invalid $description", error)
        }
        if (rational.numerator != value.numerator || rational.denominator != value.denominator) {
            invalid("Backup $description is not in normalized form")
        }
        if (value.numerator < 0 || (!allowZero && value.numerator == 0L)) {
            invalid("Backup $description must be positive")
        }
    }

    private fun validateUnit(value: String?, description: String) {
        if (value == null) return
        if (UnitCode.fromCode(value) == null) invalid("Backup contains an unsupported $description")
    }

    private fun compare(first: BackupRational, second: BackupRational): Int {
        val left = Rational.of(first.numerator, first.denominator)
        val right = Rational.of(second.numerator, second.denominator)
        return left.compareTo(right)
    }

    private fun requireNonNegative(value: Long?, description: String) {
        if (value != null && value < 0) invalid("Backup $description cannot be negative")
    }

    private fun uniqueIds(values: List<String>, description: String): Set<String> {
        val set = values.toSet()
        if (set.size != values.size) invalid("Backup contains duplicate $description IDs")
        return set
    }

    private fun uniqueNonBlank(values: List<String>, description: String): Set<String> {
        values.forEach { nonBlank(it, "$description ID") }
        return uniqueIds(values, description)
    }

    private fun uniqueNames(values: List<String>, description: String) {
        val names = mutableSetOf<String>()
        values.forEach { name ->
            if (!names.add(name.trim().lowercase())) invalid("Backup contains duplicate $description values")
        }
    }

    private fun uniqueExactValues(values: List<String>, description: String) {
        if (values.toSet().size != values.size) invalid("Backup contains duplicate $description values")
    }

    private fun nonBlank(value: String, description: String) {
        if (value.isBlank()) invalid("Backup contains a blank $description")
    }

    private fun invalid(message: String, cause: Throwable? = null): Nothing =
        throw BackupArchiveValidationException(message, cause)

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val PAYLOAD_PATH = "data.json"
        val IMAGE_EXTENSIONS = setOf("jpg", "png", "webp", "gif")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
