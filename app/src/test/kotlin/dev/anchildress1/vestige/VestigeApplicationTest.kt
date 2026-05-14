package dev.anchildress1.vestige

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VestigeApplicationTest {

    @Test
    @Config(manifest = Config.NONE, application = ReleaseVestigeApplicationTestApp::class)
    fun `onCreate initializes the container and launches backfill when not debuggable`() {
        val app = ApplicationProvider.getApplicationContext<ReleaseVestigeApplicationTestApp>()

        verify(exactly = 1) { app.testContainer.launchVectorBackfillIfReady() }
    }

    @Test
    @Config(manifest = Config.NONE, application = DebugVestigeApplicationTestApp::class)
    fun `onCreate initializes the container and launches backfill when debuggable`() {
        val app = ApplicationProvider.getApplicationContext<DebugVestigeApplicationTestApp>()

        verify(exactly = 1) { app.testContainer.launchVectorBackfillIfReady() }
    }
}

open class BaseVestigeApplicationTestApp : VestigeApplication() {
    val testContainer: AppContainer = mockk(relaxed = true)
    private val appInfo = ApplicationInfo()

    override fun createAppContainer(): AppContainer = testContainer

    override fun getApplicationInfo(): ApplicationInfo = appInfo
}

class ReleaseVestigeApplicationTestApp : BaseVestigeApplicationTestApp()

class DebugVestigeApplicationTestApp : BaseVestigeApplicationTestApp() {
    override fun getApplicationInfo(): ApplicationInfo = super.getApplicationInfo().apply {
        flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
    }
}
