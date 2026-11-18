package de.bewegungswelt.radar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.bewegungswelt.radar.model.CalendarRange
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `entries group by day expose textual outcomes copy delete confirmation and back`() {
        val first = Instant.parse("2026-11-02T09:42:00Z")
        val range = CalendarRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))
        val entries = listOf(
            QueryLogEntry(first, first.plusSeconds(2), QueryTrigger.CALENDAR, range, 1, QueryOutcome.SUCCESS,
                bookableStarts = 3, bookedHalfHours = 13),
            QueryLogEntry(first.minusSeconds(3600), first.minusSeconds(3599), QueryTrigger.BACKGROUND, range, 0,
                QueryOutcome.FAILURE, failure = QueryFailure.NETWORK),
            QueryLogEntry(first.minusSeconds(86_400), first.minusSeconds(86_399), QueryTrigger.CALENDAR, range, 0,
                QueryOutcome.CANCELLED),
        )
        var copied = ""
        var cleared = false
        var backed = false
        compose.setContent {
            CalendarTheme {
                DiagnosticsScreen(entries, { backed = true }, { copied = it }, { cleared = true })
            }
        }
        compose.onNodeWithText("Montag, 2. November 2026").assertIsDisplayed()
        compose.onNodeWithText("Sonntag, 1. November 2026").assertExists()
        compose.onNodeWithText("10:42:00 · Kalender · Erfolgreich").assertExists()
        compose.onNodeWithText("09:42:00 · Hintergrund · Fehlgeschlagen · Netzwerk").assertExists()
        compose.onNodeWithText("10:42:00 · Kalender · Abgebrochen").assertExists()
        compose.onNodeWithText("Kopieren").performClick()
        compose.runOnIdle {
            assertFalse(copied.contains("exception"))
            assertFalse(copied.contains("cookie"))
            assertEquals(3, copied.lines().count { it.contains(" | ") })
        }
        compose.onNodeWithText("Löschen").performClick()
        compose.onNodeWithText("Diagnoseverlauf löschen?").assertIsDisplayed()
        compose.onAllNodesWithText("Löschen")[1].performClick()
        compose.runOnIdle { assertEquals(true, cleared) }
        compose.onNodeWithText("Zurück").performClick()
        compose.runOnIdle { assertEquals(true, backed) }
    }
}
