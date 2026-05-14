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
import androidx.compose.runtime.Immutable
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

    val callbacks = OnboardingStepCallbacks(
        onPersonaChange = { persona = it },
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
    )
    val state =
        OnboardingStepState(step, persona, micPermissionDenied, environment.wifiConnected, environment.modelState)
    // VestigeScaffold owns floor/ink color propagation per AGENTS rule 26.
    VestigeScaffold(modifier = modifier) { padding ->
        OnboardingStepContent(
            state = state,
            callbacks = callbacks,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Immutable
private data class OnboardingStepState(
    val step: OnboardingStep,
    val persona: Persona,
    val micPermissionDenied: Boolean,
    val wifiConnected: Boolean,
    val modelState: ModelArtifactState,
)

@Immutable
private data class OnboardingStepCallbacks(
    val onPersonaChange: (Persona) -> Unit,
    val advance: () -> Unit,
    val onMicAllow: () -> Unit,
    val onNotificationAllow: () -> Unit,
    val onOpenWifiSettings: () -> Unit,
    val onComeBackLater: () -> Unit,
    val onOpenApp: () -> Unit,
)

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

    // Cheap per-step disk write — persist resume point + refresh the Wi-Fi snapshot.
    LaunchedEffect(step) {
        prefs.setCurrentStep(step)
        wifiConnected = wifiAvailability.isWifiConnected()
    }

    // Snapshot the model state ONCE on mount. `modelAvailability.status()` runs a full SHA-256
    // over the ~3.7 GB artifact when the file is complete — re-firing it on every step
    // transition would re-hash for every screen change.
    LaunchedEffect(Unit) {
        modelState = modelAvailability.status()
    }

    // Trigger the download only when the user reaches Screen 6 with a non-Complete artifact.
    // LaunchedEffect(step) is cancelled when the user navigates away, which also cancels the
    // download — leaving the .part file on disk for HTTP-Range resume on re-entry. Story 4.3
    // owns retry / pause / stall handling; this just gets the percent moving.
    LaunchedEffect(step) {
        if (step != OnboardingStep.ModelDownload) return@LaunchedEffect
        val current = modelAvailability.status()
        modelState = current
        if (current is ModelArtifactState.Complete) return@LaunchedEffect
        try {
            // withContext(IO) so the network call doesn't trip StrictMode's
            // penaltyDeathOnNetwork in debug builds.
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

@Composable
private fun OnboardingStepContent(state: OnboardingStepState, callbacks: OnboardingStepCallbacks, modifier: Modifier) {
    when (state.step) {
        OnboardingStep.PersonaPick -> PersonaPickScreen(
            modifier = modifier,
            selected = state.persona,
            onSelect = callbacks.onPersonaChange,
            onContinue = callbacks.advance,
        )

        OnboardingStep.LocalExplainer -> LocalExplainerScreen(modifier = modifier, onContinue = callbacks.advance)

        OnboardingStep.MicPermission -> MicPermissionScreen(
            modifier = modifier,
            showDeniedNotice = state.micPermissionDenied,
            onAllow = callbacks.onMicAllow,
            onSkip = callbacks.advance,
        )

        OnboardingStep.NotificationPermission -> NotificationPermissionScreen(
            modifier = modifier,
            onAllow = callbacks.onNotificationAllow,
            onSkip = callbacks.advance,
        )

        OnboardingStep.TypedFallback -> TypedFallbackScreen(modifier = modifier, onContinue = callbacks.advance)

        OnboardingStep.WifiCheck -> WifiCheckScreen(
            modifier = modifier,
            isWifiConnected = state.wifiConnected,
            onContinue = callbacks.advance,
            onOpenWifiSettings = callbacks.onOpenWifiSettings,
            onComeBackLater = callbacks.onComeBackLater,
        )

        OnboardingStep.ModelDownload -> ModelDownloadPlaceholderScreen(
            modifier = modifier,
            modelState = state.modelState,
            onContinue = callbacks.advance,
        )

        OnboardingStep.Ready -> ReadyScreen(
            modifier = modifier,
            persona = state.persona,
            onOpenApp = callbacks.onOpenApp,
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
