package de.bewegungswelt.radar.availability

import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.SourceDescriptor
import de.bewegungswelt.radar.model.SourceDiagnostic

interface AvailabilitySource {
    val descriptor: SourceDescriptor

    suspend fun loadCalendar(range: CalendarRange): CalendarSnapshot
}

/** A failed load has no usable snapshot. Never expose upstream bodies or exception causes. */
class AvailabilityLoadException(val diagnostic: SourceDiagnostic) : Exception(diagnostic.message)
