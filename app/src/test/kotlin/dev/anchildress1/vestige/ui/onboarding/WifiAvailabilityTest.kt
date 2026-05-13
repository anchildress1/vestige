package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE, application = OnboardingTestApplication::class)
class WifiAvailabilityTest {

    @Test
    fun `default returns false when ConnectivityManager is unavailable`() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null

        assertFalse(WifiAvailability.Default(context).isWifiConnected())
    }

    @Test
    fun `default returns false when there is no active network`() {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns null

        assertFalse(WifiAvailability.Default(context).isWifiConnected())
    }

    @Test
    fun `default returns false when active network has no capabilities`() {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns null

        assertFalse(WifiAvailability.Default(context).isWifiConnected())
    }

    @Test
    fun `default returns false when Wi-Fi lacks internet capability`() {
        val availability = availabilityWith(
            hasWifi = true,
            hasInternet = false,
        )

        assertFalse(availability.isWifiConnected())
    }

    @Test
    fun `default returns false when internet is present on non Wi-Fi transport`() {
        val availability = availabilityWith(
            hasWifi = false,
            hasInternet = true,
        )

        assertFalse(availability.isWifiConnected())
    }

    @Test
    fun `default returns true only when Wi-Fi also has internet capability`() {
        val availability = availabilityWith(
            hasWifi = true,
            hasInternet = true,
        )

        assertTrue(availability.isWifiConnected())
    }

    private fun availabilityWith(hasWifi: Boolean, hasInternet: Boolean): WifiAvailability {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns hasWifi
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns hasInternet
        return WifiAvailability.Default(context)
    }
}
