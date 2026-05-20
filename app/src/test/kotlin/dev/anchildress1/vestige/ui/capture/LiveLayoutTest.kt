package dev.anchildress1.vestige.ui.capture

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
// Pinned viewport: the Stop/Discard buttons are bottom-anchored with a navbar inset, so
// assertIsDisplayed() needs a deterministic device size, not Robolectric's default.
@Config(sdk = [34], qualifiers = "w360dp-h800dp", manifest = Config.NONE, application = android.app.Application::class)
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
    fun `timer renders mm colon ss once in the hero`() {
        composeRule.setContent { VestigeTheme { liveLayout(elapsedMs = 15_000L) } }
        // The duplicate AppTop timer pill was removed (design-guidelines.md §AppTop pill / the
        // recording comp) — the hero display is the single timer source.
        composeRule.onAllNodesWithText("00:15").assertCountEquals(1)
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
        composeRule.onNodeWithContentDescription(CaptureCopy.REC_LABEL_RECORDING)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, stopTaps) }
    }

    @Test
    fun `DISCARD button fires onDiscardTap`() {
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
    fun `recording layout omits route-changing chrome`() {
        composeRule.setContent { VestigeTheme { liveLayout() } }

        composeRule.onAllNodesWithContentDescription(label = "Menu", substring = true)
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("CAPTURE").assertCountEquals(0)
        composeRule.onAllNodesWithText("PATTERNS").assertCountEquals(0)
        composeRule.onAllNodesWithText("HISTORY").assertCountEquals(0)
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
