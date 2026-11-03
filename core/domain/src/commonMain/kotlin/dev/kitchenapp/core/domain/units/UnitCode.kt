package dev.kitchenapp.core.domain.units

/**
 * Unit codes used throughout the domain.
 *
 * Each code belongs to exactly one [UnitDimension].
 */
enum class UnitCode(
    val code: String,
    val dimension: UnitDimension,
    val symbol: String,
    val displayName: String,
) {
    // Mass
    GRAM("g", UnitDimension.MASS, "g", "gram"),
    KILOGRAM("kg", UnitDimension.MASS, "kg", "kilogram"),
    OUNCE("oz", UnitDimension.MASS, "oz", "ounce"),
    POUND("lb", UnitDimension.MASS, "lb", "pound"),

    // Volume
    MILLILITER("ml", UnitDimension.VOLUME, "ml", "milliliter"),
    CUP("cup", UnitDimension.VOLUME, "cup", "cup"),
    TABLESPOON("tbsp", UnitDimension.VOLUME, "tbsp", "tablespoon"),
    TEASPOON("tsp", UnitDimension.VOLUME, "tsp", "teaspoon"),

    // Time
    SECOND("sec", UnitDimension.TIME, "sec", "second"),
    MINUTE("min", UnitDimension.TIME, "min", "minute"),
    HOUR("hr", UnitDimension.TIME, "hr", "hour"),

    // Temperature
    CELSIUS("c", UnitDimension.TEMPERATURE, "°C", "celsius"),
    FAHRENHEIT("f", UnitDimension.TEMPERATURE, "°F", "fahrenheit"),
    KELVIN("k", UnitDimension.TEMPERATURE, "K", "kelvin"),

    // Count
    PIECE("pc", UnitDimension.COUNT, "pc", "piece"),
    EGG("egg", UnitDimension.COUNT, "egg", "egg"),
    CLOVE("clove", UnitDimension.COUNT, "clove", "clove"),
    SERVING("serving", UnitDimension.COUNT, "serving", "serving"),

    // Energy
    CALORIE("cal", UnitDimension.ENERGY, "cal", "calorie"),
    KILOCALORIE("kcal", UnitDimension.ENERGY, "kcal", "kilocalorie"),

    // Length
    METER("m", UnitDimension.LENGTH, "m", "meter"),
    CENTIMETER("cm", UnitDimension.LENGTH, "cm", "centimeter"),
    INCH("in", UnitDimension.LENGTH, "in", "inch"),
    FOOT("ft", UnitDimension.LENGTH, "ft", "foot"),

    // Area
    SQUARE_METER("sqm", UnitDimension.AREA, "m²", "square meter"),
    SQUARE_INCH("sqin", UnitDimension.AREA, "in²", "square inch"),

    // Unknown / custom
    UNKNOWN("unknown", UnitDimension.UNKNOWN, "?", "unknown");

    companion object {
        fun fromCode(code: String): UnitCode? = entries.find { it.code == code }
        fun fromSymbol(symbol: String): UnitCode? = entries.find { it.symbol == symbol }
        fun valuesForDimension(dimension: UnitDimension): List<UnitCode> =
            entries.filter { it.dimension == dimension }
    }
}
