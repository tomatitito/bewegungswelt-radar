package de.bewegungswelt.radar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import de.bewegungswelt.radar.model.BookingRules
import de.bewegungswelt.radar.model.CalendarRange
import de.bewegungswelt.radar.model.CalendarSnapshot
import de.bewegungswelt.radar.model.Slot
import de.bewegungswelt.radar.model.SlotStatus
import de.bewegungswelt.radar.model.SourceDiagnostic
import de.bewegungswelt.radar.model.TimeWindow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@RunWith(RobolectricTestRunner::class)
// Stable Espresso still uses InputManager.getInstance(), removed in API 37.
@Config(sdk = [26, 36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FixtureCalendarTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showCalendar(
        today: LocalDate = LocalDate.of(2026, 11, 2),
        slotsForDay: (LocalDate) -> List<Slot> = ::demoSlots,
    ) {
        compose.setContent {
            var range by remember {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                mutableStateOf(CalendarRange(start, start.plusDays(7)))
            }
            CalendarTheme {
                CalendarScreen(fixtureState(range, slotsForDay), { range = it }, {}, today)
            }
        }
    }

    @Test
    fun `calendar distinguishes every status and displays the fetch time`() {
        showCalendar()
        compose.onNodeWithText("Stand 01.11.2026 11:00 · Europe/Berlin").assertIsDisplayed()
        compose.onNodeWithText("Montag, 2. November 2026").assertIsDisplayed()
        compose.onNodeWithText("Woche").assertIsSelected()
        compose.onNodeWithText("Mindestens 60 Minuten Buchungsdauer").assertIsDisplayed()
        listOf("Buchbar", "Gebucht", "Nicht buchbar", "Unbekannt").forEach { status ->
            compose.onNodeWithText(status).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("09:00").assertExists()
        compose.onNodeWithText("09:00 – 09:30").assertDoesNotExist()
    }

    @Test
    fun `month day tap opens its week and slot details stay read only`() {
        showCalendar()
        compose.onNodeWithText("Monat").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Mittwoch, 18. November 2026, Mindestens ein buchbarer Start")
            .performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Woche").assertIsSelected()
        compose.onNodeWithText("Monat").assertIsNotSelected()
        compose.onNodeWithText("Mittwoch, 18. November 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("09:00, Buchbar").performScrollTo().performClick()
        compose.onNodeWithText("Start: 09:00").assertIsDisplayed()
        compose.onNodeWithText("Nur Anzeige, keine Reservierung. Verfügbarkeit und Buchungsregeln im Locaboo-Portal erneut prüfen. Keine Buchung in der App.").assertIsDisplayed()
        compose.onNodeWithText("Schließen").performClick()
        compose.onNodeWithText("Start: 09:00").assertDoesNotExist()
    }

    @Test
    fun `period navigation crosses year boundary and today restores the injected venue date`() {
        showCalendar()
        compose.onNodeWithText("Monat").performClick()
        repeat(2) { compose.onNodeWithContentDescription("Nächster Monat").performClick() }
        compose.onNodeWithText("Monat").performClick()
        compose.onNodeWithText("Januar 2027").assertIsDisplayed()
        compose.onNodeWithContentDescription("Freitag, 1. Januar 2027, Kein buchbarer Start")
            .performClick().assertIsSelected()
        compose.onNodeWithText("Woche").assertIsSelected()
        compose.onNodeWithContentDescription("Vorherige Woche").performClick()
        compose.onNodeWithText("Freitag, 25. Dezember 2026").assertIsDisplayed()
        compose.onNodeWithText("Heute").performClick()
        compose.onNodeWithText("Montag, 2. November 2026").assertIsDisplayed()
        compose.onNodeWithText("Monat").performClick()
        compose.onNodeWithText("November 2026").assertIsDisplayed()
    }

    @Test
    fun `a later bookable slot marks its day available but unknown and empty days do not`() {
        showCalendar(today = LocalDate.of(2028, 2, 28)) { date ->
            when (date.dayOfMonth) {
                29 -> demoSlots(date).mapIndexed { index, slot ->
                    slot.copy(status = if (index == 2) SlotStatus.BOOKABLE else SlotStatus.UNKNOWN)
                }
                28 -> demoSlots(date).map { it.copy(status = SlotStatus.UNKNOWN) }
                else -> emptyList()
            }
        }
        compose.onNodeWithText("Monat").performClick()
        compose.onNodeWithContentDescription("Montag, 28. Februar 2028, Verfügbarkeit unbestätigt").assertExists()
        compose.onNodeWithContentDescription("Sonntag, 27. Februar 2028, Kein buchbarer Start").assertExists()
        compose.onNodeWithContentDescription("Dienstag, 29. Februar 2028, Mindestens ein buchbarer Start").performClick()
        compose.onNodeWithText("Dienstag, 29. Februar 2028").assertIsDisplayed()
        compose.onNodeWithContentDescription("10:00, Buchbar").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `refresh and failure never expose stale bookable starts`() {
        val range = CalendarRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))
        var state by mutableStateOf(fixtureState(range))
        compose.setContent {
            CalendarTheme {
                CalendarScreen(state, {}, { state = state.copy(loading = true) }, today = range.start)
            }
        }
        compose.onNodeWithContentDescription("09:00, Buchbar").assertExists()
        compose.onRoot().performTouchInput { swipeDown() }
        compose.onNodeWithText("Verfügbarkeit wird geladen …").assertIsDisplayed()
        compose.onNodeWithContentDescription("09:00, Buchbar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Montag, 2. November 2026, Verfügbarkeit unbestätigt").assertExists()
        compose.runOnIdle {
            state = state.copy(loading = false, diagnostic = SourceDiagnostic("Locaboo ist nicht erreichbar."))
        }
        compose.onNodeWithText("Abruf fehlgeschlagen").assertIsDisplayed()
        compose.onNodeWithText("Locaboo ist nicht erreichbar.").assertIsDisplayed()
        compose.onNodeWithText("Letzter Stand 01.11.2026 11:00 · nicht mehr bestätigt").assertIsDisplayed()
        compose.onNodeWithContentDescription("09:00, Buchbar").assertDoesNotExist()
        compose.onNodeWithText("Aktualisieren").performClick()
        compose.onNodeWithText("Verfügbarkeit wird geladen …").assertIsDisplayed()
    }
}

internal fun fixtureState(
    range: CalendarRange,
    slotsForDay: (LocalDate) -> List<Slot> = ::demoSlots,
): CalendarUiState {
    val slots = range.start.datesUntil(range.endExclusive).flatMap { slotsForDay(it).stream() }.toList()
    val snapshot = CalendarSnapshot(range, slots.map { it.window }, emptyList(), emptyList(),
        Instant.parse("2026-11-01T10:00:00Z"), BookingRules(Duration.ofMinutes(60), Duration.ofMinutes(30)))
    return CalendarUiState(range, snapshot, slots, loading = false)
}

private fun demoSlots(date: LocalDate): List<Slot> {
    val start = date.atTime(9, 0).atZone(java.time.ZoneId.of("Europe/Berlin")).toInstant()
    val hasBookableSlot = date.dayOfMonth in setOf(2, 4, 6, 10, 12, 18, 20, 24, 27, 30)
    return SlotStatus.entries.mapIndexed { index, status ->
        val slotStart = start.plusSeconds(index * 1800L)
        Slot(TimeWindow(slotStart, slotStart.plusSeconds(1800)), if (hasBookableSlot) status else SlotStatus.BOOKED)
    }
}
