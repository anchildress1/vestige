package dev.anchildress1.vestige.ui.capture

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class LiveLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders recording eyebrow and live level eyebrow`() {
        composeRule.setContent { VestigeTheme { liveLayout(elapsedMs = 15_000L) } }
        composeRule.onNodeWithText(CaptureCopy.LIVE_RECORDING_EYEBROW).assertIsDisplayed()
        composeRule.onNodeWithText(CaptureCopy.LIVE_LEVEL_EYEBROW).assertIsDisplayed()
    }

    @Test
    fun `timer renders mm colon ss for the elapsed value in chrome and hero`() {
        composeRule.setContent { VestigeTheme { liveLayout(elapsedMs = 15_000L) } }
        // The timer label appears twice: in the top-right pill and in the big hero timer.
        composeRule.onAllNodesWithText("00:15").assertCountEquals(2)
    }

    @Test
    fun `remain block displays seconds remaining`() {
        composeRule.setContent { VestigeTheme { liveLayout(elapsedMs = 18_000L) } }
        // 30s cap - 18s elapsed = 12s remain.
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText(CaptureCopy.LIVE_SECONDS_LABEL).assertIsDisplayed()
    }

    @Test
    fun `remain block clamps to zero at the cap`() {
        composeRule.setContent { VestigeTheme { liveLayout(elapsedMs = 32_000L) } }
        composeRule.onNodeWithText("0").assertIsDisplayed()
    }

    @Test
    fun `STOP FILE IT button is announced as Stop and fires on tap`() {
        var stopTaps = 0
        composeRule.setContent {
            VestigeTheme { liveLayout(onStopTap = { stopTaps += 1 }) }
        }
        // Bottom-anchored buttons can fall outside the unit-test viewport; invoke the click
        // semantic action directly so the assertion does not depend on a physical hit-test.
        composeRule.onNodeWithContentDescription(CaptureCopy.REC_LABEL_RECORDING)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, stopTaps) }
    }

    @Test
    fun `DISCARD NO SAVE link fires onDiscardTap`() {
        var discards = 0
        composeRule.setContent {
            VestigeTheme { liveLayout(onDiscardTap = { discards += 1 }) }
        }
        composeRule.onNodeWithContentDescription(CaptureCopy.LIVE_DISCARD_SECONDARY)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, discards) }
    }

    @Test
    fun `WORD COUNT card renders`() {
        composeRule.setContent { VestigeTheme { liveLayout(elapsedMs = 10_000L) } }
        composeRule.onNodeWithText(CaptureCopy.LIVE_WORD_COUNT_LABEL).assertIsDisplayed()
        // 10 s * 2.3 words/sec = 23 words.
        composeRule.onNodeWithText("23").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun liveLayout(
        elapsedMs: Long = 0L,
        persona: Persona = Persona.WITNESS,
        readiness: ModelReadiness = ModelReadiness.Ready,
        onStopTap: () -> Unit = {},
        onDiscardTap: () -> Unit = {},
    ) {
        LiveLayout(
            state = CaptureUiState.Recording(
                persona = persona,
                modelReadiness = readiness,
                elapsedMs = elapsedMs,
                recentLevels = List(42) { 0.3f },
            ),
            onStopTap = onStopTap,
            onDiscardTap = onDiscardTap,
        )
    }
}
