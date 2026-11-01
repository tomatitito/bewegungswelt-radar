package de.bewegungswelt.radar.model

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalendarTest {
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
