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
import androidx.compose.ui.test.performClick
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
class IdleLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the empty-state line when there is no peek`() {
        composeRule.setContent { VestigeTheme { idleLayout() } }
        composeRule.onNodeWithText(CaptureCopy.NO_ENTRIES_YET).assertExists()
    }

    @Test
    fun `renders the patterns peek instead of the empty line when active`() {
        composeRule.setContent {
            VestigeTheme {
                idleLayout(
                    chrome = IdleChromeCallbacks(
                        patternsPeek = CapturePatternsPeek(2, listOf("Tuesday Meetings", "The Email"), emptySet()),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("● 2 ACTIVE PATTERNS").assertExists()
        composeRule.onAllNodesWithText(CaptureCopy.NO_ENTRIES_YET).assertCountEquals(0)
    }

    @Test
    fun `renders hero question text`() {
        composeRule.setContent { VestigeTheme { idleLayout() } }
        composeRule.onNodeWithText("WHAT HAPPENED?", substring = true).assertIsDisplayed()
    }

    @Test
    fun `REC button is announced and clickable when model is ready`() {
        var recTaps = 0
        composeRule.setContent {
            VestigeTheme { idleLayout(onRecTap = { recTaps += 1 }) }
        }
        composeRule.onNodeWithContentDescription(CaptureCopy.REC_LABEL_IDLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, recTaps) }
    }

    @Test
    fun `REC button is replaced by a spinner when model is not ready`() {
        composeRule.setContent {
            VestigeTheme {
                idleLayout(readiness = ModelReadiness.Loading)
            }
        }
        // No REC button (and no diagnostic banner) — a spinner stands in its place.
        composeRule.onAllNodesWithContentDescription(CaptureCopy.REC_LABEL_IDLE).assertCountEquals(0)
    }

    @Test
    fun `Or-type button is announced and fires onTypeTap`() {
        var typeTaps = 0
        composeRule.setContent {
            VestigeTheme { idleLayout(onTypeTap = { typeTaps += 1 }) }
        }
        composeRule.onNodeWithContentDescription(CaptureCopy.OR_TYPE)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, typeTaps) }
    }

    @Test
    fun `error band renders when Idle carries an inference error`() {
        composeRule.setContent {
            VestigeTheme {
                idleLayout(
                    error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
                )
            }
        }
        composeRule.onNodeWithText(CaptureCopy.INFERENCE_PARSE_FAILED_LINE).assertIsDisplayed()
    }

    @Test
    fun `error band is absent when Ready and no error`() {
        composeRule.setContent { VestigeTheme { idleLayout() } }
        composeRule.onAllNodesWithText(CaptureCopy.MIC_DENIED_LINE).assertCountEquals(0)
    }

    @Test
    fun `bottom nav patterns tab is always present`() {
        composeRule.setContent { VestigeTheme { idleLayout() } }
        composeRule.onNodeWithText("PATTERNS").assertExists()
    }

    @Test
    fun `bottom nav PATTERNS tab fires onPatternsTap`() {
        var patternsTaps = 0
        composeRule.setContent {
            VestigeTheme {
                idleLayout(chrome = IdleChromeCallbacks(onPatternsTap = { patternsTaps += 1 }))
            }
        }
        // The tab sits past a Spacer(weight=1f); assertExists + semantics-action click avoids
        // Robolectric's headless-viewport clipping.
        composeRule.onNodeWithText("PATTERNS")
            .assertExists()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, patternsTaps) }
    }

    @androidx.compose.runtime.Composable
    @Suppress("LongParameterList")
    private fun idleLayout(
        persona: Persona = Persona.WITNESS,
        readiness: ModelReadiness = ModelReadiness.Ready,
        error: CaptureError? = null,
        onRecTap: () -> Unit = {},
        onTypeTap: () -> Unit = {},
        chrome: IdleChromeCallbacks = IdleChromeCallbacks(),
    ) {
        IdleLayout(
            state = CaptureUiState.Idle(persona = persona, modelReadiness = readiness, error = error),
            onRecTap = onRecTap,
            onTypeTap = onTypeTap,
            chrome = chrome,
        )
    }
}
