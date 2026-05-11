# Phase 2 — Core Capture Loop

**Status:** In progress
**Dates:** 2026-05-09 – TBD
**References:** `PRD.md` §Phase 2, `concept-locked.md` §"Multi-lens extraction architecture", `concept-locked.md` §Schema, `AGENTS.md`, `architecture-brief.md`, `adrs/ADR-001-stack-and-build-infra.md` §Q3 / §Q4, `adrs/ADR-002-multi-lens-extraction-pattern.md` (entire), `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` (entire), `adrs/ADR-008-parallel-lens-execution.md` (entire), `sample-data-scenarios.md`

---

## Goal

Build the end-to-end capture loop: user records or types → foreground call returns transcription + follow-up at human pace → background 3-lens pipeline runs the multi-lens extraction → convergence resolver writes canonical/candidate/ambiguous fields → entry persists to markdown + ObjectBox. By the end of Phase 2, voice-in produces saved entries with the full content schema populated, and three of the five stop-and-test points (STT-B, STT-C, STT-D) are resolved.

**Output of this phase:** a working capture loop on the reference device. User records one capture per entry (single-turn-per-capture per the STT-B v1 scope choice — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`, which amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"), the foreground response renders fast, and the background 3-lens extraction populates the entry's structured fields within the documented latency budget. No history UI, no patterns engine, no settings screen yet.

---

## Phase-level acceptance criteria

- [ ] Capture session can run record → transcription + follow-up → save end-to-end on the reference device.
- [ ] **STT-B passes** (or has an explicit fallback recorded): multi-turn conversation maintains context across 3+ exchanges on E4B. If broken, single-turn fallback is implemented and the spec is updated.
- [x] **STT-D passes** (or the architecture is dropped): the 3-lens pipeline produces meaningfully different outputs on at least 30% of the prepared sample transcripts. If lenses always agree, multi-lens drops to single-pass and the Reading section is removed from the entry detail spec. _(2026-05-10 — 5/6 entries (83%) on E4B CPU. See Story 2.7.)_
- [x] **STT-C passes**: tag extraction is stable (≥80% same-tag emission on equivalent test dumps). If unstable, prompts have been tightened until stable, or the limitation is documented. _(2026-05-11 — **PASSED at 1.00** on S24 Ultra GPU. 41/41 (entry, tag) pairs stable across 3 runs over 17 of 18 corpus entries. C2 produced zero tags on all 3 runs (INFERENTIAL + SKEPTICAL parse-fail × 2 retries on GPU) — same regression flagged in Story 2.7's GPU re-run. Stability gate is unaffected; C2 parse-rate is tracked separately.)_
- [ ] Convergence resolver implementation lands into Story 1.12's test scaffolding and all happy-path tests pass.
- [ ] Agent-emitted template labels work for all six archetypes on representative sample transcripts.
- [ ] Background extraction populates the canonical schema on the reference device. Target latency per ADR-008: ~7–10s wall-clock per entry via parallel Session cloning on E4B GPU. The legacy 30–90s ceiling stays as the time-budget timeout guard.
- [ ] Background extraction lifecycle service is wired per ADR-004 (conditional foreground service): app promotes when extraction begins, demotes after 30-second keep-alive once all extractions reach terminal status. Notification text matches `ux-copy.md` §"Loading States".
- [ ] Personas (Witness / Hardass / Editor) demonstrably affect tone on the foreground response without affecting structured field extraction.

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
- [ ] ~~In ≥80% of scripted sessions, the model's response on turn N+1 demonstrably references context from earlier turns~~ — **NOT MET under the prompt-stuffing pattern.** Round 1 reported retention=1.0 but was a manifest-anchor false positive (anchors included substrings introduced in the same turn's audio). Rounds 2 and 3 (corrected anchors + explicit prompt instruction) both produced retention=0.0 across all 9 turn-≥2 lookups in each round. The SDK's stateful Conversation path was not measured; whether this criterion would meet under that path is open.
- [ ] ~~Latency per-turn is recorded and stays within the 1–5 second budget on the reference device.~~ — Latency recorded (32.7–65.3 s on E4B CPU); the 1–5 s target is unmet but independent of the multi-turn pattern question (latency tuning is Phase 4/5).
- [ ] ~~If the test passes, this story closes and the conversation UX from Stories 2.1–2.3 is the v1 path.~~ — Test did not pass under the pattern measured.
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

**Notes / risks:** The three lens calls run **in parallel** per `adrs/ADR-008-parallel-lens-execution.md`. Build one base Session for the entry (shared prefix: system + 5 surfaces + entry text + retrieved history), clone it three times for the three lens module suffixes, fire all three concurrently against the single Engine. Copy-on-Write KV-cache handles the divergence. Total wall-clock ≈ one parallel call's latency + parallelization overhead, not 3× sequential. If LiteRT-LM Session cloning errors on the E4B audio artifact, **stop and write a superseding ADR** — do not silently fall back to sequential.

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

### Story 2.6.6 — Parallel lens execution via Engine/Session cloning (per ADR-008)

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

**Device-run record (2026-05-10):**

| Run | Backend | Engine init | Per-lens | Per-entry | Verdict | Notes |
|---|---|---|---|---|---|---|
| CPU initial | CPU | 11.5 s | 35–55 s | 127–161 s | 5/6 meaningful | Skeptical flags on A1 + B2; only C2 unanimous. `energy_descriptor` null on 5/6. |
| GPU re-run | GPU (post-`libOpenCL.so` fix) | 19.7 s | 8–13 s | 25–55 s | 4/6 meaningful | C2 + D1 hit INFERENTIAL parse-fail × 2 retries (1/3 and 2/3 lenses survived). B2's `stated_commitment` reported as `disagree_fields` not Skeptical flag. |

**GPU regressions to track:** parse-failure rate higher than CPU under identical prompts; Skeptical-flag emission appears backend-sensitive. ADR-002 §"Structured-output reliability" addendum if pattern holds.

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

---

### Story 2.11 — Inference latency UI: foreground placeholder — **MERGED INTO STORY 4.5 (2026-05-10)**

Phase 2 has no capture screen to attach a placeholder to — Story 4.5 builds the surface (`MistHero`, transcript). All five `Done when` boxes from this story (placeholder display, 200 ms minimum hold, error-state replacement on `ParseFailure` / cancellation, no streaming) landed under Story 4.5 as the `Inference placeholder lifecycle (absorbed from Story 2.11)` bullet. Track them there.

---

### Story 2.12 — Save flow: ObjectBox + markdown after convergence

**As** the AI implementor, **I need** the entry to persist to both the ObjectBox `Entry` row and the markdown source-of-truth file (per Story 1.7) after the convergence resolver has populated the canonical fields, **so that** the entry is durably saved before the user leaves the session and the markdown export is always in sync with the ObjectBox index.

**Done when:**
- [ ] Save fires after Story 2.8 convergence resolution completes — not after the foreground call alone. (Foreground gets the user a response immediately; the durable save lands when extraction finishes.)
- [ ] The `Entry` row in ObjectBox carries: `entry_text` (joined USER transcriptions only), `timestamp`, `template_label` (Story 2.10), `tags`, `energy_descriptor`, `recurrence_link`, `stated_commitment`, `entry_observations`, `confidence` (per-field), and the operational triplet from ADR-001 Q3.
- [ ] The markdown file (per Story 1.7) is written with front-matter mirroring the row's structured fields and the body containing `entry_text` + `entry_observations`.
- [ ] If the markdown write fails, the ObjectBox row is rolled back — the two stay in sync.
- [ ] **The save flow registers `AppContainer.extractionStatusListener(entryId)` with the `BackgroundExtractionWorker` (Story 2.6) so each entry's status flips drive the foreground service lifecycle.** Story 2.6.5 staged the listener API; this story closes the loop. Until this lands, the foreground service never promotes — there is no other production caller.
- [ ] **The save flow's persistence layer also wires `AppContainer.recoveredEntryIdsLoader` to the `EntryStore`-owned `BoxStore` via `VestigeBoxStore.findNonTerminalEntryIds(boxStore)` per ADR-006 Action Item #4** — replacing the Phase-2 default `{ emptyList() }`. Cold-start sweep recovery is non-functional until this lands.
- [ ] A smoke test runs a full session through the pipeline and verifies both ObjectBox and markdown reflect the same canonical fields.

**Notes / risks:** Per Story 1.7's note: if they diverge, markdown wins. Phase 4 export-to-zip and Phase 4 delete-all flows depend on this invariant.

---

### Story 2.13 — Per-entry observation generation

**As** the AI implementor, **I need** the model to emit 1–2 per-entry observations from the convergence-resolved fields and the entry text — linguistic contradictions, captured commitments, volunteered context with one observation, theme noticing — **so that** every saved entry has user-visible signal even before any cross-entry pattern exists.

**Done when:**
- [ ] After convergence resolution (Story 2.8), an `ObservationGenerator` runs one model call composing: the entry text + the resolved structured fields + a system prompt that instructs the model to surface 1–2 observations per `concept-locked.md` §"Analysis (two-layer)".
- [ ] Each observation includes evidence — either a quoted snippet from `entry_text` or a reference to a structured field.
- [ ] Observations refuse interpretation per `concept-locked.md` §"Voice rules / Interpretation rule": no "you might be feeling," no "this could indicate," no diagnostic language. The system prompt enforces this.
- [ ] Observations persist in the `entry_observations` field.
- [ ] A smoke test confirms observations across at least three sample transcripts contain only behavior/vocabulary/pattern observations and no forbidden phrasings.

**Notes / risks:** This is a single model call per entry, not a 3-lens pass. The output is 1–2 observations max — if the model returns more, truncate to two. Don't pad observations across entries with little content; one well-evidenced observation beats two thin ones.

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

- [ ] All fifteen stories above are Done or have an explicit, recorded fallback. (Story 2.6.6 — parallel refactor per ADR-008 — added 2026-05-10.)
- [ ] **STT-B resolved** — multi-turn works on E4B *or* the single-turn fallback is implemented and the spec updated.
- [x] **STT-D resolved** — 3-lens divergence is validated *or* multi-lens has been replaced with single-pass and ADR-002 has been superseded. _(2026-05-10 — validated at 83%.)_
- [x] **STT-C resolved** — tag stability is ≥80% *or* the limitation is documented and Phase 3's pattern engine is designed around the noise floor. _(2026-05-11 — passed at 1.00 on GPU; C2 GPU parse-failure documented under Story 2.7's GPU regression note.)_
- [ ] Convergence resolver tests from Story 1.12 all pass.
- [ ] `BackgroundExtractionService` state machine tests pass; service is wired into `AppContainer` per ADR-004.
- [ ] Latency budget on the reference device is recorded (foreground per turn, background per entry).
- [ ] Markdown + ObjectBox stay in sync across at least 10 saved sessions (smoke test).
- [ ] No new entries logged to `backlog.md` from Phase 2 work that change the v1 contract beyond what an STT fallback already required.

If STT-B or STT-D fired their fallbacks: the v1 contract has shifted. Update `concept-locked.md`, `PRD.md`, and the relevant stories in `phase-3-memory-patterns.md`, `phase-4-ux-surface.md` (when those are written) before starting Phase 3 work.
