package dev.kitchenapp.core.domain.enums

/**
 * Approximation level for a quantity measurement.
 *
 * Indicates how precise a quantity is expected to be.
 */
enum class ApproximateLevel(val value: Int) {
    /** No approximation; the value is exact. */
    EXACT(0),

    /** Wide tolerance (e.g. "some flour"). */
    ROUGH(1),

    /** Moderate tolerance (e.g. "about 2 cups"). */
    MODERATE(2),

    /** Narrow tolerance (e.g. "precisely 250 g"). */
    PRECISE(3);

    companion object {
        fun fromValue(value: Int): ApproximateLevel? = entries.find { it.value == value }
    }
}
