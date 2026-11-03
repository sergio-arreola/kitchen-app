package dev.kitchenapp.core.domain.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RationalTest {

    // Table: (numerator, denominator, expectedNumerator, expectedDenominator)
    private val normalizationTable = listOf(
        3L to 6L to (1L to 2L),
        4L to 8L to (1L to 2L),
        2L to 4L to (1L to 2L),
        -3L to 6L to (-1L to 2L),   // sign on numerator
        3L to -6L to (-1L to 2L),   // negative denominator flips to numerator
        -3L to -6L to (1L to 2L),   // double negative
        0L to 5L to (0L to 1L),     // zero canonicalizes to 0/1
        0L to -5L to (0L to 1L),
        7L to 1L to (7L to 1L),     // already whole
        13L to 17L to (13L to 17L), // already coprime
    )

    @Test
    fun `of normalizes to canonical form`() {
        for ((input, expected) in normalizationTable) {
            val (n, d) = input
            val (en, ed) = expected
            val r = Rational.of(n, d)
            assertEquals(en, r.numerator, "numerator of $n/$d")
            assertEquals(ed, r.denominator, "denominator of $n/$d")
        }
    }

    @Test
    fun `of rejects zero denominator`() {
        assertFailsWith<IllegalArgumentException> { Rational.of(1, 0) }
        assertFailsWith<IllegalArgumentException> { Rational.of(0, 0) }
    }

    @Test
    fun `of whole number has denominator 1`() {
        assertEquals(5L to 1L, Rational.of(5L).let { it.numerator to it.denominator })
    }

    // Table: (a, b, a + b)
    private val plusTable = listOf(
        Triple(Rational.of(1, 2), Rational.of(1, 3), Rational.of(5, 6)),
        Triple(Rational.of(1, 2), Rational.of(-1, 2), Rational.of(0, 1)),
        Triple(Rational.of(1, 4), Rational.of(1, 4), Rational.of(1, 2)),
        Triple(Rational.of(-1, 3), Rational.of(-1, 6), Rational.of(-1, 2)),
        Triple(Rational.of(2, 1), Rational.of(3, 1), Rational.of(5, 1)),
        Triple(Rational.of(1, 6), Rational.of(1, 6), Rational.of(1, 3)),
    )

    @Test
    fun `plus table`() {
        for ((a, b, expected) in plusTable) {
            assertEquals(expected, a.plus(b), "$a + $b")
        }
    }

    // Table: (a, b, a - b)
    private val minusTable = listOf(
        Triple(Rational.of(1, 2), Rational.of(1, 3), Rational.of(1, 6)),
        Triple(Rational.of(1, 2), Rational.of(1, 2), Rational.of(0, 1)),
        Triple(Rational.of(1, 3), Rational.of(1, 2), Rational.of(-1, 6)),
        Triple(Rational.of(-1, 3), Rational.of(1, 6), Rational.of(-1, 2)),
        Triple(Rational.of(2, 1), Rational.of(5, 1), Rational.of(-3, 1)),
    )

    @Test
    fun `minus table`() {
        for ((a, b, expected) in minusTable) {
            assertEquals(expected, a.minus(b), "$a - $b")
        }
    }

    // Table: (a, b, a * b)
    private val timesTable = listOf(
        Triple(Rational.of(1, 2), Rational.of(1, 3), Rational.of(1, 6)),
        Triple(Rational.of(2, 3), Rational.of(3, 2), Rational.of(1, 1)),
        Triple(Rational.of(-1, 2), Rational.of(1, 2), Rational.of(-1, 4)),
        Triple(Rational.of(-1, 2), Rational.of(-1, 2), Rational.of(1, 4)),
        Triple(Rational.of(0, 1), Rational.of(5, 7), Rational.of(0, 1)),
    )

    @Test
    fun `times table`() {
        for ((a, b, expected) in timesTable) {
            assertEquals(expected, a.times(b), "$a * $b")
        }
    }

    // Table: (a, b, a / b)
    private val divTable = listOf(
        Triple(Rational.of(1, 2), Rational.of(1, 3), Rational.of(3, 2)),
        Triple(Rational.of(2, 3), Rational.of(2, 1), Rational.of(1, 3)),
        Triple(Rational.of(-1, 2), Rational.of(-1, 2), Rational.of(1, 1)),
        Triple(Rational.of(1, 2), Rational.of(-1, 4), Rational.of(-2, 1)),
    )

    @Test
    fun `div table`() {
        for ((a, b, expected) in divTable) {
            assertEquals(expected, a.div(b), "$a / $b")
        }
    }

    @Test
    fun `div by zero rational throws`() {
        assertFailsWith<IllegalArgumentException> { Rational.of(1, 2).div(Rational.of(0, 5)) }
    }

    // Table: (a, b, sign of a compareTo b)
    private val compareTable = listOf(
        Triple(Rational.of(1, 2), Rational.of(1, 3), 1),
        Triple(Rational.of(1, 3), Rational.of(1, 2), -1),
        Triple(Rational.of(2, 4), Rational.of(1, 2), 0),
        Triple(Rational.of(-1, 2), Rational.of(1, 3), -1),
        Triple(Rational.of(-1, 2), Rational.of(-1, 3), -1),
        Triple(Rational.of(1, 2), Rational.of(-1, 3), 1),
        Triple(Rational.of(0, 1), Rational.of(0, 7), 0),
    )

    @Test
    fun `compareTo table`() {
        for ((a, b, expected) in compareTable) {
            assertEquals(expected, a.compareTo(b), "$a vs $b")
        }
    }

    @Test
    fun `compareTo is consistent with equals across values`() {
        assertTrue(Rational.of(2, 4) == Rational.of(1, 2))
        assertTrue(Rational.of(1, 2) < Rational.of(2, 3))
        assertTrue(Rational.of(-1, 2) < Rational.of(0, 1))
    }

    @Test
    fun `isZero is true only for zero`() {
        assertTrue(Rational.of(0, 1).isZero())
        assertTrue(Rational.of(0, -7).isZero())
        assertFalse(Rational.of(1, 2).isZero())
        assertFalse(Rational.of(-1, 2).isZero())
    }

    @Test
    fun `isPositive is true only for strictly positive values`() {
        assertTrue(Rational.of(3, 4).isPositive())
        assertFalse(Rational.of(-3, 4).isPositive())
        assertFalse(Rational.of(0, 4).isPositive())
    }

    @Test
    fun `isNegative is true only for strictly negative values`() {
        assertTrue(Rational.of(-3, 4).isNegative())
        assertFalse(Rational.of(3, 4).isNegative())
        assertFalse(Rational.of(0, 4).isNegative())
    }

    @Test
    fun `toDouble matches the fraction`() {
        assertEquals(0.5, Rational.of(1, 2).toDouble())
        assertEquals(-0.75, Rational.of(-3, 4).toDouble())
        assertEquals(0.0, Rational.of(0, 1).toDouble())
    }

    @Test
    fun `toString is num-slash-den with sign on numerator`() {
        assertEquals("3/4", Rational.of(3, 4).toString())
        assertEquals("-3/4", Rational.of(-3, 4).toString())
        assertEquals("0/1", Rational.of(0, 5).toString())
    }

    @Test
    fun `arithmetic stays normalized after chained operations`() {
        // (3/4 - 1/4) * (2/5) = (1/2) * (2/5) = 1/5
        val result = Rational.of(3, 4)
            .minus(Rational.of(1, 4))
            .times(Rational.of(2, 5))
        assertEquals(1L, result.numerator)
        assertEquals(5L, result.denominator)
    }

    @Test
    fun `operations with same denominators do not overflow for realistic sizes`() {
        val a = Rational.of(1_000_000L, 999_983L)
        val b = Rational.of(999_979L, 999_979L) // normalizes to 1
        assertEquals(1_999_983L to 999_983L, a.plus(b).let { it.numerator to it.denominator })
    }
}
