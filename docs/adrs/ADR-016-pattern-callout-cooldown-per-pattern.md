# ADR-016 — Pattern Callout Cooldown is Per-Pattern, Not Global

**Status:** Accepted
**Date:** 2026-05-19
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes:**
- ADR-003 §"Cooldown (callout-side only, global)" — the entire section, including the "Mechanics" bullets and the "Why global, not per-pattern" rationale
- ADR-003 Decision-summary clause "Cooldown is global, not per-pattern."
- ADR-003 Addendum (2026-05-11) — "callout cooldown is counted per committed entry, globally" — superseded by the per-pattern model below
- ADR-003 Addendum (2026-05-15) sub-clause "Pattern-callout cooldown stays as written" — the re-affirmation of the global rule is itself flipped here. The parallel `DeterministicObservationCooldown` (ADR-002) is keyed on `ObservationEvidence`, not `patternId`, and is unaffected by this ADR.
**Depends on:** ADR-003 (everything else — primitives, persistence, lifecycle states, mark-resolved semantics, detection cadence — stays intact)

---

## Context

`docs/stories/phase-3-memory-patterns.md` line 23 specifies pattern detection's threshold conditions as:

> "≥10 entries, ≥3 supporting entries per pattern, cooldown of 3 entries **since last pattern of the same shape**"

ADR-003 §Cooldown reads "same shape" as "any callout regardless of pattern" and rejects the alternative — pointing at the concern that per-pattern cooldown allows two different patterns to fire on the same entry simultaneously.

On further use that rejection is the wrong tradeoff:

1. **Global cooldown muzzles unrelated insights.** Two patterns that fire close together by chance — a Tuesday-meeting recurrence and a separate Sunday-night vocab drift — get one of them silenced for three captures even though they have nothing to do with each other. The user experience is "the engine seems quiet" rather than "the engine respected an anti-spam rule."
2. **The repeat-noise that needs suppressing is the *same callout*, not *any callout*.** A user typing four Tuesday-meeting entries in a week doesn't want the same Tuesday-meeting callout four times. They do want a different pattern that lands on entry #2 to be allowed to surface.
3. **ADR-003's "two callouts on the same entry" concern was a real one but solvable differently.** The pattern-selection step already collapses N matched patterns into one per entry (highest `supporting_entry_count`, tie-break by `lastSeenTimestamp`). Per-pattern cooldown does not re-open that — the selector still picks at most one pattern per entry. What changes is *which* pattern qualifies after the cooldown filter.

Phase 3's wording reflects the post-use intent. This ADR flips ADR-003's clause to match.

---

## Decision (summary)

Pattern-callout cooldown is **per pattern (identity-scoped by `patternId`)**, not global.

- After a callout for pattern `P` fires on entry `E`, the next 3 entries committed cannot fire pattern `P` again.
- Those 3 entries **can** fire any other pattern whose own cooldown is clear.
- At most one callout per entry — the selector still collapses simultaneous matches into one. Per-pattern cooldown filters the candidate set; the highest-supporting tiebreak from ADR-003 still picks the winner from what remains.

---

## Lifecycle Contract

| Trigger | What changes |
|---|---|
| Entry committed; selector finds N matching active patterns | Filter the N candidates by per-pattern cooldown — any pattern with `remainingSuppression > 0` drops out. Apply ADR-003's existing selector (highest `supporting_entry_count`, tiebreak `lastSeenTimestamp`) to what remains. |
| Selector picks pattern `P`; callout reserved on entry `E` | Reserve a slot **against `P`'s row** (not a singleton). One pending reservation per pattern, not one per app. |
| `confirmReservedCallout(entryId, patternId)` | Sets `lastCalloutEntryId` + `lastCalloutTimestamp` + `remainingSuppression = 3` on `P`'s row. Any other pattern's row is untouched. |
| `releaseReservedCallout(entryId, patternId)` | Clears the pending reservation on `P`'s row only. |
| Entry committed; no callout fired | Decrement `remainingSuppression` by 1 on **every** pattern's row whose counter > 0. The window is still counted per-entry committed, just per-pattern. |
| Pattern `P` has `remainingSuppression == 0` | `P` is callout-eligible again on the next match. |

---

## What This Supersedes

| Prior location | Old rule | New rule |
|---|---|---|
| ADR-003 Decision-summary "Cooldown is global, not per-pattern." | Singleton row, one tunable knob, one shared 3-entry counter | Per-pattern row, identity-scoped 3-entry counter, multiple patterns each track their own window. |
| ADR-003 §Cooldown "Persist `lastCalloutEntryId: Long?` and `lastCalloutTimestamp: Long?` as **singleton** settings (one row per app, not per pattern)." | Singleton entity | One row per `patternId`. `CalloutCooldownEntity` gains an indexed `patternId: String` column. The singleton accessor (`snapshot()`) is replaced by `snapshotFor(patternId)`. |
| ADR-003 §Cooldown "Why global, not per-pattern" rationale | Justified rejection of per-pattern cooldown | Rejection no longer holds — the two-callouts-on-the-same-entry concern is preserved by keeping the existing single-selection rule (one pattern per entry); the cooldown's job is "don't repeat *this* observation," not "shut up briefly." |
| ADR-003 Addendum (2026-05-11) "callout cooldown is counted per committed entry, globally" | Window counted globally on every committed entry | Window counted per pattern: every committed entry decrements the counter on *every* pattern that has `remainingSuppression > 0`. The per-entry counting model survives; the global vs per-pattern axis is the one that flipped. |

---

## What This Does NOT Change

- **Pattern primitives, signature shapes, content-addressable `patternId`** — all ADR-003 §"Pattern primitives", §"Persistence", `PatternSignature`, `@ConsistentCopyVisibility` seal stay intact.
- **Pattern lifecycle states** (`ACTIVE` / `SNOOZED` / `CLOSED` / `DROPPED`) and transitions per ADR-003 + 2026-05-13 Addendum.
- **Selector tie-break** when multiple patterns match the same entry — highest `supporting_entry_count`, then `lastSeenTimestamp`. Per-pattern cooldown filters the candidate set *before* the tiebreak runs.
- **Per-entry observation appending** — observations always fire regardless of callout cooldown. Suppressing a callout never suppresses the observation. (ADR-003 §Cooldown bullet 3 stays.)
- **`recordFired` / `confirmReservedCallout` / `releaseReservedCallout` lifecycle** — same reserve→confirm-or-release shape, same race-safety, just keyed by `patternId` instead of singleton. Signatures change in two minor ways: `confirmReservedCallout` returns the resolved `patternId` (or `null` for a stale / duplicate settle) so the caller can thread it through to `decrementAllActive(except = ...)` without re-scanning; `releaseReservedCallout` returns `Boolean` so the caller can distinguish a legitimate clear from a missing reservation.
- **`clearStalePendingReservation()` at process startup** — same purpose (recover from a process death between reserve and settle); operates on every pattern's row instead of the singleton.
- **Detection cadence** — that lives in ADR-003 §"Detection" + Phase 3 line 23's "end of session" framing. Out of scope for this ADR.

---

## Consequences

**Easier:**

- Unrelated patterns coexist. Two distinct insights firing close together no longer mute each other.
- "Don't repeat the same observation" is now what it sounds like — keyed to the observation's pattern, not a global timer.
- Phase 3 line 23 and the code agree, removing one of the doc-vs-code drifts the audit found.

**Harder:**

- `CalloutCooldownEntity` becomes a per-pattern table instead of a singleton — ObjectBox schema bump.
- `CalloutCooldownStore` API changes: every method that took an `entryId` now also takes a `patternId`. `BackgroundExtractionSaveFlow` + `PatternDetectionOrchestrator` callers thread the id through.
- The "every committed entry decrements every pattern's counter" pass needs to be cheap. At v1 pattern counts (≤ tens) it's a trivial loop; if pattern counts grow to thousands the loop wants an index.
- Reservation order in the orchestrator flips: previously *reserve → choose pattern*; now *choose pattern → reserve against that pattern*. The selector must be a pure function (no side effects) so it can be called before the reservation lands.

**Revisit when:**

- Pattern counts cross ~100 and the per-entry decrement loop shows up in latency traces → add an index on `remainingSuppression > 0` or batch the decrement.
- Real-use data shows the "two patterns fire on the same entry" noise ADR-003 worried about actually happens at scale beyond what the single-selection rule catches → reconsider the selector, not the cooldown.

---

## Implementation references

- `core-storage/.../CalloutCooldownEntity.kt` carries an `@Index var patternId: String` column; an `init { require(patternId.isNotBlank()) }` fence catches default-constructed rows that bypass `snapshotFor`.
- `core-storage/.../CalloutCooldownStore.kt` exposes per-pattern API: `snapshotFor(patternId)` (lazy create-in-tx), `isCalloutPermitted(patternId)` (read-only; no row creation), `tryReserveCallout(entryId, patternId)`, `confirmReservedCallout(entryId, ...)` returns `String?`, `releaseReservedCallout(entryId)` returns `Boolean`, `decrementAllActive(exceptPatternId)` per-entry sweep, `clearStalePendingReservation()` sweeps every row.
- `app/.../patterns/PatternDetectionOrchestrator.kt` runs match → reserve. `chooseMatchingPattern` filters by `isCalloutPermitted`; `settleReservedCallout(entry, fired)` recovers the matched patternId from `confirmReservedCallout`'s return value and threads it into `decrementAllActive(except = …)`. Stale settles surface via `Log.e`.
- `app/.../save/BackgroundExtractionSaveFlow.kt` is unchanged in shape — it still calls `orchestrator.onEntryCommitted` + `orchestrator.settleReservedCallout(entry, fired)`. The orchestrator owns the patternId now.
- ObjectBox schema picks up the new `patternId` column on `CalloutCooldownEntity` (model.json regenerates on build).
- Tests cover: independent per-pattern counters, three-decrement-to-eligibility, suppression-bypass branch, the `except = firedPatternId` invariant, `clearStalePendingReservation` sweep, `decrementAllActive` idempotence at zero, persistence across reopen.
