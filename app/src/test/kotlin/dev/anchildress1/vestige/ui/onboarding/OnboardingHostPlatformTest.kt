package dev.anchildress1.vestige.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.jvm.functions.Function0

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class OnboardingHostPlatformTest {

    @Test
    fun `requestMic advances immediately when record audio is already granted`() {
        mockkStatic(ContextCompat::class)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val launcher = RecordingLauncher()
            var advanced = false
            every {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            } returns android.content.pm.PackageManager.PERMISSION_GRANTED

            invokeRequestMic(
                context = context,
                launcher = launcher,
                advance = { advanced = true },
            )

            assertTrue(advanced)
            assertNull(launcher.lastInput)
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `requestMic launches the runtime permission when record audio is missing`() {
        mockkStatic(ContextCompat::class)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val launcher = RecordingLauncher()
            var advanced = false
            every {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            } returns android.content.pm.PackageManager.PERMISSION_DENIED

            invokeRequestMic(
                context = context,
                launcher = launcher,
                advance = { advanced = true },
            )

            assertTrue(!advanced)
            assertSame(Manifest.permission.RECORD_AUDIO, launcher.lastInput)
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `requestNotifications launches POST_NOTIFICATIONS on API 33 plus`() {
        val launcher = RecordingLauncher()
        var advanced = false

        invokeRequestNotifications(
            launcher = launcher,
            advance = { advanced = true },
        )

        assertTrue(!advanced)
        assertSame(Manifest.permission.POST_NOTIFICATIONS, launcher.lastInput)
    }

    @Test
    @Config(sdk = [32], manifest = Config.NONE, application = OnboardingTestApplication::class)
    fun `requestNotifications advances immediately below API 33`() {
        val launcher = RecordingLauncher()
        var advanced = false

        invokeRequestNotifications(
            launcher = launcher,
            advance = { advanced = true },
        )

        assertTrue(advanced)
        assertNull(launcher.lastInput)
    }

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

    private fun invokeRequestMic(context: Context, launcher: ActivityResultLauncher<String>, advance: () -> Unit) {
        method(
            "requestMic",
            Context::class.java,
            ActivityResultLauncher::class.java,
            Function0::class.java,
        ).invoke(null, context, launcher, callback(advance))
    }

    private fun invokeRequestNotifications(launcher: ActivityResultLauncher<String>, advance: () -> Unit) {
        method(
            "requestNotifications",
            ActivityResultLauncher::class.java,
            Function0::class.java,
        ).invoke(null, launcher, callback(advance))
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

    private fun callback(block: () -> Unit): Function0<Unit> = object : Function0<Unit> {
        override fun invoke() {
            block()
        }
    }

    private class RecordingLauncher : ActivityResultLauncher<String>() {
        var lastInput: String? = null
        override val contract: ActivityResultContract<String, *>
            get() = object : ActivityResultContract<String, String>() {
                override fun createIntent(context: Context, input: String) =
                    throw UnsupportedOperationException("unused in unit tests")

                override fun parseResult(resultCode: Int, intent: android.content.Intent?) = ""
            }

        override fun launch(input: String, options: androidx.core.app.ActivityOptionsCompat?) {
            lastInput = input
        }

        override fun unregister() = Unit
    }
}
