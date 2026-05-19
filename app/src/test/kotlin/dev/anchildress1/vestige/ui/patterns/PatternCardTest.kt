package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class PatternCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun card(
        section: PatternSection = PatternSection.ACTIVE,
        actions: Set<PatternAction> = setOf(PatternAction.DROP, PatternAction.SKIP),
        backLabel: String? = null,
    ) = PatternCardUi(
        patternId = "p1",
        kindLabel = "TEMPLATE RECURRENCE",
        title = "Tuesday Meetings",
        observation = "Fourth entry mentions Tuesday meetings.",
        supportingCount = 4,
        totalEntryCount = 12,
        lastSeenLabel = "May 7",
        section = section,
        traceHits = setOf(3, 10, 17, 24),
        availableActions = actions,
        backLabel = backLabel,
    )

    @Test
    fun `renders name and observation without archetype label (pos neg)`() {
        composeRule.setContent {
            VestigeTheme { PatternCard(card(), onClick = {}, onDrop = {}, onSkip = {}, onRestart = {}) }
        }
        composeRule.onNodeWithText("TEMPLATE RECURRENCE").assertIsDisplayed()
        composeRule.onNodeWithText("Tuesday Meetings").assertIsDisplayed()
        composeRule.onAllNodesWithText("AFTERMATH").assertCountEquals(0)
        composeRule.onNodeWithText("Fourth entry mentions Tuesday meetings.").assertIsDisplayed()
    }

    @Test
    fun `card is a button announcing title and observation (a11y)`() {
        composeRule.setContent {
            VestigeTheme { PatternCard(card(), onClick = {}, onDrop = {}, onSkip = {}, onRestart = {}) }
        }
        composeRule.onNodeWithContentDescription("Tuesday Meetings. Fourth entry mentions Tuesday meetings.")
            .assertHasClickAction()
    }

    @Test
    fun `card click fires onClick (pos)`() {
        var clicks = 0
        composeRule.setContent {
            VestigeTheme { PatternCard(card(), onClick = { clicks++ }, onDrop = {}, onSkip = {}, onRestart = {}) }
        }
        composeRule.onNodeWithContentDescription("Tuesday Meetings. Fourth entry mentions Tuesday meetings.")
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `title still renders when no archetype label exists (edge)`() {
        composeRule.setContent {
            VestigeTheme {
                PatternCard(card(), onClick = {}, onDrop = {}, onSkip = {}, onRestart = {})
            }
        }
        composeRule.onNodeWithText("Tuesday Meetings").assertIsDisplayed()
    }

    @Test
    fun `no available actions hides the overflow menu (edge)`() {
        composeRule.setContent {
            VestigeTheme {
                PatternCard(card(actions = emptySet()), onClick = {}, onDrop = {}, onSkip = {}, onRestart = {})
            }
        }
        composeRule.onNodeWithText("Tuesday Meetings").assertIsDisplayed()
    }
}
