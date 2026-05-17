# Phase 2 — Core Capture Loop

**Status:** In progress
**Dates:** 2026-05-09 – TBD
**References:** `PRD.md` §Phase 2, `concept-locked.md` §"Multi-lens extraction architecture", `concept-locked.md` §Schema, `AGENTS.md`, `architecture-brief.md`, `adrs/ADR-001-stack-and-build-infra.md` §Q3 / §Q4, `adrs/ADR-002-multi-lens-extraction-pattern.md` (entire), `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` (entire), `adrs/ADR-008-parallel-lens-execution.md` (concurrent multi-context restored; mechanism corrected — §Correction 2026-05-16; v1 ships sequential pending measurement), `sample-data-scenarios.md`

---

## Goal

Build the end-to-end capture loop: user records or types → foreground call returns transcription + follow-up at human pace → background 3-lens pipeline runs the multi-lens extraction → convergence resolver writes canonical/candidate/ambiguous fields → entry persists to markdown + ObjectBox. By the end of Phase 2, voice-in produces saved entries with the full content schema populated, and three of the five stop-and-test points (STT-B, STT-C, STT-D) are resolved.

**Output of this phase:** a working capture loop on the reference device. User records one capture per entry (single-turn-per-capture per the STT-B v1 scope choice — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`, which amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"), the foreground response renders fast, and the background 3-lens extraction populates the entry's structured fields within the documented latency budget. No history UI, no patterns engine, no settings screen yet.

---

## Phase-level acceptance criteria

- [x] Capture session can run record → transcription + follow-up → save end-to-end on the reference device.
- [/] **STT-B passes** (or has an explicit fallback recorded): multi-turn conversation maintains context across 3+ exchanges on E4B. If broken, single-turn fallback is implemented and the spec is updated.
- [x] **STT-D passes** (or the architecture is dropped): the 3-lens pipeline produces meaningfully different outputs on at least 30% of the prepared sample transcripts. If lenses always agree, multi-lens drops to single-pass and the Reading section is removed from the entry detail spec. _(2026-05-10 — 5/6 entries (83%) on E4B CPU. See Story 2.7.)_
- [x] **STT-C passes**: tag extraction is stable (≥80% same-tag emission on equivalent test dumps). If unstable, prompts have been tightened until stable, or the limitation is documented. _(2026-05-11 — **PASSED at 1.00** on S24 Ultra GPU. 41/41 (entry, tag) pairs stable across 3 runs over 17 of 18 corpus entries. C2 produced zero tags on all 3 runs (INFERENTIAL + SKEPTICAL parse-fail × 2 retries on GPU) — same regression flagged in Story 2.7's GPU re-run. Stability gate is unaffected; C2 parse-rate is tracked separately.)_
- [x] Convergence resolver implementation lands into Story 1.12's test scaffolding and all happy-path tests pass.
- [/] Agent-emitted template labels work for all six archetypes on representative sample transcripts. _(**Won't pass as designed — structural defect found 2026-05-17.** `TemplateLabeler` consumes only CANONICAL fields; archetype triggers are inferences only the Inferential lens emits → resolves CANDIDATE → discarded → every realistic entry is `AUDIT`. Non-AUDIT only fires when the user speaks the exact internal trigger vocabulary, which is the keyword-stuffed fixture, not a real test. Code retained inert through Phase 2/3; UI yanked in Phase 4 §Story 4.16. Root cause + redesign condition: `backlog.md` §`archetype-template-labeling`. Story 2.10 below carries the full trace.)_
- [x] Background extraction populates the canonical schema on the reference device. **Latency target is ADR-002 sequential for v1** — a scope position, not an SDK limit (ADR-008 concurrent multi-context is restored; adoption gated on Story 2.6.6 / 2.19 measurement). Story 2.7's device record stands as the measured baseline: ~5–7 s per lens, 25–55 s per entry on E4B GPU after the `libOpenCL.so` manifest fix. The 30–90s ceiling remains the timeout guard. _(2026-05-11 — measured under Story 2.7 GPU re-run.)_
- [x] Background extraction lifecycle service is wired per ADR-004 (conditional foreground service): app promotes when extraction begins, demotes after 30-second keep-alive once all extractions reach terminal status. Notification text matches `ux-copy.md` §"Loading States".
- [x] Personas (Witness / Hardass / Editor) demonstrably affect tone on the foreground response without affecting structured field extraction.

---

## Stories

### Story 2.1 — Capture session model and transcript state machine

**As** the AI implementor, **I need** a `CaptureSession` model that owns turn-by-turn state across a single session — recording, awaiting-transcription, model-responded, idle — and a transcript model that records every user turn (transcription text) and every model turn (text response), **so that** the foreground call (Story 2.2) and multi-turn handling (Story 2.4) have a stable place to read and write conversation state.

**Done when:**
- [x] `:core-inference` exposes `CaptureSession` with explicit states: `IDLE`, `RECORDING`, `INFERRING`, `TRANSCRIBED`, `RESPONDED`, `ERROR`. Transitions are explicit; illegal transitions throw. _(Foreground progression is now `INFERRING -> TRANSCRIBED -> RESPONDED`, so the user's transcription can render before the model follow-up.)_
- [x] `Transcript` model holds an ordered list of `Turn { speaker: USER | MODEL, text: String, timestamp: Instant }`. _(`Transcript` is append-only; `turns` returns a defensive snapshot, not the live list.)_
- [x] User turns store transcription text only; no audio bytes (per `AGENTS.md` guardrail 11). _(`Turn.text: String` is the only payload — no `ByteArray`/`FloatArray` field exists on `Turn` or `Transcript`.)_
- [x] Model turns store text response only.
- [x] A unit test exercises the state machine through one full session in memory. _(`CaptureSessionTest."single-turn happy path walks IDLE to RESPONDED in chronological order"` runs the IDLE → RECORDING → INFERRING → TRANSCRIBED → RESPONDED happy path with an injected ticking `Clock` — single-turn-per-capture replaced the original multi-turn shape per the STT-B v1 scope choice; pos/neg/err/edge coverage in sibling tests including the new RESPONDED-is-terminal + ERROR-is-terminal assertions.)_

**Notes / risks:** The transcript is the entry's text-only history (one USER turn + one MODEL turn under the v1 single-use lifecycle), not the storage substrate. `entry_text` must be derived from the USER transcription only — never from the model turn — so retrieval, slugging, and pattern counts stay grounded in the user's words. The v1 single-use lifecycle (RESPONDED is terminal) means one `CaptureSession` instance maps to one entry; the original "one entry corresponds to one full session, multiple turns" framing was retired with the STT-B v1 scope choice (see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`, which amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior").

---

### Story 2.2 — Foreground inference: audio → transcription + follow-up

**As** the AI implementor, **I need** the foreground inference call that takes a normalized audio buffer (from Story 1.4) and the active persona's system prompt (from Story 1.8) and returns a structured `{transcription, follow_up}` response, **so that** the user gets text back at human conversation pace and the entry transcript renders.

**Done when:**
- [x] `:core-inference` exposes a `runForegroundCall(audio, persona): ForegroundResult` API. _(Class `ForegroundInference` in `:core-inference`; `ForegroundResult` is a sealed `Success` / `ParseFailure` pair. Original 3-arg signature `runForegroundCall(audioBuffer, sessionTranscript, persona)` was simplified to 2-arg per the STT-B v1 scope choice — the prompt no longer carries prior-turn context.)_
- [x] ~~The call composes the persona system prompt + the in-session transcript (as multi-turn history) + the new audio buffer.~~ **Reframed by the STT-B v1 scope choice** — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"). Current shape: persona system prompt + output-schema reminder + the new audio buffer. No `## RECENT TURNS` block; ADR-002 §Q5's "last 4 turns" plan is superseded for v1. _(Persona via `PersonaPromptComposer`; audio handed off as `Content.AudioFile` against a temp PCM_S16LE WAV per ADR-001 §Q4.)_
- [x] The response is parsed as structured output (JSON or markdown-with-headers per ADR-002 §"Structured-output reliability"). On parse failure, return a typed error — do not silently retry. _(XML-style tags `<transcription>...</transcription>` / `<follow_up>...</follow_up>` chosen after codex review round 2 surfaced that markdown-with-headers (`## TRANSCRIPTION` / `## FOLLOW_UP`) collide with verbatim transcriptions that legitimately contain those marker lines. Tags bound the section unambiguously, allow multi-line content without escaping the envelope, and remain easy for E4B to emit. `ForegroundResponseParser` returns `ForegroundResult.ParseFailure(EMPTY_RESPONSE | MISSING_TRANSCRIPTION | MISSING_FOLLOW_UP | AMBIGUOUS_BLOCKS)` and preserves `recoveredTranscription` when the transcription block parsed cleanly but the follow-up did not. STT-C will measure the parse-failure rate.)_
- [x] The transcription appears in the transcript before the follow-up renders. _(`ForegroundInference` is pure — it does not advance `CaptureSession`. The caller threads `Success.transcription` through `recordTranscription` (state → TRANSCRIBED) before `recordModelResponse(Success.followUp, persona)` (state → RESPONDED), preserving the Story 2.1 ordering.)_
- [x] Audio buffer is discarded after the call returns. _(Temp WAV is created, used, and `delete()`d in a `finally` block — even on engine error or coroutine cancellation. No `ByteArray` / `FloatArray` is retained on the result type.)_
- [x] Latency on the reference device is recorded; if outside the documented 1–5 second target, log it for ADR-002's latency note. _(`elapsedMs` and `completedAt` populate every `ForegroundResult` (success and failure); each call also emits a `Log.d("VestigeForegroundInference", …)` line carrying persona + elapsed ms + raw-response length. Reference-device measurements still get added under ADR-002 §"Latency budget" once the on-device run lands.)_

**Notes / risks:** ADR-002 §Q1 / §"Default for Phase 1" says non-streaming structured response is the default. Do not introduce token-streaming UI here unless STT-D's structured-output measurements say it's worth the parser complexity. Streaming is a Phase 4 polish decision, not a Phase 2 baseline.

---

### Story 2.3 — Per-capture persona selection

**Reframed 2026-05-09 after the STT-B fallback.** The original story (`Persona switching during session`) assumed multi-turn sessions with persona switches mid-conversation. STT-B failed (Story 2.4 below); v1 is single-turn-per-capture. Persona is now selected before each fresh capture and applies to that single exchange. The "during session" framing dies with the session.

**As** the AI implementor, **I need** persona selection to take effect on the next capture's foreground call, **so that** users can change tone between captures without affecting any prior entry's persona record or the persona-agnostic background extraction.

**Done when:**
- [x] `CaptureSession` exposes `setPersona(persona)` that sets the active persona for the foreground call this session will run. _(`CaptureSession.activePersona` defaults to `Persona.WITNESS` per `concept-locked.md` §Personas. Each `CaptureSession` instance is single-use post-fallback — switching persona means constructing a fresh session with the desired default OR calling `setPersona` before `startRecording`.)_
- [x] Prior entries (separate sessions) retain whichever persona generated them (no rewriting history). _(`Turn.persona` is immutable on construction; each completed entry's `Turn` records its persona. Cross-entry persona history lives in storage, not in any in-memory session.)_
- [x] The capture's foreground call uses the new persona's system prompt (via `PersonaPromptComposer` from Story 1.8). _(Caller threads `session.activePersona` into `ForegroundInference.runForegroundCall(audio, persona)`. The transcript parameter was removed in the STT-B fallback; only persona is threaded.)_
- [x] Background 3-lens extraction (Story 2.6) is **unaffected** by persona — extraction prompts are persona-agnostic per `concept-locked.md` §Personas. _(AGENTS.md guardrail 9 still holds. The fallback didn't loosen this constraint — if anything, it tightened it: the persona's only job is per-capture follow-up tone.)_
- [x] Smoke test: same audio buffer through different personas produces visibly different follow-up text. _(`PerCapturePersonaSmokeTest` — instrumented, manual; iterates `Persona.entries` constructing one fresh `CaptureSession` per persona and running real `ForegroundInference` against a device-pushed WAV. Asserts pairwise-different non-blank follow-ups. The pre-fallback `PersonaSwitchSessionSmokeTest` was removed because its "switch mid-session" framing no longer matches the v1 lifecycle. Verified on S24 Ultra 2026-05-09 across rounds 1–3 of STT-B as a side effect: WITNESS, HARDASS, EDITOR all produced distinct follow-ups under all three rounds — the per-persona divergence is robust even though the multi-turn behavior we hoped for never materialized. **Re-verified 2026-05-10** against the post-fallback `PerCapturePersonaSmokeTest` directly: same divergence + per-call latency dropped from 37–41 s (pre-fallback multi-turn pattern with `## RECENT TURNS` block + `RECENT_TURNS_INSTRUCTION`) to 24.4 / 31.2 / 33.4 s (WITNESS / HARDASS / EDITOR) — mean ~29.7 s, ≈24% faster, attributable to the smaller post-fallback prompt. Per-call latency still outside the 1–5 s ADR-002 §"Latency budget" target on E4B CPU; GPU/NPU work is Phase 4/5 territory.)_

**Notes / risks:** Personas are output-only. If you find yourself wiring persona into the lens prompts, stop — that's a regression. ADR-002 §"Personas are tone, not analysis" is the rule. Post-fallback, the only legitimate `setPersona` use is "the user picked a different tone for the next capture" — there is no longer a "during the same session" use case.

---

### Story 2.4 — STT-B: multi-turn session state on E4B (existential) — **PARTIALLY MEASURED 2026-05-09; v1 scopes to single-turn**

🛑 **Stop-and-test point.** If the model can't maintain context across 3+ exchanges on E4B, drop to single-turn extract-and-respond and rewrite the conversation UX.

**As** the AI implementor, **I need** to verify that Gemma 4 E4B can carry session context across multiple turns when the prior transcript is included in the prompt, **so that** the conversation loop functions as a *conversation* rather than disconnected single-turn extractions.

**Scope of what was measured (correction 2026-05-09).** All three rounds exercised one specific multi-turn pattern: per turn, `LiteRtLmEngine.sendMessageContents` opens `engine.createConversation().use { … }` (a fresh SDK conversation handle) and stuffs prior turns' transcribed text into the system prompt as a JSON `## RECENT TURNS` block. The LiteRT-LM SDK's stateful path — one persistent `Conversation` instance receiving multiple `sendMessage` calls so the SDK's native KV cache + dialogue context carry across turns — was **not** measured. The verdict below is on the prompt-stuffing pattern, not on E4B's multi-turn capability per se. v1 scopes to single-turn-per-capture for simplicity rather than ship a multi-turn UX whose pattern is unverified.

**Done when:**
- [x] ~~A test harness runs at least 5 multi-turn scripted sessions on the reference device, each with 3+ exchanges where turn N+1 depends on context from turn N.~~ _(Harness landed, ran 3 sessions × 4 turns × 3 personas = ~~5+ requirement~~ partial coverage was sufficient to expose the prompt-stuffing pattern's behavior; full ≥5-narrative coverage was unnecessary because every round produced the same shape.)_
- [/] ~~In ≥80% of scripted sessions, the model's response on turn N+1 demonstrably references context from earlier turns~~ — **NOT MET under the prompt-stuffing pattern.** Round 1 reported retention=1.0 but was a manifest-anchor false positive (anchors included substrings introduced in the same turn's audio). Rounds 2 and 3 (corrected anchors + explicit prompt instruction) both produced retention=0.0 across all 9 turn-≥2 lookups in each round. The SDK's stateful Conversation path was not measured; whether this criterion would meet under that path is open.
- [/] ~~Latency per-turn is recorded and stays within the 1–5 second budget on the reference device.~~ — Latency recorded (32.7–65.3 s on E4B CPU); the 1–5 s target is unmet but independent of the multi-turn pattern question (latency tuning is Phase 4/5).
- [/] ~~If the test passes, this story closes and the conversation UX from Stories 2.1–2.3 is the v1 path.~~ — Test did not pass under the pattern measured.
- [x] **If the test fails (model loses context, hallucinates prior turns, or refuses to continue), record the failure mode in ADR-002 §"Multi-turn behavior" and execute the fallback.** _(Recorded in `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior") with round-by-round numbers + the explicit scope-of-test boundary. v1-scope decision executed in this branch's commit bundle: docs rewritten across `concept-locked.md`, `PRD.md`, `design-guidelines.md`; `ForegroundInference` simplified; `CaptureSession` made single-use; `SttBMultiTurnSmokeTest` + `:core-inference/.../sttb/` package + `docs/stt-b-manifest.example.txt` deleted; Story 2.3 reframed.)_

**v1-scope decision executed:** drop multi-turn from v1. Each capture is a single audio chunk → single extraction → save. The conversation transcript becomes a list of independent entries rather than a session. Updates landed across `concept-locked.md` §Personas + §"Two-tier processing" + §"Stack" (transcription handling); `PRD.md` §"Multi-turn" acceptance criterion + §"STT-B" stop-and-test row + §"Phase 2" item 2 + Open Questions; `design-guidelines.md` §"Persona Selector" + §"Entry transcript" (renamed from "Conversation transcript"). Single-turn is the v1 path — no pretense of multi-turn.

**Future revival pointer:** if multi-turn comes back into scope post-v1, the test surface to exercise is `engine.createConversation()` once per session, then multiple `conversation.sendMessage(Contents.of(...))` calls within that single handle — relying on the SDK's native KV cache + dialogue management instead of injecting prior turns into the prompt. The deleted harness's manifest grammar + retention-scorer logic + the Round 1 false-positive lesson on substring-anchor design all live in `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior") as the reference for that future work.

**Notes / risks:** Per `runtime-research.md` and earlier benchmark research, E4B's multi-turn behavior is one of the riskiest assumptions in the spec. The test that ran scoped to the prompt-stuffing pattern; a future revival should exercise the SDK's stateful path before declaring the capability gap closed in either direction.

---

### Story 2.5 — Multi-lens prompt assembly: surfaces, lenses, composer

**As** the AI implementor, **I need** the multi-lens prompt assembly system per `concept-locked.md` §"Multi-lens extraction architecture" — five surface modules, three lens modules, and a composer that produces one prompt per (lens × all surfaces) — **so that** the background extraction (Story 2.6) can run three lens passes on a stored transcript and the convergence resolver (Story 2.8) has structured output to merge.

**Done when:**
- [x] `:core-inference` ships five surface prompt modules: `Behavioral`, `State`, `Vocabulary`, `Commitment`, `Recurrence`. Each is loaded from a checked-in resource file (one per surface). _(Resources at `core-inference/src/main/resources/surfaces/{slug}.txt`. Each module declares only what its surface extracts and which schema fields it populates — lens framing and persona tone live elsewhere.)_
- [x] `:core-inference` ships three lens prompt modules: `Literal`, `Inferential`, `Skeptical`. Each is loaded from a checked-in resource file (one per lens). _(Resources at `core-inference/src/main/resources/lenses/{slug}.txt`. Skeptical owns the `flags` channel; Literal and Inferential return `[]` so convergence can rely on flag origin.)_
- [x] `PromptComposer` produces a complete prompt for `(lens, all five surfaces)` by composing the lens framing on top of the five surface instructions, plus retrieved history (top 3 chunks per ADR-002 §Q2) and the entry text. _(`PromptComposer.compose(lens, entryText, retrievedHistory)` returns `ComposedPrompt(lens, text, tokenEstimate)`. Surface order is canonical (Behavioral → State → Vocabulary → Commitment → Recurrence). History is capped at three chunks with a 600-char per-chunk truncation to keep the block inside the ~500-token budget; an empty list renders the `(no prior entries)` sentinel.)_
- [x] Each composed prompt's total token count is logged so ADR-002 §"Token budget per call" can validate <2K per system block. _(`Log.d("VestigePromptComposer", "compose lens=… chars=… tokens~=… history=…")` per call. Estimate is the cheap 4-chars-per-token rule of thumb; a real tokenizer replaces it if Phase 2 measurements push the budget.)_
- [x] Surface and lens modules are independently loadable and replaceable — no surface code calls a lens module directly, and vice versa. _(Both kinds are plain-text resource files with no executable surface; `PromptComposer` is the only loader and it loads each kind through its own private helper. A lens-only tweak cannot diff a surface prompt and vice versa per ADR-002 §"Why separate storage".)_
- [x] A smoke test asserts that a literal-lens composed prompt and a skeptical-lens composed prompt for the same input differ only in the lens framing block. _(`PromptComposerTest."lens swap differs only in the lens framing block"` extracts the `## Lens:` block (terminated by the first `## Surface:` marker) from each composed prompt, asserts the blocks differ, and asserts the surrounding text is identical after the lens block is removed.)_

**Notes / risks:** Mix-and-match isolation matters. ADR-002 §"Mix-and-match" calls out that we'll be tuning prompts daily — a lens-only tweak should not risk diffing surface prompts.

---

### Story 2.6 — Background extraction worker: 3-lens pipeline runner

**As** the AI implementor, **I need** a background worker that takes a saved transcript and runs three lens passes through `PromptComposer` (Story 2.5), collects three structured extractions, and hands them to the convergence resolver (Story 2.8), **so that** canonical schema fields populate within the 30–90 second background latency budget without blocking the foreground UI.

**Done when:**
- [x] A `BackgroundExtractionWorker` (or equivalent — implementation choice per ADR-001 Q3) runs after the foreground call resolves and the transcript is persisted. _(`BackgroundExtractionWorker` in `:core-inference` exposes `suspend fun extract(request, listener)`, where `BackgroundExtractionRequest` carries `entryText`, persisted `capturedAt`, optional `retrievedHistory`, and retry/timeout metadata. Caller is the entry persistence layer (Story 2.12); the worker takes already-saved transcript text plus the persisted capture timestamp, then emits a `BackgroundExtractionResult` the caller writes back to ObjectBox + markdown.)_
- [x] The worker iterates: for each of the three lenses, compose the prompt (Story 2.5), call LiteRT-LM, parse the structured response. _(`LENSES = [LITERAL, INFERENTIAL, SKEPTICAL]` runs sequentially through `runLens`, which composes via `PromptComposer::compose`, calls `LiteRtLmEngine.generateText`, and parses through `LensResponseParser.parse`. Per ADR-002 §"Why three calls and not one combined call" the lens calls are independent; per ADR-002 §"Background lens prompt" the parser hands back a `LensExtraction` carrying the eight schema keys plus Skeptical-only `flags`.)_
- [x] Per-lens results are stored as a `LensResult { lens, parsed_fields, raw_response }` tuple in memory until convergence resolution completes. _(`LensResult` adds `attemptCount`, `elapsedMs`, and `lastError` alongside the spec'd fields for per-lens diagnostics. `BackgroundExtractionResult.Success` / `Failed` also carry `modelCallCount` for run-level latency/debug surfaces; the entry row's persisted `attempt_count` remains the ADR-001 §Q3 retry counter and is not derived from per-lens model-call volume.)_
- [x] Per-entry operational fields per ADR-001 Q3 (`extraction_status`, `attempt_count`, `last_error`) are updated as the worker progresses through the three calls. _(The worker emits transitions through `ExtractionStatusListener.onUpdate(status, entryAttemptCount, lastError)`: one `RUNNING` at start, one `RUNNING` per lens-level retry, and exactly one terminal `COMPLETED` or `FAILED`. The `lastError` argument carries the latest transient failure for diagnostic surfaces (logs, debug overlays); the persisted `EntryEntity.last_error` field is sweep-terminal only — the persistence layer should clear it on `COMPLETED` and write it on `FAILED`, ignoring intermediate `RUNNING` payloads. The listener is the seam the persistence layer uses to mirror onto the `EntryEntity` row — `:core-inference` does not depend on `:core-storage`.)_
- [x] Latency per entry on the reference device is logged; total foreground + background time per entry is recorded for the latency note. _(`Log.d("VestigeBackgroundExtraction", …)` per lens (`elapsed=Xms`) and per entry (`extract completed: lenses=N/3 model_calls=K elapsed=Yms`). Reference-device measurements get added under ADR-002 §"Latency budget" once the worker is wired into the running app.)_
- [x] If a lens call fails (parse error, model error), the worker retries per ADR-001 Q3's retry policy. Two consecutive failures on the same lens marks the lens as "no opinion" rather than retrying indefinitely. _(`maxAttemptsPerLens = 2` (one initial + one retry) per lens. After exhausting that budget the lens contributes `LensResult(extraction = null, lastError = ...)` and the resolver receives only the lenses that parsed — ADR-002 §"Convergence edge cases" already routes parse-fail to "no opinion" rather than agreement. The original story copy said `extraction_status=ambiguous_partial`; the entry-level enum stays `COMPLETED` (≥1 lens parsed) or `FAILED` (every lens failed) per ADR-001 §Q3, with the per-field ambiguity expressed by the resolver's `ConfidenceVerdict.AMBIGUOUS` rather than a new entry-level status.)_

**Notes / risks:** Lens calls run **sequentially in v1** per `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Background pass" — a scope position, not an SDK limit. ADR-008's concurrent multi-context design is restored (mechanism corrected to `Engine.createSession`/`createConversation`, no `Session.clone()` — ADR-008 §Correction 2026-05-16); concurrent adoption is scope-deferred to Story 2.6.6 / 2.19 pending the RAM + wall-clock measurement, not deferred to `backlog.md`.

The worker does **not** own its own backgrounding behavior. The `BackgroundExtractionService` from Story 2.6.5 wraps the worker and keeps it alive across app backgrounding by promoting the process to a foreground service while extractions are in flight. Don't bake `Service` lifecycle handling into the worker class itself — that's the wrapper's job.

---

### Story 2.6.5 — Background extraction lifecycle service + state machine

**As** the AI implementor, **I need** a `BackgroundExtractionService` that promotes the app to a foreground service whenever an entry's `extraction_status` is `RUNNING` and demotes back to a normal process after a 30-second keep-alive window once all extractions reach terminal status, **so that** background extraction completes reliably even when the user backgrounds the app between record and pattern reveal — and the user sees an on-brand transient notification rather than persistent always-on chrome (per ADR-004 §"Conditional foreground service").

**Done when:**
- [x] `BackgroundExtractionService` declared in `:app/src/main/AndroidManifest.xml` with `android:foregroundServiceType="dataSync"` and the matching runtime-permission stanza for the AGP-current foreground-service-type spec. _(Service registered with `android:exported="false"`; `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions added.)_
- [x] `BackgroundExtractionService` extends `LifecycleService` (or equivalent) and is wired into `AppContainer` per `architecture-brief.md` §"AppContainer Ownership" (the `ModelHandle` row's lifecycle reference points here). _(Phase-2 minimal `AppContainer` constructed from `Application.onCreate`; owns `BackgroundExtractionStatusBus` + `BackgroundExtractionLifecycleStateMachine` and dispatches `startForegroundService` on PROMOTING. `ModelHandle` and other AppContainer rows land in their own stories per the architecture brief. **The `extractionStatusListener(entryId)` / `reportExtractionStatus(entryId, status)` API surface is in place but has no production caller in Phase 2 — Story 2.12 (save flow) wires the worker into it. Lifecycle is structurally sound; data wiring lands with the persistence layer.**)_
- [x] State machine implemented per ADR-004 §"State Machine" + ADR-007 extensions (DEMOTING bounce, FGS retry, OS-kill recovery). _(Pure-Kotlin `BackgroundExtractionLifecycleStateMachine` drives the extended table; `AppContainer` seeds recovered non-terminal entry IDs on cold start (default no-op until EntryStore lands per ADR-006) and exposes the `extractionStatusListener(entryId)` callback the worker will register against in Story 2.12.)_
- [x] 30-second keep-alive timer prevents notification flicker between back-to-back captures. _(Configurable via constructor for tests; default 30s. `cancelKeepAliveAndResume` re-enters FOREGROUND without re-firing the notification.)_
- [x] Notification channel `vestige.local_processing` registered at `Application.onCreate` with importance level **LOW**. Channel registration is idempotent across cold starts. _(`LocalProcessingNotification.registerChannel` invoked from `VestigeApplication.onCreate`. Idempotency relies on `NotificationManager.createNotificationChannel`'s documented contract; pinned by unit test.)_
- [x] Notification text is `Reading the entry.` sourced from `ux-copy.md` §"Loading States" (mirrored as a string resource). _(String resource `notification_local_processing_text` pointed at by both the channel notification and any future in-app placeholder.)_
- [x] Notification tap target launches the app to History (or Capture as an acceptable Phase-2 placeholder). _(PendingIntent into `MainActivity` with `FLAG_IMMUTABLE` + `FLAG_UPDATE_CURRENT`; History deep-link is Phase 4 per Story 4.7.)_
- [x] Multiple in-flight extractions: promote/demote gates on count, not on "current handle in use." _(`BackgroundExtractionStatusBus` tracks the set of non-terminal entry IDs; the count drives the machine. Tests cover queue depths 1/2/3.)_
- [x] Unit test `BackgroundExtractionServiceStateMachineTest` exercises every transition in the ADR-004 §"State Machine" table. _(Cases: cold start NORMAL, single promote/demote cycle, back-to-back during keep-alive (no flicker), keep-alive expiry, recovery-sweep promoting, multi-entry queue, DEMOTING re-promote after ack, promote-then-drain-then-ack arms keep-alive, failed-start retry + retry cancel-on-drain, OS-kill recovery (with and without queued work), late-ack ignore, negative-count rejection.)_
- [x] `POST_NOTIFICATIONS` runtime permission is **not** requested by this story. _(No `POST_NOTIFICATIONS` permission added. Dev builds grant manually via system settings; onboarding screen 3.5 ships in Phase 4.)_
- [x] Tests assert state machine and channel registration only. No notification-posting tests. _(JVM unit tests cover state machine + bus + channel registration shape; `BackgroundExtractionServiceIntegrationTest` (Robolectric `ServiceController`) covers the Service ↔ machine handshake per ADR-007 §"Test coverage contract"; on-device notification visibility is the manual check below.)_

**Notes / risks:** The fallback to ADR-004 §"Option 1 — Always-on foreground service" is evaluated at the end of Phase 4 day 1 per ADR-004 §"Fallback Trigger," not here. If state-machine bugs block Phase 2 progress beyond half a day, escalate per the trigger criteria — don't quietly bypass the conditional logic. Recording the fallback decision goes in ADR-004's "Trigger recorded" line.

**Production wiring gap (intentional, deferred to Story 2.12):** This story stages the lifecycle plumbing but does **not** connect it to extraction events. `AppContainer.extractionStatusListener(entryId)` is callable but no production code calls it — the foreground service therefore never promotes in Phase-2 builds even if the worker runs. Story 2.12 (save flow) wires `BackgroundExtractionWorker` to register an `ExtractionStatusListener` from the container per entry; that's where the loop closes. Treat this story's green checkboxes as "lifecycle is staged for Story 2.12," not "extractions drive the service today."

Don't conflate this story with Story 2.6 (worker logic). Story 2.6 is *what* runs in the background; this story is *the lifecycle wrapper* that keeps the OS from killing it. Wiring is one-directional: the service observes `extraction_status` transitions; the worker doesn't know the service exists.

---

### Story 2.6.6 — Concurrent lens execution via one Engine, many contexts — **SCOPE-DEFERRED pending measurement (not SDK-blocked)**

**Corrected 2026-05-16.** The 2026-05-11 feasibility probe was **mis-scoped** — it searched for a method literally named `Session.clone()`, didn't find one, and wrongly concluded the SDK couldn't do this (recorded in the now-**deleted** ADR-009). A direct AAR bytecode probe of the pinned `litertlm-android:0.11.0` found `Engine.createSession(SessionConfig)` and `Engine.createConversation(ConversationConfig)`: one Engine → many **independent** contexts. ADR-008's decision (concurrent multi-context 3-lens on one Engine) is **restored**; its mechanism is corrected (independent contexts, **no** `Session.clone()` / no CoW shared-prefix) — see [ADR-008 §Correction (2026-05-16)](../adrs/ADR-008-parallel-lens-execution.md#correction-2026-05-16--mechanism-and-performance-premise).

This story is **not** superseded and **not** a `backlog.md` SDK-gap entry. It is **scope-deferred**: v1 ships Story 2.6's sequential `BackgroundExtractionWorker` until this story measures concurrent-context RAM on the reference S24 Ultra and the realized background wall-clock (a single GPU serializes at the command queue — the win is non-blocking foreground preempt, not a literal 3×). Story 2.6.6 / Story 2.19 own that measured adoption call against the 17-day timebox + demo gate. `Done when` boxes are reframed below to the corrected mechanism; they remain unticked because the work (measure, then adopt or hold) is open, not because it was deferred to backlog.

<details>
<summary>Original Story 2.6.6 contents (2026-05-10 draft — mechanism corrected; read clone/CoW/3× through ADR-008 §Correction 2026-05-16)</summary>

**As** the AI implementor, **I need** to refactor `BackgroundExtractionWorker` from sequential lens iteration to parallel execution via LiteRT-LM's Engine/Session API with Copy-on-Write KV-cache (per `adrs/ADR-008-parallel-lens-execution.md`), **so that** per-entry wall-clock drops from ~3× single-call (sequential) to ~1× single-call (parallel) and the extraction queue drains in real-time under ADHD-cadence capture.

Story 2.6 shipped a sequential 3-lens iteration that works correctly but is wall-clock-bound by 3× per-call latency. ADR-008 lifts that constraint by using LiteRT-LM's documented Engine/Session pattern: one Engine owns the model weights; multiple Sessions can run against it; cloned Sessions share KV-cache via CoW until divergence. This is exactly what the 3-lens architecture needs.

**Done when:**
- [ ] `ModelHandle` in `AppContainer` is renamed/clarified to be the **Engine wrapper** per `architecture-brief.md` §"AppContainer Ownership". One Engine per process; loaded once.
- [ ] `BackgroundExtractionWorker` no longer iterates `LENSES = [LITERAL, INFERENTIAL, SKEPTICAL]` sequentially. Instead:
  - Builds **one base Session** per entry containing the shared prefix (system role + 5 surface modules + retrieved history + `entry_text` + output schema reminder). Compute base Session's KV-cache once.
  - **Clones the base Session three times.** Each clone appends one lens module suffix (literal/inferential/skeptical).
  - **Fires all three cloned Sessions concurrently** (coroutine fan-out — `awaitAll` or equivalent).
  - Collects three `LensResult` tuples as they return.
  - Hands the three results to the convergence resolver (Story 2.8), unchanged.
- [ ] Status transitions: `RUNNING` set at fan-out start (single transition, not per-lens). Terminal `COMPLETED` / `TIMED_OUT` / `FAILED` set after all three Sessions return or the time budget trips.
- [ ] Per-clone failure handling: if one cloned Session errors (parse-fail, model error), retry that single clone per ADR-001 Q3's retry policy. Two consecutive failures on the same lens → that lens contributes "no opinion" to the resolver per existing 2-of-3 fallback. The other two clones' results still feed the resolver.
- [ ] Latency budget: ~7–10 seconds wall-clock target on E4B GPU (one parallel call + parallelization overhead + resolver). Logged per entry to `VestigeBackgroundExtraction` tag for measurement.
- [ ] RAM budget verified on reference S24 Ultra: Engine (3.66 GB) + base Session KV + 3 clone Sessions' divergent KV stays under ~5 GB total runtime overhead. Logged at process startup in dev builds.
- [ ] Unit/integration tests cover: happy path (all 3 clones succeed), one clone fails (2-of-3 fallback), all 3 clones fail (`FAILED`), time-budget timeout (`TIMED_OUT`), Session-clone API error on first attempt (**stop and write superseding ADR** per the next bullet — do not silently fall back).
- [ ] If LiteRT-LM Session cloning errors on the E4B audio artifact (any failure that suggests the API isn't supported for this specific model), **stop and write a superseding ADR** that supersedes ADR-008 with the documented failure mode. Do not silently revert to sequential — the wall-clock collapse is the whole point of ADR-008 and a quiet revert defeats it.

**Notes / risks:** Don't refactor Story 2.6's worker tests away. The parsing, retry, and "no opinion" logic Story 2.6 already validated stays correct under parallel; only the iteration shape changes. The convergence resolver (Story 2.8) sees the same `LensResult` shape regardless of execution order.

ADR-008 §"Wall-Clock Math" estimates ~7–9s per entry on E4B GPU after this refactor lands. If measured latency is materially worse than that, the issue is parallelization overhead (LiteRT-LM Session orchestration), not the model — surface it explicitly before assuming the architecture is at fault.

This story is **infra-only**; no user-visible UX changes. The "Reading the entry" notification from ADR-004 stays visible for the (shorter) parallel call's duration. The convergence verdicts the resolver writes are identical.

</details>

---

### Story 2.7 — STT-D: 3-lens divergence verification (existential) — **PASSED 2026-05-10**

🛑 **Stop-and-test point.** If lenses always return identical outputs, the architecture earns nothing visible. Drop multi-lens to single-pass and remove the Reading screen from the entry detail spec.

**As** the AI implementor, **I need** to verify that the three lens passes produce *meaningfully different* outputs on at least 30% of the prepared sample transcripts (per `sample-data-scenarios.md`), **so that** the multi-lens architecture earns its 3× inference cost.

**Done when:**
- [x] The full STT-D sample-transcript set from `sample-data-scenarios.md` runs through the background worker (Story 2.6). _(`SttDLensDivergenceTest` ran A1, A4, B1, B2, C2, D1 on S24 Ultra 2026-05-10 — all 6 entries × 3 lenses parsed cleanly at 1 attempt each, no retries; per-entry latency 127–161s on E4B CPU.)_
- [x] For each transcript, the three lens results are compared field-by-field. Difference count per field is logged. _(`VestigeSttD` logcat tag emits per-lens tags / energy / commitment / flags plus a `disagree_fields=… inferential_only=… skeptical_flags=… meaningful=…` line per entry. Divergence indicators: value disagreement on a populated field, Literal-empty-but-Inferential-populated, or any Skeptical flag.)_
- [x] Across the sample set, at least 30% of transcripts produce at least one field-level disagreement among lenses. _(**5/6 entries (83%)** flagged `meaningful=true`. Divergence was tag-set differences on A1/A4/B1/D1, plus Skeptical flags on A1 and B2. Only C2 was unanimous — all three lenses converged on `[task-app, decision-loop]`.)_
- [x] If the threshold is met, this story closes — the multi-lens architecture is validated.
- [ ] If the threshold is not met after one focused day of prompt tuning, the architecture is dropped: replace the 3-lens worker with a single-pass extraction call, remove the convergence resolver, drop "candidate" and "ambiguous" confidence values from the schema, and remove the Reading section spec from `design-guidelines.md`. Update `concept-locked.md` and `PRD.md` to match. ADR-002 gets superseded by a new ADR documenting single-pass extraction. _(Not triggered — threshold met on first run.)_

**Rubric revision (2026-05-12) — multi-factor gate replaces the single-integer threshold above.** ADR-002 §"Addendum (2026-05-12, sixth)" supersedes the methodology behind the original integer gate and the investigation-doc ≥5-flag retrofit, and records the verdict against fresh device evidence. The original tick-marks above remain as the historical record of how the architecture cleared the v0 gate on 2026-05-10; the rubric below is what subsequent runs (and any Phase 4 re-validation) are evaluated against. **Implementation is unchanged on this branch** — prompts, sampler, retry budget are identical to the shipped Skeptical-only config; only the ship-decision rubric moves.

- [x] **Factor 1 — meaningful divergence rate ≥ 50%.** _(Reruns #1 + #2: 11/15 = 73% each.)_
- [x] **Factor 2 — `canonical_with_conflict` reachable on ≥ 2 entries** (`disagree_fields=[]` AND ≥ 1 Skeptical schema-binding flag). _(Reruns #1 + #2: A4 + B2 each.)_
- [x] **Factor 3 — parse stability ≥ 90% of (entry × lens) calls, 0 timeouts at the 5-min per-entry ceiling.** _(Reruns #1 + #2: 44/45 = 97.8%, 0 timeouts each.)_
- [x] **Factor 4 — run-to-run consistency: 2 back-to-back runs same config, meaningful-set Jaccard ≥ 0.75 AND Skeptical-flag count delta ≤ 1.** _(Reruns #1 and #2 of the shipped config on 2026-05-12: meaningful-set Jaccard **1.0**, flag count delta **0**. Outputs byte-identical entry-by-entry. Greedy + `seed=42` is genuinely deterministic on the engine. Archives: `docs/stt-results/stt-d-2026-05-12-gpu-skep-rerun{1,2}.md`.)_

**Device-run record:**

| Run | Date | Backend | Corpus | Engine init | Per-entry | Verdict | Notes |
|---|---|---|---|---|---|---|---|
| CPU initial | 2026-05-10 | CPU | 6 (canonical) | 11.5 s | 127–161 s | 5/6 meaningful | Skeptical flags on A1 + B2; only C2 unanimous. `energy_descriptor` null on 5/6. |
| GPU re-run | 2026-05-10 | GPU (post-`libOpenCL.so` fix) | 6 (canonical) | 19.7 s | 25–55 s | 4/6 meaningful | C2 + D1 hit INFERENTIAL parse-fail × 2 retries (1/3 and 2/3 lenses survived). B2's `stated_commitment` reported as `disagree_fields` not Skeptical flag. |
| CPU expanded | 2026-05-12 | CPU | 15 (canonical + extras) | — | 134–157 s | **12/15 (80%) meaningful** | No retries; no parse failures across 45 lens calls. Skeptical flags on A1 + B2 (same as 2026-05-10). Full archive: `docs/stt-results/stt-d-2026-05-12-cpu.md`. |
| GPU expanded | 2026-05-12 | GPU | 15 (canonical + extras) | — | 40–78 s | **8/15 (53%) meaningful** | C2 + D1 INFERENTIAL parse-fail × 2 reproduced from 2026-05-10. 4 soft regressions (A6, C3, D3, D1 → not meaningful on GPU but were meaningful on CPU same day). Full archive: `docs/stt-results/stt-d-2026-05-12-gpu.md`. |
| GPU greedy | 2026-05-12 | GPU + `SamplerConfig(topK=1, temp=0, seed=42)` | 15 | — | 40–78 s | **9/15 (60%) meaningful** | Parse failures eliminated by greedy decode; root cause was SDK non-greedy defaults on FP16 GPU. Full archive: `docs/stt-results/stt-d-2026-05-12-gpu-greedy.md`. |
| GPU sharpened | 2026-05-12 | GPU + greedy + sharpened lens framings + `maxAttemptsPerLens=3` | 15 | — | 49–309 s | **13/15 (87%) meaningful** | Reached the highest divergence count but at unacceptable cost: 1 timeout (C1 at 5-min ceiling), 1 entry with 1/3 lenses parsed (D2), ~1.8× mean latency. **Not shipped.** Documented in ADR-002 §"Addendum (2026-05-12, fourth)" and superseded by the fifth addendum below. Full archive: `docs/stt-results/stt-d-2026-05-12-gpu-sharp.md`. |
| **GPU Skeptical-only** | **2026-05-12** | **GPU + greedy + LIT/INF baselines + revised SKEPTICAL (under 1441-char budget) + `maxAttemptsPerLens=2`** | **15** | — | **24–46 s** | **11/15 (73%) meaningful + 4 Skeptical flags + 2 `canonical_with_conflict`-eligible entries (A4, B2)** | **Shipped.** Skeptical fires real adversarial work for the first time on the corpus — `state-behavior-mismatch` (A1), `vocabulary-contradiction` (A4), `commitment-without-anchor` (B2), `unsupported-recurrence` (C1). 14/15 entries full 3-of-3 parse; 1 partial (B3); 0 timeouts. Misses the investigation doc's strict ≥5-flag gate by one; shipped anyway per the in-thread call. Full archive: `docs/stt-results/stt-d-2026-05-12-gpu-skep.md`. |
| GPU Skeptical-only rerun #1 | 2026-05-12 | Shipped config (no change from row above) | 15 | — | 24.7–44.0 s (mean 32.8 s) | 11/15 (73%) meaningful, 4 Skeptical flags, A4 + B2 reachable | Factor 4 evidence run #1 of 2. Output identical to the shipped row. Archive: `docs/stt-results/stt-d-2026-05-12-gpu-skep-rerun1.md`. |
| GPU Skeptical-only rerun #2 | 2026-05-12 | Shipped config (no change) | 15 | — | 31.7–46.1 s (mean 37.1 s) | 11/15 (73%) meaningful, 4 Skeptical flags, A4 + B2 reachable | Factor 4 evidence run #2 of 2. Byte-identical verdict to rerun #1; meaningful-set Jaccard 1.0, flag count delta 0. Latency ~13% slower (engine wall-clock noise, not output divergence). Archive: `docs/stt-results/stt-d-2026-05-12-gpu-skep-rerun2.md`. |
| GPU Skeptical-only suite-run (3rd) | 2026-05-12 | Shipped config (no change), invoked via the full instrumented suite-runner | 15 | — | 32.6–49.2 s (mean 39.5 s) | 11/15 (73%) meaningful, 4 Skeptical flags, A4 + B2 reachable | Third byte-identical verdict — same 11 meaningful entries, same 4 Skeptical flags + evidence quotes, same A4 + B2 `canonical_with_conflict` reachability, same B3 partial-parse. Reproduced across a different gradle invocation (`scripts/run-full-android-test-suite.sh`). Captured as `docs/stt-results/full-suite-2026-05-12/SttDLensDivergenceTest.{gradle,logcat}.raw.log`. |

**GPU is now the multi-lens production default at 11/15 (73%) divergence with 4 Skeptical flags fired.** The convergence-resolver `canonical_with_conflict` verdict is reachable on 2 entries — ADR-002's load-bearing demo beat is now demonstrable in the data. ADR-002 §"Addendum (2026-05-12, second)" supersedes the earlier "default to CPU" verdict; the deterministic sampler closed the GPU parse-reliability gap. §"Addendum (2026-05-12, fifth)" supersedes the sharpened-prompt experiment with the Skeptical-only revision actually shipped. §"Addendum (2026-05-12, sixth)" revises the STT-D rubric from a single-integer gate to four factors (divergence rate + `canonical_with_conflict` reachability + parse stability + run-to-run consistency) and records the verdict against fresh back-to-back reruns: all four factors satisfied, meaningful-set Jaccard 1.0, flag count delta 0 — the architecture is reproducibly validated, not "shipped anyway."

**Fallback if STT-D fails:** the demo's "intentional model use" story shifts. Native audio multimodal stays the headline (it always was). The agentic-as-product layer is no longer present, so the technical walkthrough loses the "Reading" beat. The 5-min walkthrough script changes — add a comment in `demo-storyboard.md` (when written) noting the cut.

**Notes / risks:** ADR-002 §"If lenses always agree" calls this exact failure mode out. Take the fallback seriously rather than tuning prompts forever to force disagreement.

---

### Story 2.8 — Convergence resolver implementation

**As** the AI implementor, **I need** the convergence resolver to take three lens results from the background worker (Story 2.6) and emit canonical / candidate / ambiguous / canonical-with-conflict per-field outputs per `concept-locked.md` §"Convergence rules", **so that** Story 1.12's test scaffolding has a real implementation to exercise and the saved entry has the unified schema populated.

**Done when:**
- [x] `:core-inference` exposes `ConvergenceResolver.resolve(extractions: List<LensExtraction>): ResolvedExtraction`. _(`fun interface ConvergenceResolver` is the SAM contract; `DefaultConvergenceResolver` is the production impl. List-shaped input so the worker can pass 1, 2, or 3 surviving lenses without sentinels per ADR-002 §"Edge case — lens errors mid-call". The Phase-1 `Phase2NotImplementedConvergenceResolver` placeholder was deleted with its test per AGENTS.md guardrail 16.)_
- [x] Per-field output follows the four convergence rules: ≥2 of 3 agree → canonical; only one lens populates with two surviving → candidate; lenses disagree → ambiguous (null + note); Skeptical flags conflict → canonical-with-conflict. _(Refines the ADR-002 rules with one v1 strengthening: a **lone surviving lens** (1-of-3 — i.e. two lenses parse-failed) leaves the entry under-evidenced overall; every field — populated or not — resolves to AMBIGUOUS rather than minting candidates from a single witness. Matches ADR-002 §"Edge case — lens errors mid-call" applied per-field. With 2 or 3 surviving lenses, the rule 2 "single-lens-populates → CANDIDATE" path stands.)_
- [x] Story 1.12's previously-stubbed test cases now run real assertions and pass. _(All four `@Disabled` cases in `ConvergenceResolverTest` flipped to `@Test`, real assertions land, plus eleven added cases for the edge surface — case-insensitive `energy_descriptor` agreement, partial-overlap `tags` majority, fallback to Literal's strongest, all-empty tags, single-lens survivor, two-lens majority/disagreement, all-null nullable, Skeptical-flag-without-value, irrelevant Skeptical flags ignored, and field-key union when only one lens populates.)_
- [x] Edge cases per ADR-002 §"Convergence edge cases" are handled: lens parse failure (treat as no opinion, not as agreement), Skeptical-only flag without value (canonical-with-conflict using consensus value), all three null on a nullable field (canonical-null). _(Worker filters `LensResult.extraction == null` before calling the resolver per `BackgroundExtractionWorker.completeRun`; resolver iterates `Lens.entries` and treats absent lenses as no opinion. `tags` follow the per-tag-count rule (canonical = any tag in ≥2 lenses; ordered by Literal then remaining lenses' insertion).)_
- [x] The resolver does not call the model. It is pure data merge. _(No engine reference — `DefaultConvergenceResolver` only depends on the `core-model` types.)_
- [x] Confidence values are stored on the `Entry` per `concept-locked.md` §Schema (`confidence` field). _(Per-field `ConfidenceVerdict` is carried by `ResolvedField.verdict`; the entry's storage write lives in Story 2.12, which threads `ResolvedExtraction` into `EntryEntity.confidence` via `Converters`.)_

**Notes / risks:** Don't retry lens calls inside the resolver. ADR-002 §"Convergence edge cases" — retries hide signal. If a lens fails, that's data the resolver uses (a lens with no opinion).

---

### Story 2.9 — STT-C: tag extraction consistency — **PASSED 2026-05-11**

🛑 **Stop-and-test point.** If tag extraction is unstable across equivalent dumps, downstream pattern detection is noisy. Tighten prompts; if still bad, document as known limitation.

**As** the AI implementor, **I need** to verify that the convergence resolver emits stable tag sets on equivalent test dumps (≥80% same-tag emission across re-runs of similar inputs), **so that** Phase 3's pattern engine has reliable signal to count.

**Done when:**
- [x] STT-C corpus runs through the background pipeline at least three times each. _(S24 Ultra GPU, 2026-05-11; 18 entries × 3 runs; 41 m 23 s wall-clock.)_
- [x] ≥80% of (entry, tag) pairs stable across all three runs. _(**1.00 / 41 of 41 pairs.** Every tag that emitted, emitted on all three runs.)_
- [ ] If <80%, tighten surface prompts and re-run. _(Not triggered.)_
- [ ] If <80% after one focused day of tuning, document in `PRD.md` §"Acceptance criteria — Tag extraction" with the noisy-pattern caveat. _(Not triggered.)_

**C2 caveat — GPU parse-failure regression (cross-ref Story 2.7):** C2 produced zero tags on all 3 runs because INFERENTIAL + SKEPTICAL lenses parse-failed × 2 retries each, leaving only Literal — which under the resolver's lone-survivor rule yields AMBIGUOUS on every field (including `tags`). Same backend-sensitive parse-failure pattern Story 2.7's GPU re-run flagged on C2 and D1. STT-C's stability gate is unaffected: the 17 entries that emit tags emit them stably. C2 parse-rate is tracked separately under Story 2.7. The harness logs zero-tag entries as a warning (not an assertion) so the signal stays visible without over-gating STT-C scope.

---

### Story 2.10 — Agent-emitted template labels with archetype detection

**As** the AI implementor, **I need** the agent to emit one of six template labels (Aftermath / Tunnel exit / Concrete shoes / Decision spiral / Goblin hours / Audit) per entry based on the convergence-resolved fields, **so that** Phase 3 pattern detection can group entries by archetype and the History view can show category labels.

**Done when:**
- [x] A `TemplateLabeler` runs after the convergence resolver (Story 2.8) and selects one of six labels using the detection rules in `concept-locked.md` §"Templates (agent-emitted labels, not user-facing modes)":
  - Aftermath: state surface signals `energy_descriptor=crashed` + state-shift evidence (concept-locked.md §"Templates" writes this as `state_descriptor`, which is a doc typo — the actual schema field is `energy_descriptor` per `lenses/output-schema.txt`)
  - Tunnel exit: behavioral surface signals focus subject + extended duration + things-ignored
  - Concrete shoes: behavioral surface signals stuck task + resistance markers
  - Decision spiral: state surface signals decision-looping + iteration markers
  - Goblin hours: time-of-day between midnight–5am + state surface late-night markers
  - Audit: catch-all when no archetype dominates
  _(`:core-inference/TemplateLabeler.kt` reads `ResolvedExtraction` + capture `ZonedDateTime` and returns a `TemplateLabel`. The zone rides on the timestamp — the labeler no longer consults `ZoneId.systemDefault()` — so a user crossing zones (or a DST shift) between recording and the background extraction run can't relabel the entry. `BackgroundExtractionWorker.extract(...)` now requires that persisted `capturedAt` value and computes the label after convergence, surfacing it on `BackgroundExtractionResult.Success.templateLabel`. Anchor tokens are taken from `surfaces/state.txt` (`crashed`, `tunnel-exit`, `decision-loop`, `late-night`). Concrete-shoes resistance markers (`stuck` / `stalled` / `paralyzed` / `blocked` / `resistance` / `concrete-shoes` / `task-paralysis`) are kept narrow on purpose — STT-C tag stability will widen or trim the set with measured evidence. Aftermath requires both `energy_descriptor == "crashed"` (case-insensitive, trimmed) AND `state_shift == true`. Goblin hours requires local hour ∈ 0..4 AND a `late-night` / `overnight` tag — concept-locked's "midnight–5am" reads as 00:00 inclusive, 05:00 exclusive.)_
- [x] Label emission rules are encoded in code (not asked of the model) — `TemplateLabeler` is deterministic given the resolved fields. _(No engine reference in `TemplateLabeler`; `core-inference` Lens / engine types are unimported. Same shape as `DefaultConvergenceResolver` per ADR-002 §"Convergence Resolver Contract" — pure data merge after extraction. Only `CANONICAL` / `CANONICAL_WITH_CONFLICT` verdicts drive label selection: `CANDIDATE` values (single-lens witnesses) and `AMBIGUOUS` fields are non-load-bearing per `concept-locked.md` §"Convergence rules", since the template label feeds pattern grouping which the convergence rules explicitly bar candidate evidence from until promoted.)_
- [x] Goblin hours additionally triggers context-aware prompting in the foreground call: shorter follow-up cadence, fewer probes, slightly different tone (per `concept-locked.md` §Templates). _(`ForegroundInference` now accepts a `ZoneId` (default `systemDefault()`) and consults `clock.instant()` pre-engine-call: when the local hour is in 0..4 the system prompt appends the classpath resource `/foreground/goblin-hours-addendum.txt` (file at `core-inference/src/main/resources/foreground/goblin-hours-addendum.txt`) after the output-schema reminder. The addendum tells the model to keep the follow-up short, ask one probe instead of several, stay even-toned, and not name the hour. Detection is clock-only — the foreground call has no access to extraction tags yet — so the addendum and the post-extraction `GOBLIN_HOURS` label can disagree (e.g. 3am crashed entries label as Aftermath but still receive the goblin addendum). Both detections are correct: the addendum tunes tone for the current capture; the label categorizes the entry after extraction.)_
- [x] A smoke test runs at least one transcript per archetype through the pipeline and confirms the correct label is emitted. _(`TemplateLabelerTest` exercises all six labels plus precedence (Aftermath > Goblin / Tunnel exit; Decision spiral > Tunnel exit; Concrete shoes > Goblin hours), boundary cases (5am out, case-insensitive tag matching, trimmed energy), and defensive miscasts (non-string tag entries, null-value ambiguous tags). `ForegroundInferenceTest` adds three Goblin-hours system-prompt cases: outside-window absent, inside-window present (after the schema reminder), and 5am-boundary excluded.)_

**Notes / risks:** Template labels are deterministic post-extraction logic, not a model call. If you find yourself prompting the model "what kind of entry is this?", stop — that's reintroducing user-facing template selection through the back door.

**Structural defect (2026-05-17) — feature yanked from v1 UI:** The "Done when" boxes are accurate for what they claimed: the code shipped and `TemplateLabelerTest` passes (it hand-feeds CANONICAL fields). But the *end-to-end* path is structurally broken. `TemplateLabeler` consumes only CANONICAL / CANONICAL_WITH_CONFLICT fields (deliberately — the label was specced to feed pattern grouping). `DefaultConvergenceResolver` mints CANONICAL only on ≥2-of-3 lens agreement. The archetype triggers (`energy_descriptor=="crashed"`, `state_shift`, tags `tunnel-exit`/`decision-loop`/`stuck`/`late-night`) are all inferences — only the Inferential lens produces them from natural behavioral text; Literal (strict) won't unless the user says the literal word, and Skeptical emits flags not corroborating values. Inferential-alone resolves to CANDIDATE, which the labeler discards → every realistic entry is `AUDIT`. The feature only produces a real label when the user speaks the exact internal vocabulary (Literal then also emits it → 2-lens agreement). That is the keyword-stuffed fixture, not a functional test. Decision: code stays inert through Phase 2/3 (mid-phase removal is cross-cutting with zero demo value); UI rendering is yanked in Phase 4 §Story 4.16; redesign deferred to v1.5. Full root cause + the why-not-an-easy-fix analysis + unblock condition: `backlog.md` §`archetype-template-labeling`.

---

### Story 2.11 — Inference latency UI: foreground placeholder — **MERGED INTO STORY 4.5 (2026-05-10)**

Phase 2 has no capture screen to attach a placeholder to — Story 4.5 builds the surface (Scoreboard "ON AIR" record state + entry transcript per ADR-011; original Mist `MistHero` framing retired). All five `Done when` boxes from this story (placeholder display, 200 ms minimum hold, error-state replacement on `ParseFailure` / cancellation, no streaming) landed under Story 4.5 as the `Inference placeholder lifecycle (absorbed from Story 2.11)` bullet. Track them there.

---

### Story 2.12 — Save flow: ObjectBox + markdown after convergence

**As** the AI implementor, **I need** the entry to persist to both the ObjectBox `Entry` row and the markdown source-of-truth file (per Story 1.7) after the convergence resolver has populated the canonical fields, **so that** the entry is durably saved before the user leaves the session and the markdown export is always in sync with the ObjectBox index.

**Done when:**
- [x] Save fires after Story 2.8 convergence resolution completes — not after the foreground call alone. _(`BackgroundExtractionSaveFlow.saveAndExtract` (in `:app/save/`) awaits `BackgroundExtractionWorker.extract`'s terminal `BackgroundExtractionResult` before calling `EntryStore.completeEntry` / `failEntry`. Foreground transcription persists earlier via `EntryStore.createPendingEntry` which writes `entry_text` + `extraction_status=PENDING` so the user's words are durable before the model finishes.)_
- [x] The `Entry` row in ObjectBox carries: `entry_text` (joined USER transcriptions only), the saved voice-path `follow_up` turn when present, the recorded `persona`, `timestamp`, `template_label` (Story 2.10), `tags`, `energy_descriptor`, `recurrence_link`, `stated_commitment`, `entry_observations`, `confidence` (per-field), and the operational triplet from ADR-001 Q3. _(`EntryEntity` persists the single-turn foreground exchange as `entryText` + optional `followUpText` + `persona` before background extraction completes, then `EntryStore.completeEntry` fills the resolved fields from `ResolvedExtraction.fields[…]` + the `templateLabel` argument.)_
- [x] The markdown file (per Story 1.7) is written with front-matter mirroring the row's structured fields and the body containing `entry_text`; `follow_up` + `persona` live in front-matter beside the extraction fields. _(`MarkdownEntryStore.write` renders the front-matter; `EntryStore` invokes it inside the same transaction as the box write so the two stay aligned.)_
- [x] If the markdown write fails, the ObjectBox row is rolled back — the two stay in sync. _(`EntryStoreTest."createPendingEntry rolls back row when markdown write fails"` exercises the path — markdown-first inside `boxStore.callInTx { … }` means a write throw aborts the transaction before the row commits.)_
- [x] **The save flow registers `AppContainer.extractionStatusListener(entryId)` with the `BackgroundExtractionWorker` (Story 2.6) so each entry's status flips drive the foreground service lifecycle.** _(The orchestrator threads `listenerFactory(entryId)` into `worker.extract(request, listener)` immediately after `createPendingEntry` returns the id; production wiring uses `AppContainer.extractionStatusListener` as the factory.)_
- [x] **The save flow's persistence layer also wires `AppContainer.recoveredEntryIdsLoader` to the `EntryStore`-owned `BoxStore` via `VestigeBoxStore.findNonTerminalEntryIds(boxStore)` per ADR-006 Action Item #4** — replacing the Phase-2 default `{ emptyList() }`. _(`AppContainer.seedRecoveredExtractions` now falls back to the live query when no test loader is injected. The parameter is preserved nullable so `AppContainerTest` stays BoxStore-free with `recoveredEntryIdsLoader = { listOf(11L, 12L) }`.)_
- [x] A smoke test runs a full session through the pipeline and verifies both ObjectBox and markdown reflect the same canonical fields. _(End-to-end coverage splits across two test classes by JNI boundary: `EntryStoreTest` (Robolectric + real BoxStore + real `MarkdownEntryStore`) covers the persistence half; `BackgroundExtractionSaveFlowTest` (JVM + mockk) covers the worker-result-to-store routing — `createPendingEntry → worker.extract → completeEntry` / `failEntry` with listener fan-out. Together they replicate the full Story 2.12 contract without depending on a live LiteRT-LM engine.)_

**Notes / risks:** Per Story 1.7's note: if they diverge, markdown wins. Phase 4 export-to-zip and Phase 4 delete-all flows depend on this invariant.

---

### Story 2.13 — Per-entry observation generation

**As** the AI implementor, **I need** the model to emit 1–2 per-entry observations from the convergence-resolved fields and the entry text — linguistic contradictions, captured commitments, volunteered context with one observation, theme noticing — **so that** every saved entry has user-visible signal even before any cross-entry pattern exists.

**Done when:**
- [x] After convergence resolution (Story 2.8), an `ObservationGenerator` runs one model call composing: the entry text + the resolved structured fields + a system prompt that instructs the model to surface 1–2 observations per `concept-locked.md` §"Analysis (two-layer)". _(`:core-inference/ObservationGenerator` runs **deterministic-first** per ADR-002 §3 — commitment-flag from `stated_commitment`, vocabulary-contradiction from `vocabulary_contradictions`, volunteered-context for goblin-hours captures (00:00–04:59 local). One model call only when deterministic assembly produces nothing, against the `/observations/system.txt` + `/observations/output-schema.txt` prompt resources. `BackgroundExtractionSaveFlow` invokes the generator after `BackgroundExtractionWorker.extract` resolves Success, threads observations into `EntryStore.completeEntry`.)_
- [x] Each observation includes evidence — either a quoted snippet from `entry_text` or a reference to a structured field. _(`EntryObservation` carries `text` + an `ObservationEvidence` enum (vocabulary-contradiction / commitment-flag / volunteered-context / theme-noticing / pattern-callout) + a `fields[]` list of structured-field names. Deterministic paths emit field references; model fallback's parser rejects responses without recognized evidence values.)_
- [x] Observations refuse interpretation per `concept-locked.md` §"Voice rules / Interpretation rule": no "you might be feeling," no "this could indicate," no diagnostic language. The system prompt enforces this. _(System prompt lists the forbidden openings and instructs the model to rewrite. `ObservationResponseParser` post-validates against a case-insensitive substring scan of the AGENTS.md §7 / `concept-locked.md` §"Voice rules" phrase list (`you might be feeling`, `it seems you're`, `this could indicate`, `i sense that`, `perhaps you're`, `it sounds like you're feeling`, `you may want to consider`, `you should`); any match drops the response. `ObservationGenerator.runModelFallback` retries once on rejection, then returns empty list rather than persist noise.)_
- [x] Observations persist in the `entry_observations` field. _(`EntryStore.completeEntry` serializes the `List<EntryObservation>` via `JSONArray` into `EntryEntity.entryObservationsJson`; `MarkdownEntryStore.write` renders it as the front-matter `entry_observations:` inline JSON per `architecture-brief.md` §"Field placement rules".)_
- [x] A smoke test confirms observations across at least three sample transcripts contain only behavior/vocabulary/pattern observations and no forbidden phrasings. _(`ObservationResponseParserTest` exercises three sample-transcript shapes (commitment + theme-noticing, theme-noticing only, vocabulary-contradiction), plus forbidden-phrase rejection at the start of a line, embedded mid-sentence, and across case. `ObservationGeneratorTest` covers the deterministic-first paths + the model-call retry on forbidden-phrase rejection + the empty-list outcome on two-attempts-violated. None of the test fixtures embed a forbidden phrase that would actually persist.)_

**Notes / risks:** This is a single model call per entry, not a 3-lens pass. The output is 1–2 observations max — if the model returns more, truncate to two. Don't pad observations across entries with little content; one well-evidenced observation beats two thin ones.

---

---

### Story 2.14 — SDK upgrade probe: verify Session.clone(), MTP, sampler fix, bump litertlm-android

**As** the AI implementor, **I need** to probe the pinned `litertlm-android` AAR bytecode for the concurrency + streaming surface, **so that** Stories 2.15, 2.16, and 2.19 build on the real SDK shape rather than the mis-scoped `Session.clone()` assumption.

**Done when:**
- [x] Fetch current `maven-metadata.xml` for `com.google.ai.edge.litertlm:litertlm-android`. _(2026-05-16: `<latest>` = `<release>` = `0.11.0`, `lastUpdated` `20260504232658`. No newer artifact — and none is needed; see below.)_
- [x] `javap` the **pinned 0.11.0** AAR `classes.jar` (`Engine`, `Conversation`, `Session`, `ConversationConfig`, `SessionConfig`). _(Done 2026-05-16. The earlier "only if a newer release publishes" gate was the mis-scoped framing — the question is the pinned artifact's actual surface, not the version.)_
- [x] Concurrency surface: does the SDK support one Engine → many contexts? _(**Yes, on 0.11.0.** `Engine.createSession(SessionConfig): Session` and `Engine.createConversation(ConversationConfig): Conversation` — independent contexts, each `AutoCloseable` + `cancelProcess()`. No `Session.clone()` / parent-Session / CoW prefix-fork exists, and none is needed. The 2026-05-11 `clone()`-named-method probe was mis-scoped; the resulting ADR-009 was **deleted as a mistake**; ADR-008 is restored with the mechanism corrected — see [ADR-008 §Correction (2026-05-16)](../adrs/ADR-008-parallel-lens-execution.md#correction-2026-05-16--mechanism-and-performance-premise).)_
- [x] Streaming surface: multimodal streaming present? _(**Yes, on 0.11.0.** `Conversation.sendMessageAsync(Contents, Map): Flow<Message>`. Story 2.16 is a thin wrapper over the existing Flow — no new SDK API.)_
- [x] Sampler `.so` check (`libLiteRtTopKOpenClSampler.so` / `libLiteRtTopKWebGpuSampler.so` in `jni/arm64-v8a/`). _(Not present in 0.11.0 — unchanged; tracked by ADR-012 Decision 1, blocked.)_
- [x] Dependency pin. _(`gradle/libs.versions.toml` `litert-lm` stays `0.11.0` — already `<latest>` and already exposes everything needed. No bump, no build/test delta.)_
- [x] Record findings in the ADR layer. _(ADR-008 §Correction 2026-05-16 + ADR-001/002 corrections + `architecture-brief.md`. ADR-009 deleted — not superseded — per the operator's mistake-vs-change waiver.)_

**Notes / risks:** This story's original framing ("probe a newer release for `Session.clone()`; bump if newer") was wrong on two counts: the concurrency capability was never `clone()` (it is `createSession`/`createConversation`), and it was present on the pinned `0.11.0` all along — no newer artifact needed. Net: no dependency change; the ADR record is the deliverable.

---

### Story 2.15 — Enable MTP speculative decoding (>2x decode speedup)

**As** the AI implementor, **I need** Multi-Token Prediction enabled on the LiteRT-LM engine initialization in `LiteRtLmEngine`, **so that** foreground and background inference decode at the documented >2x speedup from Single Position MTP — which ships in the already-pinned `litertlm-android:0.11.0`.

**Done when:**
- [x] Identify the MTP enablement API in `litertlm-android` (likely an `EngineConfig` or `ExperimentalFlags` parameter). Check the SDK source or AAR for the relevant flag/option name. _(`ExperimentalFlags.enableSpeculativeDecoding` — a process-global Kotlin `object` property gated by `@OptIn(ExperimentalApi::class)`, matching the `litertlm-android-sdk` skill's verbatim official-doc snippet. Confirmed present in the pinned `litertlm-android:0.11.0` AAR by `javap` bytecode probe: `ExperimentalFlags.setEnableSpeculativeDecoding(Boolean)` + the `ExperimentalApi` annotation interface both exist. It is **not** an `EngineConfig` field — `EngineConfig`'s 0.11.0 surface has no MTP parameter.)_
- [x] Enable MTP in `LiteRtLmEngine.initialize()` via the appropriate config. GPU backend stays `BackendChoice.GPU`. _(`ExperimentalFlags.enableSpeculativeDecoding = true` set as the first statement of `initialize()`, before `Engine(...)` construction per the SDK's "before initializing the engine" contract. Idempotent across re-init. Backend selection is untouched — the flag is decode-path only and stays on for CPU and GPU alike per the skill's "universally recommended" guidance. `initialize()`'s log line now carries `speculativeDecoding=on`.)_
- [ ] Measure foreground call latency on the reference S24 Ultra before and after. Record delta in logcat under `VestigeLiteRtLm`. A >1.5x decode speedup is the acceptance threshold; if below 1.5x, document why and leave MTP enabled regardless (correctness is primary). _(**Manual-check stop — on-device. Not self-ticked.** Requires an S24 Ultra A/B run; awaiting user outcome.)_
- [ ] Measure background lens call latency (one lens call, no convergence) before and after. Record delta. _(**Manual-check stop — on-device. Not self-ticked.** Pairs with the foreground measurement above.)_
- [x] All existing unit + integration tests pass. _(`lefthook` pre-push `make build+test` ran the full suite green — ✔️ ktlint, ✔️ build, ✔️ test (219 s incl. `koverVerify`) — on push of `chore/phase-2-story-2.15-mtp`. JVM unit coverage added: `LiteRtLmEngineTest."initialize turns on MTP speculative decoding before engine construction"` asserts the flag flips before the native boundary.)_
- [x] If MTP requires a minimum SDK version newer than what Story 2.14 confirms is available, defer this story and document in a dated addendum to ADR-012. _(Not triggered — `0.11.0` is both pinned and `<latest>` (Story 2.14) and the bytecode probe above proves `ExperimentalFlags.enableSpeculativeDecoding` is present on it. No newer artifact needed; no deferral; no ADR-012 addendum.)_

**Notes / risks:** MTP is a decode-path optimization only — it does not change prompt composition, sampler config, convergence logic, or output format. If the model output changes in any measurable way (unexpected tokens, format drift), that is a regression — disable MTP and surface conflict with the user for discussion.

---

### Story 2.16 — Streaming foreground inference: token-by-token output to UI

**As** the AI implementor, **I need** the foreground inference call to switch from blocking `sendMessageContents()` to streaming `sendMessageAsync()` (Kotlin Flow), **so that** the user sees tokens appearing in the UI as the model generates them rather than waiting for the full response — eliminating the perceived wall-clock stall on every capture.

**Done when:**
- [ ] `LiteRtLmEngine.streamMessageContents(parts: List<Content>): Flow<String>` is added — the multimodal streaming counterpart to `sendMessageContents`. `streamText(prompt)` already exists and wraps `sendMessageAsync` Flow for text-only calls; `streamMessageContents` applies the same pattern to the `AudioFile + Text` foreground path. The SDK's `sendMessageAsync(contents): Flow<Message>` is the underlying API (per official Android docs `https://ai.google.dev/edge/litert-lm/android`).
- [ ] `ForegroundInference.runForegroundCall()` switches from `sendMessageContents()` (blocking) to `streamMessageContents()`. The return type changes from a single `ForegroundResult` to a `Flow<ForegroundToken>` or equivalent streaming shape — design this surface so `CaptureViewModel` can update its `Reviewing` state incrementally.
- [ ] `ForegroundResponseParser` is updated to parse XML tags (`<transcription>`, `<follow_up>`) from a streamed token buffer rather than a completed string. The parser must handle tag boundaries arriving mid-token.
- [ ] `CaptureViewModel.Reviewing` state carries a `followUpText: String` field that appends tokens as they arrive. The capture screen renders the growing string in real time.
- [ ] Temp WAV file deletion still fires in `finally` after the stream completes or is cancelled — audio discard contract (ADR-001 §Q8) is unchanged.
- [ ] Coroutine cancellation: if the user navigates away during streaming, the Flow is cancelled and the partial result is discarded. No partial entries are saved.
- [ ] Existing `ForegroundInferenceTest` suite updated to exercise streaming happy path, mid-stream cancellation, and parse-failure on incomplete XML tag at stream end.
- [ ] On-device: subjective latency improvement is visible on the reference S24 Ultra. Time-to-first-token logged under `VestigeForegroundInference`.

**Notes / risks:** Per Story 2.2 §"Notes / risks": "Streaming is a Phase 4 polish decision, not a Phase 2 baseline." That framing was written before the 24–33s wall-clock was measured on-device; the UX case for streaming is now clear and this is the right time to do it. The parser complexity is real — test the boundary case where `</transcription>` arrives split across two token emissions before shipping.

---

### Story 2.17 — Fix pre-warm race: event-driven engine init instead of hardcoded delay

**As** the AI implementor, **I need** the engine pre-warm in `AppContainer.refreshModelReadiness()` to trigger on a reliable lifecycle signal rather than a hardcoded 2-second delay, **so that** a user who opens the app and taps record within 2 seconds does not block on engine initialization — and users on slower devices who need more than 2 seconds to paint the first frame are also covered.

**Done when:**
- [x] Remove `ENGINE_PREWARM_DELAY_MS = 2000L` and the `delay(ENGINE_PREWARM_DELAY_MS)` call from `AppContainer.refreshModelReadiness()`.
- [x] Pre-warm fires immediately on model-ready transition: when `probeModelReadiness()` returns `Ready` and prior state was not `Ready`, launch `ensureBackgroundEngineInitialized()` directly on `scope` with no delay.
- [x] The pre-warm coroutine runs at `Dispatchers.Default` (or lower priority) so it does not compete with the main thread's UI work. The Mutex inside `ensureBackgroundEngineInitialized()` already ensures only one init runs at a time. _(`scope` is `Dispatchers.Default` per `defaultScope()`; no change needed.)_
- [x] If a recording starts before pre-warm completes, `ensureBackgroundEngineInitialized()` is called inline on the recording path and blocks until init finishes — this behavior is unchanged and is the correct fallback.
- [x] Unit test: `AppContainerTest` verifies that pre-warm launches immediately (within one coroutine scheduler tick) after `probeModelReadiness()` returns `Ready` — not after a 2-second delay. _(`pre-warm fires immediately on Ready transition without a delay`: `coVerify(exactly=1) { engineMock.initialize() }` + `assertEquals(0L, testScheduler.currentTime)`.)_
- [ ] Manual check: open the app on the reference S24 Ultra; verify engine init completes in background before the first record tap in a typical interaction (>3 seconds between app open and first record).

**Notes / risks:** The 2s delay was added to avoid competing with UI thread on cold open. Switching to `Dispatchers.Default` + the existing Mutex achieves the same isolation without the timing guess. If the Compose frame renderer is still painting when pre-warm launches, coroutine scheduling ensures the init runs on a background thread — no contention with the main thread.

---

### Story 2.18 — Thread retrieval history into foreground call

**As** the AI implementor, **I need** `CaptureViewModel` to run a `RetrievalRepo.query()` after the transcription is available and pass the results into the foreground follow-up call, **so that** the response the user sees immediately after recording is context-aware — not context-free while only the background extraction gets prior-entry context.

**Done when:**
- [ ] After voice transcription returns (or typed text is submitted), `CaptureViewModel` calls `RetrievalRepo.query(transcriptionText, topN = 3)` before generating the follow-up.
- [ ] The retrieved history is threaded into `ForegroundInference.runForegroundCall()` (or `runForegroundTextCall()`). `PersonaPromptComposer` or the prompt construction must incorporate the history chunks — review how `PromptComposer` currently handles `retrievedHistory` for background calls and apply the same shape to the foreground prompt.
- [ ] `AppContainer.runForegroundCall()` / `runForegroundTextCall()` signature updated to accept `retrievedHistory: List<HistoryChunk>` if not already wired through.
- [ ] History retrieval runs on the background dispatcher; it does not block the UI thread.
- [ ] If `RetrievalRepo` returns empty (no prior entries, or embeddings not yet backfilled), the foreground call proceeds normally with no history block — degraded retrieval must not prevent the call.
- [ ] Unit test: `CaptureViewModelTest` verifies that `RetrievalRepo.query()` is called with the transcription text before `runForegroundCall()` fires, and that the returned history is passed through.

**Notes / risks:** This is a correctness fix for the gap documented in `architecture-brief.md` §"Retrieval History Gap (Addendum 2026-05-16)". The retrieval call adds ~880ms (embedding cost) + ObjectBox scan to the foreground path latency. With streaming output (Story 2.16), this is masked — the model starts generating before the user would perceive the retrieval delay. If streaming is not yet in place, add a logcat note about the added latency.

---

### Story 2.19 — Concurrent inference: eliminate foreground-blocks-on-background mutex contention

**As** the AI implementor, **I need** the foreground inference path to never block on a background extraction that is currently running, **so that** a user who taps record while a prior entry's extraction is in flight gets an immediate response rather than waiting for the current lens call to finish.

**Unblocked (2026-05-16).** Story 2.14's bytecode probe is done: pinned `0.11.0` exposes `Engine.createSession`/`createConversation` (concurrent independent contexts) — Path C is **buildable today**, no `Session.clone()` gate. Path choice is now a **measurement** decision (concurrent-context RAM on the reference S24 Ultra + realized wall-clock under single-GPU command-queue serialization), not an SDK gate. See [ADR-008 §Correction (2026-05-16)](../adrs/ADR-008-parallel-lens-execution.md#correction-2026-05-16--mechanism-and-performance-premise). v1 ships sequential until this story makes that measured call.

**Path B — priority queue (simplest; no concurrency RAM cost):**
- [ ] Introduce a `PriorityInferenceQueue` in `AppContainer` that serializes all engine calls with foreground priority. Foreground requests go to the front; background lens calls go to the back.
- [ ] When a foreground call arrives while a background lens call is executing, the background coroutine is cancelled (`job.cancel()`). The background worker catches `CancellationException` and re-queues its current lens as the next background request after the foreground call completes.
- [ ] Background extraction re-queues cleanly — the entry's `extraction_status` does not change to `FAILED` on a priority preempt; the worker distinguishes cancellation-for-preempt from cancellation-for-discard.
- [ ] Foreground call latency on a device with an active background extraction is indistinguishable from latency with no background work running. Manual check on reference device.

**Path C — detached background context (one Engine, independent contexts — no cloning):**
- [ ] Maintain one `LiteRtLmEngine` and per-role contexts via `Engine.createSession`/`createConversation`: a foreground context for interactive calls and one background context per active extraction. **Independent** contexts (no parent-Session, no CoW shared prefix — each composes its own prefix).
- [ ] `BackgroundExtractionWorker` creates a background context per entry extraction and `close()`s it on completion. The foreground context is never borrowed by background work.
- [ ] Background contexts are `cancelProcess()`/`close()`d when preempted by a higher-priority foreground call — the worker detects closure and re-queues.
- [ ] Measure: concurrent-context RAM on the reference S24 Ultra (weights shared; per-context KV is not) and realized background wall-clock (a single GPU serializes at the command queue — no literal 3×; the win is non-blocking preempt). These numbers are the Path-B-vs-C decision input. Log under `VestigeLiteRtLm`.

**Done when (either path):**
- [ ] A foreground call that arrives during an active background extraction does not block on the engine Mutex beyond the time needed to cancel and yield.
- [ ] Background extraction resumes after the foreground call completes without requiring user action.
- [ ] All existing `BackgroundExtractionWorkerTest` + `LiteRtLmEngineTest` cases pass.
- [ ] `architecture-brief.md` §"AppContainer Ownership" row for `InferenceCoordinator` updated to reflect the chosen path. If Path C is adopted, record the measured RAM/wall-clock and the decision in `ADR-008` via a dated addendum (no new superseding ADR — ADR-008 is the live record).

**Notes / risks:** Do not instantiate two `LiteRtLmEngine` objects. Two Engines pointing at the same model file path load weights twice — at E4B's footprint this is prohibitive on Android. One Engine, multiple contexts (`createSession`/`createConversation`) is the correct shape regardless of which path is chosen.

---

## What is explicitly NOT in Phase 2

- No history list, history filter, or entry detail UI (Phase 4).
- No patterns engine, pattern detection, patterns view, pattern detail, or Roast (Phase 3).
- No Reading / re-eval debug screen (Phase 4 P1, contingent on STT-D passing).
- No EmbeddingGemma, no vector retrieval, no `RetrievalRepo` integration (Phase 3, contingent on STT-E).
- No agentic tool-calling (cut entirely).
- No persona-flavored microcopy on errors or empty states (Phase 4).
- No onboarding, no settings, no model status surface (Phase 4).
- No demo storyboard, no sample data for the demo recording (Phase 5).

If a Phase 2 story starts pulling Phase 3+ scope, stop. Reference `backlog.md` and the scope rule.

---

## Phase 2 exit checklist

Phase 3 starts when all the following are true:

- [x] All fifteen stories above are Done or have an explicit, recorded fallback. (Story 2.6.6 — concurrent multi-context per ADR-008 — added 2026-05-10. The interim ADR-009 "SDK-impossible" supersede was a mis-scoped-probe mistake and was **deleted 2026-05-16**; ADR-008 restored, mechanism corrected. Story 2.6.6 is **scope-deferred** pending the RAM/wall-clock measurement — not a backlog SDK-gap entry. v1 contract stays ADR-002 sequential until that measurement.)
- [x] **STT-B resolved** — multi-turn works on E4B *or* the single-turn fallback is implemented and the spec updated. _(2026-05-09 — single-turn fallback shipped per `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`; `concept-locked.md`, `PRD.md`, `design-guidelines.md` updated; `CaptureSession` made single-use; multi-turn harness deleted.)_
- [x] **STT-D resolved** — 3-lens divergence is validated *or* multi-lens has been replaced with single-pass and ADR-002 has been superseded. _(2026-05-10 — validated at 83%.)_
- [x] **STT-C resolved** — tag stability is ≥80% *or* the limitation is documented and Phase 3's pattern engine is designed around the noise floor. _(2026-05-11 — passed at 1.00 on GPU; C2 GPU parse-failure documented under Story 2.7's GPU regression note.)_
- [x] Convergence resolver tests from Story 1.12 all pass. _(Story 2.8 flipped the four `@Disabled` cases and added eleven more; `:core-inference` test suite stays green.)_
- [x] `BackgroundExtractionService` state machine tests pass; service is wired into `AppContainer` per ADR-004. _(Story 2.6.5 covered the state-machine + service integration; Story 2.12 closed the wiring loop by routing `extractionStatusListener(entryId)` from `BackgroundExtractionSaveFlow` and replacing the no-op `recoveredEntryIdsLoader` default with the live `VestigeBoxStore.findNonTerminalEntryIds(boxStore)` query.)_
- [x] Latency budget on the reference device is recorded (foreground per turn, background per entry). _(Foreground per-turn: 24.4 / 31.2 / 33.4 s on E4B CPU post-fallback (Story 2.3 smoke). Background per-entry: 25–55 s on E4B GPU after the `libOpenCL.so` manifest fix (Story 2.7 device record); 5–7 s per lens. Both are outside the ADR-002 §"Latency budget" 1–5 s foreground target — GPU / NPU latency work lives in Phase 4/5.)_
- [ ] Markdown + ObjectBox stay in sync across at least 10 saved sessions (smoke test). _(Per-save sync is validated under `EntryStoreTest` + `BackgroundExtractionSaveFlowTest` from Story 2.12 — the ≥10-sessions volume check stays open until Phase 4's history UI exercises it end-to-end.)_
- [x] No new entries logged to `backlog.md` from Phase 2 work that change the v1 contract beyond what an STT fallback already required. _(The earlier `parallel-lens-execution-via-clone` backlog entry came from the mistaken ADR-009 supersede; ADR-009 is deleted and that entry is retired — concurrent multi-context is scope-deferred to Story 2.6.6 / 2.19, not a backlog SDK-gap item. ADR-002 sequential stays the v1 contract until measurement.)_

If STT-B or STT-D fired their fallbacks: the v1 contract has shifted. Update `concept-locked.md`, `PRD.md`, and the relevant stories in `phase-3-memory-patterns.md`, `phase-4-ux-surface.md` (when those are written) before starting Phase 3 work.
