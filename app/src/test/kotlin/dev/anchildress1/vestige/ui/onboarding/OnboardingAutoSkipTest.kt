package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingAutoSkipTest {

    @Test
    fun `notification permission is treated as granted before Android 13`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(hasNotificationPermission(context))
    }
}
