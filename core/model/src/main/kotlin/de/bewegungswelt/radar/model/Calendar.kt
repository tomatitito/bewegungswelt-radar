package de.bewegungswelt.radar.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Dates are start-inclusive and end-exclusive in the venue's time zone. */
data class CalendarRange(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val zone: ZoneId = ZoneId.of("Europe/Berlin"),
) {
    init {
        require(start < endExclusive) { "Calendar range must have a positive duration" }
    }
}

/** All intervals are start-inclusive and end-exclusive. */
data class TimeWindow(val start: Instant, val endExclusive: Instant) {
    init {
        require(start < endExclusive) { "Time window must have a positive duration" }
    }
}

enum class SlotStatus { BOOKABLE, BOOKED, CLOSED, UNKNOWN }

data class Slot(val window: TimeWindow, val status: SlotStatus)

data class CalendarSnapshot(
    val range: CalendarRange,
    val openingWindows: List<TimeWindow>,
    val bookings: List<TimeWindow>,
    val closures: List<TimeWindow>,
    val fetchedAt: Instant,
)

data class SourceDescriptor(val id: String, val name: String)

/** A concise, sanitized message suitable for display, never a raw upstream response. */
data class SourceDiagnostic(val message: String)
