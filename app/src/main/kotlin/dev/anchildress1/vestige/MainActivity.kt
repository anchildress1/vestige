package dev.anchildress1.vestige

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.anchildress1.vestige.debug.DebugPatternSeeder
import dev.anchildress1.vestige.ui.components.AppTop
import dev.anchildress1.vestige.ui.components.AppTopStatus
import dev.anchildress1.vestige.ui.components.VestigeSurface
import dev.anchildress1.vestige.ui.patterns.PatternsHost
import dev.anchildress1.vestige.ui.theme.Coral
import dev.anchildress1.vestige.ui.theme.Ember
import dev.anchildress1.vestige.ui.theme.Floor
import dev.anchildress1.vestige.ui.theme.Lime
import dev.anchildress1.vestige.ui.theme.VestigeTextStyles
import dev.anchildress1.vestige.ui.theme.VestigeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VestigeApplication).appContainer
        setContent {
            VestigeTheme {
                Box(modifier = Modifier.fillMaxSize().background(Floor)) {
                    var showPatterns by rememberSaveable { mutableStateOf(false) }
                    if (showPatterns) {
                        // Back unwinds patterns→shell; without this the activity exits and the user
                        // loses their place in the rough Phase-3 nav.
                        BackHandler { showPatterns = false }
                        PatternsHost(
                            patternStore = container.patternStore,
                            patternRepo = container.patternRepo,
                            entryStore = container.entryStore,
                            onExit = { showPatterns = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ) { padding ->
                            PhaseOneShell(
                                onOpenPatterns = { showPatterns = true },
                                onDebugSeed = if (isDebuggable) {
                                    { DebugPatternSeeder.seed(filesDir, container.boxStore, container.patternStore) }
                                } else {
                                    null
                                },
                                modifier = Modifier.padding(padding),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 1 placeholder shell. Real product UI lands in Phase 4.
 *
 * Wires the mic-permission flow needed for [dev.anchildress1.vestige.inference.AudioCapture]
 * dev runs (Story 1.4). Polished onboarding copy and surfaces ship in Phase 4.
 */
@Composable
private fun PhaseOneShell(
    onOpenPatterns: () -> Unit = {},
    onDebugSeed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var lastRequestDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        lastRequestDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AppTop(
            persona = "WITNESS",
            status = phaseOneTopStatus(
                permissionGranted = permissionGranted,
                lastRequestDenied = lastRequestDenied,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            VestigeSurface(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
            ) {
                PhaseOneShellCard(
                    permissionGranted = permissionGranted,
                    lastRequestDenied = lastRequestDenied,
                    onRequestMicPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenPatterns = onOpenPatterns,
                    onDebugSeed = onDebugSeed,
                )
            }
        }
    }
}

@Composable
private fun phaseOneTopStatus(permissionGranted: Boolean, lastRequestDenied: Boolean): AppTopStatus =
    when (phaseOneAppStatus(permissionGranted, lastRequestDenied)) {
        PhaseOneAppStatus.LOADING -> AppTopStatus(
            text = stringResource(R.string.app_top_loading),
            contentDescription = stringResource(R.string.app_top_loading_description),
            color = Ember,
            dot = false,
            blink = false,
        )

        PhaseOneAppStatus.READY -> AppTopStatus(
            text = stringResource(R.string.app_top_ready),
            contentDescription = stringResource(R.string.app_top_ready_description),
            color = Lime,
            dot = true,
            blink = false,
        )

        PhaseOneAppStatus.MIC_REQUIRED -> AppTopStatus(
            text = stringResource(R.string.app_top_mic_required),
            contentDescription = stringResource(R.string.mic_permission_denied),
            color = Coral,
            dot = false,
            blink = false,
        )
    }

@Composable
private fun PhaseOneShellCard(
    permissionGranted: Boolean,
    lastRequestDenied: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenPatterns: () -> Unit,
    onDebugSeed: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = stringResource(id = R.string.app_name), style = VestigeTextStyles.H1)

        when {
            permissionGranted -> Text(text = stringResource(id = R.string.mic_permission_granted))
            lastRequestDenied -> Text(text = stringResource(id = R.string.mic_permission_denied))
        }

        Button(
            onClick = onRequestMicPermission,
            enabled = !permissionGranted,
            modifier = Modifier.semantics { role = Role.Button },
        ) {
            Text(text = stringResource(id = R.string.mic_permission_request))
        }

        Button(
            onClick = onOpenPatterns,
            modifier = Modifier.semantics { role = Role.Button },
        ) {
            Text(text = stringResource(id = R.string.open_patterns))
        }

        onDebugSeed?.let { seed ->
            Button(
                onClick = seed,
                modifier = Modifier.semantics { role = Role.Button },
            ) {
                Text(text = stringResource(id = R.string.debug_seed_patterns))
            }
        }
    }
}

@Preview
@Composable
private fun PhaseOneShellPreview() {
    VestigeTheme {
        PhaseOneShell()
    }
}

internal enum class PhaseOneAppStatus {
    LOADING,
    READY,
    MIC_REQUIRED,
}

internal fun phaseOneAppStatus(permissionGranted: Boolean, lastRequestDenied: Boolean): PhaseOneAppStatus = when {
    permissionGranted -> PhaseOneAppStatus.READY
    lastRequestDenied -> PhaseOneAppStatus.MIC_REQUIRED
    else -> PhaseOneAppStatus.LOADING
}
