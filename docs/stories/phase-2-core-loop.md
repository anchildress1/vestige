# Phase 2 — Core Capture Loop

**Status:** In progress
**Dates:** 2026-05-09 – TBD
**References:** `PRD.md` §Phase 2, `concept-locked.md` §"Multi-lens extraction architecture", `concept-locked.md` §Schema, `AGENTS.md`, `architecture-brief.md`, `adrs/ADR-001-stack-and-build-infra.md` §Q3 / §Q4, `adrs/ADR-002-multi-lens-extraction-pattern.md` (entire), `sample-data-scenarios.md`

---

## Goal

Build the end-to-end capture loop: user records or types → foreground call returns transcription + follow-up at human pace → background 3-lens pipeline runs the multi-lens extraction → convergence resolver writes canonical/candidate/ambiguous fields → entry persists to markdown + ObjectBox. By the end of Phase 2, voice-in produces saved entries with the full content schema populated, and three of the five stop-and-test points (STT-B, STT-C, STT-D) are resolved.

**Output of this phase:** a working capture loop on the reference device. User can hold a multi-turn voice session, the foreground response renders fast, and the background 3-lens extraction populates the entry's structured fields within the documented latency budget. No history UI, no patterns engine, no settings screen yet.

---

## Phase-level acceptance criteria

- [ ] Capture session can run record → transcription + follow-up → save end-to-end on the reference device.
- [ ] **STT-B passes** (or has an explicit fallback recorded): multi-turn conversation maintains context across 3+ exchanges on E4B. If broken, single-turn fallback is implemented and the spec is updated.
- [ ] **STT-D passes** (or the architecture is dropped): the 3-lens pipeline produces meaningfully different outputs on at least 30% of the prepared sample transcripts. If lenses always agree, multi-lens drops to single-pass and the Reading section is removed from the entry detail spec.
- [ ] **STT-C passes**: tag extraction is stable (≥80% same-tag emission on equivalent test dumps). If unstable, prompts have been tightened until stable, or the limitation is documented.
- [ ] Convergence resolver implementation lands into Story 1.12's test scaffolding and all happy-path tests pass.
- [ ] Agent-emitted template labels work for all six archetypes on representative sample transcripts.
- [ ] Background extraction populates the canonical schema on the reference device within 30–90 seconds per entry as specified.
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
- [x] A unit test exercises the state machine through one full multi-turn session in memory. _(`CaptureSessionTest."full multi-turn session walks the happy path and preserves chronological order"` runs 3 user/model turns end-to-end with an injected ticking `Clock`; pos/neg/err/edge coverage in sibling tests.)_

**Notes / risks:** The transcript is session history, not the storage substrate. `entry_text` must be derived from the ordered USER transcriptions only — never from model turns — so retrieval, slugging, and pattern counts stay grounded in the user's words. Don't conflate per-turn state with per-entry state — one entry corresponds to one full session, multiple turns.

---

### Story 2.2 — Foreground inference: audio → transcription + follow-up

**As** the AI implementor, **I need** the foreground inference call that takes a normalized audio buffer (from Story 1.4) and the active persona's system prompt (from Story 1.8) and returns a structured `{transcription, follow_up}` response, **so that** the user gets text back at human conversation pace and the conversation transcript advances.

**Done when:**
- [ ] `:core-inference` exposes a `runForegroundCall(audioBuffer, sessionTranscript, persona): ForegroundResult` API.
- [ ] The call composes the persona system prompt + the in-session transcript (as multi-turn history) + the new audio buffer.
- [ ] The response is parsed as structured output (JSON or markdown-with-headers per ADR-002 §"Structured-output reliability"). On parse failure, return a typed error — do not silently retry.
- [ ] The transcription appears in the transcript before the follow-up renders.
- [ ] Audio buffer is discarded after the call returns.
- [ ] Latency on the reference device is recorded; if outside the documented 1–5 second target, log it for ADR-002's latency note.

**Notes / risks:** ADR-002 §Q1 / §"Default for Phase 1" says non-streaming structured response is the default. Do not introduce token-streaming UI here unless STT-D's structured-output measurements say it's worth the parser complexity. Streaming is a Phase 4 polish decision, not a Phase 2 baseline.

---

### Story 2.3 — Persona switching during session

**As** the AI implementor, **I need** persona switching to take effect on the next foreground call within a session (and persist as the session default), **so that** users can change tone mid-session per the v1 P1 "per-session persona override" story without affecting prior turns or extraction logic.

**Done when:**
- [ ] `CaptureSession` exposes `setPersona(persona)` that updates the session's active persona for subsequent turns.
- [ ] Prior turns in the transcript retain whichever persona generated them (no rewriting history).
- [ ] The next foreground call uses the new persona's system prompt (via `PersonaPromptComposer` from Story 1.8).
- [ ] Background 3-lens extraction (Story 2.6) is **unaffected** by persona — extraction prompts are persona-agnostic per `concept-locked.md` §Personas.
- [ ] Smoke test: same audio buffer through different personas in the same session produces visibly different follow-up text.

**Notes / risks:** Personas are output-only. If you find yourself wiring persona into the lens prompts, stop — that's a regression. ADR-002 §"Personas are tone, not analysis" is the rule.

---

### Story 2.4 — STT-B: multi-turn session state on E4B (existential)

🛑 **Stop-and-test point.** If the model can't maintain context across 3+ exchanges on E4B, drop to single-turn extract-and-respond and rewrite the conversation UX.

**As** the AI implementor, **I need** to verify that Gemma 4 E4B can carry session context across multiple turns when the prior transcript is included in the prompt, **so that** the conversation loop functions as a *conversation* rather than disconnected single-turn extractions.

**Done when:**
- [ ] A test harness runs at least 5 multi-turn scripted sessions on the reference device, each with 3+ exchanges where turn N+1 depends on context from turn N.
- [ ] In ≥80% of scripted sessions, the model's response on turn N+1 demonstrably references context from earlier turns (e.g., refers to a person, topic, or state mentioned earlier without it being repeated in the new audio).
- [ ] Latency per-turn is recorded and stays within the 1–5 second budget on the reference device.
- [ ] If the test passes, this story closes and the conversation UX from Stories 2.1–2.3 is the v1 path.
- [ ] If the test fails (model loses context, hallucinates prior turns, or refuses to continue), record the failure mode in ADR-002 §"Multi-turn behavior" and execute the fallback.

**Fallback if STT-B fails:** drop multi-turn from v1. Each capture is a single audio chunk → single extraction → save. The conversation transcript becomes a list of independent entries rather than a session. Update `concept-locked.md`, `PRD.md` §"Multi-turn" acceptance criterion, and `design-guidelines.md` §"Conversation transcript" to match. Do not pretend single-turn is multi-turn — judges will catch the seam.

**Notes / risks:** Per `runtime-research.md` and earlier benchmark research, E4B's multi-turn behavior is one of the riskiest assumptions in the spec. Time-box the diagnosis: if scripted sessions show 0% multi-turn success after one focused day of prompt tuning, switch to the fallback rather than burning more days.

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

**As** the AI implementor, **I need** an immediate visual placeholder in the conversation transcript when a foreground call starts and a real-time render of the model's response when it returns, **so that** the user sees motion during the 1–5 second foreground latency window without the UI feeling broken.

**Done when:**
- [ ] When the foreground call is in flight, the user-turn slot in the transcript shows an inline placeholder ("Reading the entry." or persona-flavored copy from `ux-copy.md` §"Loading States").
- [ ] When the response returns, the placeholder is replaced with the transcribed text (in muted/dimmed tone per `design-guidelines.md` §"Conversation transcript") and the model's follow-up renders below in primary text weight.
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

- [ ] All thirteen stories above are Done or have an explicit, recorded fallback.
- [ ] **STT-B resolved** — multi-turn works on E4B *or* the single-turn fallback is implemented and the spec updated.
- [ ] **STT-D resolved** — 3-lens divergence is validated *or* multi-lens has been replaced with single-pass and ADR-002 has been superseded.
- [ ] **STT-C resolved** — tag stability is ≥80% *or* the limitation is documented and Phase 3's pattern engine is designed around the noise floor.
- [ ] Convergence resolver tests from Story 1.12 all pass.
- [ ] Latency budget on the reference device is recorded (foreground per turn, background per entry).
- [ ] Markdown + ObjectBox stay in sync across at least 10 saved sessions (smoke test).
- [ ] No new entries logged to `backlog.md` from Phase 2 work that change the v1 contract beyond what an STT fallback already required.

If STT-B or STT-D fired their fallbacks: the v1 contract has shifted. Update `concept-locked.md`, `PRD.md`, and the relevant stories in `phase-3-memory-patterns.md`, `phase-4-ux-surface.md` (when those are written) before starting Phase 3 work.
