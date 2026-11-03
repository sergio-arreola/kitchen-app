package dev.kitchenapp.data.importers.ingredient

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode

/**
 * Heuristic parser for raw recipe ingredient lines; one [ParsedIngredient]
 * per line. Never throws; unrecognized content stays in the name.
 */
object IngredientLineParser {

    /** Parses one raw ingredient line. */
    fun parse(line: String): ParsedIngredient {
        var importance = IngredientImportance.CRITICAL
        var working = line.trim()
        if (OPTIONAL_REGEX.containsMatchIn(working)) {
            importance = IngredientImportance.OPTIONAL
            working = OPTIONAL_REGEX.replace(working, " ")
        }
        if (working.isBlank()) {
            return ParsedIngredient(name = line.trim(), importance = importance)
        }

        var tokens = working.split(' ').filter { it.isNotEmpty() }

        var approximate = false
        if (tokens.isNotEmpty()) {
            val first = tokens.first()
            when {
                first == "~" -> {
                    approximate = true
                    tokens = tokens.drop(1)
                }
                first.startsWith("~") -> {
                    approximate = true
                    tokens = listOf(first.drop(1)) + tokens.drop(1)
                }
                first.lowercase() in APPROX_WORDS -> {
                    approximate = true
                    tokens = tokens.drop(1)
                }
            }
        }

        var index = 0
        var quantity: Rational? = null
        var quantityHigh: Rational? = null

        if (tokens.isNotEmpty()) {
            parseAmount(tokens[index])?.let { amount ->
                quantity = amount.value
                quantityHigh = amount.high
                index++

                // Mixed number: integer token followed by a pure fraction token ("1 1/2").
                if (amount.high == null && amount.value.denominator == 1L &&
                    index < tokens.size && isFractionForm(tokens[index])
                ) {
                    parseAmount(tokens[index])?.let { fraction ->
                        quantity = quantity?.plus(fraction.value)
                        index++
                    }
                }

                // Range: "2 - 3" or "1 to 2" cups.
                if (amount.high == null && index + 1 < tokens.size) {
                    val separator = tokens[index]
                    if (separator == "-" || separator == "–" || separator == "to") {
                        parseAmount(tokens[index + 1])?.let { upper ->
                            if (upper.high == null) {
                                quantityHigh = upper.value
                                index += 2
                            }
                        }
                    }
                }
            }
        }

        var unit: UnitCode? = null
        if (index < tokens.size) {
            UNIT_VARIANTS[tokens[index].trimEnd(',', ';', '.', ':').lowercase()]?.let {
                unit = it
                index++
            }
        }

        val remainder = tokens.drop(index).joinToString(" ")
        val comma = remainder.indexOf(',')
        val namePart: String
        val preparation: String?
        if (comma >= 0) {
            namePart = remainder.substring(0, comma).trim()
            preparation = remainder.substring(comma + 1).trim().ifEmpty { null }
        } else {
            namePart = remainder
            preparation = null
        }
        val name = namePart.ifBlank { unit?.displayName ?: working }

        val level = when {
            approximate -> ApproximateLevel.MODERATE
            quantity == null -> ApproximateLevel.ROUGH
            else -> ApproximateLevel.EXACT
        }

        return ParsedIngredient(
            name = name,
            quantity = quantity,
            quantityHigh = quantityHigh,
            unit = unit,
            preparation = preparation,
            importance = importance,
            approximateLevel = level,
        )
    }

    private data class Amount(val value: Rational, val high: Rational? = null)

    private fun isFractionForm(token: String): Boolean =
        token.contains('/') || token.any { FRACTIONS.containsKey(it) }

    /** Parses a numeric token; returns null when the token is not an amount. */
    private fun parseAmount(raw: String): Amount? {
        val t = raw.trimEnd(',', ';')
        if (t.isEmpty()) return null

        val fractionChar = t.firstOrNull { FRACTIONS.containsKey(it) }
        if (fractionChar != null) {
            val before = t.substringBefore(fractionChar)
            val after = t.substringAfter(fractionChar)
            if (after.isNotEmpty()) return null
            val whole = before.toLongOrNull()
            if (before.isNotEmpty() && whole == null) return null
            val fraction = FRACTIONS.getValue(fractionChar)
            return Amount(Rational.of(whole ?: 0L).plus(Rational.of(fraction.first, fraction.second)))
        }

        val slash = t.indexOf('/')
        if (slash > 0) {
            val numerator = t.substring(0, slash).toLongOrNull() ?: return null
            val denominator = t.substring(slash + 1).toLongOrNull() ?: return null
            if (numerator < 0L || denominator == 0L) return null
            return Amount(Rational.of(numerator, denominator))
        }

        RANGE_REGEX.matchEntire(t)?.let { match ->
            val low = decimalToRational(match.groupValues[1]) ?: return null
            val high = decimalToRational(match.groupValues[2]) ?: return null
            return Amount(low, high)
        }

        val decimal = decimalToRational(t) ?: return null
        if (decimal.isNegative()) return null
        return Amount(decimal)
    }

    private fun decimalToRational(t: String): Rational? {
        val dot = t.indexOf('.')
        if (dot < 0) {
            val whole = t.toLongOrNull() ?: return null
            return Rational.of(whole)
        }
        val intPart = t.substring(0, dot)
        val fracPart = t.substring(dot + 1)
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        if (!intPart.all { it.isDigit() } || !fracPart.all { it.isDigit() }) return null
        if (fracPart.isEmpty()) return Rational.of(intPart.toLong())
        val numerator = intPart.ifEmpty { "0" }.plus(fracPart).toLongOrNull() ?: return null
        var denominator = 1L
        repeat(fracPart.length) { denominator *= 10L }
        return Rational.of(numerator, denominator)
    }

    private val RANGE_REGEX = Regex("""^(\d+(?:\.\d+)?)[-–](\d+(?:\.\d+)?)$""")
    private val OPTIONAL_REGEX = Regex("""\(\s*optional\s*\)""", RegexOption.IGNORE_CASE)
    private val APPROX_WORDS = setOf("about", "approx", "approx.", "approximately")

    /**
     * Conservative variant table; ambiguous single letters ("c", "t", "in")
     * are excluded so they cannot swallow ingredient words.
     */
    private val UNIT_VARIANTS: Map<String, UnitCode> = buildMap {
        fun add(vararg keys: String, code: UnitCode) = keys.forEach { put(it, code) }
        add("tbsp", "tbs", "tablespoon", "tablespoons", code = UnitCode.TABLESPOON)
        add("tsp", "teaspoon", "teaspoons", code = UnitCode.TEASPOON)
        add("cup", "cups", code = UnitCode.CUP)
        add("g", "gram", "grams", code = UnitCode.GRAM)
        add("kg", "kilogram", "kilograms", code = UnitCode.KILOGRAM)
        add("oz", "ounce", "ounces", code = UnitCode.OUNCE)
        add("lb", "lbs", "pound", "pounds", code = UnitCode.POUND)
        add("ml", "milliliter", "milliliters", code = UnitCode.MILLILITER)
        add("pc", "piece", "pieces", code = UnitCode.PIECE)
        add("egg", "eggs", code = UnitCode.EGG)
        add("clove", "cloves", code = UnitCode.CLOVE)
        add("serving", "servings", code = UnitCode.SERVING)
        add("sec", "second", "seconds", code = UnitCode.SECOND)
        add("min", "minute", "minutes", code = UnitCode.MINUTE)
        add("hr", "hour", "hours", code = UnitCode.HOUR)
        add("cal", "calorie", "calories", code = UnitCode.CALORIE)
        add("kcal", "kilocalorie", "kilocalories", code = UnitCode.KILOCALORIE)
        add("cm", "centimeter", "centimeters", code = UnitCode.CENTIMETER)
    }

    private val FRACTIONS: Map<Char, Pair<Long, Long>> = mapOf(
        '½' to Pair(1L, 2L),
        '⅓' to Pair(1L, 3L),
        '⅔' to Pair(2L, 3L),
        '¼' to Pair(1L, 4L),
        '¾' to Pair(3L, 4L),
        '⅕' to Pair(1L, 5L),
        '⅖' to Pair(2L, 5L),
        '⅗' to Pair(3L, 5L),
        '⅘' to Pair(4L, 5L),
        '⅙' to Pair(1L, 6L),
        '⅚' to Pair(5L, 6L),
        '⅛' to Pair(1L, 8L),
        '⅜' to Pair(3L, 8L),
        '⅝' to Pair(5L, 8L),
        '⅞' to Pair(7L, 8L),
        '⅐' to Pair(1L, 7L),
        '⅑' to Pair(1L, 9L),
        '⅒' to Pair(1L, 10L),
    )
}