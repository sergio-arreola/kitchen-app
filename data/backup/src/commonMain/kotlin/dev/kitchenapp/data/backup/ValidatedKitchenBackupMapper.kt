package dev.kitchenapp.data.backup

import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.CookHistoryEntry
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.KitchenBackupLeftover
import dev.kitchenapp.core.application.KitchenBackupRestoreRequest
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
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.identifiers.ShoppingListItemId
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
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate

/** Converts a fully validated archive into the application restore snapshot. */
fun ValidatedKitchenBackup.toSnapshot(): KitchenBackupSnapshot {
    val ingredients = document.ingredients.map { ingredient ->
        Ingredient(
            id = IngredientId(Uuid.parse(ingredient.id)),
            name = ingredient.name,
            aliases = ingredient.aliases.toSet(),
        )
    }
    val locations = document.locations.map { location ->
        KitchenLocation(KitchenLocationId(Uuid.parse(location.id)), location.name)
    }
    val recipes = document.recipes.map { backupRecipe ->
        val image = backupRecipe.imageAssetPath?.let { path ->
            val asset = imageAssets[path] ?: error("Validated backup image is unavailable")
            RecipeImage(asset.bytes, asset.mediaType)
        }
        RecipeBackupEntry(backupRecipe.toDomainRecipe(), image)
    }
    val kitchen = KitchenSnapshot(
        ingredients = ingredients,
        inventory = KitchenInventory(
            locations = locations,
            lots = document.inventoryLots.map(BackupInventoryLot::toDomainLot),
        ),
        availableEquipment = document.availableEquipment.toSet(),
        householdRules = HouseholdIngredientRules(
            hardExclusions = document.hardExclusions.map { IngredientId(Uuid.parse(it)) }.toSet(),
            allergens = document.allergens.map { IngredientId(Uuid.parse(it)) }.toSet(),
            preferences = document.preferences.map { preference ->
                IngredientPreference(
                    ingredientId = IngredientId(Uuid.parse(preference.ingredientId)),
                    strength = enumValueOf<PreferenceStrength>(preference.strength),
                    score = preference.score,
                )
            },
        ),
    )
    val shoppingList = document.shoppingList?.let { list ->
        ShoppingList(
            createdAt = DomainInstant(Instant.parse(list.createdAt)),
            items = list.items.map { item ->
                ShoppingListItem(
                    id = ShoppingListItemId(Uuid.parse(item.id)),
                    ingredientId = IngredientId(Uuid.parse(item.ingredientId)),
                    ingredientName = item.ingredientName,
                    quantity = item.quantity?.toRational(),
                    unit = item.unitCode?.toUnit(),
                    sourceRecipeIds = item.sourceRecipeIds.map { RecipeId(Uuid.parse(it)) }.toSet(),
                    checked = item.checked,
                )
            },
        )
    }
    val cookingHistory = document.cookingHistory.map { event ->
        CookHistoryEntry(
            event = CookEvent(
                id = event.id,
                recipeId = RecipeId(Uuid.parse(event.recipeId)),
                servings = event.servings,
                cookedOn = event.cookedOn.toDomainDate(),
                consumed = event.consumed.map { consumed ->
                    ConsumedInventory(
                        lotId = InventoryLotId(Uuid.parse(consumed.lotId)),
                        quantity = consumed.quantity?.toRational(),
                        unit = consumed.unitCode?.toUnit(),
                    )
                },
                note = event.note,
            ),
            leftoverFollowUpPending = event.leftoverFollowUpPending,
            hasLeftovers = event.hasLeftovers,
            leftoverReminderClaimed = event.leftoverReminderClaimed,
            undoneOn = event.undoneOn?.toDomainDate(),
            inventoryBefore = event.inventoryBefore.map(BackupInventoryLot::toDomainLot),
        )
    }
    val leftovers = document.leftovers.map { leftover ->
        KitchenBackupLeftover(
            id = InventoryLotId(Uuid.parse(leftover.id)),
            cookEventId = leftover.cookEventId,
            ingredientId = leftover.ingredientId?.let { IngredientId(Uuid.parse(it)) },
            name = leftover.name,
            quantity = leftover.quantity?.toRational(),
            unit = leftover.unitCode?.toUnit(),
            preparedOn = leftover.preparedOn.toDomainDate(),
            useBy = leftover.useBy?.toDomainDate(),
        )
    }
    return KitchenBackupSnapshot(
        recipes = recipes,
        categories = document.categories,
        kitchen = kitchen,
        shoppingList = shoppingList,
        cookingHistory = cookingHistory,
        leftovers = leftovers,
    )
}

fun ValidatedKitchenBackup.toRestoreRequest(): KitchenBackupRestoreRequest {
    val snapshot = toSnapshot()
    return KitchenBackupRestoreRequest(
        snapshot = snapshot,
        archiveFingerprint = archiveFingerprint,
        snapshotFingerprint = KitchenBackupArchiveExporter().fingerprint(snapshot),
    )
}

private fun BackupRecipe.toDomainRecipe(): Recipe = Recipe(
    id = RecipeId(Uuid.parse(id)),
    name = name,
    servings = servings,
    ingredients = ingredients.map { ingredient ->
        RecipeIngredient(
            id = RecipeIngredientId(Uuid.parse(ingredient.id)),
            ingredientId = IngredientId(Uuid.parse(ingredient.ingredientId)),
            quantity = ingredient.quantity?.toRational(),
            quantityHigh = ingredient.quantityHigh?.toRational(),
            unit = ingredient.unitCode?.toUnit(),
            preparation = ingredient.preparation,
            importance = enumValueOf<IngredientImportance>(ingredient.importance),
            approximateLevel = enumValueOf<ApproximateLevel>(ingredient.approximateLevel),
            rawLine = ingredient.rawLine,
        )
    },
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
    importedAt = importedAt?.let { DomainInstant(Instant.parse(it)) },
    mealTypes = mealTypes,
    equipmentRequirements = equipmentRequirements.map { requirement ->
        EquipmentRequirement(
            equipment = requirement.equipment,
            kind = enumValueOf<EquipmentRequirementKind>(requirement.kind),
            acceptableSubstitutes = requirement.acceptableSubstitutes.toSet(),
        )
    },
)

private fun BackupInventoryLot.toDomainLot(): InventoryLot {
    val amount = when (amountKind) {
        "PRESENCE_ONLY" -> InventoryAmount.PresenceOnly
        "APPROXIMATE" -> InventoryAmount.Approximate(
            quantity = requireNotNull(quantity).toRational(),
            unit = unitCode?.toUnit(),
            level = enumValueOf<ApproximateLevel>(requireNotNull(approximateLevel)),
        )
        "EXACT" -> InventoryAmount.Exact(
            quantity = requireNotNull(quantity).toRational(),
            unit = unitCode?.toUnit(),
        )
        else -> error("Validated backup contains an unknown inventory amount kind")
    }
    return InventoryLot(
        id = InventoryLotId(Uuid.parse(id)),
        ingredientId = IngredientId(Uuid.parse(ingredientId)),
        locationId = KitchenLocationId(Uuid.parse(locationId)),
        amount = amount,
        purchasedOn = purchasedOn?.toDomainDate(),
        openedOn = openedOn?.toDomainDate(),
        frozenOn = frozenOn?.toDomainDate(),
        manufacturerUseBy = manufacturerUseBy?.toDomainDate(),
        userUseBy = userUseBy?.toDomainDate(),
    )
}

private fun BackupRational.toRational(): Rational = Rational.of(numerator, denominator)

private fun String.toUnit(): UnitCode =
    UnitCode.fromCode(this) ?: error("Validated backup contains an unknown unit code")

private fun String.toDomainDate(): DomainLocalDate = DomainLocalDate(LocalDate.parse(this))
