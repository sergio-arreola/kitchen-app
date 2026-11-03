package dev.kitchenapp.data.importers.ingredient

import dev.kitchenapp.core.domain.enums.ApproximateLevel
import dev.kitchenapp.core.domain.enums.IngredientImportance
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngredientLineParserTest {

    private fun parse(line: String): ParsedIngredient = IngredientLineParser.parse(line)

    @Test
    fun integerWithCountUnit() {
        val parsed = parse("2 eggs")
        assertEquals(Rational.of(2), parsed.quantity)
        assertEquals(UnitCode.EGG, parsed.unit)
        assertEquals("egg", parsed.name)
        assertEquals(ApproximateLevel.EXACT, parsed.approximateLevel)
    }

    @Test
    fun asciiFraction() {
        val parsed = parse("1/2 cup milk")
        assertEquals(Rational.of(1, 2), parsed.quantity)
        assertEquals(UnitCode.CUP, parsed.unit)
        assertEquals("milk", parsed.name)
    }

    @Test
    fun mixedNumber() {
        val parsed = parse("1 1/2 cups flour")
        assertEquals(Rational.of(3, 2), parsed.quantity)
        assertEquals(UnitCode.CUP, parsed.unit)
        assertEquals("flour", parsed.name)
    }

    @Test
    fun attachedUnicodeFraction() {
        val parsed = parse("1½ tsp vanilla")
        assertEquals(Rational.of(3, 2), parsed.quantity)
        assertEquals(UnitCode.TEASPOON, parsed.unit)
        assertEquals("vanilla", parsed.name)
    }

    @Test
    fun standaloneUnicodeFraction() {
        val parsed = parse("½ cup strawberries")
        assertEquals(Rational.of(1, 2), parsed.quantity)
        assertEquals(UnitCode.CUP, parsed.unit)
        assertEquals("strawberries", parsed.name)
    }

    @Test
    fun decimalQuantity() {
        val parsed = parse("1.5 oz chocolate")
        assertEquals(Rational.of(3, 2), parsed.quantity)
        assertEquals(UnitCode.OUNCE, parsed.unit)
        assertEquals("chocolate", parsed.name)
    }

    @Test
    fun numericRange() {
        val parsed = parse("2-3 cloves garlic")
        assertEquals(Rational.of(2), parsed.quantity)
        assertEquals(Rational.of(3), parsed.quantityHigh)
        assertEquals(UnitCode.CLOVE, parsed.unit)
        assertEquals("garlic", parsed.name)
    }

    @Test
    fun rangeWithToSeparator() {
        val parsed = parse("1 to 2 cups rice")
        assertEquals(Rational.of(1), parsed.quantity)
        assertEquals(Rational.of(2), parsed.quantityHigh)
        assertEquals(UnitCode.CUP, parsed.unit)
        assertEquals("rice", parsed.name)
    }

    @Test
    fun approximatePrefix() {
        val parsed = parse("about 2 cups rice")
        assertEquals(Rational.of(2), parsed.quantity)
        assertEquals(UnitCode.CUP, parsed.unit)
        assertEquals(ApproximateLevel.MODERATE, parsed.approximateLevel)
    }

    @Test
    fun tildePrefix() {
        val parsed = parse("~250 g butter")
        assertEquals(Rational.of(250), parsed.quantity)
        assertEquals(UnitCode.GRAM, parsed.unit)
        assertEquals(ApproximateLevel.MODERATE, parsed.approximateLevel)
    }

    @Test
    fun missingQuantityIsRough() {
        val parsed = parse("Pinch of salt")
        assertNull(parsed.quantity)
        assertNull(parsed.unit)
        assertEquals("Pinch of salt", parsed.name)
        assertEquals(ApproximateLevel.ROUGH, parsed.approximateLevel)
    }

    @Test
    fun optionalMarkedAndStripped() {
        val parsed = parse("1 cup cilantro (optional)")
        assertEquals(Rational.of(1), parsed.quantity)
        assertEquals(UnitCode.CUP, parsed.unit)
        assertEquals("cilantro", parsed.name)
        assertEquals(IngredientImportance.OPTIONAL, parsed.importance)
    }

    @Test
    fun preparationClauseCaptured() {
        val parsed = parse("2 tbsp butter, melted")
        assertEquals("butter", parsed.name)
        assertEquals("melted", parsed.preparation)
        assertEquals(UnitCode.TABLESPOON, parsed.unit)
    }

    @Test
    fun unrecognizedUnitStaysInName() {
        val parsed = parse("1 pinch salt")
        assertEquals(Rational.of(1), parsed.quantity)
        assertNull(parsed.unit)
        assertEquals("pinch salt", parsed.name)
    }

    @Test
    fun blankRemainderFallsBackToUnitDisplayName() {
        val parsed = parse("½ cup")
        assertEquals("cup", parsed.name)
        assertEquals(UnitCode.CUP, parsed.unit)
    }

    @Test
    fun unparseableLineKeepsFullText() {
        val parsed = parse("salt to taste")
        assertNull(parsed.quantity)
        assertNull(parsed.unit)
        assertEquals("salt to taste", parsed.name)
        assertEquals(ApproximateLevel.ROUGH, parsed.approximateLevel)
    }

    @Test
    fun ambiguousSingleLetterUnitNotMatched() {
        // "c" is the Celsius code; it must not be treated as "cup" here.
        val parsed = parse("1 c mystery")
        assertNull(parsed.unit)
        assertEquals("c mystery", parsed.name)
    }
}