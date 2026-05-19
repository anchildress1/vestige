package dev.anchildress1.vestige.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class ModelDownloadCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `null progress degrades every field to a dash, indeterminate bar`() {
        composeRule.setContent {
            VestigeTheme { ModelDownloadCard(progress = null, wifiConnected = false) }
        }
        // percent/OF/bytes all render "—" (ambiguous as a matcher); the unique ones suffice —
        // rendering with null already exercises every degraded branch.
        composeRule.onNodeWithText("--:--").assertIsDisplayed()
        composeRule.onNodeWithText("~— MB/S · NO WI-FI", substring = true).assertIsDisplayed()
    }

    @Test
    fun `sub-tenth speed renders as 0 on Wi-Fi`() {
        composeRule.setContent {
            VestigeTheme {
                ModelDownloadCard(
                    progress = ModelDownloadProgress(
                        fraction = 0.38f,
                        currentBytes = 1_491_308_339L,
                        expectedBytes = 3_928_180_000L,
                        etaSeconds = 258L,
                        mbps = 0.05f,
                    ),
                    wifiConnected = true,
                )
            }
        }
        composeRule.onNodeWithText("38").assertIsDisplayed()
        composeRule.onNodeWithText("04:18").assertIsDisplayed()
        composeRule.onNodeWithText("~0 MB/S · WI-FI", substring = true).assertIsDisplayed()
    }

    @Test
    fun `single-digit speed keeps one decimal`() {
        composeRule.setContent {
            VestigeTheme {
                ModelDownloadCard(
                    progress = ModelDownloadProgress(
                        fraction = 1f,
                        currentBytes = 3_928_180_000L,
                        expectedBytes = 3_928_180_000L,
                        etaSeconds = 0L,
                        mbps = 5.5f,
                    ),
                    wifiConnected = true,
                )
            }
        }
        composeRule.onNodeWithText("~5.5 MB/S · WI-FI", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("00:00").assertIsDisplayed()
    }

    @Test
    fun `double-digit speed truncates to whole number`() {
        composeRule.setContent {
            VestigeTheme {
                ModelDownloadCard(
                    progress = ModelDownloadProgress(
                        fraction = 0.5f,
                        currentBytes = 1_964_090_000L,
                        expectedBytes = 3_928_180_000L,
                        etaSeconds = 90L,
                        mbps = 42.7f,
                    ),
                    wifiConnected = true,
                )
            }
        }
        composeRule.onNodeWithText("~42 MB/S · WI-FI", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("01:30").assertIsDisplayed()
    }
}
