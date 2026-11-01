package de.bewegungswelt.radar.availability

import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.SourceDescriptor

interface AvailabilitySource {
    val descriptor: SourceDescriptor

    suspend fun loadCalendar(range: CalendarRange): CalendarSnapshot
}
