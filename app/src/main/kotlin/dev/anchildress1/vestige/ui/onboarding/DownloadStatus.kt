package dev.anchildress1.vestige.ui.onboarding

import androidx.compose.runtime.Immutable

/**
 * Where the onboarding download stands beyond raw byte progress.
 *
 * - [Active]: bytes are flowing (or about to).
 * - [Stalled]: no bytes for ≥ [STALL_THRESHOLD_MS]; recoverable by Retry, re-arms on next tick.
 * - [Failed]: retries exhausted; recoverable by Try again.
 * - [Reacquiring]: artifact came back corrupt; the bad file was wiped and a fresh pull is running.
 */
internal enum class DownloadPhase { Active, Stalled, Failed, Reacquiring }

@Immutable
internal data class DownloadStatus(val phase: DownloadPhase = DownloadPhase.Active, val etaSeconds: Long? = null)

@Immutable
internal data class DownloadChrome(val downloadMbps: Float?, val downloadStatus: DownloadStatus)

/** No bytes flow for ≥ 30 s ⇒ the transfer is stalled (`ux-copy.md` §Onboarding Screen 3). */
internal const val STALL_THRESHOLD_MS: Long = 30_000L

/** Pure stall test. Re-arming (clearing on the next progress tick) is the caller's job. */
internal fun isStalled(lastProgressAtMs: Long, nowMs: Long, thresholdMs: Long = STALL_THRESHOLD_MS): Boolean =
    nowMs - lastProgressAtMs >= thresholdMs

internal fun downloadChromeOnStepEntry(
    step: OnboardingStep,
    downloadMbps: Float?,
    downloadStatus: DownloadStatus,
): DownloadChrome = if (step == OnboardingStep.ModelDownload) {
    // Re-entering Screen 3 should always start from a live transfer view. Keeping a stale
    // Failed/Stalled phase or last-run MB/s here makes the resumed download look dead until the
    // first fresh progress tick arrives.
    DownloadChrome(downloadMbps = null, downloadStatus = DownloadStatus())
} else {
    DownloadChrome(downloadMbps = downloadMbps, downloadStatus = downloadStatus)
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** ETA label for the `{ETA}` slot in `ux-copy.md` §Onboarding Screen 3. Unknown ⇒ `—`. */
internal fun formatEta(seconds: Long?): String = when {
    seconds == null || seconds < 0L -> "—"
    seconds < SECONDS_PER_MINUTE -> "~${seconds}s"
    seconds < SECONDS_PER_HOUR -> "~${seconds / SECONDS_PER_MINUTE} min"
    else -> "~${seconds / SECONDS_PER_HOUR}h ${(seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE}m"
}
