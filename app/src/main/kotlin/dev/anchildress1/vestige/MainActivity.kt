package dev.anchildress1.vestige

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anchildress1.vestige.model.Persona
import dev.anchildress1.vestige.storage.EntryStore
import dev.anchildress1.vestige.ui.capture.CaptureScreen
import dev.anchildress1.vestige.ui.capture.CaptureViewModel
import dev.anchildress1.vestige.ui.capture.ForegroundInferenceCall
import dev.anchildress1.vestige.ui.capture.ForegroundTextInferenceCall
import dev.anchildress1.vestige.ui.capture.IdleChromeCallbacks
import dev.anchildress1.vestige.ui.capture.ModelReadiness
import dev.anchildress1.vestige.ui.capture.RealVoiceCapture
import dev.anchildress1.vestige.ui.capture.SaveAndExtract
import dev.anchildress1.vestige.ui.capture.ToneGeneratorLimitWarningCue
import dev.anchildress1.vestige.ui.capture.deriveLastEntryFooter
import dev.anchildress1.vestige.ui.capture.deriveMeta
import dev.anchildress1.vestige.ui.capture.deriveStats
import dev.anchildress1.vestige.ui.history.EntryDetailOpenRequest
import dev.anchildress1.vestige.ui.history.HistoryHost
import dev.anchildress1.vestige.ui.modelstatus.ModelStatusInfo
import dev.anchildress1.vestige.ui.modelstatus.ModelStatusScreen
import dev.anchildress1.vestige.ui.onboarding.ModelAvailability
import dev.anchildress1.vestige.ui.onboarding.OnboardingHost
import dev.anchildress1.vestige.ui.onboarding.OnboardingPrefs
import dev.anchildress1.vestige.ui.patterns.PatternsHost
import dev.anchildress1.vestige.ui.theme.VestigeTheme
import java.time.Clock
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private var nextLaunchToken: Long = 1L
    private var pendingLaunchTarget by mutableStateOf<PostOnboardingLaunchTarget>(PostOnboardingLaunchTarget.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VestigeApplication).appContainer
        val onboardingPrefs = OnboardingPrefs.from(this)
        val clock = Clock.systemDefaultZone()
        val zoneId: ZoneId = ZoneId.systemDefault()
        pendingLaunchTarget = consumePostOnboardingLaunchTarget(intent, container.entryStore, nextLaunchToken++)
        setContent {
            MainActivityContent(
                container = container,
                onboardingPrefs = onboardingPrefs,
                clock = clock,
                zoneId = zoneId,
                launchTargetController = LaunchTargetController(
                    target = pendingLaunchTarget,
                    onConsumed = { pendingLaunchTarget = PostOnboardingLaunchTarget.None },
                ),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as VestigeApplication).appContainer
        pendingLaunchTarget = consumePostOnboardingLaunchTarget(intent, container.entryStore, nextLaunchToken++)
    }
}

private enum class PostOnboardingScreen { Capture, Patterns, History, ModelStatus }

private data class LaunchTargetController(val target: PostOnboardingLaunchTarget, val onConsumed: () -> Unit)

@Composable
private fun MainActivityContent(
    container: AppContainer,
    onboardingPrefs: OnboardingPrefs,
    clock: Clock,
    zoneId: ZoneId,
    launchTargetController: LaunchTargetController,
) {
    VestigeTheme {
        var onboardingComplete by remember { mutableStateOf(onboardingPrefs.isComplete) }
        var selectedPersona by remember { mutableStateOf(onboardingPrefs.defaultPersona) }
        if (!onboardingComplete) {
            OnboardingHost(
                prefs = onboardingPrefs,
                onComplete = { persona ->
                    selectedPersona = persona
                    onboardingComplete = true
                },
                modelAvailability = ModelAvailability.Default(
                    artifactStore = container.mainModelArtifactStore,
                    networkGate = container.networkGate,
                ),
                modifier = Modifier.fillMaxSize(),
            )
            return@VestigeTheme
        }
        MainPostOnboardingContent(
            container = container,
            persona = selectedPersona,
            clock = clock,
            zoneId = zoneId,
            launchTargetController = launchTargetController,
        )
    }
}

@Composable
private fun MainPostOnboardingContent(
    container: AppContainer,
    persona: Persona,
    clock: Clock,
    zoneId: ZoneId,
    launchTargetController: LaunchTargetController,
) {
    var screen by rememberSaveable { mutableStateOf(PostOnboardingScreen.Capture) }
    var historyOpenRequest by remember { mutableStateOf<EntryDetailOpenRequest?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Re-probe on ON_RESUME so a download that completed in another activity / process
        // is reflected when the user returns. AppContainer no-ops if nothing changed.
        container.refreshModelReadiness()
        // Skip windows that elapse off-screen must wake up no matter which post-onboarding
        // surface the user returns to, not just Capture.
        container.sweepExpiredSkips()
    }
    ConsumeLaunchTarget(
        screen = screen,
        controller = launchTargetController,
        onGoHistory = { request ->
            screen = PostOnboardingScreen.History
            historyOpenRequest = request
        },
    )
    when (screen) {
        PostOnboardingScreen.Capture -> CaptureRoute(
            container = container,
            persona = persona,
            clock = clock,
            zoneId = zoneId,
            onOpenPatterns = { screen = PostOnboardingScreen.Patterns },
            onOpenHistory = { screen = PostOnboardingScreen.History },
            onOpenModelStatus = { screen = PostOnboardingScreen.ModelStatus },
        )

        PostOnboardingScreen.Patterns -> PatternsHost(
            patternStore = container.patternStore,
            patternRepo = container.patternRepo,
            entryStore = container.entryStore,
            zoneId = zoneId,
            dataRevision = container.dataRevision,
            onExit = { screen = PostOnboardingScreen.Capture },
            modifier = Modifier.fillMaxSize(),
        )

        PostOnboardingScreen.History -> HistoryHost(
            entryStore = container.entryStore,
            persona = persona,
            onExit = { screen = PostOnboardingScreen.Capture },
            zoneId = zoneId,
            dataRevision = container.dataRevision,
            openRequest = historyOpenRequest,
            onOpenRequestConsumed = { historyOpenRequest = null },
            modifier = Modifier.fillMaxSize(),
        )

        PostOnboardingScreen.ModelStatus -> ModelStatusRoute(
            container = container,
            onExit = { screen = PostOnboardingScreen.Capture },
        )
    }
}

@androidx.compose.runtime.Composable
private fun ModelStatusRoute(container: AppContainer, onExit: () -> Unit) {
    val modelReadiness by container.modelReadinessFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // buildConfig isn't enabled for this module — read the version off the installed package
    // instead of generating BuildConfig (a build-config change is out of scope here).
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    ModelStatusScreen(
        info = ModelStatusInfo(
            readiness = modelReadiness,
            sizeLabel = gigabyteLabel(container.mainModelExpectedByteSize),
            versionName = versionName,
        ),
        onReDownload = container::redownloadMainModel,
        onDelete = container::deleteMainModel,
        onExit = onExit,
        modifier = Modifier.fillMaxSize(),
    )
}

private const val BYTES_PER_GB = 1_073_741_824.0 // 1024^3

private fun gigabyteLabel(bytes: Long): String = "%.2f GB".format(bytes / BYTES_PER_GB)

@androidx.compose.runtime.Composable
private fun ConsumeLaunchTarget(
    screen: PostOnboardingScreen,
    controller: LaunchTargetController,
    onGoHistory: (EntryDetailOpenRequest?) -> Unit,
) {
    LaunchedEffect(screen, controller.target) {
        when (val target = controller.target) {
            PostOnboardingLaunchTarget.None -> Unit

            is PostOnboardingLaunchTarget.History -> {
                onGoHistory(null)
                controller.onConsumed()
            }

            is PostOnboardingLaunchTarget.HistoryDetail -> {
                onGoHistory(
                    EntryDetailOpenRequest(
                        entryId = target.entryId,
                        highlightOnOpen = false,
                        token = target.token,
                    ),
                )
                controller.onConsumed()
            }
        }
    }
}

internal sealed interface PostOnboardingLaunchTarget {
    object None : PostOnboardingLaunchTarget
    data class History(val token: Long) : PostOnboardingLaunchTarget
    data class HistoryDetail(val entryId: Long, val token: Long) : PostOnboardingLaunchTarget
}

internal fun resolvePostOnboardingLaunchTarget(
    intent: Intent?,
    entryStore: EntryStore,
    token: Long,
): PostOnboardingLaunchTarget {
    if (intent?.getBooleanExtra(EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY, false) != true) {
        return PostOnboardingLaunchTarget.None
    }
    val entryId = entryStore.mostRecentNonTerminalEntryId()
    return if (entryId != null) {
        PostOnboardingLaunchTarget.HistoryDetail(entryId = entryId, token = token)
    } else {
        PostOnboardingLaunchTarget.History(token = token)
    }
}

internal fun consumePostOnboardingLaunchTarget(
    intent: Intent?,
    entryStore: EntryStore,
    token: Long,
): PostOnboardingLaunchTarget {
    val target = resolvePostOnboardingLaunchTarget(intent, entryStore, token)
    if (intent?.getBooleanExtra(EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY, false) == true) {
        intent.removeExtra(EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY)
    }
    return target
}

internal const val EXTRA_OPEN_LATEST_IN_FLIGHT_ENTRY: String =
    "dev.anchildress1.vestige.extra.OPEN_LATEST_IN_FLIGHT_ENTRY"

@Suppress("LongParameterList")
@Composable
private fun CaptureRoute(
    container: AppContainer,
    persona: Persona,
    clock: Clock,
    zoneId: ZoneId,
    onOpenPatterns: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModelStatus: () -> Unit,
) {
    val limitWarningCue = remember { ToneGeneratorLimitWarningCue() }
    DisposableEffect(limitWarningCue) {
        onDispose { limitWarningCue.release() }
    }
    val modelReadiness by container.modelReadinessFlow.collectAsStateWithLifecycle()
    val dataRevision by container.dataRevision.collectAsStateWithLifecycle()
    val factory = remember(container, persona, limitWarningCue) {
        CaptureViewModelFactory(
            initialPersona = persona,
            recordVoice = RealVoiceCapture(),
            foregroundInference = ForegroundInferenceCall { audio, sel ->
                container.runForegroundCall(audio = audio, persona = sel)
            },
            saveAndExtract = SaveAndExtract { text, capturedAt, personaSel, durationMs, followUpText ->
                container.saveAndExtract(
                    entryText = text,
                    capturedAt = capturedAt,
                    persona = personaSel,
                    durationMs = durationMs,
                    followUpText = followUpText,
                )
            },
            foregroundTextInference = ForegroundTextInferenceCall { text, personaSel ->
                container.runForegroundTextCall(text = text, persona = personaSel)
            },
            clock = clock,
            zoneId = zoneId,
            initialReadiness = container.modelReadinessFlow.value,
            limitWarningCue = limitWarningCue,
        )
    }
    val viewModel: CaptureViewModel = viewModel(factory = factory)
    // Pipe live model-readiness changes into the VM so REC enablement, the error band, and
    // the inferring-vs-loading chrome stay in sync if the artifact transitions during the
    // session (download completes, pauses, or is removed via Settings).
    LaunchedEffect(viewModel, modelReadiness) { viewModel.setModelReadiness(modelReadiness) }
    // `dataRevision` as a remember key forces re-derivation whenever AppContainer increments
    // it (entry write / pattern write / recovery sweep). Cheap — entryStore.countCompleted +
    // patternStore.findVisibleSortedByLastSeen are indexed reads.
    val stats = remember(container, dataRevision) { deriveStats(container) }
    val meta = remember(clock, zoneId) { deriveMeta(clock, zoneId) }
    val lastEntryFooter = remember(container, dataRevision) { deriveLastEntryFooter(container, zoneId) }
    CaptureScreen(
        viewModel = viewModel,
        stats = stats,
        meta = meta,
        modifier = Modifier.fillMaxSize(),
        chrome = IdleChromeCallbacks(
            onStatusTap = onOpenModelStatus,
            onPatternsTap = onOpenPatterns,
            onHistoryTap = onOpenHistory,
            lastEntryFooter = lastEntryFooter,
        ),
    )
}

@Suppress("LongParameterList")
private class CaptureViewModelFactory(
    private val initialPersona: Persona,
    private val recordVoice: RealVoiceCapture,
    private val foregroundInference: ForegroundInferenceCall,
    private val saveAndExtract: SaveAndExtract,
    private val foregroundTextInference: ForegroundTextInferenceCall,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val initialReadiness: ModelReadiness,
    private val limitWarningCue: ToneGeneratorLimitWarningCue,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CaptureViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return CaptureViewModel(
            initialPersona = initialPersona,
            recordVoice = recordVoice,
            foregroundInference = foregroundInference,
            saveAndExtract = saveAndExtract,
            foregroundTextInference = foregroundTextInference,
            clock = clock,
            zoneId = zoneId,
            initialReadiness = initialReadiness,
            limitWarningCue = limitWarningCue,
        ) as T
    }
}
