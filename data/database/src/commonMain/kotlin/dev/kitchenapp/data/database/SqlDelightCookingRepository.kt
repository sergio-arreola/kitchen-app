package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.CookingRepository
import dev.kitchenapp.core.application.KitchenRepository
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate

/** Persists cooking, stock changes, leftovers, and undo records in one SQLite transaction. */
class SqlDelightCookingRepository(
    private val database: KitchenDatabase,
    private val kitchen: KitchenRepository = SqlDelightKitchenRepository(database),
) : CookingRepository {
    override fun complete(
        event: CookEvent,
        previousLots: List<InventoryLot>,
        updatedSnapshot: KitchenSnapshot,
        leftovers: List<PreparedLeftover>,
    ) {
        database.transaction {
            val queries = database.recipeQueries
            require(queries.selectRecipeById(event.recipeId.toString()).executeAsOneOrNull() != null) {
                "Cooked recipe does not exist"
            }
            require(queries.selectCookEventExists(event.id).executeAsOneOrNull() == null) {
                "Cook event already exists"
            }
            kitchen.save(updatedSnapshot)
            queries.insertCookEvent(
                event.id,
                event.recipeId.toString(),
                event.cookedOn.toString(),
                event.servings?.toString(),
                event.note,
            )
            event.consumed.forEach { item ->
                queries.insertCookConsumption(
                    event.id,
                    item.lotId.toString(),
                    item.quantity?.numerator,
                    item.quantity?.denominator,
                    item.unit?.code,
                )
            }
            previousLots.forEach { lot ->
                queries.insertLotBeforeCooking(
                    event.id,
                    lot.id.toString(),
                    lot.ingredientId.toString(),
                    lot.locationId.toString(),
                    lot.amount.kind(),
                    lot.amount.quantity()?.numerator,
                    lot.amount.quantity()?.denominator,
                    lot.amount.unit()?.code,
                    lot.amount.level(),
                    lot.purchasedOn?.toString(),
                    lot.openedOn?.toString(),
                    lot.frozenOn?.toString(),
                    lot.manufacturerUseBy?.toString(),
                    lot.userUseBy?.toString(),
                )
            }
            leftovers.forEach { leftover ->
                queries.insertCookLeftover(
                    leftover.id.toString(),
                    event.id,
                    leftover.ingredientId.toString(),
                    leftover.name,
                    leftover.quantity.numerator,
                    leftover.quantity.denominator,
                    leftover.unit?.code,
                    leftover.preparedOn.toString(),
                    leftover.useBy?.toString(),
                )
            }
        }
    }

    override fun undo(eventId: String, undoneOn: DomainLocalDate): KitchenSnapshot? {
        var restored: KitchenSnapshot? = null
        database.transaction {
            val queries = database.recipeQueries
            if (queries.selectCookEventExists(eventId).executeAsOneOrNull() == null) return@transaction
            if (queries.selectCookEventUndone(eventId).executeAsOneOrNull() != null) return@transaction

            val before = queries.selectLotBeforeCooking(eventId).executeAsList().map { row ->
                InventoryLot(
                    id = InventoryLotId(Uuid.parse(row.id)),
                    ingredientId = IngredientId(Uuid.parse(row.ingredientId)),
                    locationId = KitchenLocationId(Uuid.parse(row.locationId)),
                    amount = when (row.amountKind) {
                        "PRESENCE_ONLY" -> InventoryAmount.PresenceOnly
                        "APPROXIMATE" -> InventoryAmount.Approximate(
                            Rational.of(row.quantityNumerator!!, row.quantityDenominator!!),
                            row.unitCode?.asUnit(),
                            enumValueOf<ApproximateLevel>(requireNotNull(row.approximateLevel)),
                        )
                        "EXACT" -> InventoryAmount.Exact(
                            Rational.of(row.quantityNumerator!!, row.quantityDenominator!!),
                            row.unitCode?.asUnit(),
                        )
                        else -> error("Unknown persisted inventory amount kind: ${row.amountKind}")
                    },
                    purchasedOn = row.purchasedOn?.asDate(),
                    openedOn = row.openedOn?.asDate(),
                    frozenOn = row.frozenOn?.asDate(),
                    manufacturerUseBy = row.manufacturerUseBy?.asDate(),
                    userUseBy = row.userUseBy?.asDate(),
                )
            }
            val snapshot = kitchen.load()
            val locations = snapshot.inventory.locations.map { it.id }.toSet()
            require(before.all { it.locationId in locations }) {
                "Cannot undo cooking because a previous inventory location was removed"
            }
            val byId = before.associateBy { it.id }
            val leftoverIds = queries.selectCookLeftoversForEvent(eventId).executeAsList()
                .map { InventoryLotId(Uuid.parse(it)) }.toSet()
            val merged = snapshot.inventory.lots.filterNot { it.id in byId || it.id in leftoverIds } + before
            val updated = snapshot.copy(inventory = KitchenInventory(snapshot.inventory.locations, merged))
            kitchen.save(updated)
            queries.deleteCookLeftovers(eventId)
            queries.insertCookEventUndo(eventId, undoneOn.toString())
            restored = updated
        }
        return restored
    }

    private fun InventoryAmount.kind(): String = when (this) {
        InventoryAmount.PresenceOnly -> "PRESENCE_ONLY"
        is InventoryAmount.Approximate -> "APPROXIMATE"
        is InventoryAmount.Exact -> "EXACT"
    }

    private fun InventoryAmount.quantity(): Rational? = when (this) {
        InventoryAmount.PresenceOnly -> null
        is InventoryAmount.Approximate -> quantity
        is InventoryAmount.Exact -> quantity
    }

    private fun InventoryAmount.unit(): UnitCode? = when (this) {
        InventoryAmount.PresenceOnly -> null
        is InventoryAmount.Approximate -> unit
        is InventoryAmount.Exact -> unit
    }

    private fun InventoryAmount.level(): String? = when (this) {
        InventoryAmount.PresenceOnly -> null
        is InventoryAmount.Approximate -> level.name
        is InventoryAmount.Exact -> ApproximateLevel.EXACT.name
    }

    private fun String.asUnit(): UnitCode =
        UnitCode.fromCode(this) ?: error("Unknown persisted unit code: $this")

    private fun String.asDate(): DomainLocalDate = DomainLocalDate(LocalDate.parse(this))
}
