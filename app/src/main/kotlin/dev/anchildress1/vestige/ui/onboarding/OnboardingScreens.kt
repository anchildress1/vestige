package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.anchildress1.vestige.R
import dev.anchildress1.vestige.model.ModelArtifactState
import dev.anchildress1.vestige.ui.theme.VestigeTheme

// Persona + Mic + Notify default; Local (model) added when Complete.
private const val PREVIEW_ENABLED_WITHOUT_MODEL = 1
private const val PREVIEW_ENABLED_WITH_MODEL = 2

@Composable
@Suppress("LongParameterList", "kotlin:S107") // Optional preview defaults — host passes only live seams.
internal fun ModelDownloadPlaceholderScreen(
    modelState: ModelArtifactState,
    modifier: Modifier = Modifier,
    downloadMbps: Float? = null,
    downloadStatus: DownloadStatus = DownloadStatus(),
    wifiConnected: Boolean = true,
    onRetry: () -> Unit = {},
    onPause: () -> Unit = {},
    enabledCount: Int = if (modelState.isReady) PREVIEW_ENABLED_WITH_MODEL else PREVIEW_ENABLED_WITHOUT_MODEL,
) {
    OnboardingScaffold(
        enabledCount = enabledCount,
        modifier = modifier,
        // No primary — completion auto-unwinds to Wiring (ux-copy.md §Onboarding Screen 3). The
        // only footer action is the labelled cancel / recovery.
        secondary = downloadSecondaryAction(
            modelState = modelState,
            phase = downloadStatus.phase,
            onRetry = onRetry,
            onPause = onPause,
        ),
    ) {
        OnboardingHeadline(text = stringResource(id = R.string.onboarding_download_header))
        BodyParagraph(text = stringResource(id = R.string.onboarding_download_body), dim = true)
        ModelReadinessBanner(
            modelState = modelState,
            downloadMbps = downloadMbps,
            downloadStatus = downloadStatus,
            wifiConnected = wifiConnected,
        )
    }
}

// Stalled → Retry, Failed → Try again, both per ux-copy.md §Onboarding Screen 3. Active →
// Pause (the labelled cancel; `.part` persists, HTTP-Range resumes). Reacquiring is an
// automatic clean re-pull — no manual affordance while it's in flight. Ready → none.
@Composable
private fun downloadSecondaryAction(
    modelState: ModelArtifactState,
    phase: DownloadPhase,
    onRetry: () -> Unit,
    onPause: () -> Unit,
): OnboardingAction? = when {
    modelState.isReady -> null

    phase == DownloadPhase.Stalled ->
        OnboardingAction(stringResource(id = R.string.onboarding_download_retry), onRetry)

    phase == DownloadPhase.Failed ->
        OnboardingAction(stringResource(id = R.string.onboarding_download_try_again), onRetry)

    phase == DownloadPhase.Reacquiring -> null

    else -> OnboardingAction(stringResource(id = R.string.onboarding_download_pause), onPause)
}

@Composable
internal fun BodyParagraph(text: String, dim: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = VestigeTheme.typography.p,
            color = if (dim) VestigeTheme.colors.dim else VestigeTheme.colors.ink,
        )
    }
}
