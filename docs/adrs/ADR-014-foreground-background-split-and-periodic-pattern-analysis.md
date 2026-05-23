# ADR-014 — Foreground/Background Split with Periodic Pattern Analysis

**Status:** Accepted
**Date:** 2026-05-17
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes:** ADR-002 §"Two-tier processing" (locks the foreground/background boundary as the only synchronous touchpoint); ADR-003 §"Pattern detection trigger" (replaces threshold-driven trigger with periodic cadence)
**Depends on:** ADR-001 §Q3 (retry-based recovery), ADR-008 §Correction (on-device single-session ceiling), `concept-locked.md` §"Multi-lens extraction architecture"
**Validated by:** on-device probe 2026-05-17 (S24 Ultra, GPU, E4B): single-lens parse at 14.7s, concurrent sessions return `FAILED_PRECONDITION` after ~95ms — confirms ADR-008's SDK-forces-sequential runtime ceiling

---

## Context

ADR-002 established two-tier processing: foreground (transcription + follow-up in one structured response) and background (3-lens × 5-surface extraction). ADR-003 wired pattern detection on a threshold trigger: ≥10 entries with ≥3 supporting per pattern.

The ADR-008 correction cycle (mis-scoped `clone()` probe → bytecode re-probe finding `Engine.createSession()` / `createConversation()` → on-device measurement returning `FAILED_PRECONDITION` on concurrent sessions) confirmed sequential dispatch as a hard runtime ceiling on `litertlm-android:0.11.0`. The convergence resolver's 2-of-3 fallback would have masked a one-lens-only ship as `outcome=SUCCESS` — only the on-device probe caught it.

Two product-shape consequences fell out of that:

1. **Sequential 3-lens runtime is too costly for foreground.** A single lens runs ~15s on GPU/E4B per the probe; three sequential lenses extrapolate to ~45s per entry. The foreground prompt's `{transcription, follow_up}` shape is the conversation the user sees. The user can't be sitting through extraction work in the synchronous path.
2. **Threshold-triggered pattern detection goes stale.** ADR-003's "≥10 entries with ≥3 supporting" fires once; users who keep capturing past the threshold have the pattern engine waiting for the next on-demand trip. Patterns are emergent — the detection cadence should match.

This ADR locks the v1 inference lifecycle that came out of those two findings.

---

## Decision (summary)

The v1 lifecycle has three call types:

1. **Foreground (synchronous, user waits):** one model call returning `{transcription, follow_up}`. Persona-flavored follow-up per ADR-002 §"Foreground prompt". The user moves on after this call returns.
2. **Background analytics (asynchronous, immediate):** 3 lens calls × 5 surfaces per ADR-002, scheduled the moment the foreground call resolves. Sequential dispatch per ADR-008's runtime ceiling. Per-field convergence populates the entry's extracted fields when complete.
3. **Background pattern analysis (asynchronous, periodic):** every 5 entries written, a pattern pass runs in the background. Patterns view updates atomically as results land.

The user only ever waits for step 1. Steps 2 and 3 deliver the same eventual information without blocking capture.

---

## Lifecycle Contract

| Trigger | Type | What runs | User-blocking? | Failure handling |
|---|---|---|---|---|
| User taps `I'm done.` | Foreground | Foreground call (transcription + persona-flavored follow-up) | Yes — `Reading the entry.` state until response | Surface error in capture; entry not persisted unless transcription returns |
| Foreground returns | Persist + enqueue | `EntryStore.persist(entry_text, follow_up, persona)` then enqueue background lens worker | No | Persist succeeds before background scheduled; markdown is source of truth |
| Background lens N completes | Background analytics | Per-lens parse + accumulate `LensResult` | No | Per-lens parse-fail → `no opinion`; resolver runs on survivors; ≥2 fail → all-ambiguous (ADR-002 contract holds) |
| All 3 lenses complete | Background analytics | `ConvergenceResolver.resolve` runs; writes consensus / candidate / ambiguous fields per ADR-002 | No | Per ADR-001 §Q3 retry-based recovery |
| `EntryStore.write` count % 5 == 0 | Background pattern | `PatternDetector` pass against the rolling 90-day window per ADR-003 | No | Patterns view updates atomically; partial completion leaves prior pattern state intact; only one pattern pass in flight per process |

---

## What This Supersedes

| Prior location | Old rule | New rule |
|---|---|---|
| ADR-002 §"Two-tier processing" | Foreground returns transcription + follow-up; background runs 3-lens fill-out over 30–90s | Shape unchanged. Locked as the only user-blocking call in v1. All other work is background, including pattern analysis. |
| ADR-003 §"Pattern detection trigger" (threshold-based) | Pattern callouts fire when ≥10 entries exist AND a pattern with ≥3 supporting entries is detected | Pattern *analysis* runs every 5 entries written, in the background. The ≥3-supporting predicate per pattern still gates *which* patterns surface; the *trigger* is now periodic, not threshold-driven. |
| ADR-008 §Correction premise | GPU serializes at the hardware command queue; real win is Kotlin-layer mutex elimination; net wall-clock improvement unmeasured | Premise-false on 0.11.0 — the SDK doesn't permit concurrent live sessions in the first place. There is nothing to serialize at the GPU layer. Sequential dispatch is the runtime ceiling, and the architectural workaround is the split documented here, not parallel dispatch. |

---

## What This Does NOT Change

- ADR-002's convergence resolver contract (deterministic Kotlin, not a model call).
- ADR-002's per-field confidence states (`consensus` / `candidate` / `ambiguous` / `consensus_with_conflict`).
- ADR-002's foreground prompt contract (single-turn-per-capture, persona module, structured `{transcription, follow_up}` output).
- ADR-001 §Q3's retry-based recovery contract.
- AGENTS.md guardrail 13 (single inference runtime).
- The pattern-engine output contract from ADR-003 (counts, dates, snippets sourced from supporting entries; no feelings/motivation interpretation).

---

## Consequences

**Easier:**

- The user only waits for the foreground call. Background work is invisible.
- Patterns view stays fresh as data accumulates — no stale-after-threshold trap.
- The architectural workaround respects the SDK ceiling instead of fighting it. No more PRs trying to ship parallel that the on-device probe has to catch.

**Harder:**

- `WorkManager` (or equivalent) needs two distinct work types: per-entry analytics (immediate) and per-5-entries pattern (modulo-triggered).
- Modulo trigger off `EntryStore.write` requires either a counter in `SessionState` or a query against ObjectBox on every write.
- Pattern worker needs single-in-flight enforcement — if entries land faster than analysis completes, the second pass waits.
- The "5 entries" cadence is a default, not measured. Backlog row added for v1.5 tuning.

**Revisit when:**

- LiteRT-LM ships a runtime that honors concurrent contexts → ADR-008's adoption gate fires; background analytics can parallelize. This ADR refines, not supersedes.
- Pattern cadence measurements show 5 is wrong → adjust the modulo; this ADR stays. The cadence is a tunable, not a contract.
- Pattern analysis itself starts hitting noticeable battery cost → batch further or defer to charging-only execution.

---

## Backlog Addition

- `pattern-cadence-tuning` — v1.5, patterns. v1 ships ADR-014's every-5-entries default; optimal cadence is a usage-data question post-v1. Unblock: usage telemetry shows 5 is too frequent (battery cost) or too sparse (stale pattern view).

---

### Addendum (2026-05-18) — Persist on call-1, navigate to the entry, follow-up patched in

Refines the foreground/background boundary; does not supersede it.

**What changed:**

- The entry now persists on the **call-1 transcription** (`EntryStore.createPendingEntry`), not the inference terminal event. The earliest a non-blank transcription exists is when call-1 lands; persisting there gives the UI a real entry id to navigate to.
- Post-capture UI is no longer an in-Capture "Reading the entry" / Reviewing pair. The user is taken straight to the entry's detail screen, which renders its own extracting → resolved states off `extractionStatus`. The synchronous wait the user experiences is unchanged in length — it just happens on the entry surface instead of a capture-local pane.
- ADR-002's write-once `follow_up` is **relaxed for the in-flight window only**: `EntryStore.attachFollowUp(entryId, text)` lands call-2's persona follow-up on a `PENDING`/`RUNNING` row and rewrites markdown front-matter. A follow-up arriving after the row reached a terminal status is dropped — extraction beat call-2; rewriting a `COMPLETED` row would race `completeEntry`. This is a bounded, in-flight-only mutation, not a general edit API.

**What this does NOT change:**

- The single synchronous touchpoint is still the foreground call; background 3-lens extraction stays invisible and unchanged.
- `completeEntry` / `failEntry` contracts, the convergence resolver, and the pattern cadence are untouched.
- `follow_up` remains immutable once an entry is terminal.

### Addendum (2026-05-19) — Pattern cadence tightened and temporal wording added

Refines the background-pattern lane; does not supersede the foreground/background split.

**What changed:**

- The pattern pass runs every **3 completed entries** in v1, not every 5. Three keeps temporal
  recurrence visible as soon as a sourced pattern exists; five is too slow for the demo loop.
- Temporal-relative patterns now get one best-effort background Gemma wording pass after the
  deterministic detector has already selected the supporting entries. The model writes the
  persisted title and latest callout text only; it does not decide whether the pattern exists.
- Callout selection prefers temporal-relative active patterns over simpler deterministic matches
  before falling back to supporting-entry count and recency.

**What this does NOT change:**

- The user still waits only for the foreground call.
- LiteRT-LM still permits only one Gemma call at a time; this is queued background work, not
  parallel inference.
- Deterministic signatures, supporting entries, lifecycle states, and cooldown behavior remain
  owned by ADR-003.
- The model's role in pattern analysis (title + callout, no existence decision) is documented
  in ADR-015, which supersedes the implicit scope boundary in this addendum.

### Addendum (2026-05-20) — Storage SOT inverted (see ADR-017)

The "markdown is source of truth" cell in the §"Lifecycle Contract" table above is now historical. **ADR-017** inverts the storage SOT: ObjectBox is authoritative; markdown is generated at export only. The persist-before-background-scheduled invariant still holds — only the SOT direction changes.

### Addendum (2026-05-20) — Foreground-priority background queue

This is an addendum because ADR-014's foreground/background split still stands: the foreground call
is the only user-blocking path, and extraction/pattern work remains detached. The implementation
choice changed inside that split: foreground now owns the inference slot by cancelling active
detached extraction, leaving the row non-terminal, and requeuing the work. Cancelled and queued
background extraction drains in FIFO order after the foreground call releases the slot.

Background extraction model calls use the streaming text path so coroutine cancellation can close
the active conversation during unwind. This is not KV-cache suspend/resume; it is discard-and-rerun
at the background unit-of-work boundary so the user does not wait behind detached extraction.
