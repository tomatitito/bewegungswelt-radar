package de.bewegungswelt.radar.availability

import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SlotStatus
import java.time.Duration
import java.time.Instant

/** Uses the calendar's start rules, requiring the larger of the user and upstream durations. */
fun calculateNotificationAvailability(
    snapshot: CalendarSnapshot,
    now: Instant,
    minimumDuration: Duration = Duration.ofMinutes(60),
): List<Slot> {
    require(!minimumDuration.isZero && !minimumDuration.isNegative) { "Minimum duration must be positive" }
    val rules = snapshot.bookingRules
    val effective = snapshot.copy(
        bookingRules = rules?.copy(minimumDuration = maxOf(rules.minimumDuration, minimumDuration)),
    )
    return calculateAvailability(snapshot.range, effective, now)
}

data class AvailabilityComparison(
    /** Persist these confirmed statuses keyed by start Instant; UNKNOWN is never stored. */
    val confirmedStatuses: Map<Instant, SlotStatus>,
    /** Starts first observed through a verified booking-horizon expansion. */
    val newlyReleasedGroups: List<List<Instant>>,
    /** Starts that changed from a confirmed unavailable state to bookable. */
    val reopenedGroups: List<List<Instant>>,
) {
    val newlyAvailableStarts: List<Instant>
        get() = (newlyReleasedGroups.flatten() + reopenedGroups.flatten()).sorted()
    val newlyAvailableGroups: List<List<Instant>>
        get() = groupAdjacent(newlyAvailableStarts)
}

/**
 * Pure comparison, reconstructible entirely from persisted [previousConfirmedStatuses] and horizons.
 * First-seen starts establish a silent baseline. UNKNOWN and absent observations preserve history.
 * A previously unseen start may alert only with explicit horizon expansion evidence: it was at or
 * beyond [previousBookingHorizonEndExclusive] and is below [bookingHorizonEndExclusive]. The first
 * ever horizon (null previous boundary) is silent. Both horizons must be verified source evidence,
 * never a fetched range boundary. Pass the SAME previous horizon to every batch in a complete scan;
 * commit the new horizon only after successful coverage of the monitored horizon. On failed or
 * incomplete scans retain the prior horizon. Scope persisted state to source and monitoring rules;
 * reset the baseline when these change. The caller owns retention, persistence and delivery dedupe.
 */
fun compareAvailability(
    observations: List<Slot>,
    previousConfirmedStatuses: Map<Instant, SlotStatus>,
    previousBookingHorizonEndExclusive: Instant? = null,
    bookingHorizonEndExclusive: Instant? = null,
): AvailabilityComparison {
    require(previousConfirmedStatuses.values.none { it == SlotStatus.UNKNOWN }) {
        "History must contain only confirmed statuses"
    }
    require(observations.map { it.window.start }.distinct().size == observations.size) {
        "Each start must have a single observation"
    }
    val confirmed = previousConfirmedStatuses.toMutableMap()
    val newlyReleased = mutableListOf<Instant>()
    val reopened = mutableListOf<Instant>()
    for (slot in observations.sortedBy { it.window.start }) {
        if (slot.status == SlotStatus.UNKNOWN) continue
        val start = slot.window.start
        val previous = previousConfirmedStatuses[start]
        val released = previous == null && previousBookingHorizonEndExclusive != null &&
            bookingHorizonEndExclusive != null && start >= previousBookingHorizonEndExclusive &&
            start < bookingHorizonEndExclusive
        if (slot.status == SlotStatus.BOOKABLE) {
            if (released) newlyReleased += start
            else if (previous == SlotStatus.BOOKED || previous == SlotStatus.CLOSED) reopened += start
        }
        confirmed[start] = slot.status
    }
    return AvailabilityComparison(
        confirmed.toMap(),
        groupAdjacent(newlyReleased),
        groupAdjacent(reopened),
    )
}

private fun groupAdjacent(starts: List<Instant>): List<List<Instant>> {
    val groups = mutableListOf<MutableList<Instant>>()
    for (start in starts.sorted()) {
        if (groups.lastOrNull()?.lastOrNull()?.plusSeconds(30 * 60L) != start) groups.add(mutableListOf())
        groups.last() += start
    }
    return groups.map { it.toList() }
}
