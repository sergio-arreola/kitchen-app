package dev.kitchenapp.core.domain.math

import kotlin.math.abs

/** Greatest common divisor of two non-negative-adjusted values. */
private fun gcd(a: Long, b: Long): Long {
    var x = abs(a)
    var y = abs(b)
    while (y != 0L) {
        val r = x % y
        x = y
        y = r
    }
    return x
}

/**
 * Immutable rational number (fraction) with exact arithmetic.
 *
 * Always kept in canonical form: denominator strictly positive, sign carried
 * by the numerator, and numerator/denominator coprime. Canonical form makes
 * structural equality equivalent to numeric equality.
 *
 * Arithmetic results are normalized, but intermediate values are plain
 * `Long` math; values whose intermediate products exceed `Long` range will
 * overflow. Recipe quantities never approach that range in practice.
 */
@ConsistentCopyVisibility
data class Rational private constructor(val numerator: Long, val denominator: Long) :
    Comparable<Rational> {

    /** Sum: `this + other`. */
    operator fun plus(other: Rational): Rational {
        val g = gcd(denominator, other.denominator)
        val thisFactor = other.denominator / g
        val otherFactor = denominator / g
        return of(
            numerator * thisFactor + other.numerator * otherFactor,
            denominator * thisFactor,
        )
    }

    /** Difference: `this - other`. */
    operator fun minus(other: Rational): Rational = plus(other.negate())

    /** Product: `this * other`. */
    operator fun times(other: Rational): Rational =
        of(numerator * other.numerator, denominator * other.denominator)

    /** Quotient: `this / other`. */
    operator fun div(other: Rational): Rational {
        require(other.numerator != 0L) { "Cannot divide by zero" }
        return times(Rational(other.denominator, other.numerator))
    }

    /** Negation. Zero negates to zero. */
    fun negate(): Rational = Rational(-numerator, denominator)

    /** `-1`, `0`, or `1` depending on ordering against [other]. */
    override fun compareTo(other: Rational): Int {
        val left = numerator * other.denominator
        val right = other.numerator * denominator
        return left.compareTo(right)
    }

    fun isZero(): Boolean = numerator == 0L

    fun isPositive(): Boolean = numerator > 0L

    fun isNegative(): Boolean = numerator < 0L

    /** Exact double representation (maybe inexact for repeating fractions). */
    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        /**
         * Creates a normalized rational: gcd-reduced, denominator positive,
         * sign on the numerator.
         *
         * @throws IllegalArgumentException if [denominator] is zero.
         */
        fun of(numerator: Long, denominator: Long): Rational {
            require(denominator != 0L) { "Denominator cannot be zero" }
            val sign = if (denominator < 0L) -1L else 1L
            val common = gcd(numerator, denominator)
            val den = abs(denominator) / common
            val num = sign * numerator / common
            return Rational(num, den)
        }

        /** Whole number with denominator 1. */
        fun of(value: Long): Rational = Rational(value, 1L)
    }
}
