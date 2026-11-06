package dev.kitchenapp.core.domain.inventory

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.InventoryLotId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus

/** A configurable place where kitchen inventory can be stored. */
data class KitchenLocation(
    val id: KitchenLocationId = KitchenLocationId.generate(),
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "Kitchen location name cannot be blank" }
    }
}

/**
 * Quantity precision for a lot. Presence-only items intentionally carry no
 * amount; approximate and exact quantities retain the unit when it is known.
 */
sealed interface InventoryAmount {
    /** The ingredient is available, but its amount is not tracked. */
    data object PresenceOnly : InventoryAmount

    /** A measured amount whose value is approximate. */
    data class Approximate(
        val quantity: Rational,
        val unit: UnitCode? = null,
        val level: ApproximateLevel = ApproximateLevel.MODERATE,
    ) : InventoryAmount {
        init {
            require(!quantity.isNegative()) { "Inventory quantity cannot be negative" }
            require(level != ApproximateLevel.EXACT) {
                "Approximate inventory must use a non-exact approximation level"
            }
        }
    }

    /** A measured amount tracked exactly. */
    data class Exact(
        val quantity: Rational,
        val unit: UnitCode? = null,
    ) : InventoryAmount {
        init {
            require(!quantity.isNegative()) { "Inventory quantity cannot be negative" }
        }
    }
}

/**
 * One batch of a canonical ingredient at one kitchen location.
 *
 * Manufacturer-provided dates, user-entered dates, and calculated freshness
 * recommendations remain separate values. Recommendations are advisory.
 */
data class InventoryLot(
    val id: InventoryLotId = InventoryLotId.generate(),
    val ingredientId: IngredientId,
    val locationId: KitchenLocationId,
    val amount: InventoryAmount = InventoryAmount.PresenceOnly,
    val purchasedOn: DomainLocalDate? = null,
    val openedOn: DomainLocalDate? = null,
    val frozenOn: DomainLocalDate? = null,
    val manufacturerUseBy: DomainLocalDate? = null,
    val userUseBy: DomainLocalDate? = null,
) {
    init {
        require(purchasedOn == null || openedOn == null || openedOn.value >= purchasedOn.value) {
            "Opened date cannot be before purchase date"
        }
        val latestKnownStart = openedOn ?: purchasedOn
        require(
            frozenOn == null || latestKnownStart == null || frozenOn.value >= latestKnownStart.value
        ) {
            "Frozen date cannot be before purchase or opening"
        }
    }

    /** True while a freeze date is recorded for this lot. */
    val isFrozen: Boolean
        get() = frozenOn != null
}

/** Which event anchors an advisory recommended-use date. */
enum class FreshnessBasis {
    PURCHASE,
    OPENING,
    FREEZING,
}

/** Calculated freshness guidance. It never changes manufacturer or user dates. */
data class FreshnessRecommendation(
    val recommendedUseBy: DomainLocalDate,
    val basis: FreshnessBasis,
)

/** Source selected for the effective date shown to the user. */
enum class UseBySource {
    USER_OVERRIDE,
    MANUFACTURER,
    RECOMMENDATION,
}

/** Separate date evidence plus the selected date for display and reminders. */
data class UseByGuidance(
    val manufacturerUseBy: DomainLocalDate?,
    val userOverride: DomainLocalDate?,
    val recommendation: FreshnessRecommendation?,
) {
    val effectiveUseBy: DomainLocalDate?
        get() = userOverride ?: manufacturerUseBy ?: recommendation?.recommendedUseBy

    val effectiveSource: UseBySource?
        get() = when {
            userOverride != null -> UseBySource.USER_OVERRIDE
            manufacturerUseBy != null -> UseBySource.MANUFACTURER
            recommendation != null -> UseBySource.RECOMMENDATION
            else -> null
        }
}

/**
 * Optional perishable-food windows, expressed as days after the relevant event.
 *
 * A missing window means no recommendation for that basis. For a frozen lot,
 * only [daysAfterFreezing] applies; the unfrozen window is paused rather than
 * silently treated as a food-safety deadline.
 */
data class FreshnessPolicy(
    val daysAfterPurchase: Int? = null,
    val daysAfterOpening: Int? = null,
    val daysAfterFreezing: Int? = null,
) {
    init {
        require(daysAfterPurchase == null || daysAfterPurchase >= 0) {
            "Purchase freshness window cannot be negative"
        }
        require(daysAfterOpening == null || daysAfterOpening >= 0) {
            "Opening freshness window cannot be negative"
        }
        require(daysAfterFreezing == null || daysAfterFreezing >= 0) {
            "Freezing freshness window cannot be negative"
        }
    }

    /** Returns an advisory date for [lot], or null when dates/policy are insufficient. */
    fun recommendFor(lot: InventoryLot): FreshnessRecommendation? {
        if (lot.frozenOn != null) {
            val window = daysAfterFreezing ?: return null
            return FreshnessRecommendation(
                recommendedUseBy = lot.frozenOn.plusDays(window),
                basis = FreshnessBasis.FREEZING,
            )
        }

        val openedOn = lot.openedOn
        if (openedOn != null && daysAfterOpening != null) {
            return FreshnessRecommendation(
                recommendedUseBy = openedOn.plusDays(daysAfterOpening),
                basis = FreshnessBasis.OPENING,
            )
        }

        val purchasedOn = lot.purchasedOn
        if (purchasedOn != null && daysAfterPurchase != null) {
            return FreshnessRecommendation(
                recommendedUseBy = purchasedOn.plusDays(daysAfterPurchase),
                basis = FreshnessBasis.PURCHASE,
            )
        }

        return null
    }

    /** Keeps source dates separate and applies user, manufacturer, then advisory precedence. */
    fun guidanceFor(lot: InventoryLot): UseByGuidance = UseByGuidance(
        manufacturerUseBy = lot.manufacturerUseBy,
        userOverride = lot.userUseBy,
        recommendation = recommendFor(lot),
    )
}

/**
 * Immutable kitchen state with explicit inventory operations.
 *
 * Lots may share an ingredient or location: separate lots preserve different
 * purchase, opening, freezing, and user date histories.
 */
data class KitchenInventory(
    val locations: List<KitchenLocation> = emptyList(),
    val lots: List<InventoryLot> = emptyList(),
) {
    init {
        require(locations.map(KitchenLocation::id).distinct().size == locations.size) {
            "Kitchen location IDs must be unique"
        }
        require(lots.map(InventoryLot::id).distinct().size == lots.size) {
            "Inventory lot IDs must be unique"
        }
        val locationIds = locations.mapTo(mutableSetOf(), KitchenLocation::id)
        require(lots.all { it.locationId in locationIds }) {
            "Every inventory lot must refer to a configured location"
        }
    }

    /** Adds a configurable storage location. */
    fun addLocation(location: KitchenLocation): KitchenInventory {
        require(locations.none { it.id == location.id }) { "Kitchen location already exists" }
        return copy(locations = locations + location)
    }

    /** Adds a lot at one of this inventory's configured locations. */
    fun addLot(lot: InventoryLot): KitchenInventory {
        require(locations.any { it.id == lot.locationId }) {
            "Inventory lot location is not configured"
        }
        require(lots.none { it.id == lot.id }) { "Inventory lot already exists" }
        return copy(lots = lots + lot)
    }

    /** Replaces a lot's tracked amount after an explicit user adjustment. */
    fun adjustAmount(
        lotId: InventoryLotId,
        amount: InventoryAmount,
    ): KitchenInventory = updateLot(lotId) { it.copy(amount = amount) }

    /** Moves a lot to another configured location without changing its dates or amount. */
    fun moveLot(
        lotId: InventoryLotId,
        destination: KitchenLocationId,
    ): KitchenInventory {
        require(locations.any { it.id == destination }) { "Destination location is not configured" }
        return updateLot(lotId) { it.copy(locationId = destination) }
    }

    /** Records the date this lot was frozen. */
    fun freezeLot(
        lotId: InventoryLotId,
        frozenOn: DomainLocalDate,
    ): KitchenInventory = updateLot(lotId) { it.copy(frozenOn = frozenOn) }

    /** Clears the recorded frozen state, for example after thawing. */
    fun thawLot(lotId: InventoryLotId): KitchenInventory =
        updateLot(lotId) { it.copy(frozenOn = null) }

    /** Removes a discarded lot from inventory. */
    fun discardLot(lotId: InventoryLotId): KitchenInventory {
        require(lots.any { it.id == lotId }) { "Inventory lot does not exist" }
        return copy(lots = lots.filterNot { it.id == lotId })
    }

    private inline fun updateLot(
        lotId: InventoryLotId,
        transform: (InventoryLot) -> InventoryLot,
    ): KitchenInventory {
        require(lots.any { it.id == lotId }) { "Inventory lot does not exist" }
        return copy(lots = lots.map { if (it.id == lotId) transform(it) else it })
    }
}

/** Computes a civil date relative to this one, without introducing a time zone. */
private fun DomainLocalDate.plusDays(days: Int): DomainLocalDate =
    DomainLocalDate(value + DatePeriod(days = days))
