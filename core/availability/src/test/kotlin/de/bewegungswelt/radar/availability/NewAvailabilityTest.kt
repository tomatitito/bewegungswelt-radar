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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAvailabilityTest {
    private val date = LocalDate.of(2026, 11, 6)
    private val range = CalendarRange(date, date.plusDays(1))
    private val now = date.atStartOfDay(range.zone).toInstant()
    private fun at(time: String) = date.atTime(java.time.LocalTime.parse(time)).atZone(range.zone).toInstant()
    private fun window(from: String, to: String) = TimeWindow(at(from), at(to))
    private fun slot(time: String, status: SlotStatus) = Slot(TimeWindow(at(time), at(time).plusSeconds(1800)), status)
    private fun source(
        openings: List<TimeWindow> = listOf(window("09:00", "12:00")),
        bookings: List<TimeWindow> = emptyList(),
        rules: BookingRules? = BookingRules(Duration.ofMinutes(30), Duration.ofMinutes(30)),
    ) = CalendarSnapshot(range, openings, bookings, emptyList(), now, rules)

    @Test
    fun `minimum is continuous not a sum across a gap and default is sixty minutes`() {
        val snapshot = source(listOf(window("09:00", "09:30"), window("10:00", "10:30")))
        assertFalse(calculateNotificationAvailability(snapshot, now).any { it.status == SlotStatus.BOOKABLE })
        val shorter = calculateNotificationAvailability(snapshot, now, Duration.ofMinutes(30))
        assertEquals(listOf(at("09:00"), at("10:00")), shorter.filter { it.status == SlotStatus.BOOKABLE }.map { it.window.start })
        val normal = calculateNotificationAvailability(source(), now)
        assertEquals(SlotStatus.BOOKABLE, normal.single { it.window.start == at("11:00") }.status)
        assertEquals(SlotStatus.CLOSED, normal.single { it.window.start == at("11:30") }.status)
    }

    @Test
    fun `upstream minimum allowed starts and missing rules are preserved`() {
        val snapshot = source(rules = BookingRules(Duration.ofHours(2), Duration.ofHours(1)))
        val slots = calculateNotificationAvailability(snapshot, now)
        assertEquals(listOf(at("09:00"), at("10:00")), slots.filter { it.status == SlotStatus.BOOKABLE }.map { it.window.start })
        assertEquals(listOf(at("09:00")), calculateNotificationAvailability(snapshot, now, Duration.ofHours(3))
            .filter { it.status == SlotStatus.BOOKABLE }.map { it.window.start })
        assertEquals(SlotStatus.UNKNOWN, calculateNotificationAvailability(source(rules = null), now)
            .single { it.window.start == at("09:00") }.status)
        assertThrows(IllegalArgumentException::class.java) {
            calculateNotificationAvailability(snapshot, now, Duration.ZERO)
        }
    }

    @Test
    fun `horizon constrains full booking and exact end may touch boundary`() {
        val snapshot = source().copy(bookingHorizonEndExclusive = at("10:00"))
        val slots = calculateNotificationAvailability(snapshot, now)
        assertEquals(listOf(at("09:00")), slots.filter { it.status == SlotStatus.BOOKABLE }.map { it.window.start })
        assertEquals(SlotStatus.CLOSED, slots.single { it.window.start == at("09:30") }.status)
        assertEquals(SlotStatus.CLOSED, slots.single { it.window.start == at("10:00") }.status)
    }

    @Test
    fun `baseline silent cancellation alerts and persisted reconstruction deduplicates`() {
        val occupied = source(bookings = listOf(window("10:00", "11:00")))
        val baseline = compareAvailability(calculateNotificationAvailability(occupied, now), emptyMap())
        assertTrue(baseline.newlyAvailableGroups.isEmpty())
        val before = baseline.confirmedStatuses.toMap()
        val freed = compareAvailability(calculateNotificationAvailability(source(), now), before)
        assertEquals(listOf(listOf(at("09:30"), at("10:00"), at("10:30"))), freed.newlyAvailableGroups)
        // Reconstruct the complete public persistence contract as an app would after process death.
        val serialized = freed.confirmedStatuses.mapKeys { it.key.toString() }.mapValues { it.value.name }
        val restored = serialized.mapKeys { Instant.parse(it.key) }.mapValues { SlotStatus.valueOf(it.value) }
        assertTrue(compareAvailability(calculateNotificationAvailability(source(), now), restored).newlyAvailableStarts.isEmpty())
        assertEquals(baseline.confirmedStatuses, before)
    }

    @Test
    fun `unknown and absent observations preserve history without false recovery alerts`() {
        val prior = mapOf(at("09:00") to SlotStatus.BOOKED, at("09:30") to SlotStatus.BOOKABLE)
        val unknown = compareAvailability(listOf(slot("09:00", SlotStatus.UNKNOWN), slot("09:30", SlotStatus.UNKNOWN)), prior)
        assertEquals(prior, unknown.confirmedStatuses)
        assertTrue(unknown.newlyAvailableStarts.isEmpty())
        assertEquals(prior, compareAvailability(emptyList(), prior).confirmedStatuses)
        val recovered = compareAvailability(listOf(slot("09:00", SlotStatus.BOOKABLE), slot("09:30", SlotStatus.BOOKABLE)), unknown.confirmedStatuses)
        assertEquals(listOf(at("09:00")), recovered.newlyAvailableStarts)
        val unseen = compareAvailability(listOf(slot("10:00", SlotStatus.UNKNOWN)), emptyMap())
        assertTrue(unseen.confirmedStatuses.isEmpty())
        assertTrue(compareAvailability(listOf(slot("10:00", SlotStatus.BOOKABLE)), unseen.confirmedStatuses).newlyAvailableStarts.isEmpty())
    }

    @Test
    fun `only verified horizon expansion alerts for previously unobserved starts`() {
        val observations = listOf("09:00", "09:30", "10:00", "10:30", "11:00").map { slot(it, SlotStatus.BOOKABLE) }
        val expanded = compareAvailability(observations, emptyMap(), at("10:00"), at("11:00"))
        assertEquals(listOf(at("10:00"), at("10:30")), expanded.newlyAvailableStarts)
        assertTrue(compareAvailability(observations, emptyMap(), null, at("11:00")).newlyAvailableStarts.isEmpty())
        assertTrue(compareAvailability(observations, emptyMap(), at("10:00"), null).newlyAvailableStarts.isEmpty())
        assertTrue(compareAvailability(observations, emptyMap(), at("11:00"), at("10:00")).newlyAvailableStarts.isEmpty())
        assertTrue(compareAvailability(observations, emptyMap(), at("11:00"), at("11:00")).newlyAvailableStarts.isEmpty())
        val reconstructed = expanded.confirmedStatuses.toMap()
        assertTrue(compareAvailability(observations, reconstructed, at("10:00"), at("11:00")).newlyAvailableStarts.isEmpty())
    }

    @Test
    fun `release expansion unknown does not alert and later confirmed release does`() {
        val unknown = compareAvailability(listOf(slot("10:00", SlotStatus.UNKNOWN)), emptyMap(), at("10:00"), at("11:00"))
        assertTrue(unknown.newlyAvailableStarts.isEmpty())
        assertTrue(unknown.confirmedStatuses.isEmpty())
        // The app retains the old verified horizon until all monitored batches are confirmed.
        val recovered = compareAvailability(listOf(slot("10:00", SlotStatus.BOOKABLE)), unknown.confirmedStatuses, at("10:00"), at("11:00"))
        assertEquals(listOf(at("10:00")), recovered.newlyAvailableStarts)
    }

    @Test
    fun `grouping stops at unchanged and unavailable starts and sorts by instant`() {
        val observations = listOf(slot("11:00", SlotStatus.BOOKABLE), slot("09:30", SlotStatus.BOOKABLE),
            slot("10:00", SlotStatus.BOOKABLE), slot("09:00", SlotStatus.BOOKABLE), slot("10:30", SlotStatus.CLOSED))
        val prior = observations.associate { it.window.start to SlotStatus.CLOSED }.toMutableMap()
        prior[at("10:00")] = SlotStatus.BOOKABLE
        assertEquals(listOf(listOf(at("09:00"), at("09:30")), listOf(at("11:00"))),
            compareAvailability(observations, prior).newlyAvailableGroups)
    }
}
