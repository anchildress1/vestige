package dev.anchildress1.vestige.ui.capture

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class LiveLevelBarsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the full bar window`() {
        val levels = (0 until 42).map { it / 42f }
        composeRule.setContent {
            VestigeTheme {
                LiveLevelBars(levels = levels, modifier = Modifier.size(width = 320.dp, height = 80.dp))
            }
        }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `empty levels list still composes`() {
        composeRule.setContent {
            VestigeTheme {
                LiveLevelBars(levels = emptyList(), modifier = Modifier.size(120.dp))
            }
        }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `out-of-range levels are tolerated`() {
        composeRule.setContent {
            VestigeTheme {
                LiveLevelBars(
                    levels = listOf(-0.2f, 0.5f, 1.7f, 0f, 0.9f),
                    modifier = Modifier.size(120.dp),
                )
            }
        }
        composeRule.onRoot().assertIsDisplayed()
    }
}
