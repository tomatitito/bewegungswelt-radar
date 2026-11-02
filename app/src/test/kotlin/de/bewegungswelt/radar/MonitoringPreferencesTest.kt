package de.bewegungswelt.radar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class MonitoringPreferencesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `preferences select one continuous minimum and cadence and retain opt out`() {
        var saved = MonitoringSettings()
        compose.setContent {
            var settings by remember { mutableStateOf(MonitoringSettings()) }
            CalendarTheme {
                MonitoringPreferences(settings, false, null, false,
                    { saved = it; settings = it }, {}, {})
            }
        }
        compose.onNodeWithText("60 Minuten").assertIsSelected()
        compose.onNodeWithText("1 Stunde").performScrollTo().assertIsSelected()
        compose.onNodeWithText("120 Minuten").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("2 Stunden").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Android-Einstellungen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aktivieren").performClick()
        assertEquals(120, saved.minimumMinutes)
        assertEquals(120, saved.cadenceMinutes)
        compose.onNodeWithText("Überwachung ausschalten").performScrollTo().performClick()
        assertFalse(saved.enabled)
    }

    @Test fun `notification date opens its week even from month mode`() {
        val today = LocalDate.of(2026, 11, 2)
        var openDate by mutableStateOf<LocalDate?>(null)
        compose.setContent {
            CalendarTheme { CalendarScreen(CalendarUiState(), {}, {}, today, openDate) }
        }
        compose.onNodeWithText("Monat").performClick()
        compose.runOnIdle { openDate = LocalDate.of(2027, 3, 17) }
        compose.onNodeWithText("Woche").assertIsSelected()
        compose.onNodeWithText("Mittwoch, 17. März 2027").performScrollTo().assertIsDisplayed()
    }
}
