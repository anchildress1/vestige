package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var prefs: OnboardingPrefs

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(OnboardingPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = OnboardingPrefs.from(ctx)
    }

    @After
    fun tearDown() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(OnboardingPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `lands on persona pick with Witness default`() {
        startHost()
        composeRule.onNodeWithText("PERSONA", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("WITNESS").assertIsDisplayed()
        composeRule.onNodeWithText("HARDASS").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("EDITOR").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `selecting Hardass persists default persona to prefs`() {
        startHost()
        composeRule.onNode(hasText("HARDASS") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitForIdle()
        tapPrimary("CONTINUE")
        assertEquals(Persona.HARDASS, prefs.defaultPersona)
    }

    @Test
    fun `persona Continue advances to the Wiring hub`() {
        startHost()
        tapPrimary("CONTINUE")
        composeRule.onNodeWithText("WIRING", substring = true).assertIsDisplayed()
    }

    @Test
    fun `Wiring Open Vestige is enabled while optional permissions are still pending`() {
        prefs.setCurrentStep(OnboardingStep.Wiring)
        startHost(modelAvailability = fakeModelAvailability(ModelArtifactState.Complete))
        // Mic + notification are optional in the hub flow. Once the local model is ready,
        // onboarding can proceed even if those permissions are still pending.
        composeRule.onNodeWithText("OPEN VESTIGE").assertIsEnabled()
    }

    @Test
    fun `Wiring Open Vestige stays disabled while the local model is missing`() {
        prefs.setCurrentStep(OnboardingStep.Wiring)
        startHost(modelAvailability = fakeModelAvailability(ModelArtifactState.Absent))

        composeRule.onNodeWithText("OPEN VESTIGE").assertIsNotEnabled()
    }

    @Test
    fun `current onboarding step survives host recreation`() {
        startHost()
        tapPrimary("CONTINUE")
        startHost()
        composeRule.onNodeWithText("WIRING", substring = true).assertIsDisplayed()
    }

    @Test
    fun `system back from Wiring returns to PersonaPick`() {
        startHost()
        tapPrimary("CONTINUE")
        composeRule.onNodeWithText("WIRING", substring = true).assertIsDisplayed()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PERSONA", substring = true).assertIsDisplayed()
    }

    @Test
    fun `download screen keeps Continue disabled while model is Absent`() {
        prefs.setCurrentStep(OnboardingStep.ModelDownload)
        startHost(
            wifiAvailability = { true },
            modelAvailability = fakeModelAvailability(ModelArtifactState.Absent),
        )

        composeRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE").assertIsNotEnabled()
        assertFalse(prefs.isComplete)
    }

    @Test
    fun `tapping the persona card on Wiring returns to PersonaPick`() {
        prefs.setCurrentStep(OnboardingStep.Wiring)
        startHost(modelAvailability = fakeModelAvailability(ModelArtifactState.Absent))
        // The persona switch sits at the top of the Wiring switch list; its title carries
        // the selected persona's name in uppercase. Tapping the card re-opens PersonaPick.
        composeRule.onNodeWithText("PERSONA · WITNESS", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PERSONA", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("WITNESS").assertIsDisplayed()
    }

    @Test
    fun `ModelDownload auto-returns to Wiring once the artifact lands as Complete`() {
        prefs.setCurrentStep(OnboardingStep.ModelDownload)
        startHost(
            wifiAvailability = { true },
            modelAvailability = fakeModelAvailability(ModelArtifactState.Complete),
        )
        // Auto-return is driven by LaunchedEffect(step, modelState): the moment status() reports
        // Complete on the ModelDownload screen, the host hops back to Wiring without a tap.
        composeRule.waitForIdle()
        composeRule.onNodeWithText("WIRING", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("OPEN VESTIGE").assertIsEnabled()
    }

    @Test
    fun `download progress drives Partial state ticks and final Complete state`() {
        prefs.setCurrentStep(OnboardingStep.ModelDownload)
        // Three onProgress ticks exercise the sampler's first-call init branch, the
        // expectedBytes > 0 percent branch, and the unknown-total branch where pct collapses
        // to -1. Same-tick clock means MB/s never emits — that branch is intentionally left
        // for a separate timed test in a future story. The terminal Partial(4MB, 0) keeps
        // the host on ModelDownload (no auto-return) so the assertion can confirm the
        // download body actually ran.
        val ticks = mutableListOf<Pair<Long, Long>>()
        val availability = object : ModelAvailability {
            override suspend fun status(): ModelArtifactState = ModelArtifactState.Absent
            override suspend fun download(onProgress: (Long, Long) -> Unit): ModelArtifactState {
                onProgress(2L * 1_048_576L, 4L * 1_048_576L)
                onProgress(3L * 1_048_576L, 4L * 1_048_576L)
                onProgress(4L * 1_048_576L, 0L)
                ticks += 2L * 1_048_576L to 4L * 1_048_576L
                ticks += 3L * 1_048_576L to 4L * 1_048_576L
                ticks += 4L * 1_048_576L to 0L
                return ModelArtifactState.Partial(currentBytes = 4L * 1_048_576L, expectedBytes = 0L)
            }
        }
        startHost(wifiAvailability = { true }, modelAvailability = availability)
        composeRule.waitForIdle()
        // The screen stays on ModelDownload's DOWNLOADING pill because the terminal state is
        // not Complete; the sampler branches above all fired during the suspending body.
        composeRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
        assertEquals(3, ticks.size)
    }

    @Test
    fun `download failure logs and leaves the Continue button disabled`() {
        prefs.setCurrentStep(OnboardingStep.ModelDownload)
        // Generic Exception path: runDownloadIfNeeded catches and logs without bubbling.
        val availability = object : ModelAvailability {
            override suspend fun status(): ModelArtifactState = ModelArtifactState.Absent
            override suspend fun download(onProgress: (Long, Long) -> Unit): ModelArtifactState {
                error("simulated network drop")
            }
        }
        startHost(wifiAvailability = { true }, modelAvailability = availability)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CONTINUE").assertIsNotEnabled()
        assertFalse(prefs.isComplete)
    }

    @Test
    fun `Open Vestige does not re-SHA the artifact on tap`() {
        prefs.setCurrentStep(OnboardingStep.Wiring)
        var statusCalls = 0
        var completed = false
        val availability = object : ModelAvailability {
            override suspend fun status(): ModelArtifactState {
                statusCalls += 1
                return ModelArtifactState.Complete
            }
        }
        startHost(onComplete = { completed = true }, modelAvailability = availability)
        composeRule.waitForIdle()
        val baseline = statusCalls

        tapPrimary("OPEN VESTIGE")

        assertTrue(completed)
        assertTrue(prefs.isComplete)
        // The tap path must not trigger another status() call — that would re-SHA the
        // 3.66 GB artifact and stall the button for tens of seconds.
        assertEquals(baseline, statusCalls)
    }

    @Test
    fun `Open Vestige forwards the selected persona to completion`() {
        prefs.setDefaultPersona(Persona.HARDASS)
        prefs.setCurrentStep(OnboardingStep.Wiring)
        var completedPersona: Persona? = null

        startHost(
            onComplete = { completedPersona = it },
            modelAvailability = fakeModelAvailability(ModelArtifactState.Complete),
        )
        tapPrimary("OPEN VESTIGE")

        assertEquals(Persona.HARDASS, completedPersona)
    }

    @Test
    fun `Open Vestige stays on onboarding when markComplete fails`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false
        val sharedPrefs = mockk<SharedPreferences>()
        every { sharedPrefs.getBoolean("complete", false) } returns false
        every { sharedPrefs.getString("default_persona", null) } returns Persona.EDITOR.name
        every { sharedPrefs.getString("current_step", null) } returns OnboardingStep.Wiring.name
        every { sharedPrefs.edit() } returns editor

        var completed = false
        startHost(
            prefs = OnboardingPrefs(sharedPrefs),
            onComplete = { completed = true },
            modelAvailability = fakeModelAvailability(ModelArtifactState.Complete),
        )
        tapPrimary("OPEN VESTIGE")

        assertFalse(completed)
        composeRule.onNodeWithText("WIRING", substring = true).assertIsDisplayed()
    }

    private fun tapPrimary(label: String) {
        // Primary action bar is pinned at the bottom of the scaffold (outside the scroll region),
        // so it's always visible — no performScrollTo needed.
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun fakeModelAvailability(state: ModelArtifactState) = object : ModelAvailability {
        override suspend fun status(): ModelArtifactState = state
    }

    private fun startHost(
        prefs: OnboardingPrefs = this.prefs,
        onComplete: (Persona) -> Unit = {},
        wifiAvailability: WifiAvailability = WifiAvailability { false },
        modelAvailability: ModelAvailability = fakeModelAvailability(ModelArtifactState.Absent),
        downloadDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        // Test default is `Unconfined` so `withContext(downloadDispatcher)` runs the download
        // body on the calling thread — `composeRule.waitForIdle()` then drains it deterministically.
        composeRule.activity.setContent {
            VestigeTheme {
                OnboardingHost(
                    prefs = prefs,
                    onComplete = onComplete,
                    modelAvailability = modelAvailability,
                    wifiAvailability = wifiAvailability,
                    downloadDispatcher = downloadDispatcher,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
