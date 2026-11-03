package dev.kitchenapp.core.domain.units

/**
 * Base unit dimensions for quantities.
 *
 * These represent the fundamental measurable kinds in the domain.
 */
enum class UnitDimension(val id: String) {
    /** Mass (grams, kilograms, pounds, ...). */
    MASS("mass"),

    /** Volume (milliliters, cups, tablespoons, ...). */
    VOLUME("volume"),

    /** Duration (minutes, hours, seconds, ...). */
    TIME("time"),

    /** Temperature (Celsius, Fahrenheit, Kelvin, ...). */
    TEMPERATURE("temperature"),

    /** Countable items (eggs, cloves, servings, ...). */
    COUNT("count"),

    /** Energy (calories, kilocalories, ...). */
    ENERGY("energy"),

    /** Length (meters, inches, feet, ...). */
    LENGTH("length"),

    /** Area (square meters, square inches, ...). */
    AREA("area"),

    /** Unknown or custom dimension. */
    UNKNOWN("unknown");

    companion object {
        fun fromId(id: String): UnitDimension? = entries.find { it.id == id }
    }
}
