package de.bewegungswelt.radar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SlotStatus
import de.bewegungswelt.radar.model.TimeWindow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FixtureCalendar() } }
    }
}

/** Explicitly synthetic; no network access or actual availability is implied. */
@Composable
private fun FixtureCalendar() {
    val zone = ZoneId.of("Europe/Berlin")
    val date = LocalDate.of(2026, 11, 2)
    val start = date.atTime(9, 0).atZone(zone).toInstant()
    val slots = SlotStatus.entries.mapIndexed { index, status ->
        val slotStart = start.plusSeconds(index * 1800L)
        Slot(TimeWindow(slotStart, slotStart.plusSeconds(1800)), status)
    }
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.venue_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.demo_notice), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.calendar_heading), style = MaterialTheme.typography.titleLarge)
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)))
            slots.forEach { slot ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${timeFormat.format(slot.window.start)} – ${timeFormat.format(slot.window.endExclusive)}")
                        Text(stringResource(when (slot.status) {
                            SlotStatus.BOOKABLE -> R.string.bookable
                            SlotStatus.BOOKED -> R.string.booked
                            SlotStatus.CLOSED -> R.string.closed
                            SlotStatus.UNKNOWN -> R.string.unknown
                        }))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, locale = "de", widthDp = 360)
@Composable
private fun CalendarPreview() {
    MaterialTheme { FixtureCalendar() }
}
