package dev.anchildress1.vestige.ui.atmosphere

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.Glow
import dev.anchildress1.vestige.ui.theme.Vapor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FogDriftComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `fog drift composes with defaults`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                FogDrift()
                Text(text = "fog-default")
            }
        }
        composeRule.onNodeWithText("fog-default").assertIsDisplayed()
    }

    @Test
    fun `fog drift accepts custom hues and intensity`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                FogDrift(intensity = 0.6f, hueA = Vapor, hueB = Glow)
                Text(text = "fog-tuned")
            }
        }
        composeRule.onNodeWithText("fog-tuned").assertIsDisplayed()
    }

    @Test
    fun `fog drift clamps intensity above 1`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                FogDrift(intensity = 99f)
                Text(text = "fog-clamped")
            }
        }
        composeRule.onNodeWithText("fog-clamped").assertIsDisplayed()
    }
}
