package de.bewegungswelt.radar.model

import java.time.Duration
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

/** Minimum elapsed booking length and allowed local wall-clock start interval from midnight. */
data class BookingRules(val minimumDuration: Duration, val startInterval: Duration) {
    init {
        require(!minimumDuration.isZero && !minimumDuration.isNegative) {
            "Minimum booking duration must be positive"
        }
        require(!startInterval.isZero && !startInterval.isNegative) {
            "Booking start interval must be positive"
        }
    }
}

data class CalendarSnapshot(
    val range: CalendarRange,
    val openingWindows: List<TimeWindow>,
    val bookings: List<TimeWindow>,
    val closures: List<TimeWindow>,
    val fetchedAt: Instant,
    /** Absent rules leave otherwise free starts UNKNOWN. */
    val bookingRules: BookingRules? = null,
    /** Non-occupancy restrictions, such as lead time and booking horizon. */
    val unavailableWindows: List<TimeWindow> = emptyList(),
    /** Verified exclusive booking boundary, independent of the requested/fetched range. */
    val bookingHorizonEndExclusive: Instant? = null,
)

data class SourceDescriptor(val id: String, val name: String)

/** A concise, sanitized message suitable for display, never a raw upstream response. */
data class SourceDiagnostic(val message: String)
