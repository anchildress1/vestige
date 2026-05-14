package dev.anchildress1.vestige.ui.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Wi-Fi gate for the onboarding download screen. [Default] reads from [ConnectivityManager]. */
fun interface WifiAvailability {
    fun isWifiConnected(): Boolean

    class Default(context: Context) : WifiAvailability {
        private val cm: ConnectivityManager? =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        override fun isWifiConnected(): Boolean {
            val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
            // `INTERNET` only means the Wi-Fi network is *configured* for the internet;
            // `VALIDATED` is Android's confirmation that the device actually reached it
            // (i.e. not stuck on a captive portal or dead access point).
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
