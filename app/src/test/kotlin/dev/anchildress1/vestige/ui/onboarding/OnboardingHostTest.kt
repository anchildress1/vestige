package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.theme.VestigeTheme
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
        composeRule.onNodeWithText("PICK A PERSONA.").assertIsDisplayed()
        composeRule.onNodeWithText("WITNESS").assertIsDisplayed()
        // Hardass + Editor live further down the scroll on smaller test viewports.
        composeRule.onNodeWithText("HARDASS").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("EDITOR").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `selecting Hardass persists default persona to prefs`() {
        startHost()
        // Click the persona card, not just the inner Text — `hasClickAction` filters to the
        // semantics node that owns the RadioButton role + onSelect handler.
        composeRule.onNode(hasText("HARDASS") and hasClickAction()).performScrollTo().performClick()
        composeRule.waitForIdle()
        tapPrimary("CONTINUE")
        assertEquals(Persona.HARDASS, prefs.defaultPersona)
    }

    @Test
    fun `full flow auto-skips Wi-Fi + Download when the model is already on disk`() {
        var completed = false
        startHost(
            onComplete = { completed = true },
            wifiAvailability = { true },
            modelAvailability = fakeModelAvailability(ModelArtifactState.Complete),
        )

        tapPrimary("CONTINUE") // Persona → Wiring
        tapPrimary("NEXT") // Wiring → Wi-Fi (auto-skipped) → Download (auto-skipped) → Ready
        tapPrimary("OPEN VESTIGE") // Ready → onComplete

        assertTrue(completed)
        assertTrue(prefs.isComplete)
    }

    @Test
    fun `Wi-Fi missing branch shows open settings + come back actions`() {
        startHost(wifiAvailability = { false })
        tapPrimary("CONTINUE") // Persona → Wiring
        tapPrimary("NEXT") // Wiring → Wi-Fi

        composeRule.onNodeWithText("WI-FI REQUIRED.").assertIsDisplayed()
        composeRule.onNodeWithText("OPEN WI-FI SETTINGS").assertIsDisplayed()
        composeRule.onNodeWithText("I'll come back").assertIsDisplayed()
    }

    @Test
    fun `does not mark complete until final Open Vestige tap`() {
        startHost(wifiAvailability = { true }, modelAvailability = fakeModelAvailability(ModelArtifactState.Complete))
        tapPrimary("CONTINUE") // Persona → Wiring
        assertFalse(prefs.isComplete)
    }

    @Test
    fun `system back walks the step pointer back through prior screens`() {
        startHost()
        tapPrimary("CONTINUE") // → Wiring
        composeRule.onNodeWithText("WIRING THIS UP.").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PICK A PERSONA.").assertIsDisplayed()
    }

    @Test
    fun `current onboarding step survives host recreation`() {
        startHost()
        tapPrimary("CONTINUE") // → Wiring
        startHost()
        composeRule.onNodeWithText("WIRING THIS UP.").assertIsDisplayed()
    }

    @Test
    fun `Wi-Fi branch refreshes after returning from settings`() {
        var wifiConnected = false
        startHost(wifiAvailability = { wifiConnected })
        tapPrimary("CONTINUE")
        tapPrimary("NEXT")

        composeRule.onNodeWithText("WI-FI REQUIRED.").assertIsDisplayed()

        wifiConnected = true
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("WI-FI CONNECTED.").assertIsDisplayed()
    }

    @Test
    fun `download screen keeps Continue disabled until model is ready`() {
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
    fun `model-ready Wi-Fi + Download steps auto-skip straight to Ready`() {
        prefs.setCurrentStep(OnboardingStep.WifiCheck)
        startHost(modelAvailability = fakeModelAvailability(ModelArtifactState.Complete))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("READY.").assertIsDisplayed()
    }

    @Test
    fun `Open Vestige does not re-SHA the artifact on tap`() {
        prefs.setCurrentStep(OnboardingStep.Ready)
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
        onComplete: () -> Unit = {},
        wifiAvailability: WifiAvailability = WifiAvailability { false },
        modelAvailability: ModelAvailability = fakeModelAvailability(ModelArtifactState.Absent),
    ) {
        composeRule.activity.setContent {
            VestigeTheme {
                OnboardingHost(
                    prefs = prefs,
                    onComplete = onComplete,
                    modelAvailability = modelAvailability,
                    wifiAvailability = wifiAvailability,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
