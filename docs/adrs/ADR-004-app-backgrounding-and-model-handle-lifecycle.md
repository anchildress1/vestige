# ADR-004 — App Backgrounding & Model-Handle Lifecycle

**Status:** Proposed
**Date:** 2026-05-08
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative once accepted.
**Depends on:** `adrs/ADR-001-stack-and-build-infra.md` §Q3 (retry-based recovery) + §Q7 (network enforcement), `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Two-Tier Processing Contract", `architecture-brief.md` §"AppContainer Ownership"

---

## Context

ADR-001 Q3 specifies retry-based recovery for the background 3-lens extraction: the entry row commits with `extraction_status=PENDING` before the user gets the follow-up; if the background pass is interrupted, a cold-start sweep re-runs it. Q3 explicitly does **not** specify what happens to the loaded `ModelHandle` (the LiteRT-LM engine + KV cache) when the user backgrounds the app for 90 seconds.

The dominant real-world case for an ADHD-coded capture app is exactly this: user records, sees the follow-up, hits home, comes back two minutes later. Whether the background extraction completes during those two minutes or gets killed and re-runs on next open is a load-bearing UX and battery decision, and it ripples back into:

- `ModelHandle` lifecycle in `AppContainer`
- AndroidManifest service declarations
- Onboarding permission flow (`POST_NOTIFICATIONS` on Android 13+)
- The frequency of "Resuming reading on N entries." cold-start UI from ADR-001 Q3
- Demo behavior — whether a 5-minute walkthrough that backgrounds the app mid-extraction completes cleanly or trips the resume path

Phase 1 scaffold has to wire lifecycle observers and decide whether a `Service` declaration ships in v1. Picking the wrong default forces a refactor of `AppContainer` plus the manifest.

---

## Decision (summary)

Adopt **conditional foreground service** as the v1 lifecycle. The app runs as a normal-priority process by default. The instant a background extraction transitions to `extraction_status=RUNNING`, the app promotes itself to a foreground service with a transient notification. The instant all in-flight extractions reach a terminal status (`COMPLETED` / `TIMED_OUT` / `FAILED`) **plus a 30-second keep-alive window**, the app demotes back to a normal process and dismisses the notification.

Document **always-on foreground service** as the explicit time-pressure fallback. If Phase 4's UI work shows the state-machine implementation eating time we don't have, switch to always-on at the end of Phase 4 day 1. The fallback decision is binary and recorded inline in this ADR when made.

---

## Options Considered

### Option 1 — Always-on foreground service (FALLBACK)

App promotes to foreground service at process start; demotes only at process exit.

| Dimension | Assessment |
|---|---|
| Demo behavior | Strong — extraction always completes, no resume path during demo |
| Battery | Worst — model handle resident across long idle backgrounding |
| Brand alignment | Weak — persistent notification reads as generic Android chrome |
| Implementation cost | Lowest — promote at app start, demote at app exit, no state machine |
| Permission ask | `POST_NOTIFICATIONS` runtime permission on Android 13+ |

**Pros:** Simplest possible implementation. Demo loop bulletproof.
**Cons:** Persistent notification fights the restrained brand voice. Material battery cost over a real day of use. The notification becomes ambient cruft, not informative signal.

### Option 2 — Process-scoped, no foreground service (REJECTED)

Model handle lives in process memory. OS reclaims at will. Recovery via ADR-001 Q3 cold-start sweep.

| Dimension | Assessment |
|---|---|
| Demo behavior | Weak — extraction frequently interrupted; "Resuming reading" surfaces during walkthrough |
| Battery | Best — no foreground priority, model dies when not in use |
| Brand alignment | Neutral — no notification at all |
| Implementation cost | Lowest if we skip the state machine entirely |
| Permission ask | None |

**Pros:** Cleanest battery story. No new permissions.
**Cons:** Demo recording risks "Resuming reading on 3 entries" mid-walkthrough — exactly the failure mode we built ADR-001 Q3 to *recover from*, not *normalize*. Loses the ambient "local model active" signal.

### Option 3 — Release on `onPause`, reload on `onResume` (REJECTED)

`ModelHandle` released when Activity pauses, reloaded on resume.

| Dimension | Assessment |
|---|---|
| Demo behavior | Worst — extraction killed AND 1-3s reload latency on every session start |
| Battery | OK — model lives only when foreground |
| Brand alignment | Neutral — no notification |
| Implementation cost | Medium — explicit lifecycle observers |
| Permission ask | None |

**Pros:** None compelling.
**Cons:** Worst of both worlds. Kills extraction *and* adds reload latency. Don't pick.

### Option 4 — Conditional foreground service (CHOSEN)

Process-priority by default. Promotes to foreground service only while at least one extraction has `extraction_status=RUNNING`. Demotes after a 30-second keep-alive window post-completion.

| Dimension | Assessment |
|---|---|
| Demo behavior | Strong — extraction always completes once started |
| Battery | Strong — model is foreground-priority only during active work |
| Brand alignment | Strong — transient notification with on-brand text matches "Reading the entry" pattern from `ux-copy.md` |
| Implementation cost | Medium — state machine + keep-alive timer |
| Permission ask | `POST_NOTIFICATIONS` runtime permission on Android 13+ |

**Pros:** Best demo + brand + battery balance. Notification doubles as ambient privacy signal ("local model is currently working") that disappears when no work is pending.
**Cons:** More implementation surface than Option 1. Notification text and lifecycle have to be precise or the flicker reads as instability rather than care.

---

## Trade-off Analysis

The dominant trade-off is **brand alignment vs. implementation simplicity**. Option 1 is the simplest correct answer; Option 4 spends ~half a day extra on a state machine to earn brand-aligned transient notifications and a cleaner battery story. For a 17-day build with a brand voice that is the differentiator, the state-machine cost pays back.

The secondary trade-off is **demo reliability**. Options 1 and 4 both deliver. Option 2 doesn't — the cold-start "Resuming reading" path is a recovery mechanism, not a feature, and surfacing it during a 5-min walkthrough reads as instability.

Option 3 is dominated by every other option. Not picked.

The fallback to Option 1 exists because the state-machine work in Option 4 is bounded but not free. If Phase 4's UX surface pass eats more time than budgeted (likely if it slips past day 13 of 17), the fallback is the same notification framing minus the demote logic — a one-line change at the promotion site.

---

## State Machine (Option 4)

```
Process lifecycle states:
  NORMAL          — default; no foreground service running
  PROMOTING       — extraction transitioned to RUNNING; service start in flight
  FOREGROUND      — service running, notification visible
  KEEP_ALIVE      — all extractions terminal; 30s timer running before demote
  DEMOTING        — keep-alive expired or new extraction → re-evaluating

Transitions:
  NORMAL          → PROMOTING        on first entry → extraction_status=RUNNING
  PROMOTING       → FOREGROUND       on Service.startForeground() success
  FOREGROUND      → FOREGROUND       on next entry → RUNNING (no churn; reset keep-alive on completion)
  FOREGROUND      → KEEP_ALIVE       on last in-flight extraction → terminal status
  KEEP_ALIVE      → FOREGROUND       on new entry → RUNNING during keep-alive window (no notification update)
  KEEP_ALIVE      → DEMOTING         on 30s timer expiry
  DEMOTING        → NORMAL           on Service.stopForeground() + Service.stopSelf()
```

**Keep-alive rationale.** ADHD-coded capture frequently comes in bursts — record one entry, immediately record another about a related thought. Without keep-alive, the notification flickers off and on within seconds. The 30s window covers the typical inter-capture gap and reduces visible churn during a multi-entry session.

**Concurrency.** Per ADR-002, lens calls run sequentially against the single `ModelHandle`. Multiple captures while one extraction is running queue at the inference layer; from the lifecycle perspective, "in-flight extractions count" can exceed 1 even though only one is actively executing. The promote/demote logic gates on count, not on "current handle in use."

**Crash recovery.** If the OS kills the service mid-extraction (rare under `START_STICKY` foreground service + active notification), recovery follows the existing ADR-001 Q3 cold-start sweep path. No new crash-recovery semantics introduced.

---

## Notification Contract

**Text:** `Reading the entry.` (matches `ux-copy.md` §"Loading States" mid-session inference copy — single source of truth for this phrase). When multiple entries are queued, the text remains singular; we do not surface queue depth ("Reading 3 entries") because the user already saw three follow-ups in the in-app transcript and does not need a redundant counter in the status bar.

**Icon:** App icon (partial footprint dissolving into mist, per `design-guidelines.md` §"App icon"). No separate notification glyph.

**Channel:** Single notification channel, `vestige.local_processing`, importance level **LOW** (visible in shade, no sound, no heads-up).

**Tap target:** Tapping the notification opens the app to the History screen scrolled to the most recent entry whose extraction is in flight. Not the capture screen — the user's mental model when tapping is "what's that thing doing?" not "I want to record."

**Forbidden notification texts:** anything that would violate `ux-copy.md` §"Things to NEVER Write" — no exclamation points, no "processing your data," no "great job!" energy, nothing that reads as a wellness app.

---

## Permission Flow (Onboarding)

Insert one screen between mic permission (current onboarding screen 3) and typed fallback (current screen 4):

**Screen 3.5 — Notification permission**

| | |
|---|---|
| Header | `One status notification.` |
| Body | `Vestige briefly shows a status when it's working locally on an entry. That's the only notification it will ever send. It disappears when work is done.` |
| Primary action | `Allow notifications` |
| Secondary action | `Skip — work runs in foreground only` |

**If user denies:** the app still works; extractions only complete while the app is in the foreground. The cold-start "Resuming reading on N entries" path runs more often. No degraded copy or warning.

**Cross-doc updates required** (out of scope for this ADR but tracked in Action Items): `ux-copy.md` onboarding section, `concept-locked.md` onboarding flow, `PRD.md` §P0 UX shell.

---

## Fallback Trigger (Option 4 → Option 1)

**Trigger condition:** End of Phase 4 day 1, if any of:
1. The state machine has more than one open bug in the promote/demote logic that's blocked Phase-4 progress on UI work.
2. The keep-alive timer is racing with extraction-start in a way that requires non-trivial debug.
3. A second engineer would be needed to ship Option 4 inside the Phase 4 budget.

**Fallback action:**
1. Replace the conditional state machine with `startForeground()` in `Application.onCreate()`.
2. Replace `stopForeground()` calls with no-ops; the service runs for the process lifetime.
3. Update notification text to `Local model active.` (still on-brand but framed as ambient state rather than active work, since it will be visible during idle).
4. Update Screen 3.5 onboarding copy: `Vestige shows a status notification while the app is open.` Keep the framing brand-aligned.
5. Record the fallback decision inline in this ADR with date and reason.

**Trigger recorded:** *(blank until invoked)*

---

## Consequences

**Easier:**
- ADR-001 Q3's cold-start sweep becomes a recovery path for genuine OS kills only, not the normal case. The "Resuming reading on N entries" UI surfaces rarely.
- Demo recording (Phase 6) doesn't need to schedule around backgrounding behavior.
- The privacy story gains an ambient signal that pairs with the explicit `tcpdump` proof clip from ADR-001 Q7.

**Harder:**
- `AppContainer` adds a `LifecycleService` (or equivalent) singleton plus the state-machine code. Bounded but not free.
- Onboarding gains a screen. Still under the 8-screen ceiling but adds one more decision point.
- `POST_NOTIFICATIONS` runtime permission becomes a P0 ask on Android 13+.
- Notification text `Reading the entry.` is now load-bearing — same string in two places (`ux-copy.md` and the Service code). Drift risk.

**Revisit when:**
- Battery telemetry from real use shows the keep-alive window is too short or too long.
- A v1.5 multi-extraction-in-parallel design lands → notification text needs queue-depth surfacing or a different framing.
- iOS port (deferred per `backlog.md`) — different lifecycle model entirely; this ADR doesn't cross-port.

---

## Action Items

**Ordering note (per ADR-001 Action Items §"Ordering note"):** Phase 1 implements the lifecycle scaffolding only after Phase 0's stop-and-test points STT-A through STT-C pass. Pre-Phase-0 work limited to validation spikes and documentation per AGENTS.md.

1. [ ] **Phase 1** — declare a `LifecycleService` (e.g., `BackgroundExtractionService`) in AndroidManifest with `foregroundServiceType="dataSync"` (or the v15-current equivalent). Wire to `AppContainer`.
2. [ ] **Phase 1** — add the state-machine implementation per the §"State Machine" pseudocode above. Unit-test the transition table.
3. [ ] **Phase 1** — define notification channel `vestige.local_processing` at `Application.onCreate`. Importance LOW.
4. [ ] **Phase 1** — runtime-permission helper for `POST_NOTIFICATIONS` (Android 13+). Used by onboarding screen 3.5.
5. [ ] **Phase 4 day 1** — evaluate state-machine progress against §"Fallback Trigger" criteria. If triggered, apply the fallback action and record the date here.
6. [ ] **Phase 4** — onboarding screen 3.5 implementation per §"Permission Flow." Cross-doc updates (`ux-copy.md`, `concept-locked.md` §Onboarding, `PRD.md` §P0 UX shell) committed alongside.
7. [ ] **Phase 4** — wire notification tap target to History screen with deep-link to most-recent-in-flight entry.
8. [ ] **Phase 4** — verify `ModelHandle` ownership in `AppContainer` is consistent with the service lifecycle: model is process-scoped (matches existing architecture-brief), service controls priority but not handle existence.
9. [ ] **Phase 6** — privacy proof clip (per ADR-001 Q7) captures the notification appearing during a normal capture and disappearing after extraction completes. Bonus ambient signal alongside the `tcpdump` chapter.
10. [ ] Update `architecture-brief.md` §"AppContainer Ownership" — `ModelHandle` row references this ADR for backgrounding behavior.
