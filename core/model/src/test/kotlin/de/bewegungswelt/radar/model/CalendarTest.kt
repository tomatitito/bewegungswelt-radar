package de.bewegungswelt.radar.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalendarTest {
    @Test
    fun `booking rules require positive durations and snapshot additions default safely`() {
        val halfHour = Duration.ofMinutes(30)
        for (invalid in listOf(Duration.ZERO, Duration.ofNanos(-1))) {
            assertThrows(IllegalArgumentException::class.java) { BookingRules(invalid, halfHour) }
            assertThrows(IllegalArgumentException::class.java) { BookingRules(halfHour, invalid) }
        }
        assertEquals(Duration.ofNanos(1), BookingRules(Duration.ofNanos(1), halfHour).minimumDuration)
        val date = LocalDate.of(2026, 1, 1)
        val snapshot = CalendarSnapshot(
            CalendarRange(date, date.plusDays(1)), emptyList(), emptyList(), emptyList(), Instant.EPOCH,
        )
        assertEquals(null, snapshot.bookingRules)
        assertEquals(emptyList<TimeWindow>(), snapshot.unavailableWindows)
    }

    @Test
    fun `ranges retain exclusive bounds and reject empty or reversed intervals`() {
        val day = LocalDate.of(2026, 3, 29)
        val range = CalendarRange(day, day.plusDays(1))
        assertEquals("Europe/Berlin", range.zone.id)
        assertEquals(day.plusDays(1), range.endExclusive)
        assertThrows(IllegalArgumentException::class.java) { CalendarRange(day, day) }
        assertThrows(IllegalArgumentException::class.java) { CalendarRange(day, day.minusDays(1)) }

        val start = Instant.parse("2026-03-29T00:30:00Z")
        val end = start.plusSeconds(1800)
        assertEquals(end, TimeWindow(start, end).endExclusive)
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(start, start) }
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(end, start) }
    }
}
