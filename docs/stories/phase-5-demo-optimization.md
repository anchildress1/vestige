# Phase 5 — Demo Optimization

**Status:** Not started
**Dates:** TBD — kicks off after Phase 4 exits with the polished UX surface in place
**References:** `PRD.md` §Phase 5, `concept-locked.md`, `design-guidelines.md`, `ux-copy.md`, `challenge-brief.md`, `sample-data-scenarios.md`, `AGENTS.md`

---

## Goal

Lock the demo. Curate the sample data set the recording will use, write the demo storyboard (90-second pitch + 5-minute technical walkthrough), and run dry runs on the reference device until the timing, beats, and STT outcomes are reproducible. By the end of Phase 5, the app is *ready to film* — Phase 6 is recording, editing, and submission package assembly.

**Output of this phase:** a tested, repeatable demo flow on the reference S24 Ultra. Sample data populated. Storyboard committed to `demo-storyboard.md` (new file). Dry runs verify the demo lands in the time budget and the magic moments hit.

---

## Phase-level acceptance criteria

- [ ] Demo sample data is loaded onto the reference device and produces the intended pattern callouts on cue.
- [ ] `demo-storyboard.md` exists with the 90-second pitch beats and the 5-minute technical walkthrough beats per `PRD.md` §Phase 5.
- [ ] At least three full dry runs of the 5-minute walkthrough on the reference device complete without app glitches, model timeouts, or off-script moments.
- [ ] All STT outcomes from Phases 1–3 are reflected in the storyboard (e.g., if STT-D failed and multi-lens dropped, the storyboard does not include the "Reading" debug screen beat).
- [ ] The 90-second pitch script is tight, on-brand per `concept-locked.md` and `design-guidelines.md`, and uses language from `ux-copy.md` where in-app copy appears on screen.

---

## Stories

### Story 5.1 — Demo sample data set

**As** the demo recorder, **I need** a curated sample data set on the reference device that produces the demo's pattern callouts and persona moments reliably, **so that** dry runs and the final recording show the magic moments instead of random output.

**Done when:**
- [ ] Sample entries are seeded into the reference device's ObjectBox + markdown source-of-truth via a dev-only data loader (not via the onboarding flow — the user is presumed to have already onboarded).
- [ ] The seeded entries collectively trigger at least one polished cross-entry pattern (e.g., the canonical "Tuesday meetings → Aftermath, 4 of 12 entries" example from `design-guidelines.md` §"Pattern card / Example").
- [ ] If STT-E passed and embeddings shipped, the seeded data includes vocabulary-drift entries so the embedding advantage is observable in the technical walkthrough.
- [ ] Sample entries respect the brand: behavioral vocabulary, not feelings vocabulary; the model's responses on these entries land Witness/Hardass/Editor tones cleanly.
- [ ] Loader is idempotent — running it twice produces the same final state, not duplicate entries.
- [ ] Loader is **not shipped in release builds**. Per `AGENTS.md` and the scope rule, demo data is dev-only; the submitted APK has a fresh install state.
- [ ] Sample data narrative is documented in `sample-data-scenarios.md` §"Demo set" (a new section, distinct from the STT validation scenarios).

**Notes / risks:** Don't seed the sample data into the demo APK as fake user history. Judges installing the APK should see `Nothing on file.` per `ux-copy.md` §"Empty States". The demo recording uses a separate dev build with seeded data; the submission is the clean release build.

---

### Story 5.2 — Demo storyboard

**As** the AI implementor (and Ashley as the demo recorder), **I need** a written `demo-storyboard.md` that locks the 90-second pitch beats and the 5-minute technical walkthrough beats — what shows on screen, what's said, what surface is highlighted, what STT outcomes are demonstrated — **so that** the final recording is a repeatable script rather than improvised tour.

**Done when:**
- [ ] `demo-storyboard.md` exists at `docs/` root.
- [ ] **90-second pitch section** breaks the pitch into beats with timing budgets:
  - 0:00–0:10 — Opening hook (the bite + the persona voice).
  - 0:10–0:30 — Voice in moment (Scoreboard "ON AIR" record button engaging, `sbBars` audio meter, `TickRule` 30s countdown, transcription appearing — per ADR-011 + `poc/Energy Direction.html`).
  - 0:30–0:50 — Pattern callout moment (a real surfaced pattern with sourced evidence).
  - 0:50–1:10 — Privacy proof beat (Local Model Status + a brief tcpdump window showing zero outbound during a session).
  - 1:10–1:30 — Brand moment + tagline + GitHub link.
- [ ] **5-minute technical walkthrough section** breaks into chapters with timing:
  - Architecture overview (the four-module split, where Gemma 4 sits).
  - Capture loop (foreground call returning transcription + follow-up).
  - Multi-lens extraction beat — **only if STT-D passed and Story 4.13 (Reading) shipped**. If STT-D failed, this chapter shows single-pass extraction with the cut explicitly mentioned.
  - Pattern detection (algorithm + persistence + lifecycle).
  - Privacy proof (tcpdump clip from Phase 6 Story 6.2).
  - Closing — what's deferred to v1.5 (the backlog as a feature, not an apology).
- [ ] Storyboard references the locked screens and copy from `design-guidelines.md` and `ux-copy.md` — no improvised microcopy in the demo.
- [ ] Storyboard explicitly notes which sample data entries are used in which beat.
- [ ] Storyboard accommodates the actual STT outcomes — no beats that depend on cut features.

**Notes / risks:** The 5-min walkthrough is the technical hero piece. It's where the dev.to judging criterion "intentional and effective use of the chosen Gemma 4 model" is answered. Multi-lens convergence is the strongest "intentional use" beat if STT-D passed; native audio multimodal is the always-true headline.

---

### Story 5.3 — Demo dry runs on the reference device

**As** the demo recorder, **I need** at least three full back-to-back dry runs of the 5-minute walkthrough on the reference device, **so that** I know the timing holds, the model's responses are reproducible enough to script around, and the failure modes (if any) surface in dry runs and not the final recording.

**Done when:**
- [ ] Three full 5-minute dry runs are completed on the reference S24 Ultra with the seeded sample data from Story 5.1.
- [ ] Each dry run is timed and notes are recorded: where it ran long, where it ran short, where the model produced unexpected output.
- [ ] Off-script moments (model produces wildly different responses on the same input) are addressed: tighten prompts, swap the seeded entry, or rework the beat to not depend on a specific phrase.
- [ ] Latency is within budget — foreground call returns within 1–5s, background extraction completes within 30–90s as documented.
- [ ] Battery and thermal are checked — three dry runs back-to-back don't throw thermal throttling or kill the demo battery.
- [ ] If a dry run fails (app crashes, navigation breaks, model errors), the failure is filed as a Phase 7 buffer task (`stories/phase-7-buffer.md` when written) and addressed before recording.

**Notes / risks:** Save dry runs for *after* Story 5.1 and 5.2 are stable. Dry-running against a moving storyboard wastes the budget. Time-box dry runs to a single afternoon — if the third run still has show-stoppers, pause and fix rather than continuing dry runs.

---

### Story 5.4 — Sample-data narrative + ADR record

**As** the AI implementor, **I need** the sample-data narrative documented in `sample-data-scenarios.md` §"Demo set" with explicit per-entry text, expected pattern outputs, and the persona behavior expected on each beat, **so that** the demo can be reproduced months from now (post-submission, when polishing v1.5) without losing the canonical demo state.

**Done when:**
- [ ] `sample-data-scenarios.md` has a "Demo set" section listing each seeded entry's: ID, text, template label, tags, energy_descriptor, the expected per-entry observation, and the cross-entry pattern it contributes to.
- [ ] The expected output for each demo beat is written down (e.g., "Tap Roast me on the patterns list → Witness produces a roast that mentions Tuesday meetings and the body-count line").
- [ ] If any expected output isn't reliably reproducible after Story 5.3's dry runs, the entry is reworked or the beat is adjusted in `demo-storyboard.md`.
- [ ] An ADR addendum is **NOT** required — sample data is a build artifact, not an architecture decision. But the demo storyboard's pattern outcomes inform Phase 6's recording shotlist (Story 6.1).

**Notes / risks:** This is what makes the demo reproducible if the recording has to be reshot. The day before submission is the wrong time to discover the sample data isn't documented.

---

## What is explicitly NOT in Phase 5

- No final recording. Phase 6 owns the capture.
- No video editing. Phase 6.
- No dev.to post body. Phase 6.
- No app icon / cover image. Phase 6.
- No new product features. Phase 5 is demo-prep only.
- No backlog grooming or v1.5 work. The point of Phase 5 is to lock the v1 demo, not start v1.5.
- No "demo mode" toggle in the app. Sample data loader is dev-only; the production APK is clean.

If a Phase 5 story starts adding product features or pushing into Phase 6, stop. Reference the scope rule.

---

## Phase 5 exit checklist

Phase 6 starts when all the following are true:

- [ ] Stories 5.1 – 5.4 are Done.
- [ ] `demo-storyboard.md` is committed and reflects actual STT outcomes from Phases 1–3.
- [ ] Sample data narrative in `sample-data-scenarios.md` §"Demo set" is committed.
- [ ] Three dry runs on the reference device completed without show-stoppers.
- [ ] Battery and thermal verified — the device can sustain a 5-minute capture session at demo intensity.
- [ ] No new entries logged to `backlog.md` from Phase 5 that change the v1 contract.

If a dry run produced a show-stopper that requires a Phase 1–4 fix, log it as a Phase 7 buffer task and address before Phase 6 recording.
