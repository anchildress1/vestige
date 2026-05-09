# AGENTS.md — Vestige Build Guardrails

These rules apply to AI implementors working in this folder.

## Operating Rule

Vestige is a challenge build, not an infinite product garden. Before accepting any feature or polish request, ask:

> Does this visibly improve the 90-second pitch or the 5-minute technical walkthrough?

If no, defer it to `backlog.md` and say: "this doesn't help us win, deferring to v1.5."

## P0 Guardrails

1. Read `README.md` first, then follow its reading order.
2. Treat `concept-locked.md`, `PRD.md`, and accepted ADRs as authoritative.
3. Do not use Android `SpeechRecognizer`, cloud STT, or third-party transcription. Voice input is Gemma 4 audio input or the feature is not the feature.
4. Do not add analytics, crash-reporting SaaS, RemoteConfig, cloud sync, or telemetry.
5. Model download is the only planned network event. Normal operation must be network-sealed.
6. Do not request broad storage permission for app-internal markdown/ObjectBox storage. Use system picker/share flows for export.
7. Do not add therapy, wellness, mood scoring, gratitude prompts, streaks, badges, mascots, or clinical framing.
8. In-app copy may use "dump"; public copy uses "voice entry," "capture," or "cognitive event" unless intentionally quoting UI.
9. Witness, Hardass, and Editor are tone variants only. They must not fork extraction logic.
10. Templates are model-emitted labels, not user-selected modes.
11. Audio is discarded after inference. Persist transcription text, not audio bytes.
12. Pattern claims must be sourced with counts, dates, snippets, tags, or field evidence.
13. Use one inference runtime in v1: LiteRT-LM. Any llama.cpp / MediaPipe / AICore switch requires a superseding ADR.
14. If LiteRT-LM or Gemma 4 audio fails an existential stop-and-test (notably STT-A audio plumbing in Phase 1), stop and replan. Do not build a pretty wrapper around a broken premise. We have standards, allegedly.

## Build Order

Build directly per `docs/PRD.md` §"Build philosophy: build first, test at failure zones." There is no upfront Phase-0 validation phase. Risk is mitigated through five stop-and-test points (STT-A–E) embedded in phases 1–3.

STT-A (audio plumbing, Phase 1) is the existential one — time-box hard. If the specs are intentionally rewritten, supersede via ADR.

