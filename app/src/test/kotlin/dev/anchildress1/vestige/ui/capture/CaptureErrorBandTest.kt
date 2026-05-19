package dev.anchildress1.vestige.ui.capture

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The band is mic/inference only. Model not-ready (deleted / loading / downloading / paused) is
 * no longer a banner — IdleLayout swaps the REC button for a spinner instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class CaptureErrorBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ─── resolver (pos / neg / err) ─────────────────────────────────────────

    @Test
    fun `resolver returns null when there is no error (neg)`() {
        assertNull(resolveBandKind(error = null))
    }

    @Test
    fun `resolver maps MicDenied (err)`() {
        val kind = resolveBandKind(error = CaptureError.MicDenied)
        assertEquals(BandKind.MicDenied, kind)
        assertTrue(kind!!.isError)
    }

    @Test
    fun `resolver maps MicUnavailable (err)`() {
        assertEquals(BandKind.MicUnavailable, resolveBandKind(error = CaptureError.MicUnavailable))
    }

    @Test
    fun `resolver maps MicBlocked (err)`() {
        assertEquals(BandKind.MicBlocked, resolveBandKind(error = CaptureError.MicBlocked))
    }

    @Test
    fun `resolver maps every InferenceFailed reason (err)`() {
        CaptureError.InferenceFailed.Reason.entries.forEach { reason ->
            assertEquals(
                BandKind.Inference(reason),
                resolveBandKind(error = CaptureError.InferenceFailed(reason)),
            )
        }
    }

    // ─── render (pos / neg / a11y) ──────────────────────────────────────────

    @Test
    fun `renders nothing when there is no error (neg)`() {
        composeRule.setContent { VestigeTheme { CaptureErrorBand(error = null) } }
        composeRule.onAllNodesWithText(CaptureCopy.MIC_DENIED_LINE).assertCountEquals(0)
    }

    @Test
    fun `renders MicDenied copy + a11y (pos, err)`() {
        composeRule.setContent { VestigeTheme { CaptureErrorBand(error = CaptureError.MicDenied) } }
        composeRule.onNodeWithText(CaptureCopy.BAND_LABEL_MIC).assertIsDisplayed()
        composeRule.onNodeWithText(CaptureCopy.MIC_DENIED_LINE).assertIsDisplayed()
        val band = composeRule.onNodeWithContentDescription("Mic permission denied.", substring = true)
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `renders MicUnavailable copy (pos, err)`() {
        composeRule.setContent { VestigeTheme { CaptureErrorBand(error = CaptureError.MicUnavailable) } }
        composeRule.onNodeWithText(CaptureCopy.MIC_UNAVAILABLE_LINE).assertIsDisplayed()
        val band = composeRule.onNodeWithContentDescription("Mic unavailable.", substring = true)
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `renders InferenceFailed PARSE_FAILED copy (err)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(
                    error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
                )
            }
        }
        composeRule.onNodeWithText(CaptureCopy.INFERENCE_PARSE_FAILED_LINE).assertIsDisplayed()
        composeRule.onNodeWithText(CaptureCopy.BAND_LABEL_MODEL).assertIsDisplayed()
    }

    @Test
    fun `renders InferenceFailed TIMED_OUT copy (err)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(
                    error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.TIMED_OUT),
                )
            }
        }
        composeRule.onNodeWithText(CaptureCopy.INFERENCE_TIMED_OUT_LINE).assertIsDisplayed()
    }

    @Test
    fun `renders InferenceFailed ENGINE_FAILED copy (err)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(
                    error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.ENGINE_FAILED),
                )
            }
        }
        composeRule.onNodeWithText(CaptureCopy.INFERENCE_ENGINE_FAILED_LINE).assertIsDisplayed()
    }

    @Test
    fun `MicBlocked band is a polite no-click status region with a separate Use-typed button`() {
        var usedTyped = false
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = CaptureError.MicBlocked, onUseTyped = { usedTyped = true })
            }
        }
        val band = composeRule.onNodeWithContentDescription(
            "${CaptureCopy.MIC_BLOCKED_LINE} ${CaptureCopy.MIC_BLOCKED_SETTINGS_LINE}",
        )
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        val useTyped = composeRule.onNodeWithContentDescription(CaptureCopy.USE_TYPED_INSTEAD)
        useTyped.assertHasClickAction()
        useTyped.performClick()
        assertEquals(true, usedTyped)
    }

    @Test
    fun `MicBlocked omits the Use-typed affordance when no callback is wired`() {
        composeRule.setContent { VestigeTheme { CaptureErrorBand(error = CaptureError.MicBlocked) } }
        composeRule.onAllNodesWithText(CaptureCopy.USE_TYPED_INSTEAD).assertCountEquals(0)
    }

    @Test
    fun `Inference band is a polite no-click status region (a11y)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(
                    error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.ENGINE_FAILED),
                )
            }
        }
        val band = composeRule.onNodeWithContentDescription("Last reading failed.", substring = true)
        band.assertIsDisplayed()
        band.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        band.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
