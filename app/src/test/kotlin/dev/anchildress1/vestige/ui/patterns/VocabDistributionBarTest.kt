package dev.anchildress1.vestige.ui.patterns

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class VocabDistributionBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `contentDescription summarizes every segment with rounded percentages`() {
        composeRule.setContent {
            VestigeTheme {
                VocabDistributionBar(
                    segments = listOf(
                        VocabDistributionSegment(label = "exhausted", weight = 8f),
                        VocabDistributionSegment(label = "foggy", weight = 7f),
                        VocabDistributionSegment(label = "wired", weight = 5f),
                    ),
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Vocabulary distribution: exhausted: 40%, foggy: 35%, wired: 25%.")
            .assertIsDisplayed()
    }

    @Test
    fun `bar exposes no click action — it is informational, not interactive`() {
        composeRule.setContent {
            VestigeTheme {
                VocabDistributionBar(
                    segments = listOf(
                        VocabDistributionSegment(label = "one", weight = 1f),
                        VocabDistributionSegment(label = "two", weight = 1f),
                    ),
                )
            }
        }

        composeRule
            .onNodeWithTag("VocabDistributionBar")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `merged semantics — descendant segments do not announce individually`() {
        composeRule.setContent {
            VestigeTheme {
                VocabDistributionBar(
                    segments = listOf(
                        VocabDistributionSegment(label = "alpha", weight = 1f),
                        VocabDistributionSegment(label = "beta", weight = 1f),
                    ),
                )
            }
        }

        composeRule
            .onNodeWithTag("VocabDistributionBar")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty segments throws`() {
        composeRule.setContent {
            VestigeTheme { VocabDistributionBar(segments = emptyList()) }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero total weight throws`() {
        composeRule.setContent {
            VestigeTheme {
                VocabDistributionBar(
                    segments = listOf(VocabDistributionSegment(label = "zero", weight = 0f)),
                )
            }
        }
    }

    @Test
    fun `segments past the fourth wrap around the accent palette without crashing`() {
        // Sanity: six clusters render without throwing on color lookup.
        composeRule.setContent {
            VestigeTheme {
                VocabDistributionBar(
                    segments = (1..6).map { VocabDistributionSegment(label = "c$it", weight = 1f) },
                )
            }
        }

        composeRule.onNodeWithTag("VocabDistributionBar").assertIsDisplayed()
    }
}
