package de.bewegungswelt.radar

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.bewegungswelt.radar.model.SlotStatus
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
// Stable Espresso still uses InputManager.getInstance(), removed in API 37.
@Config(sdk = [26, 36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FixtureCalendarTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun showCalendar() {
        compose.runOnUiThread {
            compose.activity.setContent {
                CalendarTheme { CalendarScreen(::demoSlots, today = LocalDate.of(2026, 11, 2)) }
            }
        }
    }

    @Test
    fun `example calendar labels synthetic data and distinguishes every status`() {
        compose.onNodeWithText("Demodaten · Keine Live-Verfügbarkeit").assertIsDisplayed()
        compose.onNodeWithText("Montag, 2. November 2026").assertIsDisplayed()
        compose.onNodeWithText("Woche").assertIsSelected()
        listOf("Buchbar", "Gebucht", "Geschlossen", "Unbekannt").forEach { status ->
            compose.onNodeWithText(status).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("09:00").assertExists()
        compose.onNodeWithText("09:00 – 09:30").assertDoesNotExist()
    }

    @Test
    fun `month day tap opens its week and slot details stay read only`() {
        compose.onNodeWithText("Monat").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Mittwoch, 18. November 2026, Mindestens ein buchbares Zeitfenster")
            .performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Woche").assertIsSelected()
        compose.onNodeWithText("Monat").assertIsNotSelected()
        compose.onNodeWithText("Mittwoch, 18. November 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("09:00 bis 09:30, Buchbar").performScrollTo().performClick()
        compose.onNodeWithText("09:00 – 09:30").assertIsDisplayed()
        compose.onNodeWithText("Demodaten · Nur Anzeige. Keine Buchung in der App.").assertIsDisplayed()
        compose.onNodeWithText("Schließen").performClick()
        compose.onNodeWithText("09:00 – 09:30").assertDoesNotExist()
    }

    @Test
    fun `period navigation crosses year boundary and today restores the injected venue date`() {
        compose.onNodeWithText("Monat").performClick()
        repeat(2) { compose.onNodeWithContentDescription("Nächster Monat").performClick() }
        compose.onNodeWithText("Monat").performClick()
        compose.onNodeWithText("Januar 2027").assertIsDisplayed()
        compose.onNodeWithContentDescription("Freitag, 1. Januar 2027, Kein bestätigtes buchbares Zeitfenster")
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
        compose.runOnUiThread {
            compose.activity.setContent {
                CalendarTheme {
                    CalendarScreen(
                        today = LocalDate.of(2028, 2, 28),
                        slotsForDay = { date ->
                            when (date.dayOfMonth) {
                                29 -> demoSlots(date).mapIndexed { index, slot ->
                                    slot.copy(status = if (index == 2) SlotStatus.BOOKABLE else SlotStatus.UNKNOWN)
                                }
                                28 -> demoSlots(date).map { it.copy(status = SlotStatus.UNKNOWN) }
                                else -> emptyList()
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Monat").performClick()
        compose.onNodeWithContentDescription("Montag, 28. Februar 2028, Kein bestätigtes buchbares Zeitfenster").assertExists()
        compose.onNodeWithContentDescription("Sonntag, 27. Februar 2028, Kein bestätigtes buchbares Zeitfenster").assertExists()
        compose.onNodeWithContentDescription("Dienstag, 29. Februar 2028, Mindestens ein buchbares Zeitfenster").performClick()
        compose.onNodeWithText("Dienstag, 29. Februar 2028").assertIsDisplayed()
        compose.onNodeWithContentDescription("10:00 bis 10:30, Buchbar").performScrollTo().assertIsDisplayed()
    }
}
