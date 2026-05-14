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
    context: Context,
    modelState: ModelArtifactState,
    onAdvance: () -> Unit,
) {
    LaunchedEffect(step, modelState) {
        val skip = when (step) {
            OnboardingStep.MicPermission -> hasRecordAudio(context)
            OnboardingStep.NotificationPermission -> hasNotificationPermission(context)
            OnboardingStep.WifiCheck, OnboardingStep.ModelDownload -> modelState.isReady
            else -> false
        }
        if (skip) onAdvance()
    }
}
