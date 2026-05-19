# ADR-013 — Typed entry requires the foreground model (voice/typed parity)

**Status:** Accepted  
**Date:** 2026-05-16  
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.  
**Supersedes:** the model-free typed-fallback premise carried by ADR-005-era v1 scope (`ux-copy.md` §"Capture Screen" "Typed entries work now", `CaptureCopy.MODEL_LOADING_LINE` / `MODEL_PAUSED_LINE`, `ModelReadiness.Loading`/`Paused` KDoc).

---

## Context

v1 shipped typed entry as a **model-free fallback**: while the local model was Loading,
Paused, or absent, `CaptureViewModel.submitTyped` routed through
`AppContainer.saveTypedEntry`, which persisted a PENDING entry (no foreground call) and
surfaced a Reviewing pane with an **empty follow-up**. The chrome promised this explicitly —
"Model loading. Typed entries work now." The voice path, by contrast, runs
`ForegroundInference.runForegroundCall(audio, persona)` and reviews with the model's persona
follow-up.

On-device testing surfaced the divergence as a defect: a typed entry produced no persona
reply and did not read like a voice entry. The product intent is that **typed and voice are
the same entry, one with audio and one without** — the persona response is core to the
product, not a voice-only embellishment.

## Decision

1. **Typed entry runs the same foreground call voice does.** New
   `ForegroundInference.runForegroundTextCall(text, persona)` — same persona system prompt,
   same `ForegroundResponseParser` `{transcription, follow_up}` envelope, `Content.Text`
   instead of `Content.AudioFile`, no temp WAV. Voice and typed share one result handler
   (`CaptureViewModel.runForeground`), so the Reviewing surface is identical.
2. **The model is required for typed entry.** There is no model-free typed path. When
   `ModelReadiness` is not `Ready`, `submitTyped` is a silent no-op — the exact parity of a
   disabled REC button on the voice side. No backwards-compat fallback is retained
   (`AppContainer.saveTypedEntry`, `SaveTypedEntry`, the typed-PENDING-without-extraction
   branch are deleted).
3. **Chrome no longer promises offline typed.** "Typed entries work now." is removed from
   the model-loading / paused lines; `ModelReadiness.Loading`/`Paused` gate both inputs.

## Consequences

**Easier:**
- One foreground path, one result handler, one Reviewing surface. Typed == voice minus audio.
- No orphan-PENDING typed entries to recover when the model lands; `recoverPendingExtractions`
  is now only crash-recovery for in-flight extractions, not a typed-fallback drain.

**Harder:**
- Typed entry is unavailable until the model is Ready. This is intentional and matches voice;
  the earlier "type while it downloads" affordance is gone. The model-loading chrome states
  the wait rather than offering a degraded path.
- A future offline-capture requirement would need a new decision, not a revert — the
  fallback code is deleted, not flagged off.

**Revisit when:**
- An explicit offline-entry requirement returns. It would supersede this ADR with a new
  decision describing the offline contract (storage, later-extraction, and how the persona
  reply is reconciled), not a re-introduction of the empty-follow-up fallback.

## Scope of change executed

| Surface | Change |
|---|---|
| `ForegroundInference` | + `runForegroundTextCall(text, persona)` (text-in, same parser) |
| `CaptureViewModel` | `submitTyped` gates on `Ready`, runs the foreground text call; shared `runForeground` for voice + typed; `SaveTypedEntry` seam removed |
| `AppContainer` | + `runForegroundTextCall`; `saveTypedEntry` removed |
| `MainActivity` | factory wires `foregroundTextInference` instead of `saveTypedEntry` |
| `CaptureCopy` / `CaptureUiState` KDoc / `ux-copy.md` | "Typed entries work now." removed; readiness gates both inputs |
