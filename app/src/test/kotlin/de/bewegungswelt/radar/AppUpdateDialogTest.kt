package de.bewegungswelt.radar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `check download permission and installation are distinct explicit actions with retries`() {
        var state by mutableStateOf(AppUpdateState())
        var permission by mutableStateOf(false)
        var installError by mutableStateOf(false)
        var checks = 0
        var downloads = 0
        var installs = 0
        compose.setContent {
            CalendarTheme {
                AppUpdateDialog(state, "0.2.0", permission, installError,
                    onCheck = { checks++ }, onDownload = { downloads++ }, onInstall = { installs++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Installierte Version: 0.2.0").assertIsDisplayed()
        assertEquals(0, checks)
        compose.onNodeWithText("Nach Updates suchen").performClick()
        assertEquals(1, checks)
        compose.runOnIdle { state = AppUpdateState(checking = true) }
        compose.onNodeWithText("Nach Updates suchen").assertIsNotEnabled()
        compose.runOnIdle { state = AppUpdateState(error = true) }
        compose.onNodeWithText("Nach Updates suchen").performClick()
        assertEquals(2, checks)
        compose.runOnIdle { state = AppUpdateState(checked = true) }
        compose.onNodeWithText("Keine neuere veröffentlichte Version verfügbar.").assertIsDisplayed()
        val release = AppRelease("0.3.0", 3, "https://github.com/fixture", 4)
        compose.runOnIdle { state = AppUpdateState(checked = true, release = release) }
        compose.onNodeWithText("Version 0.3.0 ist verfügbar.").assertIsDisplayed()
        assertEquals(0, downloads)
        compose.onNodeWithText("Herunterladen").performClick()
        assertEquals(1, downloads)
        compose.runOnIdle { state = state.copy(downloading = true) }
        compose.onNodeWithText("Herunterladen").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(downloading = false, error = true) }
        compose.onNodeWithText("Nach Updates suchen").performClick()
        assertEquals(3, checks) // a deleted/superseded release can be checked again instead of retrying stale metadata
        compose.runOnIdle { state = state.copy(error = false) }
        compose.runOnIdle { state = state.copy(downloading = false, apk = File("update.apk")) }
        compose.onNodeWithText("Installation erlauben").performClick()
        assertEquals(1, installs)
        compose.runOnIdle { permission = true }
        // Returning from Settings never starts installation automatically.
        assertEquals(1, installs)
        compose.onNodeWithText("Installieren").performClick()
        compose.onNodeWithText("Installieren").performClick() // user may retry after cancelling Android's prompt
        assertEquals(3, installs)
        compose.runOnIdle { installError = true }
        compose.onNodeWithText("Erneut herunterladen").performClick()
        assertEquals(2, downloads)
    }
}
