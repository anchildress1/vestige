package dev.anchildress1.vestige.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VestigeMotionComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pulse helper composes`() {
        composeRule.setContent {
            val fraction by rememberVesPulse(periodMs = 1_400)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "p${fraction.coerceIn(0f, 1f) >= 0f}")
            }
        }
        composeRule.onNodeWithText("ptrue").assertIsDisplayed()
    }

    @Test
    fun `breath helper composes`() {
        composeRule.setContent {
            val fraction by rememberVesBreath(periodMs = 6_000)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "b${fraction.coerceIn(0f, 1f) >= 0f}")
            }
        }
        composeRule.onNodeWithText("btrue").assertIsDisplayed()
    }

    @Test
    fun `shimmer helper composes`() {
        composeRule.setContent {
            val fraction by rememberVesShimmer(periodMs = 1_800)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "s${fraction.coerceIn(0f, 1f) >= 0f}")
            }
        }
        composeRule.onNodeWithText("strue").assertIsDisplayed()
    }

    @Test
    fun `spin helper composes`() {
        composeRule.setContent {
            val degrees by rememberVesSpin(periodMs = 16_000)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "rot${degrees >= 0f}")
            }
        }
        composeRule.onNodeWithText("rottrue").assertIsDisplayed()
    }
}
