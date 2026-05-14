package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
        composeRule.onNodeWithText("Pick a persona.").assertIsDisplayed()
        composeRule.onNodeWithText("Witness").assertIsDisplayed()
        composeRule.onNodeWithText("Hardass").assertIsDisplayed()
        composeRule.onNodeWithText("Editor").assertIsDisplayed()
    }

    @Test
    fun `selecting Hardass persists default persona to prefs`() {
        startHost()
        composeRule.onNodeWithText("Hardass").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(Persona.HARDASS, prefs.defaultPersona)
    }

    @Test
    fun `full flow advances and auto-skips Wi-Fi + Download once the model is already on disk`() {
        var completed = false
        startHost(
            onComplete = { completed = true },
            wifiAvailability = { true },
            modelAvailability = fakeModelAvailability(ModelArtifactState.Complete),
        )

        tapPrimary("Continue") // Screen 1
        tapPrimary("Got it") // Screen 2
        tapPrimary("Skip — I'll type instead") // Screen 3
        tapPrimary("Skip — watch the app work") // Screen 3.5
        tapPrimary("Continue") // Screen 4 → Wi-Fi + Download auto-skipped (model already Complete)
        tapPrimary("Open Vestige") // Screen 7

        assertTrue(completed)
        assertTrue(prefs.isComplete)
    }

    @Test
    fun `Wi-Fi missing branch shows open settings + come back actions`() {
        startHost(wifiAvailability = { false })
        tapPrimary("Continue")
        tapPrimary("Got it")
        tapPrimary("Skip — I'll type instead")
        tapPrimary("Skip — watch the app work")
        tapPrimary("Continue")

        composeRule.onNodeWithText("Wi-Fi required.").assertIsDisplayed()
        composeRule.onNodeWithText("Open Wi-Fi settings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("I'll come back").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `does not mark complete until final Open Vestige tap`() {
        startHost(wifiAvailability = { true }, modelAvailability = fakeModelAvailability(ModelArtifactState.Complete))
        tapPrimary("Continue")
        tapPrimary("Got it")
        assertFalse(prefs.isComplete)
    }

    @Test
    fun `system back walks the step pointer back through prior screens`() {
        startHost()
        tapPrimary("Continue") // → LocalExplainer
        tapPrimary("Got it") // → MicPermission
        composeRule.onNodeWithText("Mic permission.").assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Everything stays on your phone.").assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pick a persona.").assertIsDisplayed()
    }

    @Test
    fun `system back from notification permission returns to mic permission`() {
        startHost()
        tapPrimary("Continue") // -> LocalExplainer
        tapPrimary("Got it") // -> MicPermission
        tapPrimary("Skip — I'll type instead") // -> NotificationPermission
        composeRule.onNodeWithText("One status notification.").assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Mic permission.").assertIsDisplayed()
    }

    @Test
    fun `current onboarding step survives host recreation`() {
        startHost()
        tapPrimary("Continue") // -> LocalExplainer
        tapPrimary("Got it") // -> MicPermission

        startHost()

        composeRule.onNodeWithText("Mic permission.").assertIsDisplayed()
    }

    @Test
    fun `Wi-Fi branch refreshes after returning from settings`() {
        var wifiConnected = false
        startHost(wifiAvailability = { wifiConnected })
        tapPrimary("Continue")
        tapPrimary("Got it")
        tapPrimary("Skip — I'll type instead")
        tapPrimary("Skip — watch the app work")
        tapPrimary("Continue")

        composeRule.onNodeWithText("Wi-Fi required.").assertIsDisplayed()

        wifiConnected = true
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Wi-Fi connected.").assertIsDisplayed()
    }

    @Test
    fun `download screen does not allow completion until model is ready`() {
        var completed = false
        startHost(
            onComplete = { completed = true },
            wifiAvailability = { true },
            modelAvailability = fakeModelAvailability(ModelArtifactState.Absent),
        )
        tapPrimary("Continue")
        tapPrimary("Got it")
        tapPrimary("Skip — I'll type instead")
        tapPrimary("Skip — watch the app work")
        tapPrimary("Continue")
        tapPrimary("Download model")

        composeRule.onNodeWithText("Downloading model.").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        assertFalse(completed)
        assertFalse(prefs.isComplete)
    }

    @Test
    fun `model-ready Wi-Fi + Download steps auto-skip straight to Ready`() {
        prefs.setCurrentStep(OnboardingStep.WifiCheck)
        startHost(modelAvailability = fakeModelAvailability(ModelArtifactState.Complete))

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ready.").assertIsDisplayed()
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
        // Allow the initial status snapshot to land before we measure the tap path.
        composeRule.waitForIdle()
        val baseline = statusCalls

        tapPrimary("Open Vestige")

        assertTrue(completed)
        assertTrue(prefs.isComplete)
        // The tap path must not trigger another status() call — that would re-SHA the
        // 3.66 GB artifact and stall the button for tens of seconds.
        assertEquals(baseline, statusCalls)
    }

    private fun tapPrimary(label: String) {
        composeRule.onNodeWithText(label).performScrollTo().performClick()
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
