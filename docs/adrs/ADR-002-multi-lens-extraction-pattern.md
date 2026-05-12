# ADR-002 — Multi-Lens Extraction Pattern (3 lenses × 5 surfaces, two-tier processing)

**Status:** Accepted
**Date:** 2026-05-08
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Depends on:** ADR-001 (inference module lives in `:core-inference`), `concept-locked.md` §"Multi-lens extraction architecture", `PRD.md` §P0
**Validated by:** STT-B, STT-C, STT-D (`PRD.md` §"Build philosophy: build first, test at failure zones")

---

## Context

`concept-locked.md` locks the extraction architecture: every entry runs through three lenses (Literal, Inferential, Skeptical), each producing a full extraction across five orthogonal surfaces (Behavioral, State, Vocabulary, Commitment, Recurrence). Convergence between the three lens outputs determines per-field confidence. Two-tier processing splits the work into a fast foreground call (transcription + follow-up) and a background 3-lens pass that fills out the structured schema over the next 30–90 seconds.

That is the pattern. This ADR exists because the locked spec describes the **shape** of the agent, not the **contract**. Phase 1 is about to write the prompt composer, the convergence resolver, and the background scheduler. Without a record of what each piece commits to, three things will drift before Phase 2 lands:

1. The "≥2 of 3 lenses agree" rule has no definition of "agree."
2. The foreground/background split has no recovery semantics for an interrupted background pass (see ADR-001 Q3 — retry-based recovery, not in-flight survival).
3. The 3-call-per-entry cost has no token or battery budget that can be checked against Phase-1/2 measurements.

This ADR closes those gaps and pushes back on the one alternative shape worth naming explicitly.

---

## Decision (summary)

Adopt the multi-lens pattern from `concept-locked.md` verbatim. Implement it as **three independent model calls per entry** (not one combined call with lens-tagged sections), composed via a **separate-storage prompt module system** (lens modules and surface modules concatenated at runtime), with the **convergence resolver implemented as deterministic Kotlin code** (not a fourth model call). Foreground and background passes run as separate inference invocations against the persisted `extraction_status` / `attempt_count` / `last_error` recovery contract owned by ADR-001 Q3.

---

## Locked Pattern (recorded for the handoff)

| Component | Choice | Source |
|---|---|---|
| Lens count | 3 — Literal, Inferential, Skeptical | `concept-locked.md` |
| Surface count | 5 — Behavioral, State, Vocabulary, Commitment, Recurrence | `concept-locked.md` |
| Prompt composition | One lens module + all five surface modules per call | `concept-locked.md` |
| Calls per entry | 3 (background) + 1 (foreground) = 4 total | `concept-locked.md` |
| Convergence resolver | Deterministic code, not a model call | This ADR |
| Schema output | 9 content fields, with extracted fields carrying per-field confidence (`canonical` / `candidate` / `ambiguous` / `canonical_with_conflict`) | `concept-locked.md` §Schema |
| Re-eval | Re-runs the same 3-lens pipeline; diff against original | `concept-locked.md` §Re-eval |

These are recorded for the handoff. Future ADRs may supersede individual rows.

---

## Why three calls and not one combined call

The obvious cost-saving move is "send one prompt that says give me Literal, Inferential, and Skeptical extractions in three labeled sections." Don't.

The whole convergence story rests on **statistical independence between the three lens outputs.** A single rollout that produces all three sections sees its own earlier tokens — the Skeptical section will be conditioned on what Literal already wrote, which is the opposite of adversarial. Convergence becomes a self-fulfilling agreement signal. The 3-of-3 canonical rule degenerates into "the model said it once and didn't contradict itself."

Three independent calls cost more tokens and more battery. They are also the entire reason this architecture earns its name. If STT-D ("3-lens multi-pass produces meaningfully different outputs sometimes") fails on three independent calls, the architecture fails — combining them into one call would only mask the failure. Combined-prompt is therefore not a fallback; it's a different feature.

**One concession:** within a single lens call, all five surfaces share a rollout. That is intentional — surfaces are orthogonal *information* but they share the same lens framing, so co-rollout doesn't violate independence at the lens level.

> **ADR-008 footnote — Session cloning preserves independence.** Running the three lens calls in parallel via cloned Sessions does not collapse them into a combined call. Each clone diverges at the lens-module token and generates its own rollout from there; the shared *prefix* KV-cache (system + 5 surfaces + entry text + history) is precomputed once but the *generated* tokens per lens are independent. The 3-of-3 canonical rule still applies to three independent rollouts, just executed concurrently on top of a shared prefix.
>
> **ADR-009 supersede note (2026-05-11).** The ADR-008 footnote above is preserved as historical record but is no longer the v1 rule. The `litertlm-android:0.11.0` Kotlin SDK does not expose `Session.clone()` — see [ADR-009](ADR-009-litertlm-kotlin-session-clone-unavailable.md). For v1, the three lens calls run **sequentially** against the same Engine; the independence-of-rollouts argument stands unchanged, only the sequencing reverts. Each call still produces its own rollout free of prior-lens tokens.

---

## Prompt Composition Contracts

Three distinct prompts ship in v1 — one for the foreground call, one for each background lens call, and one (conditional) for entry-observation generation when deterministic assembly produces nothing useful. All modules live as **separate Kotlin string resources** in `:core-inference`. The composer concatenates them at runtime. No template variables across files; each module is a self-contained block. AGENTS.md guardrail 9 governs all three: persona modules are tone-only and never appear in lens prompts.

### 1. Background lens prompt (3 calls per entry)

```
[system role — extraction-only, no persona]
[lens module — one of: literal | inferential | skeptical]
[surface module — behavioral]
[surface module — state]
[surface module — vocabulary]
[surface module — commitment]
[surface module — recurrence]
[output schema reminder]
[user content — entry_text + retrieved history chunks]
```

Persona modules are **forbidden** here. The lens framing is the only voice. Adding persona tone to extraction would corrupt the convergence signal — Witness, Hardass, and Editor would produce different `tags` for the same transcript, which is exactly the failure mode AGENTS.md guardrail 9 prevents.

### 2. Foreground prompt (1 call per chunk; final chunk only for >30s captures per ADR-001 Q4)

```
[system role — conversational, on-device-only context]
[persona module — one of: witness | hardass | editor]
[session context — last 4 turns, oldest-first; per Q5]
[output schema reminder — transcription + follow-up, structured]
[user content — current audio chunk (or typed text)]
```

- **Persona module location:** immediately after the system role. Single block. No splicing into surface or lens content.
- **Session context format:** each prior turn as `{speaker: USER|MODEL, text: "..."}` in chronological order. Audio bytes are never replayed; transcribed text only.
- **Multi-turn cap:** last 4 turns by default (Q5). Foreground prompt does not see retrieved history — that is a background-pass concern. Foreground stays small for latency.
- **Output:** structured `{transcription: string, follow_up: string}`. Same shape across personas; only `follow_up` content varies. STT-B verifies reliability of multi-turn structured output on E4B; STT-C covers tag-extraction consistency, with parse-success rate captured as instrumentation (per `PRD.md` §"Build philosophy: build first, test at failure zones" + Action Item #2 below).
- **For >30s captures:** intermediate chunks use a stripped-down variant of this prompt — system role + transcription-only output reminder + audio chunk. No persona, no session context, no follow-up. The final chunk uses the full foreground prompt with the concatenated transcript-so-far injected into the user content.

### 3. Observation-generation prompt (conditional, after resolver)

The resolver attempts deterministic assembly first (commitment captured, vocabulary contradiction, obvious time-of-day context). If that produces nothing useful, run **one short model call** with this prompt:

```
[system role — observation generation, sourced-only, no diagnosis]
[persona module — same persona as the active session]
[output schema reminder — 1–2 observations, each with text + evidence + fields[]]
[user content — entry_text + canonical/candidate fields + nearby resolved entries]
```

- **Persona module location:** same slot as the foreground prompt. The observation inherits the active session's tone.
- **Output schema:** matches `entry_observations` in `concept-locked.md` §Schema and the markdown shape in `architecture-brief.md` §"Markdown Entry Shape." Each observation must include `text` (the line itself), `evidence` (one of: `vocabulary-contradiction`, `commitment-flag`, `volunteered-context`, `theme-noticing`, `pattern-callout`), and `fields[]` (the source field names from the canonical extraction).
- **Forbidden output:** any line without sourced evidence. Any line that opens with the AGENTS.md guardrail 7 / `concept-locked.md` §"Voice rules" forbidden phrases ("you might be feeling," "it seems you're avoiding," "this could indicate"). The resolver post-validates the observation against this list; a violation triggers a single retry, then deterministic fallback.
- **Pattern-enhanced callout (post-threshold):** when ≥10 entries exist and a pattern with ≥3 supporting entries matches, append a pattern callout to the generated observation. The append step is deterministic string assembly using the pattern engine output; no extra model call.

### Why separate storage

Prompts will be tuned daily through Phase 1 and Phase 2. A lens-only tweak should not risk a foreground-prompt diff, and vice versa. Persona modules tune separately again. Mix-and-match composition also makes it cheap to ablate individual surfaces during validation.

### Token budget

E4B's effective context with KV cache pressure is the constraint. Phase 2 must measure actual token counts for each prompt type and confirm headroom for variable user content (long entries, multi-turn history). If any composed system block exceeds ~2K tokens, the relevant module gets tightened — not split into more calls.

---

## Convergence Resolver Contract

The resolver is **Kotlin code**, not a fourth model call. Reasons: deterministic, debuggable, free, and the rules are simple enough that a model would be a regression.

### Per-field "agreement" definition

Each extracted schema field has its own equality predicate. The locked spec didn't pin these; they go here:

| Field | Agreement predicate |
|---|---|
| `template_label` | Exact string match against the closed enum (Aftermath / Tunnel exit / Concrete shoes / Decision spiral / Goblin hours / Audit) |
| `tags` | Normalize case/plurals, then save any tag emitted by at least 2 lenses. If no tag reaches 2 lenses, save the Literal lens's strongest tag as `candidate` so the P0 "at least one visible tag" requirement does not randomly fail on sparse entries. Per-tag confidence = count of lenses including it. |
| `energy_descriptor` | Case-insensitive string match after stop-word strip; or null on all three |
| `recurrence_link` | Same `pattern_id` (deterministic — pattern engine emits stable IDs) |
| `stated_commitment` | Both same `topic_or_person` AND same `entry_id` reference |
| `entry_observations` | Not equality-resolved as free text. Generated after convergence from `entry_text`, canonical/candidate fields, and evidence snippets. |
| `confidence` | Computed, not extracted |
| `entry_text`, `timestamp` | Not lens-extracted; substrate fields |

### Resolution rules

Per field, applied independently:

1. **≥2 of 3 lenses produce equal value (per the predicate above)** → `canonical`, value = the agreed value (for `tags`: the intersection).
2. **Only one lens populates the field, others null** → `candidate`, with the source lens recorded. Pattern engine ignores candidates until promoted by re-eval or later corroboration (auto-promotion deferred to v1.5 per `../backlog.md`).
3. **All three produce values, none agree** → `ambiguous`. Field saved null with a debug note listing the three values.
4. **Skeptical flags a contradiction even when Literal and Inferential agree** → `canonical_with_conflict`. Saved with the agreed value AND a `conflicts` blob containing Skeptical's flag. UI surfaces the marker on the entry detail's Reading section.

### Entry observation generation

After the resolver finishes, generate 1-2 `entry_observations` from the transcript plus resolved fields. This can be deterministic string assembly when a clean signal exists (commitment captured, vocabulary contradiction, obvious time-of-day context) or one short model call if deterministic assembly produces nothing useful. Each observation must include a source field or snippet. Pattern-enhanced callouts are appended only after the pattern threshold; they are not a substitute for entry-local observations.

**Edge case — lens errors mid-call:** if any one lens call errors (timeout, malformed JSON, etc.), the resolver runs with the surviving 2 lenses. Convergence threshold becomes "both must agree" for that entry. If 2+ lenses fail, the entry saves with all fields `ambiguous` and a re-eval suggestion shown to the user.

### Output format

Each lens call returns JSON conforming to the extracted-field schema. Parsing failure on a lens output = lens error (see edge case above). E4B's structured-output reliability is STT-C territory; if JSON returns are flaky, switch to a markdown-with-headers format and parse with a deterministic Kotlin parser. Do not retry the call inside the resolver — that hides a real signal.

### Addendum (2026-05-12) — backend-sensitive structured-output reliability

Two STT-D runs on the 15-entry corpus (`docs/stt-d-manifest.example.txt`), CPU and GPU, on the
reference S24 Ultra, same commit, minutes apart:

| Metric | CPU | GPU | Δ |
|---|---|---|---|
| Meaningful divergence | 12/15 (80%) | 8/15 (53%) | −27 pp |
| `parse-fail` lens calls | 0 | 4 (on C2 + D1, INFERENTIAL each × 2 retries; C2 SKEPTICAL × 2) | +4 |
| Mean per-entry latency | 144.5s | 51.3s | −64% |

Both runs cleared the 30% gate, so the multi-lens architecture stays validated. But GPU's
parse-failure profile is real, reproducible (matches the 2026-05-10 GPU re-run pattern), and
worth pinning down in this ADR rather than under STT-C.

**What we know:**

- `INFERENTIAL` is the predominant failure path. On C2 it failed twice and gave up; on D1 it
  failed twice and gave up. `LITERAL` never failed on either backend.
- Both failing entries share a reflective second clause ("I keep changing the weights and
  pretending that is progress", "This is not a system, it's a small administrative haunting").
  Hypothesis: the INFERENTIAL prompt's structured emit interacts badly with that text shape
  under GPU sampling. Untested; characterization-only.
- The resolver's "edge case — lens errors mid-call" path absorbs this: with 1 or 2 lenses
  surviving, the entry saves with the surviving fields (all marked `ambiguous` if only 1
  survives). Observations on C2 / D1 fall through to deterministic assembly.
- The resolver does not retry the lens call internally (per the §"Output format" rule). The
  retries here happen inside `BackgroundExtractionWorker`'s budgeted retry path before the
  result reaches the resolver. Two attempts is the worker's budget; it does not escalate.

**What we don't know:**

- Whether the failure is GPU FP16 precision, OpenCL delegate kernel selection, or sampling-
  determinism quirks on the structured-emit path. Verifying requires per-lens repeat runs +
  logit inspection — out of scope for v1.

**Decision superseded by Addendum (2026-05-12, second) below.** Retained for history: the
initial decision routed multi-lens to CPU and kept GPU opt-in. Investigation since then
identified the root cause (SDK default sampler — non-greedy — driving FP16 sampling variance
on GPU). Fix moved to `LiteRtLmEngine.DETERMINISTIC_SAMPLER`, restoring GPU as the multi-lens
default.

### Addendum (2026-05-12, second) — deterministic sampler restores GPU multi-lens

Root cause for the GPU regression in the prior addendum: `LiteRtLmEngine` left
`EngineConfig.maxNumTokens` and (more importantly) `ConversationConfig.samplerConfig` unset.
The SDK fell back to non-greedy defaults that produced different output across CPU and GPU
backends — most visibly as INFERENTIAL parse-fail × 2 retries on C2 + D1 (deterministic across
two GPU runs two days apart).

`LiteRtLmEngine` now pins
`SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 42)` on every conversation and
caps `maxNumTokens = 4096`. Re-run on the same 15-entry corpus, same commit, minutes apart:

| Metric | GPU (sdk defaults) | GPU (greedy) | CPU (greedy) |
|---|---|---|---|
| Meaningful divergence | 8/15 (53%) | **9/15 (60%)** | 12/15 (80%) |
| `parse-fail` lens calls | 4 | **0** | 0 |
| Total retries | 4 | **0** | 0 |
| Mean per-entry latency | 51.3s | 48.4s | 144.5s |

**What this changes:**

- **GPU is the multi-lens production default.** Both backends produce deterministic, valid
  structured output. Demo runs on GPU at ~48s/entry — 2.8× faster than CPU and well inside
  the multi-lens background budget.
- **Residual CPU vs GPU divergence gap (20 pp)** is FP16-vs-FP32 logit precision producing
  different greedy picks at narrow-margin tokens. Untestable / unfixable at the SDK layer in
  0.11.0; the GPU side still clears the STT-D 30% gate by a wide margin (60% vs 30%).

**What this doesn't change:**

- No structured-output format switch (still JSON, single schema across backends).
- The deterministic sampler is the production default for *all* `LiteRtLmEngine` callers —
  foreground extraction, observation generation, and pattern title generation get it too.
  Reasoning: the same FP16-induced variance affects every structured-output emit; pinning
  greedy everywhere removes a class of demo-flakiness regardless of which call path lights up
  on stage.
- (Updated by the fourth addendum below) `maxAttemptsPerLens` lifted from 2 → 3 to absorb
  residual FP16 jitter on the longer prompts that addendum introduces.

**Revisit if:** the demo storyboard finds a beat where slight sampling variance (temp > 0)
produces more interesting persona-flavored prose than greedy decode, or a future LiteRT-LM
release exposes a GPU FP32-mode toggle that closes the residual divergence gap.

Evidence: `docs/stt-results/stt-d-2026-05-12-cpu.md` + `stt-d-2026-05-12-gpu.md` (pre-fix)
+ `stt-d-2026-05-12-gpu-greedy.md` (post-fix), all with raw logcat archives.

### Addendum (2026-05-12, third) — two-tier decoupling landed

ADR-002 §"Two-Tier Processing Contract" specified the foreground/background split since
Phase 1. Production code (`BackgroundExtractionSaveFlow.saveAndExtract`) shipped the worker
call as a `suspend` that awaited the full 3-lens pipeline — the contract was documented but
not wired. Caller blocked for ~30-90s per save.

Refactor: `saveAndExtract` now commits the pending entry, returns
`SaveOutcome.Pending(entryId, extractionJob)` immediately, and dispatches the worker +
resolver + observation generator + pattern callout pipeline detached onto the injected
container scope. Terminal status flows through the per-entry `ExtractionStatusListener`
(typically `BackgroundExtractionStatusBus`); UI subscribes to that.

`SaveOutcome` collapses to a single `Pending` variant — the previous `Completed` / `Failed`
/ `TimedOut` subtypes carried terminal extraction state that was always an internal concern;
exposing them on the save return type was an artifact of the synchronous shape.

Persistence failures inside the detached pipeline now route through `compensatePersistenceFailure`
into a terminal FAILED row + listener event instead of escaping to the caller. ADR-001 Q3's
retry-based recovery contract is unchanged — interrupted detached jobs leave PENDING/RUNNING
rows for the cold-start sweep.

### Addendum (2026-05-12, fourth) — sharpened lens framings + 3-attempt retry budget

The second addendum closed the parse-failure gap; this one closes the divergence-rate gap.

The original lens framings (~900-1450 chars each) under-specified the per-lens role: LITERAL
sometimes normalized coined phrases (losing the verbatim signal), INFERENTIAL didn't reliably
emit pattern abstractions, SKEPTICAL fired flags too conservatively. Result on the 15-entry
STT-D corpus: 9/15 (60%) meaningful divergence — passing the gate but with weaker per-entry
evidence than the architecture is capable of.

Sharpened framings (`literal.txt`, `inferential.txt`, `skeptical.txt`) push each lens harder
in its direction with concrete examples and bounded directives:

- LITERAL: codifies verbatim-bias for coined phrases (`administrative-haunting`,
  `concrete-in-my-limbs`, `4:07am`).
- INFERENTIAL: names pattern-level abstractions (`aftermath`, `concrete-shoes`,
  `decision-spiral`, `goblin-hours`) as the lens's specialty.
- SKEPTICAL: lowers the flag-emission bar with concrete trigger patterns; aims for ≥1 flag
  per non-trivial entry.

GPU re-run with these prompts produced **13/15 (87%) meaningful divergence** with 8 entries
diverging on ≥2 axes and 7 Skeptical flags fired (vs 2 before the deterministic sampler).
The signal is qualitatively different — entries like A4 surface 4-axis divergence
(`tags`, `energy_descriptor`, `state_shift`, plus a quoted vocabulary contradiction).

**Cost**: the longer prompts push FP16 GPU into a slightly wider variance band on decode.
`maxAttemptsPerLens` lifted 2 → 3 to absorb transient single-lens jitter. Mean per-entry
latency 88s (vs 48s greedy alone). Two of 15 entries cross thresholds:

- **C1** ran for 5 minutes on a single lens call (single `nativeRunDecode` did not naturally
  terminate the structured output, hit `PER_ENTRY_TIMEOUT_MS = 300_000`). 0/3 lenses parsed.
- **D2** finished with 1/3 lenses parsed — two lenses each exhausted 3 attempts. Resolver
  runs on 1 lens output → entry saves with all-`candidate` confidence per §"Edge case — lens
  errors mid-call".

13/15 (87%) meaningful divergence with 8 entries showing multi-axis evidence is the
production headline. The two outliers are recovery paths the architecture already handles —
ADR-001 Q3 sweeps PENDING/RUNNING on cold start; Re-eval (Phase 4) re-runs the pipeline if
a user wants. Net trade is accepted for v1.

**Revisit if:** real user data shows the 1-in-8 partial-extraction rate is too high (suggests
the FP16 variance is worse than the 15-entry sample), or a future LiteRT-LM release exposes
generation-stopping criteria (max-output-tokens that actually terminate cleanly instead of
running to the engine-side `maxNumTokens` ceiling).

Evidence: `docs/stt-results/stt-d-2026-05-12-gpu-sharp.md` with raw logcat.

### Addendum (2026-05-12, fifth) — Skeptical-only revision ships

The fourth addendum (sharpened LITERAL + INFERENTIAL + SKEPTICAL with `maxAttemptsPerLens=3`)
produced 87% divergence but at unacceptable cost: 1 entry timed out at the 5-min ceiling, 1
entry parsed only 1 of 3 lenses, mean latency ~88s. Investigation document (2026-05-12,
in-thread) reframed: the architecture's load-bearing claim isn't divergence count, it's
**Skeptical doing adversarial work** — `canonical_with_conflict` verdicts reachable in the
data. The greedy baseline (Run 2 — 60% divergence) fired zero Skeptical flags, leaving the
architecture's distinguishing beat unverified.

Decision-tree step 1: revise the Skeptical lens module alone, holding under the FP16
prompt-budget cliff the other two lenses already respect.

- LITERAL + INFERENTIAL: restored to baseline. The Run 1/Run 3 sharpening added net tokens
  that pushed FP16 over the cliff (confirmed twice).
- SKEPTICAL: revised to 1398 chars — 43 chars *under* the 1441 baseline budget. Keeps five
  named flag kinds with one-line concrete triggers; drops Run 3's over-prescriptive "aim for
  ≥1 flag per non-trivial entry" directive and replaces it with "do not invent a contradiction".
- `maxAttemptsPerLens` back to 2 (the Run 3 bump was a band-aid for the prompt-budget
  violation).

Result on the 15-entry STT-D corpus (GPU, greedy, 2026-05-12 — Run 4):

| Metric | Run 2 (greedy baseline) | Run 4 (Skeptical-only) | Δ |
|---|---|---|---|
| Meaningful divergence | 9/15 (60%) | 11/15 (73%) | +13 pp |
| Skeptical flags fired | 0 | **4** | **+4** |
| `canonical_with_conflict`-eligible entries | 0 | **2** (A4, B2) | +2 |
| Mean per-entry latency | 48s | **33s** | −31% |
| Full 3-lens entries | 15/15 | 14/15 (B3 partial) | −1 |
| Timeouts | 0 | 0 | 0 |

The four flags fire on quoted user evidence — A1 `state-behavior-mismatch`, A4
`vocabulary-contradiction`, B2 `commitment-without-anchor`, C1 `unsupported-recurrence`. A4
and B2 fire with `disagree_fields=[]`, making `canonical_with_conflict` reachable for the
first time on the corpus.

**The verdict missed the investigation document's strict gate (≥5 flags) by one.** Shipped
anyway because:

1. The qualitative move is real — 0 → 4 flags with quoted evidence, 0 → 2 entries with
   reachable `canonical_with_conflict` verdict, 4 flag kinds fired (only `time-inconsistency`
   silent — corpus has no incompatible time anchors).
2. The architecture's load-bearing claim is now demonstrable. Demo Chapter 3 has 4 flagged
   entries to choose from for the storyboard.
3. The fifth flag is gated by FP16 prompt budget, not by lens-prompt iteration. The next
   meaningful improvement requires Option 2 (surfaces per lens, ADR-002 supersede) — out of
   v1 scope.

**Known limitation:** Skeptical matches the exact pattern shapes the prompt's examples name,
not adjacent cases sharing the principle. Run 3's longer triggers covered more shapes but
broke parse reliability — confirmed twice that prompt expansion is bounded by FP16.

Evidence: `docs/stt-results/stt-d-2026-05-12-gpu-skep.md` + raw logcat.

### Addendum (2026-05-10) — conservative tag persistence

Supersedes the earlier `tags` wording that implied plural normalization should rewrite the saved value itself. Surfaced when a naive singularizer in `DefaultConvergenceResolver` corrupted legitimate singular tags (`news` → `new`, `series` → `sery`) because the stem was being persisted, not just counted (Story 2.8, fix landed in commit `2944843`).

- **Agreement counting:** the resolver may normalize case/plurals to decide whether lenses agree on a tag.
- **Persisted value:** the resolver must save a deterministic surface form that was actually emitted by a lens. It must not persist a generated stem/singular form unless a future ADR adopts a proven morphology layer.
- **Current v1 behavior:** `meeting` and `meetings` count as agreement-equivalent for convergence, but the saved canonical tag is the first majority-winning surface form in lens order. If no tag reaches majority, the Literal lens's strongest tag is saved as `candidate`.
- **Reason:** the lightweight singularizer that is good enough for counting is not safe for storage. It corrupts legitimate singular tags such as `news` and `series`, which is worse than tolerated plural drift.
- **Deferred work:** full canonical morphology stays in backlog land until retrieval/embeddings work justifies a stronger normalization layer.

---

## Two-Tier Processing Contract

### Foreground call (always runs first)

- **Input:** audio bytes (or typed text) + session context
- **Output:** transcription + persona-flavored follow-up question, in **one structured response**
- **Latency budget:** show a placeholder immediately after audio chunk completion; Phase 1/2 measures actual S24 Ultra latency. Use 1-5 seconds as the target range, not a contractual promise.
- **Persistence:** transcription saved as `entry_text` immediately. Audio bytes discarded.
- **Returns to user:** the transcript + follow-up appear in the conversation. Background pass kicks off without blocking the next user turn.

### Background pass (3 lens calls, sequential)

> **Sequencing history.** Original ADR-002 rule below: sequential. [ADR-008](ADR-008-parallel-lens-execution.md) (2026-05-10) attempted to supersede this with parallel-via-Session-cloning. [ADR-009](ADR-009-litertlm-kotlin-session-clone-unavailable.md) (2026-05-11) supersedes ADR-008 — the `litertlm-android:0.11.0` Kotlin SDK does not expose the cloning API the design required, and no upstream release is published in the v1 submission window. The original sequential rule below is restored as the v1 contract. Story 2.7 device record: ~5–7 s per lens on E4B GPU; per-entry 25–55 s on the reference S24 Ultra.

- **Trigger:** foreground call completes and the entry row is committed with `extraction_status=PENDING` (per ADR-001 Q3).
- **Sequencing:** lens calls run **sequentially**, not in parallel. E4B holds one model handle on-device — parallel calls would either OOM or serialize at the runtime layer anyway, with worse error surfaces.
- **Status transitions:** `PENDING` → `RUNNING` at start of the first lens call → `COMPLETED` / `TIMED_OUT` / `FAILED` after the resolver runs (or the time budget trips).
- **Time budget:** 30–90 seconds total. If exceeded, abort, set `extraction_status=TIMED_OUT`, suggest re-eval to user.
- **On completion:** convergence resolver runs synchronously after all three lens calls return. Fields and the terminal `extraction_status` are written to ObjectBox in the same transaction.

### Retry-based recovery (per ADR-001 Q3)

If the background pass is interrupted (process killed, OOM, user backgrounded the app long enough for the OS to reclaim), the row stays in `PENDING` or `RUNNING` — the latter implies a kill mid-flight. On next cold start, the inference module sweeps for both states, increments `attempt_count`, and re-runs the 3-lens pass from scratch before accepting new user input. User sees a one-line status: `Resuming reading on {N} entries.`

This is **not** the same as Re-eval. Re-eval is a user-tappable affordance that compares new convergence to the prior canonical extraction; recovery just finishes work that was interrupted before any canonical record existed. Field schema (`extraction_status`, `attempt_count`, `last_error`) is owned by ADR-001 Q3.

---

## Open Implementation Questions (close before Phase 1 ends)

### Q1. Streaming foreground response

The foreground call returns transcription AND follow-up in one structured response. Streaming the follow-up token-by-token (for UI snappiness) requires either parsing as we go or returning unstructured text and post-extracting the transcription. STT-B will tell us how reliable structured output is on E4B; the streaming decision falls out of that.

**Default for Phase 1:** non-streaming structured response. Switch to streaming only if Phase-2 demos visibly suffer from latency.

### Q2. Retrieved history budget

Background lens calls compose `(lens + 5 surfaces + retrieved history + entry text)`. Hybrid retrieval (per ADR-001 Q1, owned by `:core-storage`) returns a candidate set; the resolver doesn't decide how many to include. **Default: top 3 retrieved chunks per call**, capped at ~500 tokens of history. Tunable from Phase 2 measurements.

### Q3. Re-eval cost story

Re-eval re-runs the full 3-lens pipeline. A power user tapping re-eval frequently is a battery footgun. Phase 4 should add a soft confirmation on the second re-eval within 60 seconds: `Re-read this entry? Costs ~30s of inference.` Not a blocker for Phase 1, but record it now.

### Q4. Convergence resolver test surface

The resolver is the most high-leverage piece of code in the build (every entry goes through it; bugs here corrupt the canonical store). It needs unit tests against synthetic 3-lens output sets covering: all-agree, 2-of-3 agree, all-disagree, only-Inferential, Skeptical-conflict, lens-error fallbacks. Phase 1 must ship this test suite before Phase 2 begins relying on the resolver.

### Q5. Foreground call's session context

Multi-turn requires the model to see prior exchanges. We pass the last N turns as context. **Default: last 4 turns**, matching `PRD.md` §Multi-turn acceptance criteria. If E4B's KV cache makes this expensive on every chunk, drop to last 2.

---

## Trade-off Analysis

The pattern trades **inference cost** (4 calls per entry, 3-of-them sequentially in the background) for **calibrated confidence** (per-field convergence is cheap and meaningful information that no single-pass extractor produces). It also trades **latency-to-canonical-fields** (30–90s) for **latency-to-acknowledgement** (placeholder immediately, response after measured foreground latency) — the user gets the conversational beat fast and the structured data fills in invisibly.

The two trade-offs that could go wrong:

- **If lenses always agree** (STT-D fails), the 3× cost buys nothing visible, and the architecture is a pretentious wrapper around single-pass extraction. Mitigation: STT-D *exists* exactly to catch this. If it fires, replan rather than ship the wrapper.
- **If E4B's structured output is flaky**, the resolver's parsing failure fallback kicks in too often, and we ship a system where 1-in-N entries silently downgrade to 2-of-2 convergence. Mitigation: STT-C measures consistency on real prompts. If parsing is below 95% reliable, switch to markdown-headers format before Phase 2.

Pretending neither risk exists, the pattern is the right shape for the product and the demo. The convergence story is **the** technical-walkthrough beat. Cutting it for "simpler" single-pass extraction would also cut what makes Vestige a non-trivial Gemma 4 application instead of a thin wrapper.

---

## Consequences

**Easier:**
- Pattern engine consumes a clean per-field confidence signal instead of having to second-guess single-pass extraction.
- Re-eval is "run the same code path again" — no special re-eval pipeline.
- Surface and lens modules can be tuned independently during Phase 1/2 without combinatorial regression risk.

**Harder:**
- Background scheduler needs the retry-based recovery path (per ADR-001 Q3): durable status fields, cold-start sweep, capped retry budget. Non-trivial code, but bounded.
- The convergence resolver test suite is required Phase-1 work, not optional.
- Battery cost is real. The on-device privacy story rules out cloud offload, so the cost is a fixed feature of the architecture.
- Prompt composition adds a layer of indirection that has to be debuggable. Logs must capture the exact composed prompt per call during dev builds.

**Revisit when:**
- STT-D fires with "lenses always agree." Then this ADR gets superseded by a single-pass design.
- E4B is replaced (post-v1) by a model with reliable native structured output and tool calling. The resolver-as-Kotlin-code may become resolver-as-tool-call.
- Re-eval auto-promotion ships in v1.5 — the resolver gains a "promote candidate to canonical after N agreeing re-evals" branch.

---

## Multi-turn behavior — STT-B device-test record

**Round 1 (2026-05-09, S24 Ultra, E4B CPU, 3 personas × 4 turns = 12 turns):** harness reported `retention=1.0/3 sessions`. Investigation showed the manifest's anchors for turns 3 and 4 included substrings (`Nora`, `outline`, `risk section`) first introduced in those turns' own audio — Gemma echoing this-turn vocabulary in the follow-up tripped the substring matcher and looked like context retention. False positive; harness output was misleading.

**Round 2 (2026-05-09, same device, corrected manifest):** anchors restricted to substrings introduced in earlier turns AND absent from the current turn's audio per `docs/sample-data-scenarios.md` §STT-B. Result: `retention=0.0/3 sessions`. Across all 9 turn-≥2 lookups, **zero cross-turn anchors hit**. Read of every follow-up: each probe quotes only the audio Gemma just heard; `launch doc` / `standup` / `the doc` / `roadmap call` are never referenced after introduction. The `## RECENT TURNS` JSON history block is composed and sent (verified in `composeSystemPrompt`); E4B receives it and ignores it on this prompt design.

**Round 3 (in flight, 2026-05-09 — explicit instruction try):** added `RECENT_TURNS_INSTRUCTION` to `ForegroundInference` — a prose block emitted between `## RECENT TURNS` and the JSON lines telling the model the JSON below is conversation context and that follow-ups must explicitly name prior facts when the new audio relates to them. Same audio, same corrected manifest re-run pending. If round 3 still produces `retention=0.0`, the Story 2.4 fallback fires (drop multi-turn from v1, single-turn becomes the v1 path, dependent docs and stories rewrite).

Latency record across all rounds, E4B CPU on S24 Ultra: per-turn 32.7–43.3 s. The ADR-002 §"Latency budget" 1–5 s target remains unmet; latency tuning is Phase 4/5 territory and does not gate the STT-B existential verdict.

---

## Action Items

1. [ ] STT-B — verify foreground call returns transcription + follow-up reliably as structured output across multi-turn. Output decision: structured JSON vs markdown-with-headers.
2. [ ] STT-C instrumentation — measure JSON-parse success rate across 10+ varied test dumps per lens as part of tag-consistency assessment. If parse <95%, switch all lens outputs to markdown-with-headers and update `:core-inference` parser accordingly. Parse reliability is the prerequisite that lets STT-C measure tag consistency at all; it is not a separate stop-and-test.
3. [ ] STT-D — confirm 3-lens outputs differ meaningfully on at least 30% of test entries. If not, halt and replan.
4. [ ] Phase 1 — implement `:core-inference` prompt composer with separate lens/surface module storage. Log composed prompts in dev builds.
5. [ ] Phase 1 — implement convergence resolver as Kotlin code with the unit test suite specified in Q4.
6. [ ] Phase 1 — implement background scheduler against the `extraction_status` / `attempt_count` / `last_error` fields and cold-start sweep defined in ADR-001 Q3.
7. [ ] Phase 2 — wire foreground call to UI streaming or non-streaming per Q1 decision.
8. [ ] Phase 4 — add re-eval cost confirmation per Q3.
9. [x] Update root README's "Reading order" to link this ADR alongside ADR-001.
