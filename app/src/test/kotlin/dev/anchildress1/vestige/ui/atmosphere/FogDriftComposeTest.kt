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
import dev.anchildress1.vestige.ui.theme.S2
import dev.anchildress1.vestige.ui.theme.Vapor
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose-host integration for [FogDrift] (does the composable mount cleanly across the input
 * matrix). Pixel-level pos/neg/err/edge coverage on the draw body lives in
 * `FogDriftDrawScopeTest`.
 */
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
    fun `fog drift accepts custom hues at mid intensity`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                FogDrift(intensity = 0.6f, hueA = Glow, hueB = Vapor)
                Text(text = "fog-tuned")
            }
        }
        composeRule.onNodeWithText("fog-tuned").assertIsDisplayed()
    }

    @Test
    fun `fog drift accepts neutral S2 hues`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                FogDrift(intensity = 0.3f, hueA = S2, hueB = S2)
                Text(text = "fog-neutral")
            }
        }
        composeRule.onNodeWithText("fog-neutral").assertIsDisplayed()
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

    @Test
    fun `fog drift clamps negative intensity`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                FogDrift(intensity = -5f)
                Text(text = "fog-neg")
            }
        }
        composeRule.onNodeWithText("fog-neg").assertIsDisplayed()
    }

    @Test
    fun `fog drift handles zero intensity`() {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                FogDrift(intensity = 0f)
                Text(text = "fog-zero")
            }
        }
        composeRule.onNodeWithText("fog-zero").assertIsDisplayed()
    }

    @Test
    fun `noise grain cache reuses the same brush for the same seed`() {
        assertSame(sharedNoiseBrush(NOISE_DEFAULT_SEED), sharedNoiseBrush(NOISE_DEFAULT_SEED))
    }

    @Test
    fun `noise grain cache mints distinct brushes for distinct seeds`() {
        val a = sharedNoiseBrush(seed = 1)
        val b = sharedNoiseBrush(seed = 2)
        assertTrue("expected distinct brushes per seed", a !== b)
    }
}
