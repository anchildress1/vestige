package dev.anchildress1.vestige.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingHostPlatformTest {

    @Test
    fun `findActivity returns direct activity and unwraps nested ContextWrappers`() {
        val activity = mockk<Activity>(relaxed = true)
        assertSame(activity, invokeFindActivity(activity))
        assertSame(activity, invokeFindActivity(ContextWrapper(ContextWrapper(activity))))
    }

    @Test
    fun `findActivity returns null for plain application context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(invokeFindActivity(context))
    }

    @Test
    fun `moveTaskToBack delegates to the resolved activity`() {
        val activity = mockk<Activity>(relaxed = true)
        invokeMoveTaskToBack(ContextWrapper(activity))
        verify { activity.moveTaskToBack(true) }
    }

    private fun invokeMoveTaskToBack(context: Context) {
        method("moveTaskToBack", Context::class.java).invoke(null, context)
    }

    private fun invokeFindActivity(context: Context): Activity? =
        method("findActivity", Context::class.java).invoke(null, context) as Activity?

    private fun method(name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method =
        Class.forName("dev.anchildress1.vestige.ui.onboarding.OnboardingHostKt")
            .getDeclaredMethod(name, *parameterTypes)
            .apply { isAccessible = true }
}
