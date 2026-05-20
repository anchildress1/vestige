package dev.anchildress1.vestige.lifecycle

import android.app.ForegroundServiceStartNotAllowedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ForegroundServiceStartRejectionTest {

    @Test
    @Config(sdk = [33])
    fun `S+ FGS rejection exception is recognised`() {
        val error = ForegroundServiceStartNotAllowedException("denied")

        assertTrue(error.isForegroundServiceStartNotAllowed())
    }

    @Test
    @Config(sdk = [33])
    fun `S+ non-FGS exceptions are not recognised as FGS rejections`() {
        val error = IllegalStateException("some other thing")

        assertFalse(error.isForegroundServiceStartNotAllowed())
    }

    // Pre-S coverage of `SDK_INT >= S` is intentionally omitted: Robolectric's manifest parser
    // fails on this app under SDK<31 in the unit-test sourceset, and the SDK guard is one line.
    // The instance-type filter above proves the rejection-class branch; the SDK gate is
    // self-evident from the implementation.
}
