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

### 2. Foreground prompt (1 call per capture; single-turn-per-capture per the STT-B fallback)

```
[system role — conversational, on-device-only context]
[persona module — one of: witness | hardass | editor]
[output schema reminder — transcription + follow-up, structured]
[user content — current audio chunk (or typed text)]
```

- **Persona module location:** immediately after the system role. Single block. No splicing into surface or lens content.
- ~~Session context format~~ **Removed 2026-05-09 per the STT-B fallback** (§"Multi-turn behavior" above). The foreground prompt no longer carries prior-turn context — each capture is a self-contained exchange.
- **Output:** structured `{transcription: string, follow_up: string}`. Same shape across personas; only `follow_up` content varies. STT-C covers tag-extraction consistency under single-turn, with parse-success rate captured as instrumentation (per `PRD.md` §"Build philosophy: build first, test at failure zones" + Action Item #2 below).
- **For >30s captures:** intermediate chunks use a stripped-down variant of this prompt — system role + transcription-only output reminder + audio chunk. No persona, no follow-up. The final chunk uses the full foreground prompt with the concatenated transcript-so-far injected into the user content. (This is single-turn-internal-to-one-capture, not multi-turn across captures.)

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

E4B's effective context with KV cache pressure is the constraint. Phase 2 must measure actual token counts for each prompt type and confirm headroom for variable user content (long entries; the multi-turn history concern is moot under the single-turn fallback). If any composed system block exceeds ~2K tokens, the relevant module gets tightened — not split into more calls.

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

---

## Two-Tier Processing Contract

### Foreground call (always runs first)

- **Input:** audio bytes (or typed text) — single-turn-per-capture, no prior session context (STT-B fallback)
- **Output:** transcription + persona-flavored follow-up question, in **one structured response**
- **Latency budget:** show a placeholder immediately after audio chunk completion; Phase 1/2 measures actual S24 Ultra latency. Use 1-5 seconds as the target range, not a contractual promise.
- **Persistence:** transcription saved as `entry_text` immediately. Audio bytes discarded.
- **Returns to user:** the transcript + follow-up appear in the conversation. Background pass kicks off without blocking the next user turn.

### Background pass (3 lens calls, sequential)

- **Trigger:** foreground call completes and the entry row is committed with `extraction_status=PENDING` (per ADR-001 Q3).
- **Sequencing:** lens calls run **sequentially**, not in parallel. E4B holds one model handle on-device — parallel calls would either OOM or serialize at the runtime layer anyway, with worse error surfaces.
- **Status transitions:** `PENDING` → `RUNNING` at start of the first lens call → `COMPLETED` / `TIMED_OUT` / `FAILED` after the resolver runs (or the time budget trips).
- **Time budget:** 30–90 seconds total. If exceeded, abort, set `extraction_status=TIMED_OUT`, suggest re-eval to user.
- **On completion:** convergence resolver runs synchronously after the third lens. Fields and the terminal `extraction_status` are written to ObjectBox in the same transaction.

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

### Q5. Foreground call's session context — SUPERSEDED 2026-05-09

~~Multi-turn requires the model to see prior exchanges. We pass the last N turns as context. **Default: last 4 turns**, matching `PRD.md` §Multi-turn acceptance criteria. If E4B's KV cache makes this expensive on every chunk, drop to last 2.~~

**Superseded by §"Multi-turn behavior" above.** STT-B verdict 2026-05-09: E4B does not use prior-turn context regardless of count or instruction. The foreground call's system prompt no longer includes a recent-turns block. `historyTurnLimit` is removed from `ForegroundInference`; `runForegroundCall(audio, persona)` is the v1 signature.

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

**Round 3 (2026-05-09 — explicit instruction try):** added `RECENT_TURNS_INSTRUCTION` to `ForegroundInference` — a prose block emitted between `## RECENT TURNS` and the JSON lines telling the model the JSON below is conversation context and that follow-ups must explicitly name prior facts when the new audio relates to them. Same audio, same corrected manifest. Result: `retention=0.0/3 sessions` again. **Zero cross-turn anchor hits across 18 turn-≥2 lookups across all three rounds (24 turns total).** The instruction did slow inference (per-turn 34.9–65.3 s vs round 2's 33.3–43.3 s — longer prompt = more CPU tokens) without changing model behavior. Every follow-up across all three rounds references only the audio Gemma just heard; `launch doc`, `standup`, `the doc`, and `roadmap call` are never echoed after introduction.

**Verdict (2026-05-09): STT-B FAILED. Story 2.4 fallback executed.** Multi-turn dropped from v1. The `## RECENT TURNS` block, the 4-turn history limit, the `transcript` parameter on `runForegroundCall`, and the `RECENT_TURNS_INSTRUCTION` block are all removed from `ForegroundInference`. `CaptureSession` becomes single-use (terminal at RESPONDED). Each capture is a self-contained `{transcription, follow_up}` exchange; subsequent recordings begin fresh sessions. Q5 below is **superseded** by this section.

Latency record across all rounds, E4B CPU on S24 Ultra: per-turn 32.7–65.3 s. The §"Latency budget" 1–5 s target remains unmet; latency tuning is Phase 4/5 territory and is unrelated to the STT-B fail mode (every round was an attention/instruction-following failure, not a speed failure).

The Round 1 false-positive lesson is worth preserving for future stop-and-test work: substring-anchor scoring is only meaningful when the anchor lists are restricted to substrings introduced in PRIOR turns AND absent from the CURRENT turn's audio. Anchors that appear in this turn's own audio measure same-turn echo, not cross-turn retention. Round 1 reported `retention=1.0` on a manifest with that bug; round 2's corrected manifest exposed `retention=0.0`. Same model, same prompt, same audio, opposite verdict.

---

## Action Items

1. [x] STT-B — ~~verify foreground call returns transcription + follow-up reliably as structured output across multi-turn~~ **FAILED 2026-05-09; fallback executed.** See §"Multi-turn behavior" above for the round-by-round record. Structured-output reliability under single-turn is captured separately by STT-C (Action Item #2).
2. [ ] STT-C instrumentation — measure JSON-parse success rate across 10+ varied test dumps per lens as part of tag-consistency assessment. If parse <95%, switch all lens outputs to markdown-with-headers and update `:core-inference` parser accordingly. Parse reliability is the prerequisite that lets STT-C measure tag consistency at all; it is not a separate stop-and-test.
3. [ ] STT-D — confirm 3-lens outputs differ meaningfully on at least 30% of test entries. If not, halt and replan.
4. [ ] Phase 1 — implement `:core-inference` prompt composer with separate lens/surface module storage. Log composed prompts in dev builds.
5. [ ] Phase 1 — implement convergence resolver as Kotlin code with the unit test suite specified in Q4.
6. [ ] Phase 1 — implement background scheduler against the `extraction_status` / `attempt_count` / `last_error` fields and cold-start sweep defined in ADR-001 Q3.
7. [ ] Phase 2 — wire foreground call to UI streaming or non-streaming per Q1 decision.
8. [ ] Phase 4 — add re-eval cost confirmation per Q3.
9. [x] Update root README's "Reading order" to link this ADR alongside ADR-001.
