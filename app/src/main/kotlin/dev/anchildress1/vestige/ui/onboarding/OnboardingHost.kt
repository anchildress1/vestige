package dev.anchildress1.vestige.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hosts the 8-screen onboarding flow. Step + persona survive process death via SharedPreferences. */
@Composable
fun OnboardingHost(
    prefs: OnboardingPrefs,
    onComplete: () -> Unit,
    modelAvailability: ModelAvailability,
    modifier: Modifier = Modifier,
    wifiAvailability: WifiAvailability? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolvedWifi = remember(context, wifiAvailability) {
        wifiAvailability ?: WifiAvailability.Default(context)
    }
    var step by rememberSaveable { mutableStateOf(prefs.currentStep) }
    var persona by rememberSaveable { mutableStateOf(prefs.defaultPersona) }
    var micPermissionDenied by rememberSaveable { mutableStateOf(false) }

    val advance: () -> Unit = { step = step.next() ?: step }
    val launchers = rememberPermissionLaunchers(
        onMicResult = { granted ->
            micPermissionDenied = !granted
            if (granted) advance()
        },
        onNotificationResult = { advance() },
    )

    BackHandler(enabled = step != OnboardingStep.PersonaPick) {
        // Re-entering the mic screen via Back should not show a stale denied notice — the user
        // is about to be re-asked.
        if (step == OnboardingStep.NotificationPermission) micPermissionDenied = false
        step = step.previous() ?: step
    }
    LaunchedEffect(persona) { prefs.setDefaultPersona(persona) }
    val environment = rememberOnboardingEnvironment(
        prefs = prefs,
        step = step,
        wifiAvailability = resolvedWifi,
        modelAvailability = modelAvailability,
    )

    // Auto-skip screens whose precondition is already satisfied. Material-3 / Android UX
    // convention: don't gate flow on a re-confirmation of a decision the user already made.
    AutoSkipAlreadySatisfied(
        step = step,
        context = context,
        modelState = environment.modelState,
        onAdvance = advance,
    )

    // VestigeScaffold owns floor/ink color propagation — onboarding screens render plain
    // composables (no per-screen Surface), so the scaffold is the only thing that keeps
    // foreground readable on the floor background. Per AGENTS rule 26.
    VestigeScaffold(modifier = modifier) { padding ->
        OnboardingStepContent(
            step = step,
            persona = persona,
            onPersonaChange = { persona = it },
            micPermissionDenied = micPermissionDenied,
            wifiConnected = environment.wifiConnected,
            modelState = environment.modelState,
            advance = advance,
            onMicAllow = { requestMic(context, launchers.mic, advance) },
            onNotificationAllow = { requestNotifications(launchers.notification, advance) },
            onOpenWifiSettings = { openWifiSettings(context) },
            onComeBackLater = { moveTaskToBack(context) },
            onOpenApp = {
                scope.launch {
                    if (!modelAvailability.status().isReady) return@launch
                    prefs.markComplete()
                    onComplete()
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

private data class OnboardingEnvironment(val wifiConnected: Boolean, val modelState: ModelArtifactState)

@Composable
private fun rememberOnboardingEnvironment(
    prefs: OnboardingPrefs,
    step: OnboardingStep,
    wifiAvailability: WifiAvailability,
    modelAvailability: ModelAvailability,
): OnboardingEnvironment {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var wifiConnected by remember { mutableStateOf(wifiAvailability.isWifiConnected()) }
    var modelState by remember { mutableStateOf<ModelArtifactState>(ModelArtifactState.Absent) }

    LaunchedEffect(step) {
        prefs.setCurrentStep(step)
        wifiConnected = wifiAvailability.isWifiConnected()
        val initial = modelAvailability.status()
        modelState = initial
        // Auto-trigger the download when the user lands on Screen 6 with a non-Complete artifact.
        // LaunchedEffect(step) is cancelled when the user navigates away, which also cancels the
        // download — leaving the file on disk for a later resume. Story 4.3 owns retry / pause /
        // stall handling; this just gets the percent moving. withContext(IO) so the network call
        // doesn't trip StrictMode's penaltyDeathOnNetwork in debug builds.
        if (step == OnboardingStep.ModelDownload && initial !is ModelArtifactState.Complete) {
            try {
                val terminal = withContext(Dispatchers.IO) {
                    modelAvailability.download { currentBytes, expectedBytes ->
                        modelState = ModelArtifactState.Partial(currentBytes, expectedBytes)
                    }
                }
                modelState = terminal
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(ONBOARDING_TAG, "Model download failed", error)
                // Keep modelState at its last reported value — the user sees the partial pill /
                // bar and can tap Back. Story 4.3 will own the polished retry / error UI.
            }
        }
    }
    DisposableEffect(lifecycleOwner, wifiAvailability, modelAvailability) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    wifiConnected = wifiAvailability.isWifiConnected()
                    modelState = modelAvailability.status()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return OnboardingEnvironment(
        wifiConnected = wifiConnected,
        modelState = modelState,
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
    modelState: ModelArtifactState,
    advance: () -> Unit,
    onMicAllow: () -> Unit,
    onNotificationAllow: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onComeBackLater: () -> Unit,
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
            onComeBackLater = onComeBackLater,
        )

        OnboardingStep.ModelDownload -> ModelDownloadPlaceholderScreen(
            modifier = modifier,
            modelState = modelState,
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

private fun moveTaskToBack(context: Context) {
    context.findActivity()?.moveTaskToBack(true)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class PermissionLaunchers(
    val mic: ActivityResultLauncher<String>,
    val notification: ActivityResultLauncher<String>,
)

@Composable
private fun rememberPermissionLaunchers(
    onMicResult: (Boolean) -> Unit,
    onNotificationResult: (Boolean) -> Unit,
): PermissionLaunchers {
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onMicResult)
    val notif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onNotificationResult,
    )
    return PermissionLaunchers(mic = mic, notification = notif)
}
