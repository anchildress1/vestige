# ADR-007 — Foreground service state machine extensions (amends ADR-004)

**Status:** Accepted
**Date:** 2026-05-10
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Depends on:** `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` §"State Machine", `adrs/ADR-006-foreground-service-restart-policy.md`

---

## Context

ADR-004 §"State Machine" lists seven transitions covering the happy path: NORMAL → PROMOTING → FOREGROUND → KEEP_ALIVE → DEMOTING → NORMAL plus the keep-alive bounce (`KEEP_ALIVE → FOREGROUND` on new work). Story 2.6.5 implementation surfaced three failure pathways the original table did not describe:

1. **Work arriving during `DEMOTING`.** A new capture's `extraction_status=RUNNING` lands after the keep-alive timer expired but before the platform stop ack arrives. ADR-004 implies the new work should restart the service, but does not say *how* — there is no `DEMOTING → NORMAL → PROMOTING` arrow in the table.
2. **Platform refusing the foreground start.** Per ADR-004 §"Notification Contract" the service relies on `Service.startForeground()`; ADR-004 does not address what happens if `startForegroundService` / `startForeground` throws (background-launch restrictions, battery-saver, app-standby buckets). Without explicit recovery, the machine wedges in `PROMOTING` forever.
3. **OS killing the service while the process survives.** ADR-004 §"Crash recovery" handles process-level kills via the cold-start sweep, but a service-only kill (e.g., `foregroundServiceType` time-limit kill on Android 14+) leaves the process alive with the machine wedged in `FOREGROUND`/`KEEP_ALIVE` — the cold-start sweep never runs.

Each pathway is required for the conditional foreground service to be robust in v1. Per AGENTS.md, ADRs are historical artifacts, so the additions land here as a new ADR amending ADR-004 §"State Machine" by reference.

---

## Decision (summary)

Three new transition pathways extend ADR-004 §"State Machine":

1. **DEMOTING → NORMAL → PROMOTING** (work arrived during demote) — `onForegroundStopConfirmed` synchronously transitions to NORMAL then immediately to PROMOTING when `inFlightCount > 0`. The PROMOTING transition fires `onPromoteRequested` so AppContainer dispatches a fresh `startForegroundService`.
2. **PROMOTING → NORMAL → PROMOTING via 5s retry timer** — `onForegroundStartFailed` resets PROMOTING to NORMAL and schedules a single bounded retry that re-enters PROMOTING after 5s if work is still queued. Retry is cancelled on count drain or successful start. Idempotent: a retry already in flight does not stack.
3. **(FOREGROUND | KEEP_ALIVE | DEMOTING) → NORMAL → PROMOTING via `onServiceKilled`** — the service's `onDestroy` calls this when shutdown was not self-initiated (OS-only service kill). Cancels keep-alive + retry jobs, drops to NORMAL, re-promotes if work is queued.

---

## Updated transition table

```
ADR-004 baseline (unchanged):
  NORMAL          → PROMOTING        on first entry → extraction_status=RUNNING
  PROMOTING       → FOREGROUND       on Service.startForeground() success
  FOREGROUND      → FOREGROUND       on next entry → RUNNING (no churn)
  FOREGROUND      → KEEP_ALIVE       on last in-flight extraction → terminal status
  KEEP_ALIVE      → FOREGROUND       on new entry → RUNNING during keep-alive window
  KEEP_ALIVE      → DEMOTING         on 30s timer expiry
  DEMOTING        → NORMAL           on Service.stopForeground() + Service.stopSelf()

ADR-007 additions:
  DEMOTING        → NORMAL → PROMOTING   on platform stop ack with inFlightCount > 0
  PROMOTING       → NORMAL                on Service.startForeground() rejection
  NORMAL          → PROMOTING             on FGS-retry timer expiry with inFlightCount > 0
  FOREGROUND      → NORMAL → PROMOTING?   on Service.onDestroy without self-initiated stop
  KEEP_ALIVE      → NORMAL → PROMOTING?   on Service.onDestroy without self-initiated stop
  DEMOTING        → NORMAL → PROMOTING?   on Service.onDestroy without self-initiated stop
```

Each `→ PROMOTING` arrow fires the `onPromoteRequested` callback the state machine accepts at construction; `AppContainer` wires this to `dispatchStartForegroundService` so every promote intention drives a fresh `startForegroundService` call.

---

## Service / state-machine handshake

The Service is a thin reflection of the machine. The handshake at each transition:

| Machine transition | Service action |
|---|---|
| `→ PROMOTING` | `onPromoteRequested` callback fires → `AppContainer.dispatchStartForegroundService(intent)` → Android creates / reuses the service instance |
| Machine `→ DEMOTING` | Service collector runs `stopForegroundCompat`, `onForegroundStopConfirmed`, `stopSelf`. Sets `shutdownHandled = true` |
| Service `onDestroy` with `shutdownHandled == false` | `onServiceKilled()` on the machine — OS-only kill recovery |

`onStartCommand` runs against whatever state the machine is in by the time Android schedules the service; the machine can legitimately progress past PROMOTING between the dispatch call and the service create (worker reports COMPLETED super-fast, ack arrives via a parallel path, OS kill / restart, etc.). `onStartCommand` resolves five distinct cases:

| State at `onStartCommand` | Service action | Why |
|---|---|---|
| `PROMOTING` | `startForegroundCompat()` → `onForegroundStartConfirmed()` (machine → FOREGROUND or KEEP_ALIVE) | Happy path — confirm the start ack |
| `FOREGROUND` | `startForegroundCompat()`, no machine ack | Late dispatch landing on an already-foreground process; re-acking would reset the keep-alive timer |
| `KEEP_ALIVE` | `startForegroundCompat()`, no machine ack | Late-start race: count drained between dispatch and create; keep-alive timer still owns the demote transition |
| `DEMOTING` | `startForegroundCompat()`, no machine ack | The state collector will reflect DEMOTING into `stopForeground` + `stopSelf` — service exits via the collector |
| `NORMAL` | `stopSelf` immediately, no `startForeground` | Stale dispatch — state was reset (start failure / OS-kill recovery completing with no queued work) before Android scheduled the service |

The OS `startForeground` deadline is satisfied for every non-NORMAL state. The NORMAL case is the only path that returns without `startForeground` — accepted as a deliberate trade because the machine guarantees no work is queued (otherwise it would be in PROMOTING).

The 5s FGS retry runs in the state machine's coroutine scope. It is not a Service-side concern; the machine schedules and cancels independently of any service instance.

---

## Test coverage contract

The state machine is unit-testable in pure Kotlin and exercises every transition in the extended table (`BackgroundExtractionServiceStateMachineTest`). The Service / machine handshake — `onStartCommand` calling `startForeground`, `onDestroy` calling `onServiceKilled`, the DEMOTING collector calling `stopSelf` — requires an Android lifecycle and is covered by `BackgroundExtractionServiceIntegrationTest` using Robolectric `ServiceController`.

Both layers are required. State-machine-only coverage is insufficient for the failure paths because:

- The bounce case requires the Service to call `onForegroundStopConfirmed` from its DEMOTING handler (in-process race between the bounce promote and `stopSelf`).
- The OS-kill case requires `Service.onDestroy` to fire `onServiceKilled` (only Service-level lifecycle reaches this code).
- The FGS retry case requires the platform to throw — exercised in unit tests via the AppContainer DI seam (`foregroundServiceStarter`), in the integration test via `ShadowApplication`/`ServiceController`.

---

## Consequences

**Easier:**
- The state-machine extensions are deterministic and testable in pure Kotlin.
- Each `→ PROMOTING` flow is observable via the same `onPromoteRequested` callback — single dispatch path, no ad-hoc service-restart logic in callers.
- The 5s retry handles transient FGS rejections without requiring the user to re-tap record.

**Harder:**
- Service / machine handshake must be exercised at the Android lifecycle level — pure Kotlin tests miss the integration. The Robolectric integration test is the floor; on-device verification of the bounce and OS-kill paths is a manual check.
- Five additional transition arrows means five additional code paths to keep in sync with the docs. Drift risk is real.

**Revisit when:**
- Real-device telemetry shows the 5s retry firing in patterns that suggest the FGS rejection is not transient (e.g., the device is in a permanent restricted bucket). Could need a retry cap.
- Phase 4 onboarding work surfaces a UI requirement to expose the retry state to the user.

---

