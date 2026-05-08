# Vestige — Product Requirements Document

## Problem Statement

ADHD adults and similar neurotypes are poorly served by every shipped AI journaling and mental wellness app. The category is uniformly therapy-coded, validation-default, cloud-hosted, and feelings-vocabulary-centric. Users who want behavioral pattern tracking without warmth performance — and without their cognitive data living on someone's server — currently have nothing on Android. A 2023 JAMA study found 74% of popular mental health apps transmit identifiable behavioral data to third-party analytics, even when users opt out, while shipped on-device LLM journaling on phones is essentially nonexistent.

## Goals

1. Win the Gemma 4 Challenge "Build with Gemma 4" prize: one of five $500 + DEV++ + badge slots, judged on intentional model use, technical implementation, creativity, and UX.
2. Make the bite land in the demo. Anti-sycophant tone visible inside 60 seconds of opening the demo video.
3. Demonstrate intentional, effective use of Gemma 4 E4B's flagship features — native audio multimodal as headline; agentic tool-calling if Day-1 validation passes.
4. Ship a literally true on-device privacy story — zero outbound network calls during normal operation; model download is the only network event.
5. Ship a working APK, 5-minute demo video, and dev.to post by 2026-05-24, 23:59 PDT.

## Non-Goals

1. **Therapy / mental wellness framing.** Different category, liability fog, saturated space. We position as a forensic instrument for cognition.
2. **iOS or web.** Single-platform builds faster; the spec assumes Android-only.
3. **Voice output / TTS.** Gemma 4 doesn't generate audio natively. Adding Kokoro or another TTS adds engineering scope (~3 days) without proportional demo lift; AI voices flatten sarcasm.
4. **Multi-step agentic tool chains.** Benchmarks show these are broken on every Gemma 4 size. Our agentic beat, if it ships, is single-step.
5. **Always-on listening / hotword.** Battery, permission friction, and scope creep that doesn't return value at hackathon scale.
6. **Cloud sync, multi-device, multi-user.** The privacy story is the differentiator; cloud anything compromises it.
7. **Gamification, streaks, scores, "good day" grading.** Incompatible with the anti-quantified-self brand.
8. **EmbeddingGemma** ships *contingent* on the Day-2 demo comparison. Drops to v1.5 if it doesn't visibly outperform tag-only retrieval on prepared sample data.

## User Stories

Primary persona: ADHD-flavored adult, technically literate, allergic to therapy-app sycophancy, wants behavioral observation without warmth performance.

Core capture loop:
- I want to dump what just happened to me by voice without typing, so I can capture state in the moment without opening a real journal app.
- I want the app to ask sharp follow-up questions about what I was doing, eating, sleeping, around the moment — not how I felt about it — so I get behavioral data instead of a feelings inventory.
- I want a different prompt path at 3am than during the day, so the app meets me where I actually am.

Persona and tone:
- I want to pick a persona (Detective / Hardass / Editor) so the bite matches my mood without me having to fight default validation.
- I want to switch personas mid-session if Detective is being too gentle today, so the bite is tunable.

Privacy:
- I want my voice to never leave the device and to know it didn't, so I can dump dark stuff without it haunting me later.
- I want to export everything as markdown and delete everything from settings, so the data sovereignty claim is provable.

Memory and patterns:
- I want to see patterns the app surfaces about my own behavior across entries, named in my own words with no interpretive overlay, so I trust what I'm seeing.
- I want to dismiss, snooze, or mark-resolved a pattern, so the app respects my agency.
- I want to browse past entries and see the tags the model extracted, so I can audit what it caught.

Edge cases:
- I want a graceful first-launch experience including the 2.5 GB model download, so I'm not abandoned at the first wall.
- I want clear handling when the app fails (download error, inference timeout, mic permission denied), so failures don't stall me.

## Requirements

### Must-Have (P0) — feature is not viable without these

**Capture loop:**
- App launches; model downloads on Wi-Fi with progress UI; permissions handled (mic, storage)
- Voice input via native Gemma 4 audio modality (no STT step)
- Audio chunked into 30-second segments seamlessly across boundaries
- Audio bytes discarded after model extraction; never persisted
- Multi-turn conversation works — the model maintains session context across at least 4 exchanges
- Detective persona fully working, with clear bite in responses
- At least 3 templates wired end-to-end (Day-shaped hole, Aftermath, Goblin hours)
- Tag extraction per entry, visible to user, stored as queryable structured data

**Memory:**
- Markdown source-of-truth (one file per entry, exportable)
- ObjectBox structured tag store
- Hybrid retrieval (keyword + tags + recency) over candidate set
- EmbeddingGemma vector layer if Day-2 comparison demo holds; else cut to v1.5
- One basic history list of past entries

**Patterns:**
- Pattern detection runs after every N entries (default 10, hardcoded for v1)
- At least one cross-entry pattern surfaces in the demo session
- Patterns persist as their own list; basic patterns view

**Privacy:**
- Zero outbound network calls during normal operation; model download is the only network event
- Network behavior verifiable (a judge can monitor traffic and see no outbound calls)

**UX shell:**
- Dark mode default, intentional design language
- Onboarding flow handles persona pick → permission → Wi-Fi check → download → first-dump scaffold

**Submission package:**
- Demo APK distributed via GitHub releases (sideload-ready)
- 5-minute demo video: 90s pitch + technical walkthrough with chapter markers
- Dev.to post covering all required sections
- README with setup, architecture summary, model-choice rationale, known limitations

### Nice-to-Have (P1) — fast follow-up

- Hardass + Editor personas fully working (Detective is default)
- Per-session persona override
- All six templates wired (Tunnel exit, Concrete shoes, Echoes added)
- Settings screen with persona default, pattern surfacing frequency, data export, "delete all" button
- Pattern dismiss / snooze / mark-resolved actions
- Single agentic tool-call beat during pattern detection — contingent on Day-1 E4B tool-call reliability validation
- History filter by tag, template, or date range
- Empty states with persona-flavored microcopy
- Top three error states fully designed (download fail, inference timeout, mic permission denied)

### Future Considerations (P2) — design decisions today should not foreclose

- Voice output / TTS via Kokoro
- Hotword / always-on capture
- Multi-step agentic tool chains (when models support them)
- iOS port
- Cloud sync, opt-in, encrypted
- Backups / auto-export to user's chosen folder
- Multilingual support
- Notifications / reminders
- Light theme
- Vision / image input (Gemma 4 supports; we don't use in v1)
- Calendar / health data correlation (read-only on-device imports)

### Acceptance Criteria

**Onboarding:**
- Given a fresh install, when the user first opens the app, then they walk through persona pick → mic permission → Wi-Fi check → model download with progress → first-dump scaffold without dead ends.

**Voice capture:**
- Given the user is in a session, when they tap record and speak ≤30s then stop, then audio is sent to E4B, model returns a contextually appropriate follow-up, and audio bytes are discarded immediately after extraction.

**Multi-turn:**
- Given a session has at least two prior exchanges, when the user records a third audio segment, then the model has session context and asks a contextually relevant follow-up referencing earlier turns.

**Tag extraction:**
- Given the user finishes a session, when the entry is saved, then the entry shows ≥1 model-extracted tag and tags are stored as queryable structured data.

**Pattern surfacing:**
- Given the user has ≥10 entries (or seeded sample data), when a session ends, then at least one cross-entry pattern surfaces, is named in the user's own words, and contains no interpretive overlay ("you might be feeling…" is forbidden).

**Privacy claim:**
- Given the user is using the app normally, when network traffic is monitored on the device, then no outbound network calls occur. Model download is the sole network event and only happens at first launch (or explicit re-download from settings).

**Embedding contingent ship:**
- Given Day-2 sample-data comparison, when tag-only vs tag+embedding retrieval are compared on prepared vocabulary-drift entries, then embeddings ship only if the difference is visually compelling in the demo. If not, EmbeddingGemma drops to v1.5.

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
- Sample-data scenario for the embedding comparison demo — specific vocabulary-drift narrative to seed. *Owner: design pass at Phase 0.*
- Tool-call schema and trigger logic for the agentic beat — where exactly does the model decide to call vs not. *Owner: engineering, contingent on Day-1 validation.*
- Onboarding microcopy per persona — who writes the persona-flavored loading text. *Owner: design + writing.*
- App icon and cover image design — when and who. *Owner: design, late phase.*
- Default font / type system — pick once, propagate everywhere. *Owner: design, early.*
- Demo storyboard — exact 90s pitch and 5-min walkthrough beats. *Owner: design + writing, mid-phase.*

**Blocking (must resolve in Phase 0):**
- Does multi-turn conversation work on E4B with our prompt patterns? If broken, the spec rebuilds.
- Is tool-calling reliable enough on E4B for a single agentic demo beat? Decides whether agentic feature ships.

## Timeline — Ordered Phases

The challenge deadline is 2026-05-24, 23:59 PDT. The user is working near-full-time across the period. Phases run in order; later phases assume earlier phases pass their gates.

### Phase 0 — Validation gates
*Nothing else starts until Phase 0 passes.*

1. Multi-turn conversation works on E4B with our prompt patterns (existential gate)
2. Tag extraction is consistent across 10+ varied test dumps
3. Audio chunking across 30s boundaries doesn't lose context
4. Tool-calling reliability check on E4B with a single stub function (decides whether the agentic feature ships)
5. EmbeddingGemma comparison on a prepared vocabulary-drift dataset: visibly better than tag-only? (decides whether embeddings ship)

**Outcome:** go/no-go on agentic beat; ship/cut decision on embeddings; confirmation that the core conversation loop works at all.

### Phase 1 — Architecture and scaffold
1. Repo structure, module boundaries, `CLAUDE.md` guardrails referencing the scope rule
2. LiteRT-LM integration, model loading, inference smoke test
3. AudioRecord pipeline + 30s chunking
4. ObjectBox schema for entries, tags, patterns
5. Markdown source-of-truth read/write
6. Persona prompt scaffold (Detective only; Hardass and Editor stubbed)

### Phase 2 — Core capture loop
1. One template wired end-to-end (Day-shaped hole): record → probe → respond → save
2. Multi-turn session state preservation
3. Tag extraction during entry, visible per entry
4. Add Aftermath template
5. Add Goblin hours template (different prompt path)
6. Streaming response UI for inference latency

### Phase 3 — Memory and patterns
1. Hybrid retrieval implementation (keyword + tags + recency)
2. Embedding layer integration if Phase 0 step 5 passed
3. Pattern detection runs at end of session
4. Patterns persist in their own list
5. Patterns view (basic list, no actions yet)

### Phase 4 — UX surface
1. Onboarding flow with model download UX
2. Session UI polish (record button, chunk indicator, conversation transcript)
3. History list (basic browse)
4. Settings screen stub (persona default, data export, delete all)
5. Dark mode design language pass — color, type, spacing
6. Empty states for major screens
7. Top three error states (download fail, inference timeout, mic denied)
8. P1: Hardass and Editor personas, persona switcher

### Phase 5 — Demo optimization
1. Sample data set for demo, curated to show bite, patterns, embedding advantage if applicable
2. Demo storyboard (90s pitch + 5-min walkthrough)
3. P1: Single agentic tool-call beat if Phase 0 step 4 passed
4. Demo dry runs on the reference device

### Phase 6 — Submission package
1. Demo recording (raw takes)
2. Video editing with chapter markers
3. Dev.to post body
4. README final pass
5. APK signing and GitHub release
6. App icon + cover image
7. Final QA on the reference device + at least one external device

### Phase 7 — Buffer
1. Bug fixes from Phase 6 QA
2. Any P1 features that fit
3. Final polish on demo and post
4. Submit before 2026-05-24, 23:59 PDT

## Scope Discipline (mandatory for AI agents working on this build)

This is a 17-day build aimed at winning a $500 prize, not a perfectionist long-tail product. Decision criterion for any change request:

> Does it visibly improve the 90-second pitch or the 5-minute technical walkthrough?

If no → reject, log to `v1.5-backlog.md`, respond: *"this doesn't help us win, deferring to v1.5."* Treat the locked spec as authoritative. Reference the P2 list before accepting feature additions or polish requests. Polish invisible in the demo is deferred.

## Authoritative documents

- `concept-locked.md` — full product spec
- `runtime-research.md` — Android Gemma 4 deployment research
- `challenge-brief.md` — challenge rules and judging criteria
- `ai-context.md` — AI context (high-level project state)
- `PRD.md` — this file
- `architecture-brief.md` — module breakdown, build plan, interface contracts (to be written)
- `v1.5-backlog.md` — features deferred to post-submission polish (to be created during Phase 0+)
