package dev.anchildress1.vestige.ui.capture

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class TypeEntrySheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `typed entry field exposes explicit a11y label`() {
        composeRule.setContent {
            VestigeTheme {
                TypeEntrySheet(onDismiss = {}, onSubmit = {})
            }
        }

        composeRule.onNodeWithContentDescription(CaptureCopy.TYPE_FIELD_LABEL)
            .assertIsDisplayed()
            .performTextInput("typed fallback works")
        composeRule.onNodeWithText("typed fallback works").assertIsDisplayed()
    }
}
