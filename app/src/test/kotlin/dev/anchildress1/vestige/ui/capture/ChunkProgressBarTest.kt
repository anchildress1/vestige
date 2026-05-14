package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class ChunkProgressBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders all four tick labels`() {
        composeRule.setContent {
            VestigeTheme {
                ChunkProgressBar(progress = 0.5f, modifier = Modifier.size(width = 320.dp, height = 30.dp))
            }
        }
        composeRule.onNodeWithText("0s").assertIsDisplayed()
        composeRule.onNodeWithText("10s").assertIsDisplayed()
        composeRule.onNodeWithText("20s").assertIsDisplayed()
        composeRule.onNodeWithText("30s ▲").assertIsDisplayed()
    }

    @Test
    fun `progress below zero is clamped without crash`() {
        composeRule.setContent {
            VestigeTheme {
                ChunkProgressBar(progress = -0.5f, modifier = Modifier.size(width = 120.dp, height = 24.dp))
            }
        }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `progress above one is clamped without crash`() {
        composeRule.setContent {
            VestigeTheme {
                ChunkProgressBar(progress = 1.7f, modifier = Modifier.size(width = 120.dp, height = 24.dp))
            }
        }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `non-positive chunk duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            composeRule.setContent {
                VestigeTheme {
                    ChunkProgressBar(progress = 0f, chunkDurationSec = 0)
                }
            }
        }
    }

    @Test
    fun `non-positive tick interval is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            composeRule.setContent {
                VestigeTheme {
                    ChunkProgressBar(progress = 0f, tickIntervalSec = -3)
                }
            }
        }
    }
}
