package dev.anchildress1.vestige.ui.modelstatus

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class ModelStatusScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun screen(
        readiness: ModelReadiness = ModelReadiness.Ready,
        onReDownload: () -> Unit = {},
        onDelete: () -> Unit = {},
        onExit: () -> Unit = {},
    ) {
        composeRule.setContent {
            VestigeTheme {
                ModelStatusScreen(
                    info = ModelStatusInfo(
                        readiness = readiness,
                        sizeLabel = "3.66 GB",
                        versionName = "1.0.0",
                    ),
                    onReDownload = onReDownload,
                    onDelete = onDelete,
                    onExit = onExit,
                )
            }
        }
    }

    @Test
    fun `renders header, eyebrow and the on-device detail line`() {
        screen()
        composeRule.onNodeWithText("Model status.").assertIsDisplayed()
        composeRule.onNodeWithText("MODEL STATUS").assertIsDisplayed()
        composeRule.onNodeWithText("Gemma 4 E4B · 3.66 GB · v1.0.0 · On-device").assertIsDisplayed()
    }

    @Test
    fun `status line is a polite live region with no click action (a11y)`() {
        screen(readiness = ModelReadiness.Ready)
        val status = composeRule.onNodeWithContentDescription("Model ready. Running locally.")
        status.assertIsDisplayed()
        status.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        status.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `status line renders Loading copy`() {
        screen(readiness = ModelReadiness.Loading)
        composeRule.onNodeWithText("Loading model.").assertIsDisplayed()
    }

    @Test
    fun `status line renders Downloading copy with the percent`() {
        screen(readiness = ModelReadiness.Downloading(percent = 50))
        composeRule.onNodeWithText("Downloading model. Wi-Fi only.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("50%", substring = true).assertIsDisplayed()
    }

    @Test
    fun `status line renders Paused as stalled copy`() {
        screen(readiness = ModelReadiness.Paused)
        composeRule.onNodeWithText("Download stalled.").assertIsDisplayed()
    }

    @Test
    fun `re-download confirm fires the callback only on confirm`() {
        var redownloaded = 0
        screen(onReDownload = { redownloaded++ })
        composeRule.onNodeWithText("Re-download model").performClick()
        composeRule.onNodeWithText("Re-download model?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "~3.7 GB on Wi-Fi. The model file is replaced. Your entries are not touched.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Re-download").assertHasClickAction().performClick()
        assertEquals(1, redownloaded)
        composeRule.onAllNodesWithText("Re-download model?").assertCountEquals(0)
    }

    @Test
    fun `re-download cancel dismisses without invoking the callback`() {
        var redownloaded = 0
        screen(onReDownload = { redownloaded++ })
        composeRule.onNodeWithText("Re-download model").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, redownloaded)
        composeRule.onAllNodesWithText("Re-download model?").assertCountEquals(0)
    }

    @Test
    fun `delete confirm fires the destructive callback on confirm`() {
        var deleted = 0
        screen(onDelete = { deleted++ })
        // Only the screen action exists before the dialog opens — unambiguous tap.
        composeRule.onNodeWithText("Delete model").performClick()
        composeRule.onNodeWithText("Delete model file?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The app won't work until you re-download. Your entries stay where they are.",
        ).assertIsDisplayed()
        // Two "Delete model" nodes now: the screen action (main root) and the dialog confirm
        // (dialog root, composed after). The confirm is last; tapping it fires onDelete.
        composeRule.onAllNodesWithText("Delete model").assertCountEquals(2)
        composeRule.onAllNodesWithText("Delete model").onLast().performClick()
        assertEquals(1, deleted)
    }
}
