package dev.kitchenapp.core.domain.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitsTest {

    @Test
    fun `UnitDimension fromId round-trips and rejects unknown`() {
        for (dimension in UnitDimension.entries) {
            assertEquals(dimension, UnitDimension.fromId(dimension.id))
        }
        assertNull(UnitDimension.fromId("invalid"))
        assertNull(UnitDimension.fromId(""))
    }

    @Test
    fun `UnitDimension ids are unique`() {
        assertEquals(UnitDimension.entries.size, UnitDimension.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `UnitCode fromCode round-trips and rejects unknown`() {
        for (unit in UnitCode.entries) {
            assertEquals(unit, UnitCode.fromCode(unit.code))
        }
        assertNull(UnitCode.fromCode("invalid"))
    }

    @Test
    fun `UnitCode codes and symbols are unique`() {
        assertEquals(UnitCode.entries.size, UnitCode.entries.map { it.code }.toSet().size)
        assertEquals(UnitCode.entries.size, UnitCode.entries.map { it.symbol }.toSet().size)
    }

    @Test
    fun `UnitCode fromSymbol resolves display symbols`() {
        assertEquals(UnitCode.GRAM, UnitCode.fromSymbol("g"))
        assertEquals(UnitCode.CELSIUS, UnitCode.fromSymbol("°C"))
        assertEquals(UnitCode.TABLESPOON, UnitCode.fromSymbol("tbsp"))
    }

    @Test
    fun `every unit code maps to a real dimension`() {
        for (unit in UnitCode.entries) {
            assertTrue(
                UnitDimension.entries.contains(unit.dimension),
                "${unit.name} has an out-of-range dimension",
            )
        }
    }

    @Test
    fun `valuesForDimension filters correctly`() {
        val mass = UnitCode.valuesForDimension(UnitDimension.MASS)
        assertTrue(mass.contains(UnitCode.GRAM))
        assertTrue(mass.contains(UnitCode.POUND))
        assertFalse(mass.contains(UnitCode.CUP))
        assertTrue(UnitCode.valuesForDimension(UnitDimension.UNKNOWN).contains(UnitCode.UNKNOWN))
    }
}
