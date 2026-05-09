# Vestige — Product Requirements Document

## Problem Statement

ADHD adults and similar neurotypes are poorly served by shipped AI journaling and mental wellness apps. The category is usually therapy-coded, validation-default, cloud-hosted, and feelings-vocabulary-centric. Users who want behavioral pattern tracking without warmth performance — and without their cognitive data living on someone's server — currently have very little on Android. The data-sharing problem is documented: Huckvale et al. assessed 36 popular Android and iOS apps for depression and smoking cessation and found 92% (33 of 36) transmitted user data to third parties — predominantly Google and Facebook for advertising and analytics — with disclosure gaps in policy text on most ([JAMA Network Open. 2019;2(4):e192542](https://jamanetwork.com/journals/jamanetworkopen/fullarticle/2730782), doi:10.1001/jamanetworkopen.2019.2542). A 2022 follow-up of 578 mental-health apps (Lagan et al.) found basic privacy hygiene still uneven, with 23% lacking a privacy policy at all ([JAMA Network Open. 2022;5(12):e2248999](https://jamanetwork.com/journals/jamanetworkopen/fullarticle/2799953), doi:10.1001/jamanetworkopen.2022.48999). Cite those, not a mystery statistic wearing a lab coat.

## Goals

1. Win the Gemma 4 Challenge "Build with Gemma 4" prize: one of five $500 + DEV++ + badge slots, judged on intentional model use, technical implementation, creativity, and UX.
2. Make the bite land in the demo. Anti-sycophant tone visible inside 60 seconds of opening the demo video.
3. Demonstrate intentional, effective use of Gemma 4 E4B's flagship features — native audio multimodal as headline; multi-lens convergence pipeline as the agentic-as-product story (see ADR-002).
4. Ship a literally true on-device privacy story — zero outbound network calls during normal operation; model download is the only network event.
5. Ship a working APK, 5-minute demo video, and dev.to post by 2026-05-24, 23:59 PDT.

## Non-Goals

1. **Therapy / mental wellness framing.** Different category, liability fog, saturated space. We position as an atmospheric on-device cognition tracker.
2. **iOS or web.** Single-platform builds faster; the spec assumes Android-only.
3. **Voice output / TTS.** Gemma 4 doesn't generate audio natively. Adding Kokoro or another TTS adds engineering scope (~3 days) without proportional demo lift; AI voices flatten sarcasm.
4. **Multi-step agentic tool chains.** Local v1 reliability is unproven and the demo only needs one auditable tool-use beat. Our agentic beat, if it ships, is single-step.
5. **Always-on listening / hotword.** Battery, permission friction, and scope creep that doesn't return value at hackathon scale.
6. **Cloud sync, multi-device, multi-user.** The privacy story is the differentiator; cloud anything compromises it.
7. **Gamification, streaks, scores, "good day" grading.** Incompatible with the anti-quantified-self brand.
8. **EmbeddingGemma** ships *contingent* on STT-E. Drops to v1.5 if it doesn't visibly outperform tag-only retrieval on prepared sample data.

## User Stories

Primary persona: ADHD-flavored adult, technically literate, allergic to therapy-app sycophancy, wants behavioral observation without warmth performance.

Core capture loop:
- I want to dump what just happened to me by voice without typing, so I can capture state in the moment without opening a real journal app.
- I want the app to ask sharp follow-up questions about what I was doing, eating, sleeping, around the moment — not how I felt about it — so I get behavioral data instead of a feelings inventory.
- I want a different prompt path at 3am than during the day, so the app meets me where I actually am.

Persona and tone:
- I want to pick a persona (Witness / Hardass / Editor) so the bite matches my mood without me having to fight default validation.
- I want to switch personas mid-session if Witness is being too gentle today, so the bite is tunable.

Privacy:
- I want my voice to never leave the device and to know it didn't, so I can dump dark stuff without it haunting me later.
- I want to export everything as markdown and delete everything from settings, so the data sovereignty claim is provable.

Memory and patterns:
- I want to see patterns the app surfaces about my own behavior across entries, named in my own words with no interpretive overlay, so I trust what I'm seeing.
- I want to dismiss, snooze, or mark-resolved a pattern, so the app respects my agency.
- I want to browse past entries and see the tags the model extracted, so I can audit what it caught.

Edge cases:
- I want a graceful first-launch experience including the 3.66 GB model download, so I'm not abandoned at the first wall.
- I want clear handling when the app fails (download error, inference timeout, mic permission denied), so failures don't stall me.

## Requirements

### Must-Have (P0) — feature is not viable without these

**Capture loop:**
- App launches; model downloads on Wi-Fi with progress UI; mic permission handled. Do not request broad storage permission for internal markdown/ObjectBox; exports use Android's system picker/share flow.
- Voice input via native Gemma 4 audio modality (no STT step)
- Audio chunking per `adrs/ADR-001-stack-and-build-infra.md` §Q4: ≤30s capture = single call returns transcription + follow-up; >30s capture = transcription-only intermediate chunks + final call returns transcription + one follow-up over the concatenated transcript
- Audio bytes discarded after each call; transcription persists as `entry_text` (same posture as a typed note)
- Multi-turn conversation works — the model maintains session context across at least 4 exchanges
- Witness / Hardass / Editor personas fully working as prompt-and-copy variants. They change tone only, not extraction logic. This keeps onboarding honest and gives the demo its visible bite without adding a second analytical system.
- Multi-lens extraction pipeline (3 lenses × 5 surfaces) producing the minimal v1 schema with convergence-based confidence. Templates are agent-emitted labels (Aftermath, Tunnel exit, Concrete shoes, Decision spiral, Goblin hours, Audit), not user-picked.
- Tag extraction per entry, visible to user, stored as queryable structured data
- **Per-entry observations:** every saved entry surfaces 1–2 behavioral or vocabulary observations from that entry alone, even before any cross-entry pattern exists. The product produces useful observable signal from entry one. Pattern-enhanced callouts remain gated at ≥10 entries + ≥3 supporting entries.

**Memory:**
- Markdown source-of-truth (one file per entry, exportable)
- ObjectBox structured tag store + pattern store
- Hybrid retrieval (keyword + tags + recency) over candidate set
- **Vector layer (EmbeddingGemma 300M + ObjectBox vector index) ships only if STT-E passes.** If STT-E fails, v1 ships keyword + tags + recency only; embeddings move to v1.5.
- One basic history list of past entries
- Entry detail screen: transcript, tags, template label, the per-entry observation. Reading/Re-eval debug output is P1.

**Patterns:**
- Pattern detection runs after every N entries (default 10, hardcoded for v1)
- At least one cross-entry pattern surfaces in the demo session
- Patterns persist as their own list; basic patterns view
- **Pattern actions in v1:** dismiss / snooze / mark-resolved. Agency over surfaced patterns is part of the user story; promoted from P1 to P0.

**Privacy:**
- Zero outbound network calls during normal operation; model download is the only network event
- Network behavior verifiable (a judge can monitor traffic and see no outbound calls)
- Settings includes export all entries, delete all data, model status, re-download model, and delete model. Export/delete are part of the data-sovereignty claim, not ornamental settings chrome.
- Static safety floor: no proactive crisis detection or clinical triage, but if the user explicitly asks for immediate self-harm help, return a local static message that Vestige is not a crisis tool and they should contact local emergency services or a crisis hotline. No network call, no diagnosis, no model improvisation.

**UX shell:**
- Dark mode default, intentional design language
- Onboarding flow handles persona pick → permission → Wi-Fi check → download → first-dump scaffold
- **Persistent local model status indicator/screen visible from app shell or settings.** A judge should understand within 10 seconds that this is a local AI app; the indicator carries that signal alongside the capture screen.

**Submission package:**
- **Submission category: Build with Gemma 4 only** (not Write). One submission, one category per challenge rules.
- Required tags on dev.to: `devchallenge`, `gemmachallenge`, `gemma`
- Demo APK distributed via GitHub releases (sideload-ready)
- 5-minute demo video: 90s pitch + technical walkthrough with chapter markers, including a privacy proof clip showing zero outbound traffic during a normal capture session
- Dev.to post following `blog-template.md` scaffolding
- README with setup, architecture summary, model-choice rationale, known limitations

### Nice-to-Have (P1) — fast follow-up

- Per-session persona override
- All six template labels reliably emitted by agent (low false-positive rate for Audit fallback; specific archetype detection working well across edge cases)
- **Re-eval / Reading on entry detail** — re-runs the 3-lens pipeline on the stored transcript, shows the diff per surface field, lets the user accept the new shape or keep the original. The implementation is cheap once the 3-lens pipeline exists; ships at P1 if scope holds, otherwise drops to v1.5.
- **Roast me** bottom sheet from the Patterns screen — sourced, ephemeral, persona-flavored pattern cut. It may use the insufficient-data fallback before enough history exists. Ships only after the normal pattern list works; the core app is not a roast generator with storage attached.
- History filter by tag, template, or date range
- Empty states with persona-flavored microcopy
- Top three polished error states (download fail/stall, inference timeout/fail, mic denied/unavailable). Other states from `ux-copy.md` §Error States are available as strings but only implemented when naturally encountered.

### Future Considerations (P2) — design decisions today should not foreclose

- Voice output / TTS via Kokoro
- Hotword / always-on capture
- Multi-step agentic tool chains (when local reliability is proven and the UX need is real)
- iOS port
- Cloud sync, opt-in, encrypted
- Backups / auto-export to user's chosen folder
- Multilingual support
- Notifications / reminders
- Light theme
- Vision / image input (Gemma 4 supports; we don't use in v1)
- **Video input** — Gemma can process video-style prompts as extracted frames plus audio chunks. Use cases for Vestige: environmental capture (point camera at the mess you're describing), artifact capture (receipts, notes, evidence), Tunnel exit POV walkthrough. Skipped in v1 for engineering scope (~3-4 days) and RAM pressure (visual tokens are expensive).
- Audio retention with N-day auto-expiry + encryption-at-rest (default OFF setting for verification use case)
- Calendar / health data correlation (read-only on-device imports)

### Acceptance Criteria

**Onboarding:**
- Given a fresh install, when the user first opens the app, then they walk through persona pick → mic permission → Wi-Fi check → model download with progress → first-dump scaffold without dead ends.

**Voice capture:**
- Given the user is in a session, when they tap record and speak ≤30s then stop, then audio is sent to E4B, model returns transcription + a contextually appropriate follow-up in a single response, transcription is shown in the transcript and saved as `entry_text`, and audio bytes are discarded immediately after the model call completes.

**Multi-turn:**
- Given a session has at least two prior exchanges, when the user records a third audio segment, then the model has session context and asks a contextually relevant follow-up referencing earlier turns.

**Tag extraction:**
- Given the user finishes a session, when the entry is saved, then the entry shows ≥1 model-extracted tag and tags are stored as queryable structured data.

**Pattern surfacing:**
- Given the user has ≥10 entries (or seeded sample data), when a session ends, then at least one cross-entry pattern surfaces, is named in the user's own words, and contains no interpretive overlay ("you might be feeling…" is forbidden).

**Privacy claim:**
- Given the user is using the app normally, when network traffic is monitored on the device, then no outbound network calls occur. Model download is the sole network event and only happens at first launch (or explicit re-download from settings).

**Embedding contingent ship:**
- Given STT-E (Phase 3 stop-and-test), when tag-only vs tag+embedding retrieval are compared on prepared vocabulary-drift entries, then embeddings ship only if the difference is visually compelling in the demo. If not, EmbeddingGemma and the ObjectBox vector index both drop to v1.5.

## Success Metrics

### Primary (binary)
- Win one of five "Build with Gemma 4" prize slots.

### Leading indicators (during challenge period)
- Demo video plays in full for non-judges who watch it (no scrubbing past 30s).
- Dev.to post receives engagement (comments, reactions) within 48 hours of submission.
- APK installs cleanly on the reference S24 Ultra plus at least one external test device.

### Lagging indicators (post-challenge)
- Ashley uses Vestige as a real tool for 2+ weeks post-submission (genuine retention test).
- GitHub stars on the repo: ≥10 within 30 days post-submission as a low-bar interest signal.
- External issues or PRs from non-team developers within 30 days post-submission.

## Open Questions

**Non-blocking (resolve during build):**
- App icon and cover image design — when and who. *Owner: design, late phase.*
- Default font / type system — pick once, propagate everywhere. *Owner: design, early.*
- Demo storyboard — exact 90s pitch and 5-min walkthrough beats. *Owner: design + writing, mid-phase.*

**Blocking (must resolve at the relevant stop-and-test point):**
- Does multi-turn conversation work on E4B with our prompt patterns? If broken at the Phase 2 multi-turn stop-and-test point, the spec rebuilds.
- Is tool-calling reliable enough on E4B for a single agentic demo beat? Decides whether agentic feature ships.

## Timeline — Ordered Phases

The challenge deadline is 2026-05-24, 23:59 PDT. The user is working near-full-time across the period. Phases run in order; later phases assume earlier phases pass their gates.

### Build philosophy: build first, test at failure zones

**Executive decision: there is no Phase 0 validation phase.** We build directly. Risk is mitigated through **stop-and-test points** marked at known failure zones during phases 1–3. Each stop-and-test has a clear failure-mode and a clear contingency.

The five stop-and-test points are:

| # | Failure zone | Lives in | Failure mode | If it fails |
|---|---|---|---|---|
| **STT-A** | Audio input plumbing — can we feed bytes to E4B at all? | Phase 1, audio pipeline | Format/encoding error; LiteRT-LM `AudioBytes`/`AudioFile` doesn't accept what we're passing | Stop and replan. This is existential — without audio in, the pitch dies. Time-box debugging to a hard limit (e.g., one focused day). |
| **STT-B** | Multi-turn conversation reliability on E4B | Phase 2, capture loop | Model loses session context across 3+ turns; per-benchmark E4B is fragile here | Drop to single-turn extract-and-respond. Loop weaker but product still ships. Spec re-write needed for the conversation UX. |
| **STT-C** | Tag extraction consistency across 10+ varied dumps | Phase 2, extraction | Model returns inconsistent tags for similar content; pattern engine downstream gets noisy | Tighten extraction prompt; if still bad, accept noisy patterns and document as known limitation. |
| **STT-D** | 3-lens convergence is meaningfully different sometimes | Phase 2, multi-lens pipeline | Lenses always return identical outputs — architecture earns nothing | Drop multi-lens to single-pass. Remove "Reading" debug screen. Architecture story weakens but app ships. |
| **STT-E** | EmbeddingGemma visibly outperforms tag-only on prepared sample data | Phase 3, retrieval | Same retrieval results between methods on demo scenarios | Ship without embeddings. Drop the ObjectBox vector index. EmbeddingGemma defers to v1.5. |

Sample data for STT-C, STT-D, STT-E lives in `sample-data-scenarios.md`.

### Phase 1 — Architecture and scaffold
*Build/infra contract owned by `adrs/ADR-001-stack-and-build-infra.md`. Items 1–6 are product scaffolding; 7–10 are infra gates ADR-001 front-loads into Phase 1.*

1. Repo structure, four-module split (`:app`, `:core-model`, `:core-inference`, `:core-storage`) per ADR-001 Q1, `AGENTS.md` guardrails referencing the scope rule
2. LiteRT-LM SDK integration, model loading, inference smoke test
3. AudioRecord pipeline + 30s chunking (per ADR-001 Q4). **🛑 STT-A — audio input plumbing.** First test: can we feed any audio bytes to E4B via LiteRT-LM Android and get back coherent transcription? Existential. Time-box to one focused day; if it doesn't work, stop and replan.
4. ObjectBox schema for entries, tags, patterns — including `extraction_status` / `attempt_count` / `last_error` operational fields per ADR-001 Q3
5. Markdown source-of-truth read/write
6. Persona prompt scaffold for Witness / Hardass / Editor as tone-only variants
7. `ModelArtifactStore` interface + SHA-256 verification + retry policy (ADR-001 Q6, storage/load contract only — onboarding UX is Phase 4)
8. `NetworkGate` abstraction with `OPEN`/`SEALED` states + StrictMode network detection in dev + dependency grep for telemetry (ADR-001 Q7)
9. Signed dummy release APK installed on the reference S24 Ultra (ADR-001 Q5)
10. Convergence resolver unit-test suite scaffolded (ADR-002 Q4) — implementation lands in Phase 2

### Phase 2 — Core capture loop
1. Capture loop end-to-end: record → fast foreground transcription + follow-up → background 3-lens extraction → save with convergence confidence
2. Multi-turn session state preservation. **🛑 STT-B — multi-turn reliability.** Test that model maintains context across 3+ turns. If broken on E4B, drop to single-turn extract-and-respond and re-write the conversation UX.
3. Multi-lens prompt architecture (5 surface modules + 3 lens modules, mix-and-match composition). **🛑 STT-D — 3-lens convergence differs.** Run sample transcripts (`sample-data-scenarios.md`) through all three lenses; verify lenses produce meaningfully different outputs at least sometimes. If they always agree, drop multi-lens to single-pass and remove the Reading screen.
4. Convergence resolver (canonical / candidate / ambiguous per field). **🛑 STT-C — tag extraction consistency.** Run 10+ varied test dumps through the resolver; verify stable tag emission. If unstable, tighten prompts.
5. Agent-emitted template labels with archetype detection rules
6. Inference latency UI: immediate placeholder, measured target on reference device, streaming only if structured-output tests during STT-D say it's worth the parser complexity

### Phase 3 — Memory and patterns
1. Hybrid retrieval implementation (keyword + tags + recency)
2. Embedding layer + ObjectBox vector index integration. **🛑 STT-E — EmbeddingGemma vs tag-only comparison.** Run prepared vocabulary-drift sample data through both retrieval paths; embeddings ship only if visibly better. If not, drop EmbeddingGemma and the vector index to v1.5.
3. Pattern detection runs at end of session
4. Patterns persist in their own list
5. Pattern detection list + minimal pattern detail with source evidence; pattern actions (dismiss / snooze / mark-resolved) reachable per P0
6. Per-entry observation generation wired into the capture loop (P0)

### Phase 4 — UX surface
1. Onboarding flow with model download UX
2. Session UI polish (record button, chunk indicator, conversation transcript)
3. History list + entry detail (transcript, tags, template label, per-entry observation)
4. Polished Pattern List + Pattern Detail UI, action affordances, empty states
5. Persistent Local Model Status indicator/screen (P0 — visible from app shell or settings)
6. Settings screen — v1 P0 scope: persona default, export all entries, delete all data, model status / re-download / delete. Pattern threshold, cooldown, default-input toggle, transcription-visibility toggle are deferred unless they visibly improve the demo (those settings are removed from `ux-copy.md` for v1).
7. Dark mode design language pass — color, type, spacing
8. Empty states for major screens
9. Top three polished error states (download fail/stall, inference timeout/fail, mic denied/unavailable)
10. P1: per-session persona override
11. P1: Re-eval / Reading on entry detail (if scope holds)
12. P1: Roast me bottom sheet (if normal pattern evidence is already solid)

### Phase 5 — Demo optimization
1. Sample data set for demo, curated to show bite, patterns, embedding advantage if applicable
2. Demo storyboard (90s pitch + 5-min walkthrough)
3. Demo dry runs on the reference device

### Phase 6 — Submission package
1. Demo recording (raw takes)
2. **Privacy proof clip:** record a normal capture session with `tcpdump` running on the reference device, showing zero outbound traffic after the model download completes. Edited into a chapter of the demo video.
3. Video editing with chapter markers
4. Dev.to post body following `blog-template.md`. Required tags: `devchallenge`, `gemmachallenge`, `gemma`. Submission category: Build with Gemma 4 only.
5. README final pass
6. Final signed release APK + GitHub release (signing pipeline already validated in Phase 1 per ADR-001 Q5)
7. App icon + cover image
8. Final QA on the reference device + at least one external device

### Phase 7 — Buffer
1. Bug fixes from Phase 6 QA
2. Any P1 features that fit
3. Final polish on demo and post
4. Submit before 2026-05-24, 23:59 PDT

## Scope Discipline (mandatory for AI agents working on this build)

This is a 17-day build aimed at winning a $500 prize, not a perfectionist long-tail product. Decision criterion for any change request:

> Does it visibly improve the 90-second pitch or the 5-minute technical walkthrough?

If no → reject, log to `backlog.md`, respond: *"this doesn't help us win, deferring to v1.5."* Reference the P2 list before accepting feature additions or polish requests. Polish invisible in the demo is deferred.

## Authoritative documents

- `concept-locked.md` — full product spec
- `runtime-research.md` — Android Gemma 4 deployment research
- `challenge-brief.md` — challenge rules and judging criteria
- `PRD.md` — this file
- `AGENTS.md` — AI implementor guardrails
- `adrs/ADR-001-stack-and-build-infra.md` — stack and build-infra decisions
- `adrs/ADR-002-multi-lens-extraction-pattern.md` — agent design pattern
- `adrs/ADR-003-pattern-detection-and-persistence.md` — pattern detection algorithm and lifecycle
- `architecture-brief.md` — module breakdown, build plan, interface contracts
- `sample-data-scenarios.md` — stop-and-test validation transcripts (STT-C, STT-D, STT-E)
- `backlog.md` — features deferred from v1, with rationale and "what would unblock" per entry
