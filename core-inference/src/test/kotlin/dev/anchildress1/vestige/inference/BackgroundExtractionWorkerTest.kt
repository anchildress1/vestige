package dev.anchildress1.vestige.inference

import dev.anchildress1.vestige.model.ConfidenceVerdict
import dev.anchildress1.vestige.model.ExtractionStatus
import dev.anchildress1.vestige.model.Lens
import dev.anchildress1.vestige.model.LensExtraction
import dev.anchildress1.vestige.model.ResolvedExtraction
import dev.anchildress1.vestige.model.ResolvedField
import dev.anchildress1.vestige.model.TemplateLabel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@Suppress("LargeClass") // Cohesive worker-contract suite; splitting would scatter the shared fixture.
class BackgroundExtractionWorkerTest {

    // 17:00Z = 12:00 Chicago (CDT) — outside the goblin window, so a non-committal/absent model pick
    // resolves to AUDIT by default. Goblin-window cases use explicit timestamps in their own tests.
    private val capturedAt = Instant.parse("2026-05-09T17:00:00Z").atZone(ZoneId.of("America/Chicago"))
    private val goblinCapturedAt = Instant.parse("2026-05-09T08:00:00Z").atZone(ZoneId.of("America/Chicago"))
    private val request = BackgroundExtractionRequest(entryText = "user words", capturedAt = capturedAt)
    private val resolved = ResolvedExtraction(
        fields = mapOf(
            "tags" to ResolvedField(listOf("crashed"), ConfidenceVerdict.CONSENSUS),
        ),
    )

    private fun extraction(lens: Lens, label: String = "aftermath"): LensExtraction = LensExtraction(
        lens = lens,
        fields = mapOf("template_label" to label),
    )

    private fun fakeComposer(): (Lens, String, List<HistoryChunk>) -> ComposedPrompt = { lens, _, _ ->
        ComposedPrompt(
            lens = lens,
            systemInstruction = "prompt-for-$lens",
            userText = "entry-text",
            tokenEstimate = 100,
        )
    }

    private fun proseWrappedSkepticalLines(): String = """
        Here is my skeptical read of the entry.
        tags: sink, noon, 1pm, three-hours-later
        flag: commitment-without-anchor | completely fine by 1pm i was gone not tired exactly | ${
        "The user describes a state of being fine then immediately negates it with 'not tired exactly'."
    }
        done.
    """.trimIndent()

    private fun skepticalFlag(): String =
        "commitment-without-anchor:completely fine by 1pm i was gone not tired exactly:" +
            "The user describes a state of being fine then immediately negates it with 'not tired exactly'."

    private class RecordingResolver(val resolved: ResolvedExtraction) : ConvergenceResolver {
        var captured: List<LensExtraction> = emptyList()
        override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction {
            captured = extractions
            return resolved
        }
    }

    private class RecordingListener : ExtractionStatusListener {
        data class Update(val status: ExtractionStatus, val entryAttemptCount: Int, val lastError: String?)
        val updates: MutableList<Update> = mutableListOf()
        override suspend fun onUpdate(status: ExtractionStatus, entryAttemptCount: Int, lastError: String?) {
            updates += Update(status, entryAttemptCount, lastError)
        }
    }

    @Test
    fun `runs three lenses sequentially and resolves on first-attempt success`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any(), any()) } returns flowOf("raw-literal")
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any(), any()) } returns flowOf("raw-skeptical")
        val resolver = RecordingResolver(resolved)
        val seenRaws = mutableMapOf<Lens, String>()
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            seenRaws[lens] = raw
            extraction(lens)
        }
        val listener = RecordingListener()

        val worker = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = parser,
            composer = fakeComposer(),
        )

        val result = worker.extract(
            request = BackgroundExtractionRequest(entryText = "the user said something", capturedAt = capturedAt),
            listener = listener,
        )

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertAll(
            { assertSame(resolved, success.resolved) },
            { assertEquals(TemplateLabel.AUDIT, success.templateLabel) },
            { assertEquals(3, success.lensResults.size) },
            { assertEquals(3, success.modelCallCount) },
            {
                assertEquals(
                    listOf(Lens.LITERAL, Lens.INFERENTIAL, Lens.SKEPTICAL),
                    success.lensResults.map {
                        it.lens
                    },
                )
            },
            { assertEquals(3, resolver.captured.size) },
            {
                assertEquals(
                    mapOf(
                        Lens.LITERAL to "raw-literal",
                        Lens.INFERENTIAL to "raw-inferential",
                        Lens.SKEPTICAL to "raw-skeptical",
                    ),
                    seenRaws,
                )
            },
        )
        // Listener fires exactly twice on the happy path: initial RUNNING and terminal COMPLETED.
        // No retry events since every lens parsed cleanly on attempt 1.
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `single inferential lens config runs one model call and resolves it`() = runTest {
        // Tuning-harness config: one Inferential pass, no cross-lens convergence.
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returns flowOf("raw-inferential")
        val resolver = RecordingResolver(resolved)
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
            lenses = listOf(Lens.INFERENTIAL),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertAll(
            { assertEquals(1, success.lensResults.size) },
            { assertEquals(1, success.modelCallCount) },
            { assertEquals(listOf(Lens.INFERENTIAL), success.lensResults.map { it.lens }) },
            { assertEquals(1, resolver.captured.size) },
            { assertEquals(Lens.INFERENTIAL, resolver.captured.single().lens) },
        )
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `lenses run sequentially, never concurrently — SDK is single-session`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        val inFlight = AtomicInteger(0)
        var maxInFlight = 0
        val callOrder = mutableListOf<String>()
        every { engine.streamText(any(), any(), any()) } answers {
            flow {
                callOrder += firstArg<String>()
                inFlight.incrementAndGet().also { if (it > maxInFlight) maxInFlight = it }
                delay(10)
                inFlight.decrementAndGet()
                emit("raw")
            }
        }

        BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(entryText = "user words", capturedAt = capturedAt),
        )

        assertAll(
            { assertEquals(1, maxInFlight, "single-session SDK: at most one lens call in-flight at a time") },
            {
                assertEquals(
                    listOf("prompt-for-LITERAL", "prompt-for-INFERENTIAL", "prompt-for-SKEPTICAL"),
                    callOrder,
                    "lenses must run one at a time in LENSES order",
                )
            },
        )
    }

    @Test
    fun `worker parses prose-wrapped skeptical lines without burning retries`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any(), any()) } returns flowOf("tags: sink")
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returns
            flowOf("tags: sink")
        every { engine.streamText("prompt-for-SKEPTICAL", any(), any()) } returns flowOf(proseWrappedSkepticalLines())
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val skepticalResult = success.lensResults.first { it.lens == Lens.SKEPTICAL }
        assertAll(
            { assertNotNull(skepticalResult.extraction) },
            { assertEquals(1, skepticalResult.attemptCount) },
            { assertNull(skepticalResult.lastError) },
            {
                assertEquals(
                    listOf(skepticalFlag()),
                    skepticalResult.extraction!!.flags,
                )
            },
            { assertEquals(3, success.modelCallCount) },
        )
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `retries a lens once on parse failure and counts both attempts`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any(), any()) } returnsMany
            listOf(flowOf("garbage-1"), flowOf("raw-literal"))
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any(), any()) } returns flowOf("raw-skeptical")
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw == "garbage-1") null else extraction(lens)
        }
        val listener = RecordingListener()

        val worker = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        )

        val result = worker.extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(4, success.modelCallCount, "1 retry on LITERAL + 1 each on INFERENTIAL/SKEPTICAL = 4")
        assertEquals(2, success.lensResults.first { it.lens == Lens.LITERAL }.attemptCount)
        // Single RUNNING for the whole worker: per-lens retries no longer emit their own status.
        // The retry is still observable on the lens result's attemptCount above.
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 0, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 0, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `lens that exhausts retry budget contributes null extraction and convergence still runs`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any(), any()) } returns flowOf("raw-literal")
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returnsMany
            listOf(flowOf("garbage-1"), flowOf("garbage-2"))
        every { engine.streamText("prompt-for-SKEPTICAL", any(), any()) } returns flowOf("raw-skeptical")
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw.startsWith("garbage")) null else extraction(lens)
        }
        val resolver = RecordingResolver(resolved)
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(
            2,
            resolver.captured.size,
            "INFERENTIAL exhausted its budget; LITERAL + SKEPTICAL feed convergence",
        )
        val inferentialResult = success.lensResults.first { it.lens == Lens.INFERENTIAL }
        assertAll(
            { assertNull(inferentialResult.extraction) },
            { assertEquals(2, inferentialResult.attemptCount) },
            { assertEquals("parse-fail", inferentialResult.lastError) },
        )
        // 1 (LITERAL ok) + 2 (INFERENTIAL exhausted) + 1 (SKEPTICAL ok) = 4
        assertEquals(4, success.modelCallCount)
    }

    @Test
    fun `every lens failing causes Failed result without invoking the resolver`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("garbage-always")
        val resolver = RecordingResolver(resolved)
        val parser: (Lens, String) -> LensExtraction? = { _, _ -> null }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = resolver,
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val failed = assertInstanceOf(BackgroundExtractionResult.Failed::class.java, result)
        assertAll(
            { assertEquals(6, failed.modelCallCount, "3 lenses × 2 attempts each = 6") },
            { assertEquals(3, failed.lensResults.size) },
            { assertTrue(failed.lensResults.all { it.extraction == null }) },
            { assertEquals("parse-fail", failed.lastError) },
            {
                assertEquals(
                    emptyList<LensExtraction>(),
                    resolver.captured,
                    "Resolver must not be invoked when all lenses fail",
                )
            },
        )
        assertEquals(ExtractionStatus.FAILED, listener.updates.last().status)
        assertEquals(0, listener.updates.last().entryAttemptCount)
    }

    @Test
    fun `engine error on a lens is treated as a parse failure for retry accounting`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any(), any()) } throws IllegalStateException("OOM-like")
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any(), any()) } returns flowOf("raw-skeptical")
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw.isEmpty()) null else extraction(lens)
        }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        val literalResult = success.lensResults.first { it.lens == Lens.LITERAL }
        assertAll(
            { assertNull(literalResult.extraction) },
            { assertEquals(2, literalResult.attemptCount) },
            { assertNotNull(literalResult.lastError) },
            { assertTrue(literalResult.lastError!!.startsWith("engine-error:")) },
        )
        val terminal = listener.updates.last()
        assertEquals(ExtractionStatus.COMPLETED, terminal.status)
        assertNull(terminal.lastError)
    }

    @Test
    fun `specific semantic model pick wins, even inside the goblin window`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        // The model converged on a specific semantic archetype. It stands as the model's read — the
        // deterministic goblin/audit layer is not consulted, even though this capture is at 03:00.
        val modelLabeled = ResolvedExtraction(
            fields = mapOf(
                "template_label" to ResolvedField("decision-spiral", ConfidenceVerdict.CONSENSUS),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(modelLabeled),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = BackgroundExtractionRequest(entryText = "user words", capturedAt = goblinCapturedAt))

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.DECISION_SPIRAL, success.templateLabel)
    }

    @Test
    fun `model goblin-hours pick stands on its own, even outside the goblin window`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        // The model itself converged on goblin-hours. That is "what the model said" — it stands; the
        // capture-time override only ever promotes `audit`, it never demotes a model goblin pick.
        val modelGoblin = ResolvedExtraction(
            fields = mapOf("template_label" to ResolvedField("goblin-hours", ConfidenceVerdict.CONSENSUS)),
        )

        // request's capturedAt is 12:00 — outside the goblin window.
        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(modelGoblin),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.GOBLIN_HOURS, success.templateLabel)
    }

    @Test
    fun `converged audit inside the goblin window resolves to GOBLIN_HOURS deterministically`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        // Model picked the non-committal `audit`; the capture is at 03:00, so determinism wins goblin.
        val modelAudit = ResolvedExtraction(
            fields = mapOf("template_label" to ResolvedField("audit", ConfidenceVerdict.CONSENSUS)),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(modelAudit),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = BackgroundExtractionRequest(entryText = "user words", capturedAt = goblinCapturedAt))

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.GOBLIN_HOURS, success.templateLabel)
    }

    @Test
    fun `converged audit outside the goblin window stays AUDIT`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        val modelAudit = ResolvedExtraction(
            fields = mapOf("template_label" to ResolvedField("audit", ConfidenceVerdict.CONSENSUS)),
        )

        // request's capturedAt is 12:00 — outside goblin, so the deterministic layer lands AUDIT.
        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(modelAudit),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.AUDIT, success.templateLabel)
    }

    @Test
    fun `absent or unknown model pick falls to the deterministic layer`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        // No template_label, and an unrecognized serial — both are "no model pick", so the
        // deterministic layer decides. Outside the goblin window that is AUDIT.
        val unknown = ResolvedExtraction(
            fields = mapOf("template_label" to ResolvedField("not-a-real-archetype", ConfidenceVerdict.CONSENSUS)),
        )

        val absentResult = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val unknownResult = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(unknown),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        assertEquals(
            TemplateLabel.AUDIT,
            assertInstanceOf(BackgroundExtractionResult.Success::class.java, absentResult).templateLabel,
        )
        assertEquals(
            TemplateLabel.AUDIT,
            assertInstanceOf(BackgroundExtractionResult.Success::class.java, unknownResult).templateLabel,
        )
    }

    @Test
    fun `a CANDIDATE template pick is not load-bearing and is dropped`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        // A single-lens CANDIDATE serial is not the model's converged pick, so it's dropped to
        // "no pick"; outside the goblin window the deterministic layer lands AUDIT.
        val candidate = ResolvedExtraction(
            fields = mapOf("template_label" to ResolvedField("decision-spiral", ConfidenceVerdict.CANDIDATE)),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(candidate),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.AUDIT, success.templateLabel)
    }

    @Test
    fun `a CONSENSUS_WITH_CONFLICT semantic pick is load-bearing and stands`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        // Converged-but-flagged still counts as the model's pick — it stands.
        val conflicted = ResolvedExtraction(
            fields = mapOf(
                "template_label" to ResolvedField("decision-spiral", ConfidenceVerdict.CONSENSUS_WITH_CONFLICT),
            ),
        )

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(conflicted),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        val success = assertInstanceOf(BackgroundExtractionResult.Success::class.java, result)
        assertEquals(TemplateLabel.DECISION_SPIRAL, success.templateLabel)
    }

    @Test
    fun `inferential lens samples for vocabulary variety while the other lenses stay greedy`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")

        BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = { lens, _ -> extraction(lens) },
            composer = fakeComposer(),
        ).extract(request = request)

        verify { engine.streamText("prompt-for-INFERENTIAL", any(), LiteRtLmEngine.VOCAB_DIVERSITY_SAMPLER) }
        verify { engine.streamText("prompt-for-LITERAL", any(), null) }
        verify { engine.streamText("prompt-for-SKEPTICAL", any(), null) }
    }

    @Test
    fun `worker labels using the capture timestamp's zone, not the JVM default`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        // No model template_label -> the deterministic layer decides purely on the local capture hour.
        val noModelPick = ResolvedExtraction(
            fields = mapOf("tags" to ResolvedField(listOf("spinning"), ConfidenceVerdict.CONSENSUS)),
        )
        val parser: (Lens, String) -> LensExtraction? = { lens, _ -> extraction(lens) }
        // 08:00 UTC = 03:00 Chicago (hour 3, inside goblin) but 08:00 in UTC (hour 8, outside).
        // Same instant, two zones: proves the labeler reads the captured zone, not ambient.
        val instant = Instant.parse("2026-05-09T08:00:00Z")

        val chicagoResult = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(noModelPick),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = instant.atZone(ZoneId.of("America/Chicago")),
            ),
        )

        val utcResult = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(noModelPick),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = instant.atZone(ZoneId.of("UTC")),
            ),
        )

        assertEquals(
            TemplateLabel.GOBLIN_HOURS,
            assertInstanceOf(BackgroundExtractionResult.Success::class.java, chicagoResult).templateLabel,
        )
        assertEquals(
            TemplateLabel.AUDIT,
            assertInstanceOf(BackgroundExtractionResult.Success::class.java, utcResult).templateLabel,
        )
    }

    @Test
    fun `blank entry text fails fast`() {
        val worker = BackgroundExtractionWorker(
            engine = mockk(),
            resolver = RecordingResolver(resolved),
            parser = { _, _ -> null },
            composer = fakeComposer(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                worker.extract(BackgroundExtractionRequest(entryText = "   ", capturedAt = capturedAt))
            }
        }
    }

    @Test
    fun `maxAttemptsPerLens must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundExtractionWorker(
                engine = mockk(),
                resolver = RecordingResolver(resolved),
                maxAttemptsPerLens = 0,
            )
        }
    }

    @Test
    fun `lenses list must not be empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundExtractionWorker(
                engine = mockk(),
                resolver = RecordingResolver(resolved),
                lenses = emptyList(),
            )
        }
    }

    @Test
    fun `terminal listener events carry the caller-supplied entry attempt count`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText("prompt-for-LITERAL", any(), any()) } returnsMany
            listOf(flowOf("garbage-1"), flowOf("raw-literal"))
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returns flowOf("raw-inferential")
        every { engine.streamText("prompt-for-SKEPTICAL", any(), any()) } returns flowOf("raw-skeptical")
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw == "garbage-1") null else extraction(lens)
        }
        val listener = RecordingListener()

        BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(
                entryText = "user words",
                capturedAt = capturedAt,
                entryAttemptCount = 2,
            ),
            listener = listener,
        )

        // Even with a LITERAL retry, the caller's entryAttemptCount=2 rides every emitted event;
        // Per-lens retries no longer emit their own status.
        assertEquals(
            listOf(
                RecordingListener.Update(ExtractionStatus.RUNNING, 2, null),
                RecordingListener.Update(ExtractionStatus.COMPLETED, 2, null),
            ),
            listener.updates,
        )
    }

    @Test
    fun `negative entry attempt count fails fast`() {
        val worker = BackgroundExtractionWorker(
            engine = mockk(),
            resolver = RecordingResolver(resolved),
            parser = { _, _ -> null },
            composer = fakeComposer(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                worker.extract(
                    BackgroundExtractionRequest(
                        entryText = "ok",
                        capturedAt = capturedAt,
                        entryAttemptCount = -1,
                    ),
                )
            }
        }
    }

    @Test
    fun `non-positive timeout fails fast`() {
        val worker = BackgroundExtractionWorker(
            engine = mockk(),
            resolver = RecordingResolver(resolved),
            parser = { _, _ -> null },
            composer = fakeComposer(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                worker.extract(BackgroundExtractionRequest(entryText = "ok", capturedAt = capturedAt, timeoutMs = 0L))
            }
        }
    }

    @Test
    fun `resolver throwing emits terminal FAILED instead of leaving status RUNNING`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        every { engine.streamText(any(), any(), any()) } returns flowOf("raw-ok")
        val parser: (Lens, String) -> LensExtraction? = { lens, _ -> extraction(lens) }
        val throwingResolver = object : ConvergenceResolver {
            override fun resolve(extractions: List<LensExtraction>): ResolvedExtraction = error("resolver-explosion")
        }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = throwingResolver,
            parser = parser,
            composer = fakeComposer(),
        ).extract(request = request, listener = listener)

        val failed = assertInstanceOf(BackgroundExtractionResult.Failed::class.java, result)
        assertTrue(failed.lastError.startsWith("resolver-error:"))
        // Persistence layer needs the terminal transition — without it the entry stalls in RUNNING.
        assertEquals(ExtractionStatus.FAILED, listener.updates.last().status)
        assertTrue(listener.updates.last().lastError!!.startsWith("resolver-error:"))
    }

    @Test
    fun `timeout produces TimedOut with whatever lens results completed before the cap`() = runTest {
        val engine = mockk<LiteRtLmEngine>()
        // Sequential run: LITERAL completes; INFERENTIAL hangs past the cap, so only LITERAL lands
        // in the completed accumulator before timeout cancellation wins.
        every { engine.streamText("prompt-for-LITERAL", any(), any()) } returns flowOf("raw-literal")
        every { engine.streamText("prompt-for-INFERENTIAL", any(), any()) } returns flow {
            delay(Long.MAX_VALUE / 2)
            emit("never")
        }
        every { engine.streamText("prompt-for-SKEPTICAL", any(), any()) } returns flow {
            delay(Long.MAX_VALUE / 2)
            emit("never")
        }
        val parser: (Lens, String) -> LensExtraction? = { lens, raw ->
            if (raw == "raw-literal") extraction(lens) else null
        }
        val listener = RecordingListener()

        val result = BackgroundExtractionWorker(
            engine = engine,
            resolver = RecordingResolver(resolved),
            parser = parser,
            composer = fakeComposer(),
        ).extract(
            request = BackgroundExtractionRequest(entryText = "user words", capturedAt = capturedAt, timeoutMs = 50L),
            listener = listener,
        )

        val timedOut = assertInstanceOf(BackgroundExtractionResult.TimedOut::class.java, result)
        assertEquals(50L, timedOut.timeoutMs)
        // LITERAL completed before the cap; INFERENTIAL was in-flight when the timeout cancelled
        // the run, so only LITERAL is in the accumulator.
        assertEquals(listOf(Lens.LITERAL), timedOut.lensResults.map { it.lens })
        val terminal = listener.updates.last()
        assertEquals(ExtractionStatus.TIMED_OUT, terminal.status)
        assertEquals("timeout-after-50ms", terminal.lastError)
    }
}
