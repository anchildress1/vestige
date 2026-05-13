# Spec — Pattern Action Buttons

**Feature:** Pattern lifecycle actions — Skip and Drop (user), model-detected Close (v1.5)
**Status:** Draft — 2026-05-13
**Phase:** 4 (UX Surface)
**Author:** Ashley (sole owner). AI implementors read this as authoritative.
**References:** `ux-copy.md` §Pattern List, §Pattern Detail, §System Messages, §Locked UX Decisions; `backlog.md` §`pattern-auto-close`; `poc/screens-patterns.jsx`; `adrs/ADR-011-design-language-scoreboard-pivot.md`

---

## Problem Statement

The pattern list has no user-facing actions that move a pattern out of the active state. Patterns accumulate indefinitely once detected. The prior action model (Dismiss / Snooze 7 days / Mark resolved) had two problems: Dismiss and Mark resolved were functionally identical — both expressed "I am done with this" — and user-declared resolution is the wrong model. A pattern closing should be earned by behavioral evidence (it stopped appearing in entries), not self-reported by the user. That mirrors the core product philosophy: Vestige observes, it doesn't validate.

In v1, users need exactly two controls: Skip (this is real but I don't want to look at it right now — temporary) and Drop (this is noise, remove it from my view — permanent). Closed is a state the model earns for a pattern in v1.5 when it has not appeared in entries for long enough.

---

## Goals

1. Users can remove a pattern from the active view with two distinct semantic gestures (Skip vs. Drop) — both complete in one tap from the overflow menu or action row.
2. Skipped patterns surface again after 7 days without any user action — no re-add required.
3. Dropped patterns are accessible in a DROPPED section (not deleted) — the record exists, the claim doesn't.
4. Pattern state transitions are undoable within the snackbar window (standard Material 3 undo timing, ~4s).
5. No third user-facing "I resolved this" action exists. The spec eliminates the false affordance that closure is user-declared.

---

## Non-Goals

1. **Model-detected pattern close (v1.5).** Automatic lifecycle transition from active → Closed when a pattern hasn't appeared in N days is deferred. See `backlog.md` §`pattern-auto-close`. This spec does not wire a staleness check — the CLOSED · DONE section will be empty in v1.
2. **Custom skip durations.** Skip is 7 days, fixed. No picker, no settings row. See `ux-copy.md` §Locked UX Decisions.
3. **Permanent deletion.** Drop moves a pattern to DROPPED state — it does not delete the ObjectBox record. Purge-on-wipe (DestructiveScreen) handles mass deletion.
4. **Cross-pattern actions.** No multi-select, no "skip all," no batch drop. Single-pattern scope only for v1.
5. **Re-activation from Dropped.** Dropped is a terminal user intent in v1. If the pattern resurfaces in new entries, the PatternEngine re-detects it and creates a new pattern card — it does not un-drop the old one.

---

## User Stories

**As** Vestige's user, **I want** to skip a pattern **so that** it leaves my active list and reappears in 7 days without me having to remember to bring it back.

**As** Vestige's user, **I want** to drop a pattern **so that** I can signal it's noise and stop seeing it in my primary view — without permanently deleting the detection record.

**As** Vestige's user, **I want** both actions available from the pattern card overflow menu **so that** I don't have to open the full detail screen to act on a pattern.

**As** Vestige's user, **I want** both actions available at the bottom of the Pattern Detail screen **so that** I can act on a pattern after reviewing the evidence.

**As** Vestige's user, **I want** an undo affordance immediately after taking either action **so that** a mis-tap doesn't require hunting through SKIPPED or DROPPED to undo.

**As** Vestige's user, **I want** skipped patterns to live under a SKIPPED · ON HOLD section **so that** I can confirm they exist and haven't been deleted.

**As** Vestige's user, **I want** dropped patterns to live under a DROPPED section **so that** there's an audit trail of what I've called noise.

---

## Requirements

### P0 — Must-Have

**P0.1 — Skip action (overflow + detail action row)**

Users can Skip a pattern from two surfaces:
- Pattern card overflow menu (three-dot or equivalent)
- Pattern Detail bottom action row

Behavior: transitions pattern state from `ACTIVE` → `SKIPPED`, records skip timestamp, schedules wake-up at `skip_ts + 7 days`.

Acceptance criteria:
- Given an ACTIVE pattern card, when user opens overflow and taps Skip, the card disappears from ACTIVE and appears in SKIPPED · ON HOLD.
- Given a pattern in Pattern Detail with action row visible, when user taps Skip, the screen pops back to Pattern List and the card appears under SKIPPED · ON HOLD.
- Skip timestamp is written to the pattern's ObjectBox record (`PatternEntity.skippedUntil: Long?`).
- Snackbar fires: `Skipped.` with Undo (standard Material undo timing, ~4s).
- Tapping Undo reverses the state to ACTIVE. No skip timestamp persists.

**P0.2 — Drop action (overflow + detail action row)**

Users can Drop a pattern from two surfaces:
- Pattern card overflow menu
- Pattern Detail bottom action row

Behavior: transitions pattern state from `ACTIVE` → `DROPPED`. Does not delete the `PatternEntity` record.

Acceptance criteria:
- Given an ACTIVE pattern card, when user opens overflow and taps Drop, the card disappears from ACTIVE and appears in DROPPED.
- Given a pattern in Pattern Detail, when user taps Drop, the screen pops back to Pattern List and the card appears under DROPPED.
- ObjectBox record is NOT deleted. `PatternEntity.state` is set to `DROPPED`.
- Snackbar fires: `Dropped.` with Undo (~4s window).
- Tapping Undo reverses the state to ACTIVE.

**P0.3 — Section structure**

Pattern List displays four sections, each only rendered when non-empty:

| Section header | Condition |
|---|---|
| `ACTIVE` | state = ACTIVE, skippedUntil = null or expired |
| `SKIPPED · ON HOLD` | state = SKIPPED, skippedUntil > now |
| `CLOSED · DONE` | state = CLOSED (model-detected — empty in v1; section omitted if empty) |
| `DROPPED` | state = DROPPED |

Section headers render in JetBrains Mono, uppercase, `EyebrowE` primitive per Scoreboard tokens.

Acceptance criteria:
- An empty section is not rendered (no ghost header with no cards beneath it).
- CLOSED · DONE section is absent in v1 (no patterns reach CLOSED state without `pattern-auto-close` shipping).
- Section order is fixed: ACTIVE → SKIPPED → CLOSED → DROPPED.

**P0.4 — Skip wake-up**

When `skippedUntil` elapses, the pattern transitions from `SKIPPED` → `ACTIVE` automatically.

Acceptance criteria:
- A cold start after the skip window has elapsed returns the pattern to ACTIVE.
- The pattern does not appear in both sections simultaneously.
- No user action is required to bring a skipped pattern back.
- Implementation: evaluated on app start / resume via `AppContainer` or the existing cold-start sweep — not a WorkManager job in v1 (pattern-auto-close deferred; this is a simpler date check on load).

**P0.5 — No "Mark resolved" / "Dismiss" / "Snooze" actions**

None of these labels appear anywhere in the UI. No third action exists in the overflow or action row. No undo copy references resolution.

Acceptance criteria:
- Full-text search of `:app` source for "dismiss", "Dismiss", "resolve", "Resolve", "Mark resolved", "Snooze" returns zero hits in any user-visible string resource or composable.

---

### P1 — Nice-to-Have

**P1.1 — Filter chips**

Small filter chips above the pattern list: `All · Active · Skipped · Closed · Dropped`. Tapping a chip collapses all other sections. Default: All.

Deferred until Pattern List, Pattern Detail, and core actions are stable. Do not implement before P0 ships.

**P1.2 — Empty state for active tab when all skipped/dropped**

If ACTIVE is empty but SKIPPED or DROPPED contain patterns:
- Eyebrow: `ACTIVE`
- Header: `Nothing active.`
- Sub: `{N} skipped · {N} dropped` (live counts from ObjectBox)

---

### P2 — Future Considerations

**P2.1 — Model-detected close (`pattern-auto-close`).**
See `backlog.md` §`pattern-auto-close`. PatternEngine runs a staleness check post-extraction; patterns inactive for 30 days transition to CLOSED. This is the primary mechanism for patterns leaving the active list without user action. Design the ObjectBox schema and state enum to include `CLOSED` even in v1 so the v1.5 transition is a backfill + check, not a migration.

**P2.2 — Re-activation from Dropped.**
If the PatternEngine re-detects a dropped pattern in new entries, surface it as a new card rather than un-dropping the old one. In v2, consider a "This looks like a pattern you dropped" banner linking the two.

**P2.3 — Skip duration picker.**
7 days is fixed in v1. If usage data shows 7 days is too long (pattern still live when it wakes) or too short (users re-skip immediately), add 3d / 7d / 30d options in a bottom sheet before surfacing the pattern again.

---

## UX Copy

All copy is locked in `ux-copy.md`. Reproduced here for implementor convenience.

**Overflow menu labels:**
- `Skip`
- `Drop`

**Action row (Pattern Detail):**
- `Skip`
- `Drop`

**Pattern Detail — Closed state banner (v1.5, read-only, no action row):**
> Closed {date}. No new entries matched in {N} days.

**Snackbars:**
- `Skipped.` *(with Undo)*
- `Dropped.` *(with Undo)*
- Pattern closed (model): *(no snackbar — silent, visible on next list load)*

**Section headers (JetBrains Mono, EyebrowE primitive):**
- `ACTIVE`
- `SKIPPED · ON HOLD`
- `CLOSED · DONE`
- `DROPPED`

---

## Data Model

`PatternEntity` needs:

```kotlin
// Existing field — extend the enum
enum class PatternState { ACTIVE, SKIPPED, CLOSED, DROPPED }

// Existing or add
var state: PatternState = PatternState.ACTIVE

// New field for skip wake-up
var skippedUntil: Long? = null  // epoch ms; null when not skipped
```

State transition rules:

| From | Action | To | skippedUntil |
|---|---|---|---|
| ACTIVE | Skip | SKIPPED | now + 7 days |
| ACTIVE | Drop | DROPPED | null |
| SKIPPED | Undo (skip) | ACTIVE | null |
| DROPPED | Undo (drop) | ACTIVE | null |
| SKIPPED | Wake-up check on load | ACTIVE | null (cleared) |
| ACTIVE | Model detects stale (v1.5) | CLOSED | null |

**ObjectBox note:** Do not delete `PatternEntity` on Drop. Hard-deletes lose the audit trail and break the v1.5 re-detection comparison.

---

## Visual / Interaction Design

Scoreboard system applies. Key decisions:

- **Overflow menu trigger:** standard three-dot icon on pattern card trailing edge. Tooltip: `More actions` (per `ux-copy.md` §Tooltips & Helpers).
- **Action row on Pattern Detail:** two equal-weight buttons at screen bottom. Neither is destructive-red — Drop is a demotion, not a deletion. Both use `Pill` primitive in `dim` / `faint` weight.
- **DROPPED section cards:** reduced opacity or `dim` tint to signal de-prioritization. Do not hide — the record should feel archived, not gone.
- **SKIPPED cards:** `SKIPPED · ON HOLD` eyebrow on each card with wake-up date: `Back {date}`.
- **No confirmation dialog on Drop.** The Undo snackbar is the safety net. A confirmation dialog on a non-destructive action is patronizing.

---

## Success Metrics

**Leading (days to weeks):**
- Pattern cards acted on per session (target: >0 in first week of use — baseline is currently zero because no actions exist)
- Skip vs. Drop ratio (signal: if Drop >> Skip, pattern detection quality may need tuning)
- Undo rate on Drop (target: <15% — high undo rate suggests accidental activation or label confusion)

**Lagging (weeks to months):**
- Active list length over time (target: does not grow unbounded — Skip/Drop keep it actionable)
- Re-skip rate (proxy for skip window being too short — should be <25% of skipped patterns)

---

## Open Questions

| # | Question | Owner | Blocking? |
|---|---|---|---|
| 1 | Does the PatternEngine v1 schema already have a `state` field or does it use a separate status table? Confirm ObjectBox entity before wiring state transitions. | Engineering | Yes — before P0.1 |
| 2 | Cold-start skip-wake check: does the existing cold-start sweep in `AppContainer` run before the Pattern List composable draws, or is there a race? If race, skipped patterns might flash in ACTIVE on first frame. | Engineering | Yes — before P0.4 |
| 3 | Should DROPPED patterns be excluded from EmbeddingGemma re-evaluation and pattern re-detection? (i.e., if a dropped pattern's name matches a new cluster, do we surface a new card or skip it?) Decision needed before v1.5 `pattern-auto-close` work starts. | Ashley | No — v1.5 gate |

---

## Timeline Considerations

- This spec is Phase 4 scope. Pattern List and Pattern Detail screens (Stories 4.x) are the implementation surface.
- P0 actions must ship before the demo app is considered complete — without them, the pattern list accumulates indefinitely on the reference device during the 5-min walkthrough.
- P0.4 (skip wake-up) can be a simple cold-start date check in v1. No WorkManager job required.
- P1 filter chips are polish — defer until P0 is stable and tested on device.
- P2 items do not affect v1 schema if `PatternState.CLOSED` is included in the enum from day one (no migration needed later).
