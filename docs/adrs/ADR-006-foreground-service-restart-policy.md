# ADR-006 — Foreground service restart policy: START_NOT_STICKY (amends ADR-004)

**Status:** Accepted
**Date:** 2026-05-10
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Depends on:** `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` §"Crash recovery", `adrs/ADR-001-stack-and-build-infra.md` §Q3 (retry-based recovery)

---

## Context

ADR-004 §"Crash recovery" originally referenced `START_STICKY` as the foreground-service restart contract. Story 2.6.5 implementation work (2026-05-10) surfaced that `START_STICKY` carries a downside the original ADR didn't consider:

- The OS re-creates the service with a `null` intent. Because the service's `onStartCommand` already routes through `BackgroundExtractionLifecycleStateMachine` and the `extraction_status` count, any sticky restart that lands when the count is zero promotes the process to foreground for no work — a phantom notification with nothing reading.
- The legitimate restart case (OS killed the service while extraction was in flight) is already covered by the ADR-001 §Q3 cold-start sweep: the next time the user opens the app, non-terminal entries are re-seeded into the bus and the service re-promotes only when real work exists.
- `START_STICKY` therefore adds zero recovery value over the cold-start sweep and creates a phantom-notification failure mode.

ADR-004 also assumed an in-process state observer (StateFlow.collect) would catch every PROMOTING transition. Story 2.6.5 review surfaced that observer pattern's replay/conflation hazards; the implementation moved to a synchronous callback (`onPromoteRequested`) the state machine fires on every transition into PROMOTING. With `START_NOT_STICKY` the callback handles all promote dispatches deterministically — no OS-driven retry needed.

Per AGENTS.md, ADRs are historical artifacts and must not be rewritten. This change lands as a new ADR amending ADR-004 §"Crash recovery" by reference.

---

## Decision

1. **`BackgroundExtractionService` returns `START_NOT_STICKY` from `onStartCommand`.** The service is conditional by design; the OS does not restart it on its own.
2. **Crash recovery flows entirely through ADR-001 §Q3 cold-start sweep.** On `Application.onCreate`, `AppContainer.seedRecoveredExtractions` queries non-terminal entries from ObjectBox via `VestigeBoxStore.findNonTerminalEntryIds(boxStore)` and seeds the bus. The state machine promotes only when the count is non-zero.
3. **The cold-start sweep query owner is `EntryStore`** (architecture-brief §"AppContainer Ownership"), which lands in its own story. Until then, `AppContainer`'s `recoveredEntryIdsLoader` defaults to `{ emptyList() }` — recovery is a no-op for Phase-2 builds, which is correct because no prior persisted extraction state exists yet.
4. **ADR-004 §"Crash recovery" is amended** to reflect `START_NOT_STICKY` + cold-start-sweep ownership.

---

## Trade-off Analysis

`START_STICKY` would buy automatic OS restart at the cost of phantom-notification windows. `START_NOT_STICKY` cedes auto-restart to the cold-start sweep, which already exists per ADR-001 §Q3 and runs on every app open. The cold-start sweep is the canonical recovery path; sticky restart was redundant insurance with a UX downside.

The narrow window where this matters: OS kills the service while the user has the app foregrounded and extraction is in flight. Under `START_STICKY`, the OS would re-create the service mid-extraction; under `START_NOT_STICKY`, the foreground extraction stalls until the user backgrounds and re-opens the app, at which point the cold-start sweep re-promotes. Practically: rare on Android 14+ with `foregroundServiceType=dataSync` and an active notification. ADR-004 §"Crash recovery" already characterized this case as "rare."

---

## Consequences

**Easier:**
- No phantom-notification failure mode.
- The promote dispatch pipeline is one path (state machine callback), not two (callback + OS sticky restart).
- Service test coverage simplifies — no need to assert behavior for stale sticky-restart intents.

**Harder:**
- `EntryStore` lands with a slight obligation: it must expose a non-terminal-entry query to AppContainer (or AppContainer adopts the EntryStore-owned BoxStore directly via `VestigeBoxStore.findNonTerminalEntryIds`). The query helper already exists in `:core-storage`; wiring is a one-line plumbing change in the EntryStore story.

**Revisit when:**
- A future device-test surfaces a recovery case the cold-start sweep doesn't cover.
- The notification-tap deep-link work in Phase 4 (Story 4.7) needs the service to be alive on resume.

---

