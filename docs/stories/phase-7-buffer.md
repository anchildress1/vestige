# Phase 7 — Buffer

**Status:** Not started
**Dates:** TBD — runs in parallel with Phase 6 if needed, or absorbs schedule slack from any phase that finished early
**References:** `PRD.md` §Phase 7, `concept-locked.md`, `backlog.md`, `AGENTS.md`

---

## Goal

Absorb whatever the previous phases didn't finish, fix bugs surfaced during Phase 5 dry runs and Phase 6 QA, and ship any P1 features that fit in remaining time. Phase 7 is **not a feature-development phase**. It is a triage phase. The scope rule applies harder here than anywhere else.

**Output of this phase:** a submitted, working v1 build. Or, if scope was already clean, an unused buffer that proves the schedule was honest.

---

## Phase-level acceptance criteria

- [ ] All show-stopping bugs from Phase 5 dry runs and Phase 6 QA are addressed.
- [ ] At least one final dry run of the demo on the reference device after any bug fixes.
- [ ] Any P1 stories from earlier phases that fit in remaining time are shipped (or explicitly punted to v1.5 with a logged backlog entry).
- [ ] No new P0 work introduced. Phase 7 is for closing, not for opening.
- [ ] Submission filed on dev.to before deadline (Story 6.8).

---

## Stories

### Story 7.1 — Triage and fix Phase 5/6 show-stoppers

**As** the AI implementor, **I need** every Phase 5 dry-run regression and Phase 6 QA failure addressed before final recording or final QA pass, **so that** the submitted APK doesn't ship a known-broken state.

**Done when:**
- [ ] Every issue logged during Phase 5 dry runs (Story 5.3) is either fixed, accepted as a known limitation and documented in `README.md` §"Known limitations", or moved to v1.5 backlog with a rationale.
- [ ] Every issue logged during Phase 6 QA (Story 6.8) is either fixed before final submission or rolled back (revert the change that introduced the regression).
- [ ] Each fix is verified on the reference device with the affected scenario re-run.
- [ ] No bug fix introduces a new show-stopper (no panic-fix that breaks something else).

**Notes / risks:** Resist the urge to refactor while fixing. Phase 7 is for *minimal* fixes. If a fix requires a refactor, the refactor is v1.5 work; ship the minimal patch and log the cleanup as `tech-debt` in `backlog.md`.

---

### Story 7.2 — P1 features that fit

**As** the AI implementor, **I need** any P1 feature from Phases 1–4 that didn't ship in its phase to either land here (if it fits in remaining time and visibly improves the demo) or be explicitly punted to v1.5, **so that** P1 scope decisions are deliberate rather than defaulted.

**Done when:**
- [ ] Each P1 story not-yet-Done from earlier phases (Stories 4.12 / 4.13 / 4.14, plus any that slipped) is reviewed against time remaining.
- [ ] For each P1 story, the decision is made: **ship now** (sufficient time + visibly improves demo), **defer to v1.5** (logged in `backlog.md` with rationale).
- [ ] Decisions are recorded — `backlog.md` gets entries for any deferred P1.
- [ ] Shipped P1 stories are tested in the final dry run before recording (or if recording is already done, on the reference device + secondary device).

**Notes / risks:** Don't ship a P1 that's not been tested. A half-finished Reading screen is worse than no Reading screen. Cut > ship-broken every time.

---

### Story 7.3 — Final polish on demo and post

**As** the demo recorder, **I need** the demo video and dev.to post reviewed once more after any Phase 7 fixes or P1 ships, **so that** the final state of the v1 build is what the submission shows — not the state two days ago when recording happened.

**Done when:**
- [ ] If any Phase 7 fix changed user-facing behavior shown in the demo, the affected demo segment is re-recorded and re-edited (or the post copy is updated to acknowledge the difference).
- [ ] If any P1 story shipped in Phase 7, the post and video are updated to reflect it (or the cuts are reversed if they no longer apply).
- [ ] Final pass through the dev.to post draft for: typos, broken links, missed STT outcomes, accidental forbidden copy from `ux-copy.md` §"Things to NEVER Write".
- [ ] Final pass through the GitHub README for the same.

**Notes / risks:** Re-recording is expensive. If the Phase 7 fix is small (a microcopy correction, a color tweak), update the post copy rather than re-recording. Re-record only if the change is visible in the demo and material to the technical narrative.

---

### Story 7.4 — Submit

**As** the demo recorder, **I need** the submission filed on dev.to before the deadline with all the right tags, links, and the right submission category, **so that** the entry counts.

**Done when:**
- [ ] Per Story 6.8 — final QA pass complete.
- [ ] Dev.to post published with `devchallenge`, `gemmachallenge`, `gemma` tags and the `Build with Gemma 4` submission category.
- [ ] APK link verified live.
- [ ] Demo video plays from the post.
- [ ] GitHub repo public and findable.
- [ ] Submitted **before 2026-05-24, 23:59 PDT** with at least a few hours of buffer ideally.

**Notes / risks:** This is the same as Story 6.8's final action. If Story 6.8 already submitted, this story is a no-op (mark Done). If Phase 7 absorbed a bug fix that delayed Phase 6's submission step, this is where it lands.

---

## What is explicitly NOT in Phase 7

- No new product features beyond previously-scoped P1.
- No new architectural decisions. ADRs don't get added in Phase 7. If something needs an ADR, it's v1.5 work.
- No "while we're at it" cleanup. Tech debt is `backlog.md` material.
- No re-recording the entire demo unless a show-stopper required it. Surgical re-records only.
- No dev.to category changes (Build with Gemma 4 only, locked since concept).

---

## Phase 7 exit checklist

The build is shipped when all the following are true:

- [ ] Stories 7.1 – 7.4 are Done.
- [ ] Submission posted on dev.to before deadline.
- [ ] APK works on at least two devices.
- [ ] No known show-stopping bugs in the released APK.
- [ ] `backlog.md` reflects every cut feature with rationale and unblock-condition.
- [ ] Ashley closes the laptop, ideally with hours of buffer to spare, and the v1 build is in the world.

After this: post-submission backlog work begins, but that's outside the v1 phase plan.
