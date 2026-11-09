package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
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

enum class FirstReturnState {
    NOT_RETURNED,
    PROMPTED,
    DISMISSED,
    CONFIRMED,
}

/** The durable, single active cooking session. Checklist state is session-only. */
data class ActiveCookingSession(
    val sessionId: String,
    val recipeId: RecipeId,
    val servings: Int?,
    val checkedIngredients: Set<RecipeIngredientId> = emptySet(),
    val firstReturnState: FirstReturnState = FirstReturnState.NOT_RETURNED,
    val awayGeneration: Long = 0,
    val isAway: Boolean = false,
    val deliveredCheckInGeneration: Long? = null,
    val resumeRequired: Boolean = false,
) {
    init {
        require(sessionId.isNotBlank())
        require(servings == null || servings > 0)
        require(awayGeneration >= 0)
        require(deliveredCheckInGeneration == null || deliveredCheckInGeneration in 1..awayGeneration)
    }
}

/** Persistence boundary for the one active cooking session and lifecycle-safe check-ins. */
interface ActiveCookingSessionRepository {
    fun getActive(): ActiveCookingSession?

    /** Must fail without replacing an existing session. */
    fun start(session: ActiveCookingSession)

    fun setIngredientChecked(
        sessionId: String,
        ingredientId: RecipeIngredientId,
        checked: Boolean,
    ): ActiveCookingSession

    fun dismissFirstReturnPrompt(sessionId: String)

    fun resume(sessionId: String): ActiveCookingSession

    /** Marks a genuine departure and returns its schedule generation, or null if resume is required. */
    fun markAway(sessionId: String): Long?

    /** Marks a return and atomically claims the one-time first-return prompt. */
    fun markReturned(sessionId: String): CookingReturn?

    /** At-most-once claim for this away generation, before the platform posts its notification. */
    fun claimCheckInDelivery(sessionId: String, generation: Long): Boolean

    fun clear(sessionId: String)
}

data class CookingReturn(
    val session: ActiveCookingSession,
    val showContinuePrompt: Boolean,
    val resumeRequired: Boolean,
)

data class CookingSession(
    val recipe: Recipe,
    val servings: Int?,
    val sessionId: String,
    val checkedIngredients: Set<RecipeIngredientId> = emptySet(),
    val firstReturnState: FirstReturnState = FirstReturnState.NOT_RETURNED,
) {
    fun persistedState(): ActiveCookingSession = ActiveCookingSession(
        sessionId = sessionId,
        recipeId = recipe.id,
        servings = servings,
        checkedIngredients = checkedIngredients,
        firstReturnState = firstReturnState,
    )
}

/** Atomic boundary for inventory depletion, cook history, leftovers, and undo. */
interface CookingRepository {
    fun complete(
        event: CookEvent,
        previousLots: List<InventoryLot>,
        updatedSnapshot: KitchenSnapshot,
        leftovers: List<PreparedLeftover>,
        sessionId: String? = null,
        leftoverFollowUpPending: Boolean = false,
    )

    /** Adds post-cook leftovers only while the event's follow-up is pending and not undone. */
    fun recordLeftovers(
        eventId: String,
        leftovers: List<PreparedLeftover>,
        updatedSnapshot: KitchenSnapshot,
    )

    fun dismissLeftoverFollowUp(eventId: String)

    fun claimLeftoverReminder(eventId: String): Boolean

    fun getHistory(): List<CookHistoryEntry>

    /** Restores the lots changed by [eventId] and returns the resulting snapshot, or null if unavailable. */
    fun undo(eventId: String, undoneOn: DomainLocalDate): KitchenSnapshot?
}

data class CookHistoryEntry(
    val event: CookEvent,
    val leftoverFollowUpPending: Boolean,
    val hasLeftovers: Boolean,
)

/** Starting a recipe persists a new session but never changes inventory. */
class StartCooking(private val sessions: ActiveCookingSessionRepository) {
    fun start(recipe: Recipe, servings: Int? = null): CookingSession {
        require(servings == null || servings > 0)
        val session = CookingSession(
            recipe = recipe,
            servings = servings,
            sessionId = Uuid.random().toString(),
        )
        sessions.start(session.persistedState())
        return session
    }
}

class UpdateCookingChecklist(
    private val sessions: ActiveCookingSessionRepository,
    private val recipes: RecipeRepository,
) {
    fun setChecked(
        sessionId: String,
        ingredientId: RecipeIngredientId,
        checked: Boolean,
    ): ActiveCookingSession {
        val session = requireNotNull(sessions.getActive()) { "There is no active cooking session" }
        require(session.sessionId == sessionId) { "Cooking session is no longer active" }
        val recipe = requireNotNull(recipes.get(session.recipeId)) { "Active recipe no longer exists" }
        require(recipe.ingredients.any { it.id == ingredientId }) { "Ingredient does not belong to the active recipe" }
        return sessions.setIngredientChecked(sessionId, ingredientId, checked)
    }
}

class MarkCookingAway(private val sessions: ActiveCookingSessionRepository) {
    fun markAway(sessionId: String): Long? = sessions.markAway(sessionId)
}

class MarkCookingReturned(private val sessions: ActiveCookingSessionRepository) {
    fun markReturned(sessionId: String): CookingReturn? = sessions.markReturned(sessionId)
}

class DismissCookingReturnPrompt(private val sessions: ActiveCookingSessionRepository) {
    fun dismiss(sessionId: String) {
        sessions.dismissFirstReturnPrompt(sessionId)
    }
}

class ResumeCooking(private val sessions: ActiveCookingSessionRepository) {
    fun resume(sessionId: String): ActiveCookingSession {
        return sessions.resume(sessionId)
    }
}

class ClaimCookingCheckIn(private val sessions: ActiveCookingSessionRepository) {
    fun claim(sessionId: String, generation: Long): Boolean =
        sessions.claimCheckInDelivery(sessionId, generation)
}

class GetActiveCookingSession(
    private val sessions: ActiveCookingSessionRepository,
    private val recipes: RecipeRepository,
) {
    fun get(): CookingSession? {
        val active = sessions.getActive() ?: return null
        val recipe = recipes.get(active.recipeId) ?: return null
        return CookingSession(
            recipe = recipe,
            servings = active.servings,
            sessionId = active.sessionId,
            checkedIngredients = active.checkedIngredients,
            firstReturnState = active.firstReturnState,
        )
    }
}

class GetCookingHistory(private val cookingRepository: CookingRepository) {
    fun get(): List<CookHistoryEntry> = cookingRepository.getHistory()
}

class RecordLeftoversForCookEvent(
    private val kitchenRepository: KitchenRepository,
    private val cookingRepository: CookingRepository,
) {
    fun record(eventId: String, leftovers: List<PreparedLeftover>) {
        require(leftovers.isNotEmpty())
        val snapshot = kitchenRepository.load()
        var inventory = snapshot.inventory
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
        cookingRepository.recordLeftovers(
            eventId = eventId,
            leftovers = leftovers,
            updatedSnapshot = snapshot.copy(inventory = inventory),
        )
    }
}

class DismissLeftoverFollowUp(private val cookingRepository: CookingRepository) {
    fun dismiss(eventId: String) = cookingRepository.dismissLeftoverFollowUp(eventId)
}

class ClaimLeftoverFollowUpReminder(private val cookingRepository: CookingRepository) {
    fun claim(eventId: String): Boolean = cookingRepository.claimLeftoverReminder(eventId)
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
        recordLeftoversLater: Boolean = false,
        note: String? = null,
    ): CookEvent {
        require(!(leftovers.isNotEmpty() && recordLeftoversLater)) {
            "Record leftovers now or later, not both"
        }
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
            sessionId = session.sessionId,
            leftoverFollowUpPending = recordLeftoversLater,
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
