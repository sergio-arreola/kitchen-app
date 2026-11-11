package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.CookEvent
import dev.kitchenapp.core.application.CookHistoryEntry
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.CookingRepository
import dev.kitchenapp.core.application.KitchenRepository
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.identifiers.RecipeId
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
        sessionId: String?,
        leftoverFollowUpPending: Boolean,
    ) {
        require(!(leftovers.isNotEmpty() && leftoverFollowUpPending))
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
                leftoverFollowUpPending.toLong(),
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
            sessionId?.let { id ->
                require(queries.selectCookingSessionById(id).executeAsOneOrNull() != null) {
                    "Active cooking session is no longer available"
                }
                queries.deleteActiveCookingSession(id)
            }
        }
    }

    override fun recordLeftovers(
        eventId: String,
        leftovers: List<PreparedLeftover>,
        updatedSnapshot: KitchenSnapshot,
    ) {
        require(leftovers.isNotEmpty())
        database.transaction {
            val queries = database.recipeQueries
            require(queries.selectCookEventExistsAndNotUndone(eventId).executeAsOneOrNull() != null) {
                "Cook event does not exist or has been undone"
            }
            require(queries.selectCookEventFollowUpPending(eventId).executeAsOneOrNull() == 1L) {
                "Cook event is not awaiting leftover follow-up"
            }
            kitchen.save(updatedSnapshot)
            leftovers.forEach { leftover ->
                queries.insertCookLeftover(
                    leftover.id.toString(), eventId, leftover.ingredientId.toString(), leftover.name,
                    leftover.quantity.numerator, leftover.quantity.denominator, leftover.unit?.code,
                    leftover.preparedOn.toString(), leftover.useBy?.toString(),
                )
            }
            queries.markCookEventFollowUpComplete(eventId)
            check(queries.selectChangedRows().executeAsOne() == 1L) {
                "Cook event follow-up was already completed"
            }
        }
    }

    override fun dismissLeftoverFollowUp(eventId: String) {
        database.transaction {
            val queries = database.recipeQueries
            require(queries.selectCookEventExistsAndNotUndone(eventId).executeAsOneOrNull() != null) {
                "Cook event does not exist or has been undone"
            }
            queries.markCookEventFollowUpDismissed(eventId)
            check(queries.selectChangedRows().executeAsOne() == 1L) {
                "Cook event is not awaiting leftover follow-up"
            }
        }
    }

    override fun claimLeftoverReminder(eventId: String): Boolean {
        var claimed = false
        database.transaction {
            val queries = database.recipeQueries
            queries.claimLeftoverReminder(eventId)
            claimed = queries.selectChangedRows().executeAsOne() == 1L
        }
        return claimed
    }

    override fun getHistory(): List<CookHistoryEntry> =
        database.recipeQueries.selectCookHistory().executeAsList().map { row ->
            CookHistoryEntry(
                event = CookEvent(
                    id = row.id,
                    recipeId = RecipeId(Uuid.parse(row.recipeId)),
                    servings = row.servings?.toInt(),
                    cookedOn = row.cookedOn.asDate(),
                    consumed = database.recipeQueries.selectCookConsumptions(row.id).executeAsList().map { item ->
                        ConsumedInventory(
                            lotId = InventoryLotId(Uuid.parse(item.lotId)),
                            quantity = item.quantityNumerator?.let {
                                Rational.of(it, requireNotNull(item.quantityDenominator))
                            },
                            unit = item.unitCode?.asUnit(),
                        )
                    },
                    note = row.note,
                ),
                leftoverFollowUpPending = row.leftoverFollowUpPending == 1L,
                hasLeftovers = row.hasLeftovers,
                leftoverReminderClaimed = row.leftoverReminderClaimed == 1L,
                undoneOn = row.undoneOn?.asDate(),
                inventoryBefore = database.recipeQueries.selectLotBeforeCooking(row.id).executeAsList()
                    .map { previous ->
                        InventoryLot(
                            id = InventoryLotId(Uuid.parse(previous.id)),
                            ingredientId = IngredientId(Uuid.parse(previous.ingredientId)),
                            locationId = KitchenLocationId(Uuid.parse(previous.locationId)),
                            amount = when (previous.amountKind) {
                                "PRESENCE_ONLY" -> InventoryAmount.PresenceOnly
                                "APPROXIMATE" -> InventoryAmount.Approximate(
                                    Rational.of(previous.quantityNumerator!!, previous.quantityDenominator!!),
                                    previous.unitCode?.asUnit(),
                                    enumValueOf<ApproximateLevel>(requireNotNull(previous.approximateLevel)),
                                )
                                "EXACT" -> InventoryAmount.Exact(
                                    Rational.of(previous.quantityNumerator!!, previous.quantityDenominator!!),
                                    previous.unitCode?.asUnit(),
                                )
                                else -> error("Unknown persisted inventory amount kind: ${previous.amountKind}")
                            },
                            purchasedOn = previous.purchasedOn?.asDate(),
                            openedOn = previous.openedOn?.asDate(),
                            frozenOn = previous.frozenOn?.asDate(),
                            manufacturerUseBy = previous.manufacturerUseBy?.asDate(),
                            userUseBy = previous.userUseBy?.asDate(),
                        )
                    },
            )
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
            queries.clearCookEventFollowUp(eventId)
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

    private fun Boolean.toLong(): Long = if (this) 1L else 0L

    private fun String.asUnit(): UnitCode =
        UnitCode.fromCode(this) ?: error("Unknown persisted unit code: $this")

    private fun String.asDate(): DomainLocalDate = DomainLocalDate(LocalDate.parse(this))
}
