package dev.anchildress1.vestige

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.anchildress1.vestige.ui.patterns.PatternsHost
import dev.anchildress1.vestige.ui.theme.VestigeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VestigeApplication).appContainer
        setContent {
            VestigeTheme {
                var showPatterns by rememberSaveable { mutableStateOf(false) }
                if (showPatterns) {
                    PatternsHost(
                        patternStore = container.patternStore,
                        patternRepo = container.patternRepo,
                        entryStore = container.entryStore,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        PhaseOneShell(
                            onOpenPatterns = { showPatterns = true },
                            modifier = Modifier.padding(padding),
                        )
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
private fun PhaseOneShell(onOpenPatterns: () -> Unit = {}, modifier: Modifier = Modifier) {
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = stringResource(id = R.string.app_name))

        when {
            permissionGranted -> Text(text = stringResource(id = R.string.mic_permission_granted))
            lastRequestDenied -> Text(text = stringResource(id = R.string.mic_permission_denied))
        }

        Button(
            onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
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
    }
}

@Preview
@Composable
private fun PhaseOneShellPreview() {
    VestigeTheme {
        PhaseOneShell()
    }
}
