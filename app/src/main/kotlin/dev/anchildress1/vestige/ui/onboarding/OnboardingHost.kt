package dev.anchildress1.vestige.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.ui.components.VestigeScaffold
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Hosts the onboarding hub flow. Step + persona survive process death via SharedPreferences. */
@Suppress("LongMethod", "LongParameterList") // Orchestration root — wiring is intentionally co-located.
@Composable
fun OnboardingHost(
    prefs: OnboardingPrefs,
    onComplete: (Persona) -> Unit,
    modelAvailability: ModelAvailability,
    modifier: Modifier = Modifier,
    wifiAvailability: WifiAvailability? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val resolvedWifi = remember(context, wifiAvailability) {
        wifiAvailability ?: WifiAvailability.Default(context)
    }
    val persistedStateWriteLane = remember(ioDispatcher) { PersistedStateWriteLane(ioDispatcher) }
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
        persistedStateWriteLane.run {
            prefs.setDefaultPersona(persona)
        }
    }
    val environment = rememberOnboardingEnvironment(
        prefs = prefs,
        step = step,
        wifiAvailability = resolvedWifi,
        modelAvailability = modelAvailability,
        persistedStateWriteLane = persistedStateWriteLane,
        onDownloadBlockedByWifi = { step = OnboardingStep.Wiring },
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

    ObserveBackgroundResume {
        micGranted = hasRecordAudio(context)
        notifGranted = hasNotificationPermission(context)
    }

    val completionScope = rememberCoroutineScope()
    val callbacks = buildCallbacks(
        context = context,
        launchers = launchers,
        handles = OnboardingStateHandles(
            setStep = { step = it },
            setPersona = { persona = it },
            advance = advance,
        ),
        onOpenApp = {
            // Hop to IO for the markComplete commit() — staying on main would block the click
            // handler and trip StrictMode's detectDiskWrites. Only flip the gate on success.
            completionScope.launch {
                if (persistedStateWriteLane.run { prefs.markComplete() }) {
                    onComplete(persona)
                }
            }
        },
        onRetryDownload = environment.onRetryDownload,
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
        downloadStatus = environment.downloadStatus,
    )
    // VestigeScaffold owns floor/ink propagation + system-bar insets — onboarding never paints
    // its own background or applies its own padding, so it stays in lockstep with the rest of
    // the app shell instead of drifting per surface.
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
    val advance: () -> Unit,
)

@Suppress("LongParameterList") // Callback assembler — host seams are intentionally co-located.
private fun buildCallbacks(
    context: Context,
    launchers: PermissionLaunchers,
    handles: OnboardingStateHandles,
    onOpenApp: () -> Unit,
    onRetryDownload: () -> Unit,
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
    onOpenApp = onOpenApp,
    onOpenModelDownload = { handles.setStep(OnboardingStep.ModelDownload) },
    onDownloadReturn = { handles.setStep(OnboardingStep.Wiring) },
    onChangePersona = { handles.setStep(OnboardingStep.PersonaPick) },
    onRetryDownload = onRetryDownload,
)

private data class OnboardingEnvironment(
    val wifiConnected: Boolean,
    val modelState: ModelArtifactState,
    val downloadMbps: Float?,
    val downloadStatus: DownloadStatus,
    val onRetryDownload: () -> Unit,
)

@Composable
@Suppress("LongParameterList") // Environment builder needs the live host seams in one place.
private fun rememberOnboardingEnvironment(
    prefs: OnboardingPrefs,
    step: OnboardingStep,
    wifiAvailability: WifiAvailability,
    modelAvailability: ModelAvailability,
    persistedStateWriteLane: PersistedStateWriteLane,
    onDownloadBlockedByWifi: () -> Unit,
): OnboardingEnvironment {
    val scope = rememberCoroutineScope()
    var wifiConnected by remember { mutableStateOf(wifiAvailability.isWifiConnected()) }
    var modelState by remember { mutableStateOf<ModelArtifactState>(ModelArtifactState.Absent) }
    var downloadMbps by remember { mutableStateOf<Float?>(null) }
    var downloadStatus by remember { mutableStateOf(DownloadStatus()) }
    // Bumping this re-keys the download effect: a Retry/Try-again resumes from the `.part`
    // file via HTTP Range — same code path as a fresh entry, no special-case restart.
    var retryNonce by remember { mutableStateOf(0) }

    // Persist resume point + refresh the Wi-Fi snapshot. commit() runs on IO so the durability
    // guarantee survives a process kill mid-step without blocking the main thread.
    LaunchedEffect(step) {
        persistedStateWriteLane.run { prefs.setCurrentStep(step) }
        wifiConnected = wifiAvailability.isWifiConnected()
    }

    // Snapshot the model state ONCE on mount for every screen except ModelDownload. Screen 6
    // immediately calls runDownloadIfNeeded(), which begins with its own status() read; doing
    // both on a cold start would SHA the full ~3.7 GB artifact twice before auto-return.
    LaunchedEffect(Unit) {
        if (step != OnboardingStep.ModelDownload) {
            modelState = modelAvailability.status()
        }
    }

    // ModelDownload is only for active/resumable transfers. If the artifact is already complete,
    // or Wi-Fi is unavailable on entry/restoration, resolve that once and unwind back to Wiring.
    // retryNonce re-runs this on a Retry/Try-again tap.
    LaunchedEffect(step, wifiConnected, retryNonce) {
        when (
            runDownloadIfNeeded(
                step = step,
                wifiConnected = wifiConnected,
                modelAvailability = modelAvailability,
                onState = { modelState = it },
                onSpeed = { downloadMbps = it },
                onStatus = { downloadStatus = it },
            )
        ) {
            ModelDownloadEntryResult.BlockedByWifi -> onDownloadBlockedByWifi()
            else -> Unit
        }
    }
    ObserveBackgroundResume {
        scope.launch {
            wifiConnected = wifiAvailability.isWifiConnected()
            modelState = modelAvailability.status()
        }
    }

    return OnboardingEnvironment(
        wifiConnected = wifiConnected,
        modelState = modelState,
        downloadMbps = downloadMbps,
        downloadStatus = downloadStatus,
        onRetryDownload = {
            downloadStatus = DownloadStatus()
            retryNonce++
        },
    )
}

/**
 * Serializes durable SharedPreferences commits onto a single IO lane so stale persona/step
 * writes cannot overtake newer ones. Off-main execution avoids StrictMode disk-write hits;
 * the mutex preserves arrival order across independent LaunchedEffects and completion writes.
 */
internal class PersistedStateWriteLane(
    private val dispatcher: CoroutineDispatcher,
    private val mutex: Mutex = Mutex(),
) {
    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            block()
        }
    }
}

@Composable
private fun ObserveBackgroundResume(onResumeFromBackground: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResumeFromBackground by rememberUpdatedState(onResumeFromBackground)
    DisposableEffect(lifecycleOwner) {
        var enteredBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> enteredBackground = true

                Lifecycle.Event.ON_RESUME -> {
                    if (enteredBackground) {
                        enteredBackground = false
                        currentOnResumeFromBackground()
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private enum class ModelDownloadEntryResult {
    NotOnDownloadScreen,
    BlockedByWifi,
    CompletedAlready,
    DownloadRan,
}

// Resolves a ModelDownload entry exactly once: complete artifacts unwind immediately, Wi-Fi-
// blocked entries bounce back to Wiring, and only active/resumable states start download().
@Suppress("LongParameterList") // Download seam — each callback feeds a distinct UI surface.
private suspend fun runDownloadIfNeeded(
    step: OnboardingStep,
    wifiConnected: Boolean,
    modelAvailability: ModelAvailability,
    onState: (ModelArtifactState) -> Unit,
    onSpeed: (Float?) -> Unit,
    onStatus: (DownloadStatus) -> Unit,
): ModelDownloadEntryResult {
    if (step != OnboardingStep.ModelDownload) return ModelDownloadEntryResult.NotOnDownloadScreen
    val current = modelAvailability.status()
    Log.i(ONBOARDING_TAG, "ModelDownload entered. currentState=$current")
    return when {
        current is ModelArtifactState.Complete -> {
            onState(current)
            Log.i(ONBOARDING_TAG, "Model already complete; skipping download.")
            ModelDownloadEntryResult.CompletedAlready
        }

        !wifiConnected -> {
            onState(current)
            onSpeed(null)
            Log.i(ONBOARDING_TAG, "Wi-Fi unavailable; returning to Wiring instead of starting download.")
            ModelDownloadEntryResult.BlockedByWifi
        }

        else -> performModelDownload(
            modelAvailability = modelAvailability,
            resumeFrom = current,
            onState = onState,
            onSpeed = onSpeed,
            onStatus = onStatus,
        )
    }
}

private const val WATCHDOG_TICK_MS = 5_000L

private suspend fun performModelDownload(
    modelAvailability: ModelAvailability,
    resumeFrom: ModelArtifactState,
    onState: (ModelArtifactState) -> Unit,
    onSpeed: (Float?) -> Unit,
    onStatus: (DownloadStatus) -> Unit,
): ModelDownloadEntryResult = coroutineScope {
    // `probe()` reports the resumable `.part` as Partial, so seeding it makes a cold-process
    // re-entry show the real resumed byte count immediately instead of flashing 0%.
    if (resumeFrom is ModelArtifactState.Partial) onState(resumeFrom)

    var status = DownloadStatus()
    fun publish(next: DownloadStatus) {
        status = next
        onStatus(next)
    }

    val lastProgressAtMs = AtomicLong(System.currentTimeMillis())
    val tracker = DownloadProgressTracker(
        onState = {
            lastProgressAtMs.set(System.currentTimeMillis())
            if (status.phase == DownloadPhase.Stalled) publish(status.copy(phase = DownloadPhase.Active))
            onState(it)
        },
        onSpeed = onSpeed,
        onEta = { publish(status.copy(etaSeconds = it)) },
    )
    val watchdog = launch {
        while (isActive) {
            delay(WATCHDOG_TICK_MS)
            if (status.phase == DownloadPhase.Active &&
                isStalled(lastProgressAtMs.get(), System.currentTimeMillis())
            ) {
                publish(status.copy(phase = DownloadPhase.Stalled))
            }
        }
    }
    try {
        Log.i(ONBOARDING_TAG, "Starting download()...")
        // `ModelAvailability.download` is a suspend fun and main-safe by convention
        // (`DefaultModelArtifactStore.download` already wraps its blocking I/O in
        // `withContext(ioDispatcher)`). Wrapping again here would be redundant and trips
        // Sonar S6311.
        var terminal = modelAvailability.download(tracker::onProgress)
        if (terminal is ModelArtifactState.Corrupt) {
            // The store already wiped the bad payload. One automatic clean re-pull, surfaced —
            // a silent retry would hide a persistently broken artifact host.
            Log.w(ONBOARDING_TAG, "Artifact corrupt post-download; re-pulling once.")
            publish(status.copy(phase = DownloadPhase.Reacquiring))
            terminal = modelAvailability.download(tracker::onProgress)
        }
        onSpeed(null)
        onState(terminal)
        if (terminal is ModelArtifactState.Corrupt) publish(status.copy(phase = DownloadPhase.Failed))
        Log.i(ONBOARDING_TAG, "download() finished. terminalState=$terminal")
        ModelDownloadEntryResult.DownloadRan
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        // Normal coroutine teardown when the user navigates away from ModelDownload. The
        // .part file persists; HTTP-Range resume picks up where we left off on re-entry.
        // Re-throw so structured concurrency keeps working — never swallow cancellation.
        Log.i(ONBOARDING_TAG, "download() cancelled (user navigated away)")
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.e(ONBOARDING_TAG, "Model download failed", error)
        publish(status.copy(phase = DownloadPhase.Failed))
        ModelDownloadEntryResult.DownloadRan
    } finally {
        watchdog.cancel()
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
