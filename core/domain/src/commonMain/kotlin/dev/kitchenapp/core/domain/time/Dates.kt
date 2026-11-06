package dev.kitchenapp.core.domain.time

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * Domain wrapper over [kotlin.time.Instant] (a moment on the UTC timeline).
 *
 * Kept thin on purpose: the domain layer should not duplicate the standard
 * time API, only give timestamps a named home inside the domain.
 */
@JvmInline
value class DomainInstant(val value: Instant) {
    override fun toString(): String = value.toString()
}

/**
 * Domain wrapper over [kotlinx.datetime.LocalDate] (a civil date without a
 * time or zone).
 *
 * All "current date" answers are derived from an injected [Clock] so callers
 * (and tests) control what "now" means.
 */
@JvmInline
value class DomainLocalDate(val value: LocalDate) {

    /** The date one day before this one. */
    fun previousDay(): DomainLocalDate = DomainLocalDate(value - DatePeriod(days = 1))

    /** The date one day after this one. */
    fun nextDay(): DomainLocalDate = plusDays(1)

    /** Adds a non-negative number of civil days. */
    fun plusDays(days: Int): DomainLocalDate {
        require(days >= 0) { "Days cannot be negative" }
        return DomainLocalDate(value + DatePeriod(days = days))
    }

    /** Compares civil dates. */
    fun isBefore(other: DomainLocalDate): Boolean = value < other.value

    fun isOnOrBefore(other: DomainLocalDate): Boolean = value <= other.value

    override fun toString(): String = value.toString()

    companion object {
        /** Creates a civil date. */
        fun of(year: Int, monthNumber: Int, dayOfMonth: Int): DomainLocalDate =
            DomainLocalDate(LocalDate(year, monthNumber, dayOfMonth))

        /** Today's date in [timeZone], as reported by [clock]. */
        fun today(timeZone: TimeZone, clock: Clock = Clock.System): DomainLocalDate =
            DomainLocalDate(clock.todayIn(timeZone))
    }
}
