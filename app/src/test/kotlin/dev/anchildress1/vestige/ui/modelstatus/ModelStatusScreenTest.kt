package dev.anchildress1.vestige.ui.modelstatus

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.components.ModelDownloadProgress
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

    @Suppress("LongParameterList") // Test fixture mirrors the route callbacks.
    private fun screen(
        readiness: ModelReadiness = ModelReadiness.Ready,
        onDiskLabel: String = "3.66 GB",
        downloadProgress: ModelDownloadProgress? = null,
        onReDownload: () -> Unit = {},
        onDelete: () -> Unit = {},
        onPause: () -> Unit = {},
        onExit: () -> Unit = {},
    ) {
        composeRule.setContent {
            VestigeTheme {
                ModelStatusScreen(
                    info = ModelStatusInfo(
                        readiness = readiness,
                        sizeLabel = "3.66 GB",
                        onDiskLabel = onDiskLabel,
                        versionName = "1.0.0",
                        downloadProgress = downloadProgress,
                    ),
                    onReDownload = onReDownload,
                    onDelete = onDelete,
                    onPause = onPause,
                    onExit = onExit,
                )
            }
        }
    }

    @Test
    fun `renders the headline, status band and on-device stack`() {
        screen()
        composeRule.onNodeWithText("MODEL STATUS.").assertIsDisplayed()
        // Band body is a merged status node — assert via contentDescription.
        composeRule.onNodeWithContentDescription("Gemma 4 E4B · 3.66 GB · v1.0.0 · On-device").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "EmbeddingGemma 300M. VECTOR · HYBRID. 210 MB",
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            "LiteRT-LM 0.11.0. RUNTIME. NATIVE",
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            "Allowlist: model artifact host only.",
        ).assertExists()
    }

    @Test
    fun `on-disk stat shows the artifact size, and drops to 0 once deleted`() {
        screen(onDiskLabel = "3.66 GB")
        composeRule.onNodeWithContentDescription("3.66 GB on disk, 0 cloud calls").assertExists()
    }

    @Test
    fun `on-disk stat reads 0 when the model is gone`() {
        screen(readiness = ModelReadiness.Loading, onDiskLabel = "0")
        composeRule.onNodeWithContentDescription("0 on disk, 0 cloud calls").assertExists()
    }

    @Test
    fun `ready status band is a polite live region with no click action (a11y)`() {
        screen(readiness = ModelReadiness.Ready)
        val band = composeRule.onNodeWithContentDescription("Gemma 4 E4B · 3.66 GB · v1.0.0 · On-device")
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `status band renders Loading copy`() {
        screen(readiness = ModelReadiness.Loading)
        composeRule.onNodeWithContentDescription("Loading model.").assertIsDisplayed()
    }

    @Test
    fun `a deleted model surfaces the re-download error, not the loading line`() {
        screen(readiness = ModelReadiness.Loading, onDiskLabel = "0")
        composeRule.onNodeWithContentDescription(
            "Model file unreadable. Re-download from settings.",
        ).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Loading model.").assertCountEquals(0)
    }

    @Test
    fun `status band renders Paused as stalled copy`() {
        screen(readiness = ModelReadiness.Paused)
        composeRule.onNodeWithContentDescription("Download stalled.").assertIsDisplayed()
    }

    @Test
    fun `downloading state shows the shared progress card, pulled ribbon and active gate`() {
        screen(
            readiness = ModelReadiness.Downloading(percent = 38),
            downloadProgress = ModelDownloadProgress(
                fraction = 0.38f,
                currentBytes = 1_491_308_339L,
                expectedBytes = 3_928_180_000L,
                etaSeconds = 258L,
                mbps = 6.4f,
            ),
        )
        composeRule.onNodeWithContentDescription("Downloading model. 38 percent.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1.39 gigabytes pulled, 0 cloud calls").assertExists()
        composeRule.onNodeWithContentDescription(
            "Model artifact host only. Closes the moment the pull completes.",
        ).assertExists()
    }

    @Test
    fun `downloading state replaces re-download and delete with a working PAUSE`() {
        var paused = 0
        screen(
            readiness = ModelReadiness.Downloading(percent = 50),
            downloadProgress = ModelDownloadProgress(
                fraction = 0.5f,
                currentBytes = 1_964_090_000L,
                expectedBytes = 3_928_180_000L,
                etaSeconds = 120L,
                mbps = 8f,
            ),
            onPause = { paused++ },
        )
        composeRule.onAllNodesWithContentDescription("Re-download model").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Delete model").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("PAUSE").performScrollTo().performClick()
        assertEquals(1, paused)
    }

    @Test
    fun `re-download confirm fires the callback only on confirm`() {
        var redownloaded = 0
        screen(onReDownload = { redownloaded++ })
        composeRule.onNodeWithContentDescription("Re-download model").performScrollTo().performClick()
        composeRule.onNodeWithText("RE-DOWNLOAD MODEL?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "~3.7 GB on Wi-Fi. The model file is replaced. Your entries are not touched.",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Re-download").assertHasClickAction().performClick()
        assertEquals(1, redownloaded)
        composeRule.onAllNodesWithText("RE-DOWNLOAD MODEL?").assertCountEquals(0)
    }

    @Test
    fun `re-download cancel dismisses without invoking the callback`() {
        var redownloaded = 0
        screen(onReDownload = { redownloaded++ })
        composeRule.onNodeWithContentDescription("Re-download model").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(0, redownloaded)
        composeRule.onAllNodesWithText("RE-DOWNLOAD MODEL?").assertCountEquals(0)
    }

    @Test
    fun `delete confirm fires the destructive callback on confirm`() {
        var deleted = 0
        screen(onDelete = { deleted++ })
        composeRule.onNodeWithContentDescription("Delete model").performScrollTo().performClick()
        composeRule.onNodeWithText("DELETE MODEL FILE?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The app won't work until you re-download. Your entries stay where they are.",
        ).assertIsDisplayed()
        // Two "Delete model" nodes: the screen action and the dialog confirm (composed after).
        // The confirm is last; tapping it fires onDelete.
        composeRule.onAllNodesWithContentDescription("Delete model").onLast().performClick()
        assertEquals(1, deleted)
    }
}
