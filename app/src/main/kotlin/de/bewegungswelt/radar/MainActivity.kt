package de.bewegungswelt.radar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SlotStatus
import de.bewegungswelt.radar.model.TimeWindow
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalendarTheme { CalendarScreen(slotsForDay = ::demoSlots) }
        }
    }
}

/** Synthetic display data only, not opening-hours or availability calculation. */
internal fun demoSlots(date: LocalDate): List<Slot> {
    val start = date.atTime(9, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant()
    val hasBookableSlot = date.dayOfMonth in setOf(2, 4, 6, 10, 12, 18, 20, 24, 27, 30)
    return SlotStatus.entries.mapIndexed { index, status ->
        val slotStart = start.plusSeconds(index * 1800L)
        Slot(
            TimeWindow(slotStart, slotStart.plusSeconds(1800)),
            if (status == SlotStatus.BOOKABLE && !hasBookableSlot) SlotStatus.BOOKED else status,
        )
    }
}
