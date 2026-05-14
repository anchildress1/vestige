package dev.anchildress1.vestige

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import dev.anchildress1.vestige.lifecycle.LocalProcessingNotification
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VestigeApplicationTest {

    @Test
    @Config(manifest = Config.NONE, application = ReleaseVestigeApplicationTestApp::class)
    fun `non-debuggable onCreate wires container, registers channel, leaves StrictMode at default`() {
        val app = ApplicationProvider.getApplicationContext<ReleaseVestigeApplicationTestApp>()

        assertSame(app.testContainer, app.appContainer)
        verify(exactly = 1) { app.testContainer.launchVectorBackfillIfReady() }
        confirmVerified(app.testContainer)
        assertChannelRegistered(app)
        // Release path skips installStrictModeOnDebugBuilds — mask stays at the LAX default.
        // ThreadPolicy.LAX is a singleton but Robolectric installs an equivalent fresh instance
        // during app bootstrap, so identity comparison is unreliable; assert the mask itself.
        assertTrue(
            "release build must leave StrictMode at the default (mask=0)",
            StrictMode.getThreadPolicy().toString().contains("mask=0"),
        )
    }

    @Test
    @Config(manifest = Config.NONE, application = DebugVestigeApplicationTestApp::class)
    fun `debuggable onCreate wires container, registers channel, arms StrictMode network policy`() {
        val app = ApplicationProvider.getApplicationContext<DebugVestigeApplicationTestApp>()

        assertSame(app.testContainer, app.appContainer)
        verify(exactly = 1) { app.testContainer.launchVectorBackfillIfReady() }
        confirmVerified(app.testContainer)
        assertChannelRegistered(app)
        // Debug path installs detectNetwork + penaltyDeathOnNetwork — mask must be non-zero.
        assertFalse(
            "debug build must arm StrictMode (non-zero mask)",
            StrictMode.getThreadPolicy().toString().contains("mask=0"),
        )
    }

    private fun assertChannelRegistered(context: Context) {
        val manager = context.getSystemService<NotificationManager>()!!
        assertNotNull(
            "LocalProcessingNotification.registerChannel must run during onCreate",
            manager.getNotificationChannel(LocalProcessingNotification.CHANNEL_ID),
        )
    }
}

open class BaseVestigeApplicationTestApp : VestigeApplication() {
    val testContainer: AppContainer = mockk {
        every { launchVectorBackfillIfReady() } returns Unit
    }
    private val appInfo = ApplicationInfo()

    override fun createAppContainer(): AppContainer = testContainer

    override fun getApplicationInfo(): ApplicationInfo = appInfo
}

class ReleaseVestigeApplicationTestApp : BaseVestigeApplicationTestApp()

// FLAG_DEBUGGABLE drives the StrictMode branch in VestigeApplication.installStrictModeOnDebugBuilds.
class DebugVestigeApplicationTestApp : BaseVestigeApplicationTestApp() {
    override fun getApplicationInfo(): ApplicationInfo = super.getApplicationInfo().apply {
        flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
    }
}
