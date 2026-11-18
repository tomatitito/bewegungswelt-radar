package de.bewegungswelt.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val DiagnosticsZone = ZoneId.of("Europe/Berlin")
private val DiagnosticsDay = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.GERMAN)
private val DiagnosticsTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.GERMAN).withZone(DiagnosticsZone)
private val DiagnosticsDate = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)

@Composable
internal fun DiagnosticsScreen(
    entries: List<QueryLogEntry>,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val rows = remember(entries) {
        entries.groupBy { it.startedAt.atZone(DiagnosticsZone).toLocalDate() }
            .toSortedMap(reverseOrder())
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack) { Text("Zurück") }
                Text("Diagnose", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onCopy(formatQueryDiagnostics(entries)) }) { Text("Kopieren") }
            }
            Text(
                "Aufzeichnung aktiv · private Abrufdiagnose · maximal 200 Einträge und 30 Tage. Keine Antworten, URLs, Cookies oder Personendaten. Zeitpunkte grenzen Änderungen nur zwischen zwei Prüfungen ein.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (entries.isEmpty()) {
                Text("Noch keine Abrufe aufgezeichnet.", Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    rows.forEach { (day, dayEntries) ->
                        item(key = day.toString()) {
                            Text(
                                DiagnosticsDay.format(day),
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(dayEntries, key = { "${it.startedAt}-${it.trigger}-${it.finishedAt}" }) { entry ->
                            DiagnosticEntry(entry)
                            HorizontalDivider()
                        }
                    }
                }
            }
            TextButton(
                onClick = { confirmClear = true },
                modifier = Modifier.padding(8.dp),
            ) { Text("Löschen") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Diagnoseverlauf löschen?") },
        text = { Text("Nur gespeicherte Diagnoseeinträge werden gelöscht.") },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; onClear() }) { Text("Löschen") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
    )
}

@Composable
private fun DiagnosticEntry(entry: QueryLogEntry) {
    val outcome = when (entry.outcome) {
        QueryOutcome.SUCCESS -> "Erfolgreich"
        QueryOutcome.CANCELLED -> "Abgebrochen"
        QueryOutcome.FAILURE -> "Fehlgeschlagen · ${when (entry.failure) {
            QueryFailure.NETWORK -> "Netzwerk"
            QueryFailure.TIMEOUT -> "Zeitüberschreitung"
            QueryFailure.INVALID_DATA -> "Ungültige Daten"
            QueryFailure.INTERNAL, null -> "Intern"
        }}"
    }
    val trigger = if (entry.trigger == QueryTrigger.CALENDAR) "Kalender" else "Hintergrund"
    val duration = if (entry.elapsed.toMillis() < 1_000) "${entry.elapsed.toMillis()} ms"
        else String.format(Locale.GERMAN, "%.1f s", entry.elapsed.toMillis() / 1_000.0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${DiagnosticsTime.format(entry.startedAt)} · $trigger · $outcome", fontWeight = FontWeight.SemiBold)
        Text("Dauer $duration · ${entry.batchCount} ${if (entry.batchCount == 1) "Abschnitt" else "Abschnitte"}")
        Text("Zeitraum ${DiagnosticsDate.format(entry.range.start)} – ${DiagnosticsDate.format(entry.range.endExclusive.minusDays(1))}")
        if (entry.outcome == QueryOutcome.SUCCESS) {
            Text("${entry.bookableStarts} buchbare Starts · ${entry.bookedHalfHours} gebuchte Halbstunden")
            entry.bookingHorizonEndExclusive?.let {
                Text("Buchungshorizont bis ${DiagnosticsDate.format(it.atZone(DiagnosticsZone).toLocalDate())} (exklusiv)")
            }
        }
    }
}
