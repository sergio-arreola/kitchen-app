package dev.kitchenapp.sharedui

import dev.kitchenapp.core.domain.math.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CookingQuantityTest {
    @Test
    fun acceptsPositiveWholeDecimalAndFractionalAmounts() {
        assertEquals(Rational.of(2), parseCookingQuantity(" 2 "))
        assertEquals(Rational.of(3, 2), parseCookingQuantity("1.5"))
        assertEquals(Rational.of(1, 2), parseCookingQuantity("1/2"))
    }

    @Test
    fun rejectsAmountsThatCannotBeConsumed() {
        listOf("", "0", "-1", "1/0", "1/2/3", "1.2.3", "0.0000001").forEach {
            assertNull(parseCookingQuantity(it), it)
        }
    }
}
