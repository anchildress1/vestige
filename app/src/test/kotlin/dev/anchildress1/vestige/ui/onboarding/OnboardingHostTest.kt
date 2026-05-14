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
        composeRule.onNodeWithText("PERSONA").assertIsDisplayed()
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
        composeRule.onNodeWithText("WIRING").assertIsDisplayed()
    }

    @Test
    fun `Wiring Next is disabled while permissions still pending`() {
        prefs.setCurrentStep(OnboardingStep.Wiring)
        startHost(modelAvailability = fakeModelAvailability(ModelArtifactState.Complete))
        // Robolectric does not auto-grant RECORD_AUDIO / POST_NOTIFICATIONS, so the Wiring
        // gate keeps Next disabled even when the model is on disk.
        composeRule.onNodeWithText("NEXT").assertIsNotEnabled()
    }

    @Test
    fun `current onboarding step survives host recreation`() {
        startHost()
        tapPrimary("CONTINUE")
        startHost()
        composeRule.onNodeWithText("WIRING").assertIsDisplayed()
    }

    @Test
    fun `system back from Wiring returns to PersonaPick`() {
        startHost()
        tapPrimary("CONTINUE")
        composeRule.onNodeWithText("WIRING").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PERSONA").assertIsDisplayed()
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
