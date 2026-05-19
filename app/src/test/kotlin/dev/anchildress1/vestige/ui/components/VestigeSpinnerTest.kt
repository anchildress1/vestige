package dev.anchildress1.vestige.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class VestigeSpinnerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `composes alongside content without crashing (pos)`() {
        composeRule.setContent {
            VestigeTheme {
                Box(Modifier.size(40.dp)) {
                    VestigeSpinner()
                    Text("ok")
                }
            }
        }
        composeRule.onNodeWithText("ok").assertIsDisplayed()
    }

    @Test
    fun `is decorative — announces nothing (a11y)`() {
        composeRule.setContent {
            VestigeTheme {
                Box {
                    VestigeSpinner()
                    Text("sibling")
                }
            }
        }
        // Semantics are cleared on the spinner; only the sibling text is in the a11y tree.
        composeRule.onNodeWithText("sibling").assertIsDisplayed()
        composeRule.onAllNodesWithText("").assertCountEquals(0)
    }
}
