# ADR-018 — Inline Foreground Follow-Up

**Status:** Accepted
**Date:** 2026-05-20
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes:**
- ADR-014 detached call-2 behavior for foreground follow-up persistence
- ADR-013 typed-entry foreground model call

---

## Context

Foreground latency is still dominated by LiteRT-LM runtime contention, not by
the short follow-up in the foreground response.

The old capture VM persisted voice entries as soon as streamed transcription
appeared, then launched a second foreground text call to patch `follow_up`
later. That added another model call and detached the visible follow-up from
the transcript-producing call.

Typed entries already have authoritative text, so they persist directly.

---

## Decision

Voice foreground returns transcription and follow-up together in one structured
response.

- Voice: one Gemma audio call returns `{transcription, follow_up}`.
- Typed: persist the typed text directly as `entry_text`; do not run a foreground text call.
- `follow_up` is persisted from the same terminal foreground voice result.
- Background extraction still runs from the saved transcript and owns the analytical value.
- Pattern callouts remain the cross-entry intelligence surface.

---

## Consequences

- Capture has no detached call-2 and no late `attachFollowUp` patch.
- Persona still changes foreground follow-up wording only.
- Typed captures keep `follow_up = null` unless a later product decision adds a typed follow-up path.
- Background extraction and pattern detection remain unchanged.
