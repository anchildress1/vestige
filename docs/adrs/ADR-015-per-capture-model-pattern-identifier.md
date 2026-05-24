# ADR-015 — Per-Capture Model-Driven Pattern Identifier

**Status:** Proposed (no implementation on `main` as of 2026-05-19; this ADR records the contract the v1.5 / v2 follow-up branch will build against)
**Date:** 2026-05-19
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative once the status flips to **Accepted**.
**Supersedes:**
- ADR-014 §"Background pattern analysis (asynchronous, periodic)" — replaces the modulo-3 (formerly modulo-5, formerly modulo-10) cadence with a per-capture trigger
- ADR-014 Addendum (2026-05-19) §"The model writes the persisted title and latest callout text only; it does not decide whether the pattern exists" — the model now owns *identification* in addition to wording
- ADR-003 §"Pattern detection algorithm" — the deterministic detector becomes a fallback shape, not the primary identifier
**Depends on:** ADR-001 §Q3 (retry-based recovery), ADR-002 §"Two-tier processing" (the foreground/background split this builds on), ADR-008 §Correction (single-session SDK ceiling), ADR-014 (foreground/background lifecycle this refines)

---

## Context

ADR-014 locked the v1 lifecycle: foreground transcription → background 3-lens × 5-surface extraction → background deterministic-pattern pass every N entries. The 2026-05-19 Addendum tightened N to 3 and added a best-effort Gemma wording pass for `TEMPORAL_RELATIVE` matches **only**, with the explicit boundary "the model does not decide whether the pattern exists."

That boundary is the design constraint this ADR pivots:

1. **Patterns are inherently fuzzy.** Deterministic rules (`detectTemplateRecurrence`, `detectTagPair`, `detectGoblinHours`, `detectCommitments`, `detectVocab`, `TemporalRelativePatternDetector`) cover the cleanest signatures — recurring tag pairs, fixed time-of-day clusters, calendar-aligned recurrences. They miss the patterns a reader would *recognise* but can't write a rule for: "the user keeps describing the same kind of email", "the energy crash always follows a specific person's name", "every late-night entry is about the same decision they keep deferring." A model reading the entry-against-history is the natural identifier for that shape.
2. **Modulo-N cadence is the wrong unit.** Every 3rd entry means two-thirds of captures finish with the pattern engine inert. The user's expectation — surfaced explicitly in conversation — is that *every capture* gets a pattern pass. Modulo is a battery optimisation that pre-supposed an expensive deterministic-fixed-cost identifier; with a model identifier the cost is per-call and the cadence question becomes "how do we keep one in flight cheaply", not "how rarely can we afford to look."
3. **ADR-014's 5-entries-then-3-entries cadence already conceded** that the right cadence is "as often as we can afford." Per-capture is the limit of that line; the SDK's single-session ceiling (ADR-008) is what bounds parallelism, not cadence.

This ADR makes the per-capture model identifier the v1.5 / v2 contract, leaves the deterministic detector in place as a structural fallback, and pins the input shape so the privacy invariant holds.

---

## Decision (summary)

The post-v1 lifecycle has four call types — foreground unchanged, background analytics unchanged, background pattern split into two phases:

1. **Foreground (synchronous, user waits):** one model call returning `{transcription, follow_up}`. Unchanged from ADR-014.
2. **Background analytics (asynchronous, immediate):** 3 lens × 5 surfaces, sequential per ADR-008. Unchanged from ADR-014.
3. **Background pattern identifier (asynchronous, every capture):** one model call against a **distilled history slice** + the just-completed entry's distilled fields. The model returns a structured list of pattern candidates (`{pattern_signature, supporting_entry_refs, kind, confidence, suggested_title, suggested_callout}`). The output feeds the existing `PatternStore` upsert path.
4. **Background pattern fallback (deterministic, conditional):** when the identifier call fails (`parse-fail`, timeout, model unavailable), the existing deterministic detector runs against the same history slice. The deterministic kinds (`TEMPLATE_RECURRENCE`, `TAG_PAIR`, `TIME_OF_DAY_CLUSTER`, `COMMITMENT_RECURRENCE`, `VOCAB_FREQUENCY`, `TEMPORAL_RELATIVE`) survive as the floor. ADR-003's deterministic rules become the safety net, not the primary surface.

The user waits only for step 1. Steps 2, 3, 4 are background; steps 3 and 4 are sequential after step 2 completes (SDK ceiling) and never run concurrently with each other (3 first, 4 only on 3's failure).

---

## Lifecycle Contract

| Trigger | Type | What runs | User-blocking? | Failure handling |
|---|---|---|---|---|
| User taps `I'm done.` | Foreground | `{transcription, follow_up}` call | Yes | Surface error in capture; entry not persisted unless transcription returns |
| Foreground returns | Persist + enqueue | `EntryStore.createPendingEntry` → schedule background lens worker | No | Persist before scheduling; markdown is source of truth |
| Background lens N completes (×3) | Background analytics | Per-lens parse + `LensResult` accumulation | No | Per-lens parse-fail → no-opinion; ADR-002 contract intact |
| All 3 lenses complete | Background analytics | `ConvergenceResolver` writes consensus / candidate / ambiguous fields | No | ADR-001 §Q3 retry recovery |
| `completeEntry` returns | **Background pattern identifier** | Build history slice (see §History Slice Contract); one Gemma call; parse identifier output; upsert patterns | No | On parse-fail / timeout / model unavailable → fall through to deterministic detector on the same slice |
| Identifier failure | **Background pattern fallback** | Deterministic detector (ADR-003's rule set) over the same slice | No | Detector failure logged; entry persists without pattern updates; next capture re-triggers the pipeline |

The "one Gemma call at a time" runtime ceiling (ADR-008) is what serialises the per-capture identifier behind the lens batch. There is no concurrent dispatch in v1.5; the identifier call queues behind the 3-lens batch and dispatches when the engine releases.

---

## History Slice Contract

The identifier call's input is the load-bearing privacy surface. The slice is **never raw transcripts** — only distilled fields the user has already seen in the entry detail UI.

**Slice content (per supporting entry, capped at N most-recent COMPLETED entries — N is a tunable, default ≤ 50):**

- `entryId` (opaque, not the markdown filename)
- `timestamp` (UTC ms)
- `templateLabel` (if assigned)
- `tags` (the converged tag list)
- `energy_descriptor` (the converged single value)
- `stated_commitment` (topic-or-person only; commitment text excluded)
- `recurrence_link` (the converged value)
- A single-line summary if available from existing observation output — never the raw `entry_text`

**Slice content for the just-committed entry:** identical shape, marked as the "target" the identifier is reasoning about.

**What the slice never contains:**

- Raw transcription bytes (`entry_text`) — model already saw it in the foreground call; persisting it into the identifier prompt would multiply the privacy exposure
- Audio bytes (already discarded after foreground per AGENTS.md guardrail)
- Lens raw outputs (`lensReceiptsJson`) — distilled values only
- `follow_up` text — user-facing, not pattern signal

The slice is built inside the box read transaction that produces the supporting candidates; the identifier prompt is templated against the slice and discarded after the call returns. The privacy invariant ("network = model download only", "no raw user content in logs") extends: identifier prompts never reach a log sink, identifier responses are length-logged only.

---

## Identifier Output Contract

The model returns a JSON list of candidate patterns. Each candidate carries:

- `kind` — one of the existing `PatternKind` values **or** a model-emitted free-form label (the v1.5 question: do we let the model invent new kinds or constrain it to the enum? Default in this ADR: constrain to the enum; the deterministic enum stays the source of truth for `PatternSignature` content-addressability)
- `signature` — the structured payload the model uses to derive the `Signature.json` content-addressable hash (preserves the `@ConsistentCopyVisibility` contract from `PatternSignature`)
- `supporting_entry_ids` — references back to the slice IDs
- `confidence` — `consensus` / `candidate` / `ambiguous` mirroring ADR-002's verdict ladder
- `suggested_title` — model-written title (replaces `PatternTitleGenerator`'s standalone call)
- `suggested_callout` — model-written callout text (replaces `PatternAnalysisGenerator`'s temporal-only callout pass; now general-purpose)

The output flows through the existing `PatternStore.upsert` path. Lifecycle states (`ACTIVE`/`SNOOZED`/`CLOSED`/`DROPPED`) are unchanged; the model identifies, the deterministic state machine still owns transitions.

**Output validation (fail-closed):**

- Schema rejected → deterministic fallback runs on the same slice
- Empty list → no pattern updates (legitimate "nothing identified" — not a failure)
- Unknown `kind` → that candidate dropped; remaining candidates proceed
- `supporting_entry_ids` referencing entries outside the slice → that candidate dropped
- Confidence outside the consensus/candidate/ambiguous ladder → that candidate dropped

---

## What This Supersedes

| Prior location | Old rule | New rule |
|---|---|---|
| ADR-014 §"Background pattern analysis (asynchronous, periodic)" | Pattern analysis runs every 3 entries written (post-Addendum 2026-05-19; was 5 in body, was 10 in ADR-003) | Pattern identifier runs **every capture**, after the 3-lens batch completes. Cadence is now bounded by SDK serialisation, not by an arbitrary modulo. |
| ADR-014 Addendum (2026-05-19) §"Temporal-relative patterns get one best-effort background Gemma wording pass... it does not decide whether the pattern exists" | Model is the namer; deterministic detector is the identifier | Model is both **identifier** and **namer**. Deterministic detector is the fallback when the model call fails. |
| ADR-003 §"Pattern detection algorithm" (deterministic rule cascade) | Deterministic rules are the only identifier; model has no role in pattern existence | Deterministic rules survive as the failure-mode fallback. Their output shape (`DetectedPattern`, `Signature`, `PatternKind`) is the contract the model identifier must match. |
| Phase-3 Story 3.5 §"Detection is deterministic Kotlin code, not a model call" | True at v1 | False at v1.5 / v2. The implementing story (see §Backlog Addition) supersedes this clause. |

---

## What This Does NOT Change

- **Foreground call contract** (ADR-002 §"Foreground prompt", ADR-014 step 1) — the user-blocking call remains transcription + follow-up only.
- **Background analytics contract** (ADR-002 §"Two-tier processing", ADR-014 step 2) — 3 lens × 5 surfaces, sequential, sealed.
- **Convergence resolver** (ADR-002 §"Convergence Resolver Contract") — still deterministic Kotlin over lens outputs. The identifier is a *separate* model call on the resolver's outputs, not a replacement.
- **`PatternStore` lifecycle states + transitions** (ADR-003 §"Auto-promotion of snoozed → active", ADR-003 Addendum 2026-05-13 §`SNOOZED`/`snoozedUntil`) — model identifies, state machine still owns transitions.
- **Callout cooldown** (ADR-003 §"Pattern callout cooldown" via `CalloutCooldownStore`) — the 3-entry global reservation invariant survives unchanged.
- **`PatternSignature` content-addressable contract** (ADR-003 + the `@ConsistentCopyVisibility` seal on `Signature`) — `patternId = sha256(signatureJson)`. The model emits the signature payload; the hash is still computed deterministically by `Signature.of`.
- **Privacy invariants** — model download is the only network path; no raw transcripts in identifier prompts or logs; identifier responses logged length + exception class only.
- **Single-runtime guardrail** (AGENTS.md guardrail 13) — one LiteRT-LM Engine, queued sequentially.

---

## Consequences

**Easier:**

- Pattern surface tracks user activity in real time — no "stale until the next modulo trigger" gap.
- Fuzzy patterns the deterministic detector can never reach become reachable (recurring topics described in different words, cross-tag co-occurrence the rule set doesn't enumerate, situational recurrence tied to extracted state).
- Title + callout generation collapses into the same call as identification — one model dispatch per capture instead of three separate Gemma calls (identifier + title + callout).
- The deterministic detector becomes a true fallback (validated by the same on-device probe path), which is easier to reason about than "the deterministic detector is the primary but the model also runs sometimes for naming."

**Harder:**

- Battery: one additional Gemma call per capture. Measurement gate before this lands (see §Backlog Addition).
- Latency: identifier call queues behind the 3-lens batch. At the ADR-014 probe's 14.7s/lens, 3 lenses + identifier ≈ 60s per entry of background work. Not user-blocking but battery-and-thermal-relevant.
- Schema discipline: the model's freedom to emit candidates needs validation. A malformed candidate (unknown kind, dangling entry id, bad confidence) drops the candidate without dropping the whole pass; the deterministic fallback never fires partially.
- The fallback path doubles the worst-case background work for an entry where the identifier fails. Cap the fallback to one attempt per capture; no second-try cascade.
- The deterministic detector's existence tax stays — it must compile, lint, scan clean, and be tested at the floor invariants even though it rarely runs.

**Revisit when:**

- Battery telemetry from a real demo run shows the per-capture identifier hits a measurable battery floor → revert to modulo-N cadence (this ADR refines; doesn't supersede ADR-014's split).
- LiteRT-LM ships concurrent contexts (ADR-008 adoption gate) → identifier and analytics can dispatch in parallel; reduces tail latency for the background batch.
- Identifier accuracy measurements (post-implementation) show the deterministic kinds dominate the model output anyway → re-evaluate whether the model identifier is earning its cost over the deterministic detector. The two-path design makes this measurable.

---

## Backlog Addition

- `pattern-identifier-implementation` — v1.5, patterns. Implementing story for this ADR. Unblock: v1 ships; the next branch lands the history-slice builder, identifier prompt resource, response parser, fail-closed fallback wiring, and on-device measurement of one full capture-to-pattern-update cycle. Phase-3 Story 3.5's "deterministic only" clause is superseded by this work; the implementing story must cite this ADR.
- `pattern-identifier-battery-gate` — v1.5, perf. On-device measurement of three demo-shape captures end-to-end (foreground + 3-lens + identifier) before the cadence ships. If battery floor exceeds the v1 baseline by > 10% on the reference device, fall back to modulo cadence per the §"Revisit when" trigger.
- `pattern-kind-vocabulary` — v1.5 / v2, model. Decide whether the identifier can emit free-form kinds or must constrain to the `PatternKind` enum. Default in this ADR is the enum; the trade-off lives here.

---

### Implementation references

When this ADR's status flips to **Accepted** and the implementing story lands, the following surfaces will be modified or replaced. Recorded here so the implementing branch has a starting map:

- `core-storage/.../PatternDetector.kt` — moves to fallback path; called only when the identifier fails.
- `core-inference/.../PatternAnalysisGenerator.kt` — generalised from temporal-only callout to whole-output identifier + namer.
- `core-inference/.../PatternTitleGenerator.kt` — folded into the identifier output (`suggested_title`); the standalone call is removed.
- `core-inference/src/main/resources/patterns/` — new prompt resource for the identifier schema.
- `app/.../patterns/PatternDetectionOrchestrator.kt` — `DETECTION_INTERVAL` removed; `onEntryCommitted` calls the identifier directly. `chooseMatchingPattern` reads identifier-produced patterns the same way as deterministic-produced ones.
- `docs/architecture-brief.md` — refresh the "pattern engine" section to reflect the model-primary, deterministic-fallback topology.

### Addendum (2026-05-20) — Storage SOT inverted (see ADR-017)

The "markdown is source of truth" cell in the §"Lifecycle Contract" table above is now historical. **ADR-017** inverts the storage SOT: ObjectBox is authoritative; markdown is generated at export only. The persist-before-scheduling invariant still holds.
