package de.bewegungswelt.radar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Stable Espresso still uses InputManager.getInstance(), removed in API 37.
@Config(sdk = [26, 36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FixtureCalendarTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `example calendar labels synthetic data and distinguishes every status`() {
        compose.onNodeWithText("Demodaten · Keine Live-Verfügbarkeit").assertIsDisplayed()
        compose.onNodeWithText("Montag, 2. November 2026").assertIsDisplayed()
        listOf("Buchbar", "Gebucht", "Geschlossen", "Unbekannt").forEach { status ->
            compose.onNodeWithText(status).assertIsDisplayed()
        }
    }
}
