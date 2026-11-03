package dev.kitchenapp.core.domain.time

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class DatesTest {

    private val fixedInstant: Instant = Instant.parse("2024-06-15T12:00:00Z")

    /** Clock frozen at the fixture instant. */
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedInstant
    }

    private val utc = TimeZone.UTC

    @Test
    fun `DomainInstant wraps the underlying instant`() {
        val domain = DomainInstant(fixedInstant)
        assertEquals(fixedInstant, domain.value)
        assertEquals("2024-06-15T12:00:00Z", domain.toString())
    }

    @Test
    fun `today uses the injected clock`() {
        assertEquals(LocalDate(2024, 6, 15), DomainLocalDate.today(utc, fixedClock).value)
    }

    @Test
    fun `today respects the time zone`() {
        // 2024-06-15T12:00:00Z is 2024-06-16 in UTC+13.
        val plusThirteen = TimeZone.of("Etc/GMT-13") // POSIX sign: UTC+13
        assertEquals(LocalDate(2024, 6, 16), DomainLocalDate.today(plusThirteen, fixedClock).value)
    }

    @Test
    fun `previousDay and nextDay cross month boundaries correctly`() {
        val firstOfMonth = DomainLocalDate(LocalDate(2024, 6, 1))
        assertEquals(LocalDate(2024, 5, 31), firstOfMonth.previousDay().value)

        val endOfJune = DomainLocalDate(LocalDate(2024, 6, 30))
        assertEquals(LocalDate(2024, 7, 1), endOfJune.nextDay().value)

        val leapFebEnd = DomainLocalDate(LocalDate(2024, 2, 29))
        assertEquals(LocalDate(2024, 3, 1), leapFebEnd.nextDay().value)
    }

    @Test
    fun `today with system clock returns a plausible current date`() {
        val today = DomainLocalDate.today(utc)
        assertTrue(today.value.year >= 2024, "unexpected year ${today.value.year}")
    }
}
