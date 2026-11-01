package de.bewegungswelt.radar.availability

import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SlotStatus
import de.bewegungswelt.radar.model.TimeWindow
import java.time.Duration
import java.time.Instant

/** A full venue-local day grid; BOOKABLE denotes an eligible booking start. */
fun calculateAvailability(range: CalendarRange, snapshot: CalendarSnapshot?, now: Instant): List<Slot> {
    val openings = normalize(snapshot?.openingWindows.orEmpty())
    val bookings = normalize(snapshot?.bookings.orEmpty())
    val restrictions = normalize(snapshot?.let { it.closures + it.unavailableWindows }.orEmpty())
    // Coverage belongs to the source's date range, even when displaying another zone.
    val coverageStart = snapshot?.range?.let { it.start.atStartOfDay(it.zone).toInstant() }
    val coverageEnd = snapshot?.range?.let { it.endExclusive.atStartOfDay(it.zone).toInstant() }
    val rules = snapshot?.bookingRules
    val halfHour = Duration.ofMinutes(30)

    fun status(window: TimeWindow): SlotStatus {
        if (coverageStart == null || coverageEnd == null ||
            window.start < coverageStart || window.endExclusive > coverageEnd
        ) return SlotStatus.UNKNOWN
        if (window.start < now || restrictions.any { it.overlaps(window) } ||
            openings.none { it.contains(window) }
        ) return SlotStatus.CLOSED
        if (bookings.any { it.overlaps(window) }) return SlotStatus.BOOKED
        if (rules == null) return SlotStatus.UNKNOWN

        val sinceMidnight = Duration.ofNanos(window.start.atZone(range.zone).toLocalTime().toNanoOfDay())
        val aligned = sinceMidnight.isZero ||
            (rules.startInterval <= sinceMidnight && sinceMidnight.toNanos() % rules.startInterval.toNanos() == 0L)
        if (!aligned) return SlotStatus.CLOSED
        // Check coverage before constructing a possibly enormous minimum-duration interval.
        if (rules.minimumDuration > Duration.between(window.start, coverageEnd)) return SlotStatus.UNKNOWN
        val booking = TimeWindow(window.start, window.start.plus(rules.minimumDuration))
        if (openings.none { it.contains(booking) } ||
            restrictions.any { it.overlaps(booking) } || bookings.any { it.overlaps(booking) }
        ) return SlotStatus.CLOSED
        return SlotStatus.BOOKABLE
    }

    return buildList {
        var date = range.start
        while (date < range.endExclusive) {
            var start = date.atStartOfDay(range.zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(range.zone).toInstant()
            while (start < end) {
                val window = TimeWindow(start, minOf(start.plus(halfHour), end))
                add(Slot(window, status(window)))
                start = window.endExclusive
            }
            date = date.plusDays(1)
        }
    }
}

private fun TimeWindow.overlaps(other: TimeWindow): Boolean =
    start < other.endExclusive && other.start < endExclusive

private fun TimeWindow.contains(other: TimeWindow): Boolean =
    start <= other.start && endExclusive >= other.endExclusive

private fun normalize(windows: List<TimeWindow>): List<TimeWindow> = buildList {
    for (window in windows.sortedBy { it.start }) {
        val previous = lastOrNull()
        if (previous != null && window.start <= previous.endExclusive) {
            this[lastIndex] = TimeWindow(previous.start, maxOf(previous.endExclusive, window.endExclusive))
        } else {
            add(window)
        }
    }
}
