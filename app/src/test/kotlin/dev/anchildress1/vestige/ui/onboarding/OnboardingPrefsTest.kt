package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.vestige.model.Persona
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
        prefs.markComplete()
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
    fun `unknown persona string falls back to Witness without throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(OnboardingPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("default_persona", "NOT_A_PERSONA").commit()
        val reopened = OnboardingPrefs.from(ctx)
        assertEquals(Persona.WITNESS, reopened.defaultPersona)
    }
}
