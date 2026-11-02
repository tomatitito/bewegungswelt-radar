package de.bewegungswelt.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MonitoringPreferences(
    settings: MonitoringSettings,
    allowed: Boolean,
    lastCheck: String?,
    error: Boolean,
    onSave: (MonitoringSettings) -> Unit,
    onSystemSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var minimum by rememberSaveable(settings) { mutableIntStateOf(settings.minimumMinutes) }
    var cadence by rememberSaveable(settings) { mutableIntStateOf(settings.cadenceMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue freie Zeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (settings.enabled) "Überwachung aktiviert" else "Überwachung ausgeschaltet", style = MaterialTheme.typography.titleSmall)
                Text("Alle Öffnungstage und Uhrzeiten · Europe/Berlin. Der gesamte freigegebene Buchungszeitraum wird geprüft, einschließlich neu freigegebener Termine.")
                Text("Mindestens durchgehend frei", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    listOf(60, 90, 120).forEach { value ->
                        PreferenceChoice("$value Minuten", minimum == value) { minimum = value }
                    }
                }
                Text("Ungefähr prüfen alle", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    listOf(15, 30, 60, 120, 360).forEach { value ->
                        PreferenceChoice(if (value < 60) "$value Minuten" else "${value / 60} Stunde${if (value == 60) "" else "n"}", cadence == value) { cadence = value }
                    }
                }
                Text("Android kann Prüfungen verzögern. Kurzzeitig freie Zeiten können unbemerkt bleiben. Keine Echtzeitgarantie und keine Reservierung. Ton und Ruhezeiten richten sich nach Android.")
                Text("Der erste erfolgreiche Abruf bleibt still. Zusammenhängende neue Starts werden pro Datum zusammengefasst. Erneute Meldung erst nach bestätigter Nichtverfügbarkeit.")
                if (lastCheck != null) Text("Letzte vollständige Prüfung: $lastCheck", style = MaterialTheme.typography.bodySmall)
                if (error) Text("Letzte Prüfung fehlgeschlagen. Bestätigter Vergleichsstand bleibt erhalten.")
                if (!allowed) {
                    Text("Benachrichtigungen sind nicht erlaubt oder der Kanal ist gesperrt. Der Kalender bleibt nutzbar.")
                    TextButton(onClick = onSystemSettings) { Text("Android-Einstellungen") }
                }
                if (settings.enabled) TextButton(onClick = { onSave(settings.copy(enabled = false)) }) { Text("Überwachung ausschalten") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(settings.copy(enabled = true, minimumMinutes = minimum, cadenceMinutes = cadence)) }) {
                Text(if (settings.enabled) "Speichern" else "Aktivieren")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun PreferenceChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
