package dev.anchildress1.vestige.ui.capture

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class PatternsPeekCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val peek = CapturePatternsPeek(
        activeCount = 3,
        names = listOf("Tuesday Meetings", "The Email", "\"Tired\" drift"),
        traceHits = setOf(2, 9, 17, 28),
    )

    @Test
    fun `renders the count eyebrow and the name teaser (pos)`() {
        composeRule.setContent { VestigeTheme { PatternsPeekCard(peek = peek) } }
        composeRule.onNodeWithText("● 3 ACTIVE PATTERNS").assertIsDisplayed()
        composeRule.onNodeWithText("Tuesday Meetings", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("The Email", substring = true).assertIsDisplayed()
    }

    @Test
    fun `is an informational band — merged description, polite liveRegion, no click (a11y)`() {
        composeRule.setContent { VestigeTheme { PatternsPeekCard(peek = peek) } }
        val band = composeRule.onNodeWithContentDescription("● 3 ACTIVE PATTERNS", substring = true)
        band.assertHasNoClickAction()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `single active pattern renders without a separator (edge)`() {
        composeRule.setContent {
            VestigeTheme {
                PatternsPeekCard(peek = CapturePatternsPeek(1, listOf("Solo"), emptySet()))
            }
        }
        composeRule.onNodeWithText("● 1 ACTIVE PATTERNS").assertIsDisplayed()
        composeRule.onNodeWithText("Solo").assertIsDisplayed()
    }
}
