package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.KitchenRepository
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.household.HouseholdIngredientRules
import dev.kitchenapp.core.domain.household.IngredientPreference
import dev.kitchenapp.core.domain.household.PreferenceStrength
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.ingredient.Ingredient
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.inventory.KitchenLocation
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate

/** SQLDelight-backed local kitchen state. A complete save is one SQLite transaction. */
class SqlDelightKitchenRepository(
    private val database: KitchenDatabase,
) : KitchenRepository {
    override fun load(): KitchenSnapshot {
        val queries = database.recipeQueries
        val ingredientRows = queries.selectAllIngredients().executeAsList()
        val ingredients = ingredientRows.groupBy { it.id }.values.map { rows ->
            val first = rows.first()
            Ingredient(
                id = IngredientId(Uuid.parse(first.id)),
                name = first.name,
                aliases = rows.mapNotNull { it.alias }.toSet(),
            )
        }
        val locations = queries.selectKitchenLocations().executeAsList().map {
            KitchenLocation(KitchenLocationId(Uuid.parse(it.id)), it.name)
        }
        val inventory = KitchenInventory(
            locations = locations,
            lots = queries.selectInventoryLots().executeAsList().map { row ->
                val amount = when (row.amountKind) {
                    "PRESENCE_ONLY" -> InventoryAmount.PresenceOnly
                    "APPROXIMATE" -> InventoryAmount.Approximate(
                        quantity = Rational.of(row.quantityNumerator!!, row.quantityDenominator!!),
                        unit = row.unitCode?.asUnit(),
                        level = enumValueOf<ApproximateLevel>(requireNotNull(row.approximateLevel)),
                    )
                    "EXACT" -> InventoryAmount.Exact(
                        quantity = Rational.of(row.quantityNumerator!!, row.quantityDenominator!!),
                        unit = row.unitCode?.asUnit(),
                    )
                    else -> error("Unknown persisted inventory amount kind: ${row.amountKind}")
                }
                InventoryLot(
                    id = InventoryLotId(Uuid.parse(row.id)),
                    ingredientId = IngredientId(Uuid.parse(row.ingredientId)),
                    locationId = KitchenLocationId(Uuid.parse(row.locationId)),
                    amount = amount,
                    purchasedOn = row.purchasedOn?.asDomainDate(),
                    openedOn = row.openedOn?.asDomainDate(),
                    frozenOn = row.frozenOn?.asDomainDate(),
                    manufacturerUseBy = row.manufacturerUseBy?.asDomainDate(),
                    userUseBy = row.userUseBy?.asDomainDate(),
                )
            },
        )
        val exclusions = queries.selectHardExclusions().executeAsList()
            .map { IngredientId(Uuid.parse(it)) }.toSet()
        val preferences = queries.selectIngredientPreferences().executeAsList().map {
            IngredientPreference(
                ingredientId = IngredientId(Uuid.parse(it.ingredientId)),
                strength = enumValueOf<PreferenceStrength>(it.strength),
                score = it.score.toInt(),
            )
        }

        return KitchenSnapshot(
            ingredients = ingredients,
            inventory = inventory,
            availableEquipment = queries.selectAvailableEquipment().executeAsList().toSet(),
            householdRules = HouseholdIngredientRules(exclusions, preferences),
        )
    }

    override fun save(snapshot: KitchenSnapshot) {
        database.transaction {
            val queries = database.recipeQueries

            snapshot.ingredients.forEach { ingredient ->
                val ingredientId = ingredient.id.toString()
                if (queries.selectIngredientExists(ingredientId).executeAsOneOrNull() == null) {
                    queries.insertIngredient(ingredientId, ingredient.name)
                } else {
                    queries.updateIngredient(ingredient.name, ingredientId)
                }
                queries.deleteIngredientAliases(ingredientId)
                ingredient.aliases.sorted().forEach { alias ->
                    queries.insertIngredientAlias(ingredientId, alias)
                }
            }

            queries.deleteInventoryLots()
            queries.deleteKitchenLocations()
            snapshot.inventory.locations.forEach {
                queries.insertKitchenLocation(it.id.toString(), it.name)
            }
            snapshot.inventory.lots.forEach { lot ->
                val amount = lot.amount.toColumns()
                queries.insertInventoryLot(
                    lot.id.toString(),
                    lot.ingredientId.toString(),
                    lot.locationId.toString(),
                    amount.kind,
                    amount.quantity?.numerator,
                    amount.quantity?.denominator,
                    amount.unit?.code,
                    amount.level,
                    lot.purchasedOn?.toString(),
                    lot.openedOn?.toString(),
                    lot.frozenOn?.toString(),
                    lot.manufacturerUseBy?.toString(),
                    lot.userUseBy?.toString(),
                )
            }

            queries.deleteAvailableEquipment()
            snapshot.availableEquipment.sortedBy { it.trim().lowercase() }.forEach { equipment ->
                queries.insertAvailableEquipment(equipment.trim().lowercase(), equipment.trim())
            }

            queries.deleteHardExclusions()
            snapshot.householdRules.hardExclusions.sortedBy { it.toString() }.forEach {
                queries.insertHardExclusion(it.toString())
            }
            queries.deleteIngredientPreferences()
            snapshot.householdRules.preferences.sortedBy { it.ingredientId.toString() }.forEach {
                queries.insertIngredientPreference(it.ingredientId.toString(), it.strength.name, it.score.toLong())
            }
        }
    }

    private fun String.asUnit(): UnitCode =
        UnitCode.fromCode(this) ?: error("Unknown persisted unit code: $this")

    private fun String.asDomainDate(): DomainLocalDate = DomainLocalDate(LocalDate.parse(this))

    private data class AmountColumns(
        val kind: String,
        val quantity: Rational?,
        val unit: UnitCode?,
        val level: String?,
    )

    private fun InventoryAmount.toColumns(): AmountColumns =
        when (this) {
            InventoryAmount.PresenceOnly -> AmountColumns("PRESENCE_ONLY", null, null, null)
            is InventoryAmount.Approximate ->
                AmountColumns("APPROXIMATE", quantity, unit, level.name)
            is InventoryAmount.Exact ->
                AmountColumns("EXACT", quantity, unit, ApproximateLevel.EXACT.name)
        }
}
