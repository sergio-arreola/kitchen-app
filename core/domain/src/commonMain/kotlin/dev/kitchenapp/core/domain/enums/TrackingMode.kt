package dev.kitchenapp.core.domain.enums

/**
 * Tracking mode for recipe and inventory quantity tracking.
 *
 * Determines how strictly the system treats quantities.
 */
enum class TrackingMode(val value: Int) {
    /** Quantities must be tracked precisely. */
    EXACT(0),

    /** Quantities are acceptable as approximations. */
    APPROXIMATE(1),

    /** Either exact or approximate quantities are accepted. */
    FLEXIBLE(2);

    companion object {
        fun fromValue(value: Int): TrackingMode? = entries.find { it.value == value }
    }
}
