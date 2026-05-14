package dev.anchildress1.vestige.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal const val ONBOARDING_TAG = "Onboarding"
internal const val PERCENT_LOG_SCALE: Long = 100L
internal const val SPEED_SAMPLE_INTERVAL_MS: Long = 1_000L
internal const val BYTES_PER_MB: Float = 1_048_576f // 1024 * 1024
internal const val MS_PER_SECOND: Float = 1_000f

internal fun hasRecordAudio(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

internal fun hasNotificationPermission(context: Context): Boolean {
    // POST_NOTIFICATIONS is a runtime permission only on API 33+. Older releases auto-grant it
    // at install time, so the check always returns true.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
