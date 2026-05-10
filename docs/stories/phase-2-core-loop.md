# Phase 2 — Core Capture Loop

**Status:** In progress
**Dates:** 2026-05-09 – TBD
**References:** `PRD.md` §Phase 2, `concept-locked.md` §"Multi-lens extraction architecture", `concept-locked.md` §Schema, `AGENTS.md`, `architecture-brief.md`, `adrs/ADR-001-stack-and-build-infra.md` §Q3 / §Q4, `adrs/ADR-002-multi-lens-extraction-pattern.md` (entire), `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` (entire), `sample-data-scenarios.md`

---

## Goal

Build the end-to-end capture loop: user records or types → foreground call returns transcription + follow-up at human pace → background 3-lens pipeline runs the multi-lens extraction → convergence resolver writes canonical/candidate/ambiguous fields → entry persists to markdown + ObjectBox. By the end of Phase 2, voice-in produces saved entries with the full content schema populated, and three of the five stop-and-test points (STT-B, STT-C, STT-D) are resolved.

**Output of this phase:** a working capture loop on the reference device. User records one capture per entry (single-turn-per-capture per the STT-B v1 scope choice — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`, which amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"), the foreground response renders fast, and the background 3-lens extraction populates the entry's structured fields within the documented latency budget. No history UI, no patterns engine, no settings screen yet.

---

## Phase-level acceptance criteria

- [ ] Capture session can run record → transcription + follow-up → save end-to-end on the reference device.
- [ ] **STT-B passes** (or has an explicit fallback recorded): multi-turn conversation maintains context across 3+ exchanges on E4B. If broken, single-turn fallback is implemented and the spec is updated.
- [ ] **STT-D passes** (or the architecture is dropped): the 3-lens pipeline produces meaningfully different outputs on at least 30% of the prepared sample transcripts. If lenses always agree, multi-lens drops to single-pass and the Reading section is removed from the entry detail spec.
- [ ] **STT-C passes**: tag extraction is stable (≥80% same-tag emission on equivalent test dumps). If unstable, prompts have been tightened until stable, or the limitation is documented.
- [ ] Convergence resolver implementation lands into Story 1.12's test scaffolding and all happy-path tests pass.
- [ ] Agent-emitted template labels work for all six archetypes on representative sample transcripts.
- [ ] Background extraction populates the canonical schema on the reference device within 30–90 seconds per entry as specified.
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
- [x] Smoke test: same audio buffer through different personas produces visibly different follow-up text. _(`PerCapturePersonaSmokeTest` — instrumented, manual; iterates `Persona.entries` constructing one fresh `CaptureSession` per persona and running real `ForegroundInference` against a device-pushed WAV. Asserts pairwise-different non-blank follow-ups. The pre-fallback `PersonaSwitchSessionSmokeTest` was removed because its "switch mid-session" framing no longer matches the v1 lifecycle. Verified on S24 Ultra 2026-05-09 across rounds 1–3 of STT-B as a side effect: WITNESS, HARDASS, EDITOR all produced distinct follow-ups under all three rounds — the per-persona divergence is robust even though the multi-turn behavior we hoped for never materialized.)_

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
- [ ] `:core-inference` ships five surface prompt modules: `Behavioral`, `State`, `Vocabulary`, `Commitment`, `Recurrence`. Each is loaded from a checked-in resource file (one per surface).
- [ ] `:core-inference` ships three lens prompt modules: `Literal`, `Inferential`, `Skeptical`. Each is loaded from a checked-in resource file (one per lens).
- [ ] `PromptComposer` produces a complete prompt for `(lens, all five surfaces)` by composing the lens framing on top of the five surface instructions, plus retrieved history (top 3 chunks per ADR-002 §Q2) and the entry text.
- [ ] Each composed prompt's total token count is logged so ADR-002 §"Token budget per call" can validate <2K per system block.
- [ ] Surface and lens modules are independently loadable and replaceable — no surface code calls a lens module directly, and vice versa.
- [ ] A smoke test asserts that a literal-lens composed prompt and a skeptical-lens composed prompt for the same input differ only in the lens framing block.

**Notes / risks:** Mix-and-match isolation matters. ADR-002 §"Mix-and-match" calls out that we'll be tuning prompts daily — a lens-only tweak should not risk diffing surface prompts.

---

### Story 2.6 — Background extraction worker: 3-lens pipeline runner

**As** the AI implementor, **I need** a background worker that takes a saved transcript and runs three lens passes through `PromptComposer` (Story 2.5), collects three structured extractions, and hands them to the convergence resolver (Story 2.8), **so that** canonical schema fields populate within the 30–90 second background latency budget without blocking the foreground UI.

**Done when:**
- [ ] A `BackgroundExtractionWorker` (or equivalent — implementation choice per ADR-001 Q3) runs after the foreground call resolves and the transcript is persisted.
- [ ] The worker iterates: for each of the three lenses, compose the prompt (Story 2.5), call LiteRT-LM, parse the structured response.
- [ ] Per-lens results are stored as a `LensResult { lens, parsed_fields, raw_response }` tuple in memory until convergence resolution completes.
- [ ] Per-entry operational fields per ADR-001 Q3 (`extraction_status`, `attempt_count`, `last_error`) are updated as the worker progresses through the three calls.
- [ ] Latency per entry on the reference device is logged; total foreground + background time per entry is recorded for the latency note.
- [ ] If a lens call fails (parse error, model error), the worker retries per ADR-001 Q3's retry policy. Two consecutive failures on the same lens marks the entry as `extraction_status=ambiguous_partial` rather than retrying indefinitely.

**Notes / risks:** The three lens calls are sequential, not parallel — E4B is one model on one device. Don't try to parallelize. Total latency = ~3× single-lens latency.

The worker does **not** own its own backgrounding behavior. The `BackgroundExtractionService` from Story 2.6.5 wraps the worker and keeps it alive across app backgrounding by promoting the process to a foreground service while extractions are in flight. Don't bake `Service` lifecycle handling into the worker class itself — that's the wrapper's job.

---

### Story 2.6.5 — Background extraction lifecycle service + state machine

**As** the AI implementor, **I need** a `BackgroundExtractionService` that promotes the app to a foreground service whenever an entry's `extraction_status` is `RUNNING` and demotes back to a normal process after a 30-second keep-alive window once all extractions reach terminal status, **so that** background extraction completes reliably even when the user backgrounds the app between record and pattern reveal — and the user sees an on-brand transient notification rather than persistent always-on chrome (per ADR-004 §"Conditional foreground service").

**Done when:**
- [ ] `BackgroundExtractionService` declared in `:app/src/main/AndroidManifest.xml` with `android:foregroundServiceType="dataSync"` and the matching runtime-permission stanza for the AGP-current foreground-service-type spec.
- [ ] `BackgroundExtractionService` extends `LifecycleService` (or equivalent) and is wired into `AppContainer` per `architecture-brief.md` §"AppContainer Ownership" (the `ModelHandle` row's lifecycle reference points here).
- [ ] State machine implemented per ADR-004 §"State Machine": `NORMAL → PROMOTING → FOREGROUND → KEEP_ALIVE → DEMOTING → NORMAL`. Transitions are driven by changes in any `Entry.extraction_status` value: `RUNNING` triggers promote; all extractions reaching terminal status (`COMPLETED` / `TIMED_OUT` / `FAILED`) starts the keep-alive timer.
- [ ] 30-second keep-alive timer prevents notification flicker between back-to-back captures. A new extraction starting during the keep-alive window cancels demote and re-enters `FOREGROUND` without re-firing the notification.
- [ ] Notification channel `vestige.local_processing` registered at `Application.onCreate` with importance level **LOW** (visible in shade, no sound, no heads-up). Channel registration is idempotent across cold starts.
- [ ] Notification text is `Reading the entry.` — sourced from `ux-copy.md` §"Loading States" (single source of truth, mirrored as a string resource so the in-app placeholder copy and the system notification stay in lockstep). Notification icon is the app icon per `design-guidelines.md` §"App icon".
- [ ] Notification tap target launches the app to History (or Capture as an acceptable Phase-2 placeholder). Deep-link to the most-recent-in-flight entry is Phase 4 polish per Story 4.7.
- [ ] Multiple in-flight extractions: the service's promote/demote logic gates on the count of entries with non-terminal `extraction_status`, not on "current handle in use." Per ADR-002 lens calls are sequential, so at most one extraction is actively executing — the count can exceed 1 if captures queue, and the service stays `FOREGROUND` until the count returns to zero.
- [ ] Unit test `BackgroundExtractionServiceStateMachineTest` exercises every transition in the ADR-004 §"State Machine" table with synthetic `extraction_status` flips. Cases: cold start with no pending entries (stays `NORMAL`), single entry promote/demote cycle, back-to-back captures during keep-alive (no flicker), keep-alive expiry, recovery sweep entries promoting on cold start.
- [ ] `POST_NOTIFICATIONS` runtime permission is **not** requested by this story. The permission ask flow lives in Phase 4 onboarding (Story 4.2 modification per ADR-004 §"Permission Flow"). Dev builds can grant the permission manually via system settings until Phase 4 lands.
- [ ] Tests assert state machine and channel registration only. No tests assert on actual notification posting — that requires `POST_NOTIFICATIONS` granted, which is out of scope for this story.

**Notes / risks:** The fallback to ADR-004 §"Option 1 — Always-on foreground service" is evaluated at the end of Phase 4 day 1 per ADR-004 §"Fallback Trigger," not here. If state-machine bugs block Phase 2 progress beyond half a day, escalate per the trigger criteria — don't quietly bypass the conditional logic. Recording the fallback decision goes in ADR-004's "Trigger recorded" line.

Don't conflate this story with Story 2.6 (worker logic). Story 2.6 is *what* runs in the background; this story is *the lifecycle wrapper* that keeps the OS from killing it. Wiring is one-directional: the service observes `extraction_status` transitions; the worker doesn't know the service exists.

---

### Story 2.7 — STT-D: 3-lens divergence verification (existential)

🛑 **Stop-and-test point.** If lenses always return identical outputs, the architecture earns nothing visible. Drop multi-lens to single-pass and remove the Reading screen from the entry detail spec.

**As** the AI implementor, **I need** to verify that the three lens passes produce *meaningfully different* outputs on at least 30% of the prepared sample transcripts (per `sample-data-scenarios.md`), **so that** the multi-lens architecture earns its 3× inference cost.

**Done when:**
- [ ] The full STT-D sample-transcript set from `sample-data-scenarios.md` runs through the background worker (Story 2.6).
- [ ] For each transcript, the three lens results are compared field-by-field. Difference count per field is logged.
- [ ] Across the sample set, at least 30% of transcripts produce at least one field-level disagreement among lenses.
- [ ] If the threshold is met, this story closes — the multi-lens architecture is validated.
- [ ] If the threshold is not met after one focused day of prompt tuning, the architecture is dropped: replace the 3-lens worker with a single-pass extraction call, remove the convergence resolver, drop "candidate" and "ambiguous" confidence values from the schema, and remove the Reading section spec from `design-guidelines.md`. Update `concept-locked.md` and `PRD.md` to match. ADR-002 gets superseded by a new ADR documenting single-pass extraction.

**Fallback if STT-D fails:** the demo's "intentional model use" story shifts. Native audio multimodal stays the headline (it always was). The agentic-as-product layer is no longer present, so the technical walkthrough loses the "Reading" beat. The 5-min walkthrough script changes — add a comment in `demo-storyboard.md` (when written) noting the cut.

**Notes / risks:** ADR-002 §"If lenses always agree" calls this exact failure mode out. Take the fallback seriously rather than tuning prompts forever to force disagreement.

---

### Story 2.8 — Convergence resolver implementation

**As** the AI implementor, **I need** the convergence resolver to take three lens results from the background worker (Story 2.6) and emit canonical / candidate / ambiguous / canonical-with-conflict per-field outputs per `concept-locked.md` §"Convergence rules", **so that** Story 1.12's test scaffolding has a real implementation to exercise and the saved entry has the unified schema populated.

**Done when:**
- [ ] `:core-inference` exposes `ConvergenceResolver.resolve(literal, inferential, skeptical): ResolvedEntry`.
- [ ] Per-field output follows the four convergence rules: ≥2 of 3 agree → canonical; only Inferential populates → candidate; lenses disagree → ambiguous (null + note); Skeptical flags conflict → canonical-with-conflict.
- [ ] Story 1.12's previously-stubbed test cases now run real assertions and pass.
- [ ] Edge cases per ADR-002 §"Convergence edge cases" are handled: lens parse failure (treat as no opinion, not as agreement), Skeptical-only flag without value (canonical-with-conflict using consensus value), all three null on a nullable field (canonical-null).
- [ ] The resolver does not call the model. It is pure data merge.
- [ ] Confidence values are stored on the `Entry` per `concept-locked.md` §Schema (`confidence` field).

**Notes / risks:** Don't retry lens calls inside the resolver. ADR-002 §"Convergence edge cases" — retries hide signal. If a lens fails, that's data the resolver uses (a lens with no opinion).

---

### Story 2.9 — STT-C: tag extraction consistency

🛑 **Stop-and-test point.** If tag extraction is unstable across equivalent dumps, downstream pattern detection is noisy. Tighten prompts; if still bad, document as known limitation.

**As** the AI implementor, **I need** to verify that the convergence resolver emits stable tag sets on equivalent test dumps (≥80% same-tag emission across re-runs of similar inputs), **so that** Phase 3's pattern engine has reliable signal to count.

**Done when:**
- [ ] The STT-C sample-dump set from `sample-data-scenarios.md` runs through the foreground + background pipeline at least three times each.
- [ ] Per-dump tag stability is measured: across the runs of the same dump, what fraction of tags are emitted on every run?
- [ ] Across the sample set, ≥80% of tags are stable (emitted on all three runs of an equivalent dump).
- [ ] If stability is below 80%, prompts are tightened (likely the surface modules) and the test re-runs.
- [ ] If stability is still below 80% after one focused day of tuning, the limitation is documented in `PRD.md` §"Acceptance criteria — Tag extraction" with a noisy-pattern caveat. Pattern engine in Phase 3 has to be designed for that noise floor.

**Notes / risks:** The 80% threshold is a v1 floor, not a quality bar. Phase 3's pattern engine design depends on it; if we ship at 60% stability, pattern callouts surface false positives 40% of the time. That's a demo-killer.

---

### Story 2.10 — Agent-emitted template labels with archetype detection

**As** the AI implementor, **I need** the agent to emit one of six template labels (Aftermath / Tunnel exit / Concrete shoes / Decision spiral / Goblin hours / Audit) per entry based on the convergence-resolved fields, **so that** Phase 3 pattern detection can group entries by archetype and the History view can show category labels.

**Done when:**
- [ ] A `TemplateLabeler` runs after the convergence resolver (Story 2.8) and selects one of six labels using the detection rules in `concept-locked.md` §"Templates (agent-emitted labels, not user-facing modes)":
  - Aftermath: state surface signals `state_descriptor=crashed` + state-shift evidence
  - Tunnel exit: behavioral surface signals focus subject + extended duration + things-ignored
  - Concrete shoes: behavioral surface signals stuck task + resistance markers
  - Decision spiral: state surface signals decision-looping + iteration markers
  - Goblin hours: time-of-day between midnight–5am + state surface late-night markers
  - Audit: catch-all when no archetype dominates
- [ ] Label emission rules are encoded in code (not asked of the model) — `TemplateLabeler` is deterministic given the resolved fields.
- [ ] Goblin hours additionally triggers context-aware prompting in the foreground call: shorter follow-up cadence, fewer probes, slightly different tone (per `concept-locked.md` §Templates).
- [ ] A smoke test runs at least one transcript per archetype through the pipeline and confirms the correct label is emitted.

**Notes / risks:** Template labels are deterministic post-extraction logic, not a model call. If you find yourself prompting the model "what kind of entry is this?", stop — that's reintroducing user-facing template selection through the back door.

---

### Story 2.11 — Inference latency UI: foreground placeholder

**As** the AI implementor, **I need** an immediate visual placeholder in the entry transcript when a foreground call starts and a real-time render of the model's response when it returns, **so that** the user sees motion during the 1–5 second foreground latency window without the UI feeling broken.

**Done when:**
- [ ] When the foreground call is in flight, the user-turn slot in the entry transcript shows an inline placeholder ("Reading the entry." or persona-flavored copy from `ux-copy.md` §"Loading States").
- [ ] When the response returns, the placeholder is replaced with the transcribed text (in muted/dimmed tone per `design-guidelines.md` §"Entry transcript") and the model's follow-up renders below in primary text weight.
- [ ] The placeholder displays for no less than 200ms (avoid the jarring instant-replace flash on fast inferences) and no more than the actual call duration (no fake delay).
- [ ] If the foreground call fails or times out, the placeholder is replaced with the appropriate error state from `ux-copy.md` §"Error States".
- [ ] No streaming UI in this story — see Note below.

**Notes / risks:** Streaming the model's response token-by-token is deferred until STT-D's structured-output measurements show parsing remains reliable when streamed (per ADR-002 §Q1). If non-streaming feels noticeably slow during demo dry runs in Phase 5, revisit then. For now: placeholder, then full response.

---

### Story 2.12 — Save flow: ObjectBox + markdown after convergence

**As** the AI implementor, **I need** the entry to persist to both the ObjectBox `Entry` row and the markdown source-of-truth file (per Story 1.7) after the convergence resolver has populated the canonical fields, **so that** the entry is durably saved before the user leaves the session and the markdown export is always in sync with the ObjectBox index.

**Done when:**
- [ ] Save fires after Story 2.8 convergence resolution completes — not after the foreground call alone. (Foreground gets the user a response immediately; the durable save lands when extraction finishes.)
- [ ] The `Entry` row in ObjectBox carries: `entry_text` (joined USER transcriptions only), `timestamp`, `template_label` (Story 2.10), `tags`, `energy_descriptor`, `recurrence_link`, `stated_commitment`, `entry_observations`, `confidence` (per-field), and the operational triplet from ADR-001 Q3.
- [ ] The markdown file (per Story 1.7) is written with front-matter mirroring the row's structured fields and the body containing `entry_text` + `entry_observations`.
- [ ] If the markdown write fails, the ObjectBox row is rolled back — the two stay in sync.
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

- [ ] All fourteen stories above are Done or have an explicit, recorded fallback.
- [ ] **STT-B resolved** — multi-turn works on E4B *or* the single-turn fallback is implemented and the spec updated.
- [ ] **STT-D resolved** — 3-lens divergence is validated *or* multi-lens has been replaced with single-pass and ADR-002 has been superseded.
- [ ] **STT-C resolved** — tag stability is ≥80% *or* the limitation is documented and Phase 3's pattern engine is designed around the noise floor.
- [ ] Convergence resolver tests from Story 1.12 all pass.
- [ ] `BackgroundExtractionService` state machine tests pass; service is wired into `AppContainer` per ADR-004.
- [ ] Latency budget on the reference device is recorded (foreground per turn, background per entry).
- [ ] Markdown + ObjectBox stay in sync across at least 10 saved sessions (smoke test).
- [ ] No new entries logged to `backlog.md` from Phase 2 work that change the v1 contract beyond what an STT fallback already required.

If STT-B or STT-D fired their fallbacks: the v1 contract has shifted. Update `concept-locked.md`, `PRD.md`, and the relevant stories in `phase-3-memory-patterns.md`, `phase-4-ux-surface.md` (when those are written) before starting Phase 3 work.
