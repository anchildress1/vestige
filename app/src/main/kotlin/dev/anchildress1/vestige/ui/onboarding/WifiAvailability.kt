package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Test seam for Screen 5's Wi-Fi gate. Production lookups go through [Default], which reads the
 * active network from [ConnectivityManager]. The interface keeps the screen composable mockable
 * under Robolectric without a real network stack.
 */
fun interface WifiAvailability {
    fun isWifiConnected(): Boolean

    class Default(context: Context) : WifiAvailability {
        private val cm: ConnectivityManager? =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        override fun isWifiConnected(): Boolean {
            val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
