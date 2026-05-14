package dev.anchildress1.vestige.ui.capture

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class CaptureErrorBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ─── resolver (pos / neg / edge / err) ──────────────────────────────────

    @Test
    fun `resolver returns null when Ready and no error (neg)`() {
        assertNull(resolveBandKind(error = null, readiness = ModelReadiness.Ready))
    }

    @Test
    fun `resolver maps MicDenied (err)`() {
        val kind = resolveBandKind(error = CaptureError.MicDenied, readiness = ModelReadiness.Ready)
        assertEquals(BandKind.MicDenied, kind)
        assertTrue(kind!!.isError)
    }

    @Test
    fun `resolver maps MicUnavailable (err)`() {
        val kind = resolveBandKind(error = CaptureError.MicUnavailable, readiness = ModelReadiness.Ready)
        assertEquals(BandKind.MicUnavailable, kind)
    }

    @Test
    fun `resolver maps InferenceFailed PARSE_FAILED (err)`() {
        val kind = resolveBandKind(
            error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
            readiness = ModelReadiness.Ready,
        )
        assertEquals(BandKind.Inference(CaptureError.InferenceFailed.Reason.PARSE_FAILED), kind)
    }

    @Test
    fun `resolver maps InferenceFailed TIMED_OUT (err)`() {
        val kind = resolveBandKind(
            error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.TIMED_OUT),
            readiness = ModelReadiness.Ready,
        )
        assertEquals(BandKind.Inference(CaptureError.InferenceFailed.Reason.TIMED_OUT), kind)
    }

    @Test
    fun `resolver maps InferenceFailed ENGINE_FAILED (err)`() {
        val kind = resolveBandKind(
            error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.ENGINE_FAILED),
            readiness = ModelReadiness.Ready,
        )
        assertEquals(BandKind.Inference(CaptureError.InferenceFailed.Reason.ENGINE_FAILED), kind)
    }

    @Test
    fun `resolver maps readiness states when no error (pos)`() {
        assertEquals(BandKind.ModelLoading, resolveBandKind(error = null, readiness = ModelReadiness.Loading))
        assertEquals(BandKind.ModelPaused, resolveBandKind(error = null, readiness = ModelReadiness.Paused))
        assertEquals(
            BandKind.ModelDownloading(percent = 42),
            resolveBandKind(error = null, readiness = ModelReadiness.Downloading(percent = 42)),
        )
    }

    @Test
    fun `error wins over non-Ready readiness (edge — both set)`() {
        val kind = resolveBandKind(
            error = CaptureError.MicDenied,
            readiness = ModelReadiness.Loading,
        )
        assertEquals(BandKind.MicDenied, kind)
    }

    // ─── render (pos / neg / a11y) ──────────────────────────────────────────

    @Test
    fun `renders nothing when Ready and no error (neg)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = null, readiness = ModelReadiness.Ready)
            }
        }
        composeRule.onAllNodesWithText(CaptureCopy.MIC_DENIED_LINE).assertCountEquals(0)
        composeRule.onAllNodesWithText(CaptureCopy.MODEL_LOADING_LINE).assertCountEquals(0)
    }

    @Test
    fun `renders MicDenied copy + a11y (pos, err)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = CaptureError.MicDenied, readiness = ModelReadiness.Ready)
            }
        }
        composeRule.onNodeWithText(CaptureCopy.BAND_LABEL_MIC).assertIsDisplayed()
        composeRule.onNodeWithText(CaptureCopy.MIC_DENIED_LINE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mic permission denied.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `renders MicUnavailable copy (pos, err)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = CaptureError.MicUnavailable, readiness = ModelReadiness.Ready)
            }
        }
        composeRule.onNodeWithText(CaptureCopy.MIC_UNAVAILABLE_LINE).assertIsDisplayed()
    }

    @Test
    fun `renders InferenceFailed PARSE_FAILED copy (err)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(
                    error = CaptureError.InferenceFailed(CaptureError.InferenceFailed.Reason.PARSE_FAILED),
                    readiness = ModelReadiness.Ready,
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
                    readiness = ModelReadiness.Ready,
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
                    readiness = ModelReadiness.Ready,
                )
            }
        }
        composeRule.onNodeWithText(CaptureCopy.INFERENCE_ENGINE_FAILED_LINE).assertIsDisplayed()
    }

    @Test
    fun `renders ModelLoading informational band (pos)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = null, readiness = ModelReadiness.Loading)
            }
        }
        composeRule.onNodeWithText(CaptureCopy.BAND_LABEL_MODEL_LOADING).assertIsDisplayed()
        composeRule.onNodeWithText(CaptureCopy.MODEL_LOADING_LINE).assertIsDisplayed()
    }

    @Test
    fun `renders ModelPaused informational band (pos)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = null, readiness = ModelReadiness.Paused)
            }
        }
        composeRule.onNodeWithText(CaptureCopy.MODEL_PAUSED_LINE).assertIsDisplayed()
    }

    @Test
    fun `renders ModelDownloading percent in eyebrow and body (edge — percent threading)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = null, readiness = ModelReadiness.Downloading(percent = 73))
            }
        }
        composeRule.onNodeWithText("MODEL · 73%").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading model · 73%").assertIsDisplayed()
    }

    @Test
    fun `error band shadows non-Ready readiness in UI (edge — error wins)`() {
        composeRule.setContent {
            VestigeTheme {
                CaptureErrorBand(error = CaptureError.MicDenied, readiness = ModelReadiness.Loading)
            }
        }
        composeRule.onNodeWithText(CaptureCopy.MIC_DENIED_LINE).assertIsDisplayed()
        composeRule.onAllNodesWithText(CaptureCopy.MODEL_LOADING_LINE).assertCountEquals(0)
    }
}
