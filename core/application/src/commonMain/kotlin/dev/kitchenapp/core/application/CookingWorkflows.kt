package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.inventory.InventoryAmount
import dev.kitchenapp.core.domain.inventory.InventoryLot
import dev.kitchenapp.core.domain.inventory.KitchenInventory
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.core.domain.quantity.UnitConverter
import kotlin.uuid.Uuid

/** One user-confirmed cook event. It is created only when cooking is completed. */
data class CookEvent(
    val id: String,
    val recipeId: RecipeId,
    val servings: Int?,
    val cookedOn: DomainLocalDate,
    val consumed: List<ConsumedInventory>,
    val note: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(servings == null || servings > 0)
        require(consumed.map { it.lotId }.distinct().size == consumed.size)
    }
}

/** Actual amount consumed from one lot; a null amount consumes a presence-only lot. */
data class ConsumedInventory(
    val lotId: InventoryLotId,
    val quantity: Rational? = null,
    val unit: UnitCode? = null,
) {
    init {
        require(quantity == null || quantity.isPositive())
        require(quantity != null || unit == null)
    }
}

/** Optional recipe-linked prepared food saved alongside its inventory lot. */
data class PreparedLeftover(
    val id: InventoryLotId = InventoryLotId.generate(),
    val ingredientId: IngredientId,
    val name: String,
    val quantity: Rational,
    val unit: UnitCode?,
    val locationId: KitchenLocationId,
    val preparedOn: DomainLocalDate,
    val useBy: DomainLocalDate? = null,
) {
    init {
        require(name.isNotBlank())
        require(quantity.isPositive())
        require(useBy == null || !useBy.isBefore(preparedOn))
    }
}

/** Atomic boundary for inventory depletion, cook history, leftovers, and undo. */
interface CookingRepository {
    fun complete(
        event: CookEvent,
        previousLots: List<InventoryLot>,
        updatedSnapshot: KitchenSnapshot,
        leftovers: List<PreparedLeftover>,
    )

    /** Restores the lots changed by [eventId] and returns the resulting snapshot, or null if unavailable. */
    fun undo(eventId: String, undoneOn: DomainLocalDate): KitchenSnapshot?
}

data class CookingSession(
    val recipe: Recipe,
    val servings: Int?,
)

/** Starting a recipe only creates an in-memory session. */
class StartCooking {
    fun start(recipe: Recipe, servings: Int? = null): CookingSession {
        require(servings == null || servings > 0)
        return CookingSession(recipe, servings)
    }
}

/** Applies the user's actual consumption only after explicit completion. */
class CompleteCooking(
    private val kitchenRepository: KitchenRepository,
    private val cookingRepository: CookingRepository,
    private val converter: UnitConverter = UnitConverter(),
) {
    fun complete(
        session: CookingSession,
        cookedOn: DomainLocalDate,
        consumed: List<ConsumedInventory>,
        leftovers: List<PreparedLeftover> = emptyList(),
        note: String? = null,
    ): CookEvent {
        val event = CookEvent(
            id = Uuid.random().toString(),
            recipeId = session.recipe.id,
            servings = session.servings,
            cookedOn = cookedOn,
            consumed = consumed,
            note = note,
        )
        val snapshot = kitchenRepository.load()
        var inventory = snapshot.inventory
        val previousLots = mutableListOf<InventoryLot>()
        consumed.forEach { item ->
            val lot = inventory.lots.singleOrNull { it.id == item.lotId }
                ?: error("Consumed inventory lot does not exist")
            previousLots += lot
            inventory = inventory.consume(lot, item)
        }
        leftovers.forEach { leftover ->
            require(leftover.locationId in inventory.locations.map { it.id }) {
                "Leftover location is not configured"
            }
            inventory = inventory.addLot(
                InventoryLot(
                    id = leftover.id,
                    ingredientId = leftover.ingredientId,
                    locationId = leftover.locationId,
                    amount = InventoryAmount.Exact(leftover.quantity, leftover.unit),
                    purchasedOn = leftover.preparedOn,
                    userUseBy = leftover.useBy,
                ),
            )
        }
        cookingRepository.complete(
            event = event,
            previousLots = previousLots,
            updatedSnapshot = snapshot.copy(inventory = inventory),
            leftovers = leftovers,
        )
        return event
    }

    private fun KitchenInventory.consume(lot: InventoryLot, item: ConsumedInventory): KitchenInventory {
        val amount = lot.amount
        if (amount == InventoryAmount.PresenceOnly) {
            require(item.quantity == null) { "Presence-only stock must be consumed as a whole lot" }
            return discardLot(lot.id)
        }
        val measured = when (amount) {
            is InventoryAmount.Approximate -> amount.quantity to amount.unit
            is InventoryAmount.Exact -> amount.quantity to amount.unit
            InventoryAmount.PresenceOnly -> error("Handled above")
        }
        val (available, stockUnit) = measured
        val requested = requireNotNull(item.quantity) { "Measured stock requires an actual consumed quantity" }
        val requestedInLotUnit = when {
            item.unit == stockUnit -> requested
            item.unit == null || stockUnit == null -> error("Consumed quantity unit does not match tracked stock")
            else -> converter.convert(requested, item.unit, stockUnit, lot.ingredientId)
                ?: error("Consumed quantity units are incompatible")
        }
        require(requestedInLotUnit <= available) { "Consumed quantity exceeds tracked stock" }
        val remaining = available - requestedInLotUnit
        if (remaining.isZero()) return discardLot(lot.id)
        val updatedAmount = when (amount) {
            is InventoryAmount.Approximate -> amount.copy(quantity = remaining)
            is InventoryAmount.Exact -> amount.copy(quantity = remaining)
            InventoryAmount.PresenceOnly -> error("Handled above")
        }
        return adjustAmount(lot.id, updatedAmount)
    }
}
