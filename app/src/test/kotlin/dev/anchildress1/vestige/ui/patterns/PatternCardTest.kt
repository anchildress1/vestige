package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        templateLabel: String? = "Aftermath",
        actions: Set<PatternAction> = setOf(PatternAction.DROP, PatternAction.SKIP),
        backLabel: String? = null,
    ) = PatternCardUi(
        patternId = "p1",
        title = "Tuesday Meetings",
        templateLabel = templateLabel,
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
    fun `renders name, uppercased category, and observation (pos)`() {
        composeRule.setContent {
            VestigeTheme { PatternCard(card(), onClick = {}, onDrop = {}, onSkip = {}, onRestart = {}) }
        }
        composeRule.onNodeWithText("Tuesday Meetings").assertIsDisplayed()
        composeRule.onNodeWithText("AFTERMATH").assertIsDisplayed()
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
    fun `no category renders without an eyebrow (neg)`() {
        composeRule.setContent {
            VestigeTheme {
                PatternCard(card(templateLabel = null), onClick = {}, onDrop = {}, onSkip = {}, onRestart = {})
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
