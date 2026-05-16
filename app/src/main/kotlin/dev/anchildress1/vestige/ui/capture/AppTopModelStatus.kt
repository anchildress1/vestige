package dev.anchildress1.vestige.ui.capture

import androidx.compose.runtime.Composable
import dev.anchildress1.vestige.ui.components.AppTopStatus
import dev.anchildress1.vestige.ui.components.AppTopStatuses
import dev.anchildress1.vestige.ui.theme.VestigeTheme

/**
 * Maps live [ModelReadiness] to the always-visible AppTop status pill per `ux-copy.md`
 * §"Capture Screen / Status row". Recording is owned by `LiveLayout` (it implies Ready), so
 * this only resolves the idle-chrome states. The pill stays lime in every state — coral is
 * reserved for the REC button + destructive flows (`design-guidelines.md` §"AppTop status
 * pill"); only the `Ready` "alive" pulse blinks.
 *
 * `ux-copy.md` does not spell a Loading pill string (it specifies the standalone Model Status
 * screen instead); `GEMMA 4 · LOADING` is the reconciled label, consistent with the
 * `GEMMA 4 · LOCAL ONLY` / `DOWNLOADING · {N}%` / `MODEL PAUSED` set already in the doc.
 */
@Composable
fun appTopStatusFor(readiness: ModelReadiness): AppTopStatus = when (readiness) {
    ModelReadiness.Ready -> AppTopStatuses.Ready

    ModelReadiness.Loading -> AppTopStatus(
        text = "GEMMA 4 · LOADING",
        contentDescription = "Gemma 4 local model. Loading.",
        color = VestigeTheme.colors.lime,
        dot = true,
        blink = false,
    )

    is ModelReadiness.Downloading -> AppTopStatus(
        text = "DOWNLOADING · ${readiness.percent}%",
        contentDescription = "Gemma 4 local model downloading. ${readiness.percent} percent.",
        color = VestigeTheme.colors.lime,
        dot = true,
        blink = false,
    )

    ModelReadiness.Paused -> AppTopStatus(
        text = "MODEL PAUSED",
        contentDescription = "Gemma 4 local model paused. Reconnect to Wi-Fi to resume.",
        color = VestigeTheme.colors.lime,
        dot = true,
        blink = false,
    )
}
