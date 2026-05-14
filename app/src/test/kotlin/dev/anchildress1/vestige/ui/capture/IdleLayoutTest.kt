package dev.anchildress1.vestige.ui.capture

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    fun `renders date strip headline content`() {
        composeRule.setContent { VestigeTheme { idleLayout() } }
        composeRule.onNodeWithText("NOW · THU MAY 8").assertIsDisplayed()
        composeRule.onNodeWithText("12", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("STREAK").assertIsDisplayed()
    }

    @Test
    fun `renders the four stat labels`() {
        composeRule.setContent { VestigeTheme { idleLayout() } }
        composeRule.onNodeWithText("KEPT").assertIsDisplayed()
        composeRule.onNodeWithText("ACTIVE").assertIsDisplayed()
        composeRule.onNodeWithText("HITS/MO").assertIsDisplayed()
        composeRule.onNodeWithText("CLOUD").assertIsDisplayed()
    }

    @Test
    fun `renders hero question text`() {
        composeRule.setContent { VestigeTheme { idleLayout() } }
        composeRule.onNodeWithText("WHAT JUST HAPPENED?", substring = true).assertIsDisplayed()
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
    fun `REC button is disabled when model is not ready`() {
        composeRule.setContent {
            VestigeTheme {
                idleLayout(readiness = ModelReadiness.Loading)
            }
        }
        composeRule.onNodeWithContentDescription(CaptureCopy.REC_LABEL_IDLE)
            .assertIsNotEnabled()
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

    @androidx.compose.runtime.Composable
    private fun idleLayout(
        persona: Persona = Persona.WITNESS,
        readiness: ModelReadiness = ModelReadiness.Ready,
        onRecTap: () -> Unit = {},
        onTypeTap: () -> Unit = {},
    ) {
        IdleLayout(
            state = CaptureUiState.Idle(persona = persona, modelReadiness = readiness),
            stats = CaptureStats(kept = 31, active = 3, hitsThisMonth = 47, cloud = 0),
            meta = CaptureMeta(
                weekdayLabel = "THU",
                monthDayLabel = "MAY 8",
                timeLabel = "09:41",
                dayNumber = 134,
                streakDays = 12,
            ),
            onRecTap = onRecTap,
            onTypeTap = onTypeTap,
        )
    }
}
