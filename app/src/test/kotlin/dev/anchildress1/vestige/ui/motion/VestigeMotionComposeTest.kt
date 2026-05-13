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
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = android.app.Application::class,
)
class VestigeMotionComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pulse helper composes`() {
        composeRule.setContent {
            val fraction by rememberSbPulse(periodMs = VestigeMotion.PULSE_MS)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "p${fraction.coerceIn(0f, 1f) >= 0f}")
            }
        }
        composeRule.onNodeWithText("ptrue").assertIsDisplayed()
    }

    @Test
    fun `blink helper composes`() {
        composeRule.setContent {
            val fraction by rememberSbBlink(periodMs = VestigeMotion.BLINK_MS)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "b${fraction.coerceIn(0f, 1f) >= 0f}")
            }
        }
        composeRule.onNodeWithText("btrue").assertIsDisplayed()
    }

    @Test
    fun `blink helper holds a step waveform across the cycle`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val fraction by rememberSbBlink(periodMs = VestigeMotion.BLINK_MS)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = if (fraction < 0.5f) "off" else "on")
            }
        }

        composeRule.onNodeWithText("off").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy((VestigeMotion.BLINK_MS * 3L) / 4L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("on").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy((VestigeMotion.BLINK_MS / 2).toLong())
        composeRule.waitForIdle()
        composeRule.onNodeWithText("off").assertIsDisplayed()

        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `bars helper composes`() {
        composeRule.setContent {
            val fraction by rememberSbBars(periodMs = VestigeMotion.BARS_MS)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "a${fraction.coerceIn(0f, 1f) >= 0f}")
            }
        }
        composeRule.onNodeWithText("atrue").assertIsDisplayed()
    }

    @Test
    fun `sweep helper composes`() {
        composeRule.setContent {
            val fraction by rememberSbSweep(periodMs = VestigeMotion.SWEEP_MS)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "s${fraction.coerceIn(0f, 1f) >= 0f}")
            }
        }
        composeRule.onNodeWithText("strue").assertIsDisplayed()
    }

    @Test
    fun `wobble helper composes`() {
        composeRule.setContent {
            val fraction by rememberSbWobble(periodMs = VestigeMotion.WOBBLE_MS)
            Box(modifier = Modifier.size(40.dp)) {
                Text(text = "w${fraction in -1f..1f}")
            }
        }
        composeRule.onNodeWithText("wtrue").assertIsDisplayed()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pulse rejects zero period`() {
        composeRule.setContent {
            rememberSbPulse(periodMs = 0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blink rejects negative period`() {
        composeRule.setContent {
            rememberSbBlink(periodMs = -1)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bars rejects zero period`() {
        composeRule.setContent {
            rememberSbBars(periodMs = 0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sweep rejects negative period`() {
        composeRule.setContent {
            rememberSbSweep(periodMs = -2)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wobble rejects zero period`() {
        composeRule.setContent {
            rememberSbWobble(periodMs = 0)
        }
    }
}
