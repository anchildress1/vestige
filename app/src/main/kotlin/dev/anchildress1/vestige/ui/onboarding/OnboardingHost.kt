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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hosts the onboarding hub flow. Step + persona survive process death via SharedPreferences. */
@Suppress("LongMethod", "LongParameterList") // Orchestration root — wiring is intentionally co-located.
@Composable
fun OnboardingHost(
    prefs: OnboardingPrefs,
    onComplete: (Persona) -> Unit,
    modelAvailability: ModelAvailability,
    modifier: Modifier = Modifier,
    wifiAvailability: WifiAvailability? = null,
    downloadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedWifi = remember(context, wifiAvailability) {
        wifiAvailability ?: WifiAvailability.Default(context)
    }
    var step by rememberSaveable { mutableStateOf(prefs.currentStep) }
    var persona by rememberSaveable { mutableStateOf(prefs.defaultPersona) }
    var micGranted by rememberSaveable { mutableStateOf(hasRecordAudio(context)) }
    var notifGranted by rememberSaveable { mutableStateOf(hasNotificationPermission(context)) }
    var micPermissionDenied by rememberSaveable { mutableStateOf(false) }

    val advance: () -> Unit = { step = step.next() ?: step }
    val launchers = rememberPermissionLaunchers(
        onMicResult = { granted ->
            micGranted = granted
            micPermissionDenied = !granted
        },
        onNotificationResult = { granted -> notifGranted = granted },
    )

    BackHandler(enabled = step != OnboardingStep.PersonaPick) {
        // Re-entering Wiring via Back clears any stale mic-denied notice — the user can
        // re-tap the toggle to re-ask.
        if (step == OnboardingStep.Wiring) micPermissionDenied = false
        step = step.previous() ?: step
    }
    LaunchedEffect(persona) {
        // commit() on IO — durable across process kill; off main so StrictMode's
        // detectDiskWrites (debug) doesn't fire on every persona toggle.
        withContext(Dispatchers.IO) { prefs.setDefaultPersona(persona) }
    }
    val environment = rememberOnboardingEnvironment(
        prefs = prefs,
        step = step,
        wifiAvailability = resolvedWifi,
        modelAvailability = modelAvailability,
        downloadDispatcher = downloadDispatcher,
    )

    // Once the model lands while the user is on the download screen, hop back to Wiring —
    // there's nothing left to do here, and Wiring's Next opens the app directly.
    LaunchedEffect(step, environment.modelState) {
        if (step == OnboardingStep.ModelDownload &&
            environment.modelState is ModelArtifactState.Complete
        ) {
            step = OnboardingStep.Wiring
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micGranted = hasRecordAudio(context)
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val callbacks = buildCallbacks(
        context = context,
        prefs = prefs,
        launchers = launchers,
        handles = OnboardingStateHandles(
            setStep = { step = it },
            setPersona = { persona = it },
            currentPersona = { persona },
            advance = advance,
        ),
        onComplete = onComplete,
    )
    val state = OnboardingStepState(
        step = step,
        persona = persona,
        micPermissionDenied = micPermissionDenied,
        wifiConnected = environment.wifiConnected,
        modelState = environment.modelState,
        micGranted = micGranted,
        notifGranted = notifGranted,
        downloadMbps = environment.downloadMbps,
    )
    // VestigeScaffold owns floor/ink color propagation per AGENTS rule 26.
    VestigeScaffold(modifier = modifier) { padding ->
        OnboardingStepContent(
            state = state,
            callbacks = callbacks,
            context = context,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/** Bundles the host's mutable state seam so [buildCallbacks] stays under S107's parameter cap. */
private data class OnboardingStateHandles(
    val setStep: (OnboardingStep) -> Unit,
    val setPersona: (Persona) -> Unit,
    val currentPersona: () -> Persona,
    val advance: () -> Unit,
)

private fun buildCallbacks(
    context: Context,
    prefs: OnboardingPrefs,
    launchers: PermissionLaunchers,
    handles: OnboardingStateHandles,
    onComplete: (Persona) -> Unit,
): OnboardingStepCallbacks = OnboardingStepCallbacks(
    onPersonaChange = handles.setPersona,
    advance = handles.advance,
    onMicAllow = {
        if (!hasRecordAudio(context)) launchers.mic.launch(Manifest.permission.RECORD_AUDIO)
    },
    onNotificationAllow = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchers.notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    },
    onOpenWifiSettings = { openWifiSettings(context) },
    onComeBackLater = { moveTaskToBack(context) },
    onOpenApp = {
        // Trust the Wiring gate — re-running modelAvailability.status() would re-SHA the
        // 3.66 GB artifact and stall the button for tens of seconds.
        if (prefs.markComplete()) {
            onComplete(handles.currentPersona())
        }
    },
    onOpenModelDownload = { handles.setStep(OnboardingStep.ModelDownload) },
    onDownloadReturn = { handles.setStep(OnboardingStep.Wiring) },
    onChangePersona = { handles.setStep(OnboardingStep.PersonaPick) },
)

private data class OnboardingEnvironment(
    val wifiConnected: Boolean,
    val modelState: ModelArtifactState,
    val downloadMbps: Float?,
)

@Composable
private fun rememberOnboardingEnvironment(
    prefs: OnboardingPrefs,
    step: OnboardingStep,
    wifiAvailability: WifiAvailability,
    modelAvailability: ModelAvailability,
    downloadDispatcher: CoroutineDispatcher,
): OnboardingEnvironment {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var wifiConnected by remember { mutableStateOf(wifiAvailability.isWifiConnected()) }
    var modelState by remember { mutableStateOf<ModelArtifactState>(ModelArtifactState.Absent) }
    var downloadMbps by remember { mutableStateOf<Float?>(null) }

    // Persist resume point + refresh the Wi-Fi snapshot. commit() runs on IO so the durability
    // guarantee survives a process kill mid-step without blocking the main thread.
    LaunchedEffect(step) {
        withContext(Dispatchers.IO) { prefs.setCurrentStep(step) }
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
        runDownloadIfNeeded(
            step = step,
            modelAvailability = modelAvailability,
            downloadDispatcher = downloadDispatcher,
            onState = { modelState = it },
            onSpeed = { downloadMbps = it },
        )
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
        downloadMbps = downloadMbps,
    )
}

// Runs the download with diagnostic logs + live MB/s sampling.
@Suppress("LongParameterList")
private suspend fun runDownloadIfNeeded(
    step: OnboardingStep,
    modelAvailability: ModelAvailability,
    downloadDispatcher: CoroutineDispatcher,
    onState: (ModelArtifactState) -> Unit,
    onSpeed: (Float?) -> Unit,
) {
    if (step != OnboardingStep.ModelDownload) return
    val current = modelAvailability.status()
    Log.i(ONBOARDING_TAG, "ModelDownload entered. currentState=$current")
    if (current is ModelArtifactState.Complete) {
        onState(current)
        Log.i(ONBOARDING_TAG, "Model already complete; skipping download.")
        return
    }
    // For Absent / Partial / Corrupt: don't publish `current` to state. `currentState()` reports
    // Absent any time the .part file exists but the final artifact doesn't — publishing that
    // on re-entry collapses the percent to 0 before the resumed download's first onProgress
    // ticks it back to the actual byte count. Result: percent appears to flash. Letting the
    // prior `state` ride keeps the screen showing the last known progress until the resumed
    // bytes arrive.
    val tracker = DownloadProgressTracker(onState, onSpeed)
    try {
        Log.i(ONBOARDING_TAG, "Starting download()...")
        val terminal = withContext(downloadDispatcher) {
            modelAvailability.download(tracker::onProgress)
        }
        onSpeed(null)
        Log.i(ONBOARDING_TAG, "download() finished. terminalState=$terminal")
        onState(terminal)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        // Normal coroutine teardown when the user navigates away from ModelDownload. The
        // .part file persists; HTTP-Range resume picks up where we left off on re-entry.
        // Re-throw so structured concurrency keeps working — never swallow cancellation.
        Log.i(ONBOARDING_TAG, "download() cancelled (user navigated away)")
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.e(ONBOARDING_TAG, "Model download failed", error)
    }
}

/**
 * Wraps the per-tick progress bookkeeping for [runDownloadIfNeeded]. Splitting speed sampling
 * and pct logging into their own methods keeps the orchestrator's cognitive complexity within
 * S3776's limit and lets each helper be reasoned about in isolation.
 *
 * The sample baseline is set on the FIRST `onProgress` callback (not zero-initialised) because
 * HTTP-Range resume reports the .part file's existing length as the first `currentBytes` — a
 * zero baseline would emit an absurd MB/s spike before the next chunk arrives.
 */
internal class DownloadProgressTracker(
    private val onState: (ModelArtifactState) -> Unit,
    private val onSpeed: (Float?) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastPct = -1
    private var sampleBytes = -1L
    private var sampleTimeMs = 0L

    fun onProgress(currentBytes: Long, expectedBytes: Long) {
        onState(ModelArtifactState.Partial(currentBytes, expectedBytes))
        sampleSpeed(currentBytes)
        logPercent(currentBytes, expectedBytes)
    }

    private fun sampleSpeed(currentBytes: Long) {
        val now = nowMillis()
        if (sampleBytes < 0L) {
            sampleBytes = currentBytes
            sampleTimeMs = now
            return
        }
        val elapsed = now - sampleTimeMs
        if (elapsed < SPEED_SAMPLE_INTERVAL_MS) return
        val deltaBytes = (currentBytes - sampleBytes).coerceAtLeast(0L)
        val mbps = (deltaBytes.toFloat() / BYTES_PER_MB) / (elapsed.toFloat() / MS_PER_SECOND)
        onSpeed(mbps.coerceAtLeast(0f))
        sampleBytes = currentBytes
        sampleTimeMs = now
    }

    private fun logPercent(currentBytes: Long, expectedBytes: Long) {
        val pct = if (expectedBytes > 0L) {
            ((currentBytes * PERCENT_LOG_SCALE) / expectedBytes).toInt()
        } else {
            -1
        }
        if (pct != lastPct) {
            lastPct = pct
            Log.d(ONBOARDING_TAG, "download progress $currentBytes/$expectedBytes ($pct%)")
        }
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
