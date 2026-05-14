package dev.anchildress1.vestige.ui.capture

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Single-shot audible cue when a recording approaches the 30s hard cap. The user picks STOP or
 * lets the cap fire naturally — the cue is informational, not a state transition.
 */
fun interface LimitWarningCue {
    fun fire()
}

/**
 * Android implementation. Uses [ToneGenerator] on the notification stream so the cue respects
 * the user's notification volume (not media volume — playback isn't the right semantic). The
 * generator is constructed lazily on first fire and reused; release on host teardown.
 */
class ToneGeneratorLimitWarningCue : LimitWarningCue {

    private var tone: ToneGenerator? = null

    override fun fire() {
        val gen = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME) }
            .onFailure { Log.w(TAG, "ToneGenerator init failed; skipping limit cue", it) }
            .getOrNull()
            ?.also { tone = it }
            ?: return
        gen.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
    }

    /** Host calls on VM clear so the system audio handle is freed promptly. */
    fun release() {
        tone?.release()
        tone = null
    }

    private companion object {
        const val VOLUME: Int = 60
        const val BEEP_DURATION_MS: Int = 150
        const val TAG = "VestigeLimitCue"
    }
}
