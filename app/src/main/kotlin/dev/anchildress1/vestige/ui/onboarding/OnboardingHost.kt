package dev.anchildress1.vestige.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.anchildress1.vestige.model.Persona

/** Hosts the 8-screen onboarding flow. Step + persona survive process death via SharedPreferences. */
@Composable
fun OnboardingHost(
    prefs: OnboardingPrefs,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    wifiAvailability: WifiAvailability? = null,
) {
    val context = LocalContext.current
    val resolvedWifi = remember(context, wifiAvailability) {
        wifiAvailability ?: WifiAvailability.Default(context)
    }
    var step by rememberSaveable { mutableStateOf(OnboardingStep.PersonaPick) }
    var persona by rememberSaveable { mutableStateOf(prefs.defaultPersona) }
    var micPermissionDenied by rememberSaveable { mutableStateOf(false) }

    val advance: () -> Unit = { step = step.next() ?: step }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micPermissionDenied = !granted
        if (granted) advance()
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> advance() }

    BackHandler(enabled = step != OnboardingStep.PersonaPick) {
        // Re-entering the mic screen via Back should not show a stale denied notice — the user
        // is about to be re-asked.
        if (step == OnboardingStep.NotificationPermission) micPermissionDenied = false
        step = step.previous() ?: step
    }
    LaunchedEffect(persona) { prefs.setDefaultPersona(persona) }

    OnboardingStepContent(
        step = step,
        persona = persona,
        onPersonaChange = { persona = it },
        micPermissionDenied = micPermissionDenied,
        wifiConnected = resolvedWifi.isWifiConnected(),
        advance = advance,
        onMicAllow = { requestMic(context, micLauncher, advance) },
        onNotificationAllow = { requestNotifications(notifLauncher, advance) },
        onOpenWifiSettings = { openWifiSettings(context) },
        onOpenApp = {
            prefs.markComplete()
            onComplete()
        },
        modifier = modifier,
    )
}

@Suppress("LongParameterList") // Step content takes the full set of orchestrated callbacks.
@Composable
private fun OnboardingStepContent(
    step: OnboardingStep,
    persona: Persona,
    onPersonaChange: (Persona) -> Unit,
    micPermissionDenied: Boolean,
    wifiConnected: Boolean,
    advance: () -> Unit,
    onMicAllow: () -> Unit,
    onNotificationAllow: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier,
) {
    when (step) {
        OnboardingStep.PersonaPick -> PersonaPickScreen(
            modifier = modifier,
            selected = persona,
            onSelect = onPersonaChange,
            onContinue = advance,
        )

        OnboardingStep.LocalExplainer -> LocalExplainerScreen(modifier = modifier, onContinue = advance)

        OnboardingStep.MicPermission -> MicPermissionScreen(
            modifier = modifier,
            showDeniedNotice = micPermissionDenied,
            onAllow = onMicAllow,
            onSkip = advance,
        )

        OnboardingStep.NotificationPermission -> NotificationPermissionScreen(
            modifier = modifier,
            onAllow = onNotificationAllow,
            onSkip = advance,
        )

        OnboardingStep.TypedFallback -> TypedFallbackScreen(modifier = modifier, onContinue = advance)

        OnboardingStep.WifiCheck -> WifiCheckScreen(
            modifier = modifier,
            isWifiConnected = wifiConnected,
            onContinue = advance,
            onOpenWifiSettings = onOpenWifiSettings,
            onComeBackLater = advance,
        )

        OnboardingStep.ModelDownload -> ModelDownloadPlaceholderScreen(
            modifier = modifier,
            onContinue = advance,
        )

        OnboardingStep.Ready -> ReadyScreen(
            modifier = modifier,
            persona = persona,
            onOpenApp = onOpenApp,
        )
    }
}

private fun requestMic(context: Context, launcher: ActivityResultLauncher<String>, advance: () -> Unit) {
    if (hasRecordAudio(context)) {
        advance()
    } else {
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

private fun requestNotifications(launcher: ActivityResultLauncher<String>, advance: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        // Pre-API 33 has no runtime permission for notifications — channels register
        // unconditionally in VestigeApplication. Treat as a no-op success.
        advance()
    }
}

private fun openWifiSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun hasRecordAudio(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
