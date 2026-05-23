package dev.anchildress1.vestige.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Live device airplane-mode flag. Reads the current [Settings.Global.AIRPLANE_MODE_ON] state and
 * recomposes on [Intent.ACTION_AIRPLANE_MODE_CHANGED] so the indicator tracks toggles mid-session.
 * Reading the global setting and listening for the system broadcast need no permission.
 */
@Composable
fun rememberAirplaneMode(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isAirplaneModeOn(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                enabled = isAirplaneModeOn(context)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return enabled
}

private fun isAirplaneModeOn(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
