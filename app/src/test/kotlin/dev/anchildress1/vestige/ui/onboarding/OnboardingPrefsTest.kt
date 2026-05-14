package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.Persona
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingPrefsTest {

    private lateinit var prefs: OnboardingPrefs

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(OnboardingPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = OnboardingPrefs.from(ctx)
    }

    @Test
    fun `defaults to incomplete and Witness persona`() {
        assertFalse(prefs.isComplete)
        assertEquals(Persona.WITNESS, prefs.defaultPersona)
    }

    @Test
    fun `markComplete flips the completion flag and survives a fresh instance`() {
        assertTrue(prefs.markComplete())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val reopened = OnboardingPrefs.from(ctx)
        assertTrue(reopened.isComplete)
    }

    @Test
    fun `setDefaultPersona round-trips the persona`() {
        prefs.setDefaultPersona(Persona.HARDASS)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val reopened = OnboardingPrefs.from(ctx)
        assertEquals(Persona.HARDASS, reopened.defaultPersona)
    }

    @Test
    fun `setCurrentStep round-trips the onboarding step`() {
        prefs.setCurrentStep(OnboardingStep.Wiring)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val reopened = OnboardingPrefs.from(ctx)
        assertEquals(OnboardingStep.Wiring, reopened.currentStep)
    }

    @Test
    fun `unknown persona string falls back to Witness without throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(OnboardingPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("default_persona", "NOT_A_PERSONA").commit()
        val reopened = OnboardingPrefs.from(ctx)
        assertEquals(Persona.WITNESS, reopened.defaultPersona)
    }

    @Test
    fun `unknown onboarding step string falls back to PersonaPick without throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(OnboardingPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("current_step", "NOT_A_STEP").commit()
        val reopened = OnboardingPrefs.from(ctx)
        assertEquals(OnboardingStep.PersonaPick, reopened.currentStep)
    }

    @Test
    fun `markComplete clears the stored onboarding step`() {
        prefs.setCurrentStep(OnboardingStep.ModelDownload)
        assertTrue(prefs.markComplete())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val reopened = OnboardingPrefs.from(ctx)
        assertEquals(OnboardingStep.PersonaPick, reopened.currentStep)
    }

    @Test
    fun `markComplete returns false when SharedPreferences flush fails`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns false
        val sharedPrefs = mockk<SharedPreferences> {
            every { edit() } returns editor
        }

        assertFalse(OnboardingPrefs(sharedPrefs).markComplete())
    }
}
