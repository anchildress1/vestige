package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class AppTopModelStatusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun Probe(readiness: ModelReadiness) {
        val status = appTopStatusFor(readiness)
        Column {
            Text(text = status.text)
            Text(text = status.contentDescription)
        }
    }

    @Test
    fun `Ready maps to the local-only pill (pos)`() {
        composeRule.setContent { VestigeTheme { Probe(ModelReadiness.Ready) } }
        composeRule.onNodeWithText("GEMMA 4 · LOCAL ONLY").assertIsDisplayed()
    }

    @Test
    fun `Loading maps to the reconciled loading label (pos)`() {
        composeRule.setContent { VestigeTheme { Probe(ModelReadiness.Loading) } }
        composeRule.onNodeWithText("GEMMA 4 · LOADING").assertIsDisplayed()
        composeRule.onNodeWithText("Gemma 4 local model. Loading.").assertIsDisplayed()
    }

    @Test
    fun `Downloading threads the percent into the pill and a11y (edge)`() {
        composeRule.setContent { VestigeTheme { Probe(ModelReadiness.Downloading(percent = 42)) } }
        composeRule.onNodeWithText("DOWNLOADING · 42%").assertIsDisplayed()
        composeRule.onNodeWithText("Gemma 4 local model downloading. 42 percent.").assertIsDisplayed()
    }

    @Test
    fun `Paused maps to the paused pill (pos)`() {
        composeRule.setContent { VestigeTheme { Probe(ModelReadiness.Paused) } }
        composeRule.onNodeWithText("MODEL PAUSED").assertIsDisplayed()
    }
}
