package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import java.time.Instant
import java.time.ZoneId

internal val capturedAt = Instant.parse("2026-05-09T08:00:00Z").atZone(ZoneId.of("America/Chicago"))

internal val request = BackgroundExtractionRequest(entryText = "user words", capturedAt = capturedAt)

internal val resolved = ResolvedExtraction(
    fields = mapOf(
        "energy_descriptor" to ResolvedField("crashed", ConfidenceVerdict.CANONICAL),
        "state_shift" to ResolvedField(true, ConfidenceVerdict.CANONICAL),
    ),
)

internal fun extraction(lens: Lens, label: String = "aftermath"): LensExtraction = LensExtraction(
    lens = lens,
    fields = mapOf("template_label" to label),
)

internal fun fakeComposer(): (Lens, String, List<HistoryChunk>) -> ComposedPrompt = { lens, _, _ ->
    ComposedPrompt(
        lens = lens,
        systemInstruction = "prompt-for-$lens",
        userText = "entry-text",
        tokenEstimate = 100,
    )
}

internal class RecordingResolver(val resolved: ResolvedExtraction) : ConvergenceResolver {
    var captured: List<LensExtraction> = emptyList()
    override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction {
        captured = extractions
        return resolved
    }
}

internal class RecordingListener : ExtractionStatusListener {
    data class Update(val status: ExtractionStatus, val entryAttemptCount: Int, val lastError: String?)

    val updates: MutableList<Update> = mutableListOf()
    override suspend fun onUpdate(status: ExtractionStatus, entryAttemptCount: Int, lastError: String?) {
        updates += Update(status, entryAttemptCount, lastError)
    }
}
