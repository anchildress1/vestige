package dev.anchildress1.vestige.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import dev.anchildress1.vestige.model.ModelArtifactState

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

/**
 * Material-3 / Android UX convention: don't gate the flow on re-confirming a decision the user
 * already made. If the runtime permission for the current step is already granted, advance
 * silently. Same idea for Wi-Fi + Download steps when the model is already on disk.
 */
@Composable
internal fun AutoSkipAlreadySatisfied(
    step: OnboardingStep,
    @Suppress("UNUSED_PARAMETER") context: Context,
    modelState: ModelArtifactState,
    onAdvance: () -> Unit,
) {
    // Wiring is the hub — the user advances explicitly via Next once every switch is green.
    // ModelDownload is a drill-in *from* Wiring; it returns through onDownloadReturn, not
    // through advance. No automatic forward skipping remains in the new flow.
    @Suppress("UNUSED_VARIABLE")
    val unused = step to modelState to onAdvance
}
