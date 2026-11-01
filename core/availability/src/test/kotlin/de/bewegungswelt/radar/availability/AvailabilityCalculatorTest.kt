package de.bewegungswelt.radar.availability

import de.bewegungswelt.radar.model.BookingRules
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SlotStatus
import de.bewegungswelt.radar.model.TimeWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityCalculatorTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 2, 2)
    private val range = CalendarRange(date, date.plusDays(1), zone)
    private val now = date.atStartOfDay(zone).toInstant()
    private val rules = BookingRules(Duration.ofMinutes(30), Duration.ofMinutes(30))

    private fun instant(time: String): Instant = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun window(start: String, end: String) = TimeWindow(instant(start), instant(end))
    private fun snapshot(
        openings: List<TimeWindow> = listOf(window("09:00", "12:00")),
        bookings: List<TimeWindow> = emptyList(),
        closures: List<TimeWindow> = emptyList(),
        unavailable: List<TimeWindow> = emptyList(),
        bookingRules: BookingRules? = rules,
    ) = CalendarSnapshot(range, openings, bookings, closures, now, bookingRules, unavailable)

    private fun List<Slot>.at(time: String): SlotStatus = single { it.window.start == instant(time) }.status
    private fun calculate(snapshot: CalendarSnapshot?, at: Instant = now) =
        calculateAvailability(range, snapshot, at)

    @Test
    fun `full day grid includes closed hours and full half hour must be open`() {
        val slots = calculate(snapshot(openings = listOf(window("09:01", "10:45"))))
        assertEquals(48, slots.size)
        assertEquals(now, slots.first().window.start)
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant(), slots.last().window.endExclusive)
        assertEquals(SlotStatus.CLOSED, slots.at("00:00"))
        assertEquals(SlotStatus.CLOSED, slots.at("09:00"))
        assertEquals(SlotStatus.BOOKABLE, slots.at("09:30"))
        assertEquals(SlotStatus.BOOKABLE, slots.at("10:00"))
        assertEquals(SlotStatus.CLOSED, slots.at("10:30"))
    }

    @Test
    fun `positive partial booking overlaps count but touching boundaries do not`() {
        val slots = calculate(snapshot(bookings = listOf(window("09:45", "10:15"))))
        assertEquals(SlotStatus.BOOKABLE, slots.at("09:00"))
        assertEquals(SlotStatus.BOOKED, slots.at("09:30"))
        assertEquals(SlotStatus.BOOKED, slots.at("10:00"))
        assertEquals(SlotStatus.BOOKABLE, slots.at("10:30"))
        val tiny = calculate(snapshot(bookings = listOf(TimeWindow(instant("10:00").minusNanos(1), instant("10:00")))))
        assertEquals(SlotStatus.BOOKED, tiny.at("09:30"))
        assertEquals(SlotStatus.BOOKABLE, tiny.at("10:00"))
    }

    @Test
    fun `unsorted overlapping and adjoining openings merge without mutating input`() {
        val openings = mutableListOf(window("10:00", "11:00"), window("09:00", "09:45"), window("09:30", "10:00"))
        val original = openings.toList()
        val source = snapshot(openings = openings, bookingRules = rules.copy(minimumDuration = Duration.ofHours(2)))
        assertEquals(SlotStatus.BOOKABLE, calculate(source).at("09:00"))
        assertEquals(SlotStatus.CLOSED, calculate(source).at("09:30"))
        assertEquals(original, openings)
        assertEquals(original, source.openingWindows)
    }

    @Test
    fun `closures win over bookings and unavailable lead and horizon windows are closed`() {
        val slots = calculate(snapshot(
            bookings = listOf(window("09:00", "10:30")),
            closures = listOf(window("09:45", "10:00")),
            unavailable = listOf(window("08:00", "09:30"), window("11:00", "13:00")),
        ))
        assertEquals(SlotStatus.CLOSED, slots.at("09:00"))
        assertEquals(SlotStatus.CLOSED, slots.at("09:30"))
        assertEquals(SlotStatus.BOOKED, slots.at("10:00"))
        assertEquals(SlotStatus.BOOKABLE, slots.at("10:30"))
        assertEquals(SlotStatus.CLOSED, slots.at("11:00"))
    }

    @Test
    fun `now is inclusive but any earlier start is closed`() {
        assertEquals(SlotStatus.BOOKABLE, calculate(snapshot(), instant("09:00")).at("09:00"))
        assertEquals(SlotStatus.CLOSED, calculate(snapshot(), instant("09:00").plusNanos(1)).at("09:00"))
        assertEquals(SlotStatus.BOOKABLE, calculate(snapshot(), instant("09:00").plusNanos(1)).at("09:30"))
    }

    @Test
    fun `minimum duration and local start alignment determine eligible starts`() {
        val source = snapshot(bookingRules = BookingRules(Duration.ofMinutes(90), Duration.ofHours(1)))
        val slots = calculate(source)
        assertEquals(SlotStatus.BOOKABLE, slots.at("09:00"))
        assertEquals(SlotStatus.CLOSED, slots.at("09:30"))
        assertEquals(SlotStatus.BOOKABLE, slots.at("10:00"))
        assertEquals(SlotStatus.CLOSED, slots.at("11:00"))
        val fortyFive = calculate(snapshot(bookingRules = rules.copy(startInterval = Duration.ofMinutes(45))))
        assertEquals(SlotStatus.BOOKABLE, fortyFive.at("09:00"))
        assertEquals(SlotStatus.CLOSED, fortyFive.at("09:30"))
        assertEquals(SlotStatus.BOOKABLE, fortyFive.at("10:30"))
    }

    @Test
    fun `minimum booking may not overlap future bookings closures restrictions or opening gaps`() {
        val longRules = rules.copy(minimumDuration = Duration.ofHours(1))
        for (source in listOf(
            snapshot(bookings = listOf(window("09:45", "10:00")), bookingRules = longRules),
            snapshot(closures = listOf(window("09:45", "10:00")), bookingRules = longRules),
            snapshot(unavailable = listOf(window("09:45", "10:00")), bookingRules = longRules),
            snapshot(openings = listOf(window("09:00", "09:30"), window("09:31", "12:00")), bookingRules = longRules),
        )) {
            assertEquals(SlotStatus.CLOSED, calculate(source).at("09:00"))
        }
        val touches = snapshot(bookings = listOf(window("10:00", "10:30")), bookingRules = longRules)
        assertEquals(SlotStatus.BOOKABLE, calculate(touches).at("09:00"))
    }

    @Test
    fun `missing snapshot or rules never imply availability but known states survive missing rules`() {
        assertTrue(calculate(null).all { it.status == SlotStatus.UNKNOWN })
        assertTrue(calculate(snapshot(openings = emptyList())).all { it.status == SlotStatus.CLOSED })
        val slots = calculate(snapshot(bookingRules = null, bookings = listOf(window("10:00", "10:30"))))
        assertEquals(SlotStatus.UNKNOWN, slots.at("09:00"))
        assertEquals(SlotStatus.BOOKED, slots.at("10:00"))
        assertEquals(SlotStatus.CLOSED, slots.at("08:00"))
    }

    @Test
    fun `uncovered dates and minimum duration crossing coverage are unknown`() {
        val requested = CalendarRange(date.minusDays(1), date.plusDays(2), zone)
        val source = snapshot(openings = listOf(TimeWindow(now, now.plus(Duration.ofDays(2)))))
        val slots = calculateAvailability(requested, source, now.minus(Duration.ofDays(1)))
        assertEquals(144, slots.size)
        assertEquals(96, slots.count { it.status == SlotStatus.UNKNOWN })
        val longSource = source.copy(bookingRules = rules.copy(minimumDuration = Duration.ofHours(1)))
        assertEquals(SlotStatus.UNKNOWN, calculate(longSource).at("23:30"))
        assertEquals(SlotStatus.BOOKABLE, calculate(longSource).at("23:00"))
        assertEquals(SlotStatus.UNKNOWN, calculate(source.copy(
            bookingRules = rules.copy(minimumDuration = Duration.ofSeconds(Long.MAX_VALUE)),
        )).at("09:00"))
    }

    @Test
    fun `display zone controls alignment but cannot extend source coverage`() {
        val requested = range.copy(zone = ZoneId.of("UTC"))
        val midnight = date.atStartOfDay(requested.zone).toInstant()
        val source = snapshot(openings = listOf(TimeWindow(midnight, midnight.plus(Duration.ofDays(1)))),
            bookingRules = rules.copy(startInterval = Duration.ofHours(2)))
        val slots = calculateAvailability(requested, source, midnight)
        assertEquals(midnight, slots.first().window.start)
        assertEquals(SlotStatus.BOOKABLE, slots.first().status)
        assertEquals(SlotStatus.CLOSED, slots[2].status)
        assertEquals(SlotStatus.BOOKABLE, slots[4].status)
        assertEquals(2, slots.count { it.status == SlotStatus.UNKNOWN })
        assertEquals(SlotStatus.UNKNOWN, slots[46].status) // Berlin snapshot ends at 23:00 UTC.
        assertEquals(SlotStatus.UNKNOWN, slots[47].status)
    }

    @Test
    fun `minimum duration remains elapsed time across DST transitions`() {
        for ((day, localStart) in listOf(
            LocalDate.of(2026, 3, 29) to LocalTime.of(1, 30),
            LocalDate.of(2026, 10, 25) to LocalTime.of(2, 30),
        )) {
            val requested = CalendarRange(day, day.plusDays(1), zone)
            val start = day.atTime(localStart).atZone(zone).toInstant()
            val opening = TimeWindow(start, start.plus(Duration.ofHours(1)))
            val source = CalendarSnapshot(
                requested, listOf(opening), emptyList(), emptyList(), start,
                rules.copy(minimumDuration = Duration.ofHours(1)),
            )
            val slots = calculateAvailability(requested, source, start)
            assertEquals(SlotStatus.BOOKABLE, slots.single { it.window.start == start }.status)
            assertEquals(SlotStatus.CLOSED, slots.single { it.window.start == start.plusSeconds(1800) }.status)
        }
    }

    @Test
    fun `Berlin DST uses unique consecutive instants with 46 spring and 50 fall slots`() {
        for ((day, count) in listOf(LocalDate.of(2026, 3, 29) to 46, LocalDate.of(2026, 10, 25) to 50)) {
            val requested = CalendarRange(day, day.plusDays(1), zone)
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val source = CalendarSnapshot(requested, listOf(TimeWindow(start, end)), emptyList(), emptyList(), start, rules)
            val slots = calculateAvailability(requested, source, start)
            assertEquals(count, slots.size)
            assertEquals(count, slots.map { it.window.start }.toSet().size)
            assertEquals(start, slots.first().window.start)
            assertEquals(end, slots.last().window.endExclusive)
            assertTrue(slots.all { it.status == SlotStatus.BOOKABLE && Duration.between(it.window.start, it.window.endExclusive) == Duration.ofMinutes(30) })
            assertTrue(slots.zipWithNext().all { (a, b) -> a.window.endExclusive == b.window.start })
            val hourly = calculateAvailability(requested, source.copy(bookingRules = rules.copy(startInterval = Duration.ofHours(1))), start)
            assertTrue(hourly.all {
                it.status == if (it.window.start.atZone(zone).minute == 0) SlotStatus.BOOKABLE else SlotStatus.CLOSED
            })
        }
    }
}
