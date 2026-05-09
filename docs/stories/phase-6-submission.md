# Phase 6 — Submission Package

**Status:** Not started
**Dates:** TBD — kicks off after Phase 5 dry runs are clean
**References:** `PRD.md` §Phase 6, `concept-locked.md`, `design-guidelines.md`, `ux-copy.md`, `challenge-brief.md`, `blog-template.md`, `AGENTS.md`, `adrs/ADR-001-stack-and-build-infra.md` §Q5 (signing)

---

## Goal

Produce the four deliverables the submission requires: the **APK** (signed release, sideload-ready), the **demo video** (90-second pitch + 5-minute technical walkthrough with chapter markers, including a tcpdump privacy-proof clip), the **dev.to post** (following `blog-template.md`, with required tags `devchallenge`, `gemmachallenge`, `gemma`), and the **GitHub release** with a polished README. By the end of Phase 6, the submission is filed.

**Output of this phase:** the complete submission package, filed on dev.to before 2026-05-24, 23:59 PDT, with the APK linked from the post.

---

## Phase-level acceptance criteria

- [ ] Final signed release APK installs cleanly on the reference S24 Ultra and at least one secondary 2024+ flagship Android.
- [ ] tcpdump privacy-proof clip recorded showing zero outbound traffic during a normal capture session after model download completes.
- [ ] 5-minute demo video edited with chapter markers, including the tcpdump clip as a chapter.
- [ ] Dev.to post drafted following `blog-template.md`, with the canonical tagline, hook, and explicit acknowledgment of any STT outcomes that shaped the v1 build.
- [ ] GitHub release published with the APK as a release asset and the polished `README.md` at the repo root.
- [ ] App icon and cover image designed and integrated.
- [ ] Final QA on reference device + secondary device passes.
- [ ] Submission filed on dev.to with required tags, before deadline.

---

## Stories

### Story 6.1 — Demo recording (raw takes)

**As** the demo recorder, **I need** raw screen recordings of the 90-second pitch and the 5-minute technical walkthrough on the reference S24 Ultra following the `demo-storyboard.md` script, **so that** Story 6.3 has clean source material to edit.

**Done when:**
- [ ] At least 2 full takes of the 90-second pitch recorded on the reference device using the device's built-in screen recorder (Galaxy S24 Ultra has it natively per earlier discussion).
- [ ] At least 2 full takes of the 5-minute walkthrough recorded.
- [ ] Audio is captured cleanly — narration is recorded separately on a quality microphone, not via the phone speaker (phone audio is for in-app demonstration of voice-in, not narration capture).
- [ ] Screen recordings show the polished UX from Phase 4 — no half-finished states.
- [ ] Sample data is loaded from Phase 5's loader before each take.
- [ ] At least one take per video has the timing and beats matching `demo-storyboard.md`.

**Notes / risks:** Phone-side screen recorder may include ambient phone audio. Do narration on a separate mic and overlay during edit. Don't record narration through the phone — it sounds like a phone call.

---

### Story 6.2 — Privacy proof clip (tcpdump)

**As** the demo recorder, **I need** a chapter-of-the-video clip showing a normal capture session running on the reference device while `tcpdump` (or equivalent) on a connected machine shows zero outbound packets after model download completes, **so that** the privacy claim ("zero outbound network calls during normal operation") is demonstrated visually rather than just asserted.

**Done when:**
- [ ] The reference device is connected (USB debugging or via a controlled Wi-Fi network) so packet capture can observe its outbound traffic.
- [ ] `tcpdump` (or Wireshark, or equivalent) is running and recording outbound traffic from the device's IP.
- [ ] A capture session is performed on the device (record → transcription → background extraction → save).
- [ ] Packet capture shows zero outbound traffic from the app's process after the model download completed in onboarding.
- [ ] The clip shows both the device screen and the packet capture window simultaneously (split screen or picture-in-picture in the final edit).
- [ ] Clip duration: 30–60 seconds. Long enough to demonstrate, short enough to keep the chapter tight.

**Notes / risks:** Per `AGENTS.md` guardrail and `PRD.md` §"Privacy claim", the only network event expected is the one-time model download. If anything else shows up in tcpdump, debug before recording — don't show a clip with unexpected packets and try to explain them away.

---

### Story 6.3 — Demo video editing with chapter markers

**As** the demo recorder, **I need** the raw takes edited into a single 5-minute video with chapter markers per `PRD.md` §Phase 6, **so that** judges can scrub between chapters and the dev.to post can deep-link to specific timestamps.

**Done when:**
- [ ] Final video is between 4:30 and 5:30 minutes total.
- [ ] Video opens with the 90-second pitch as the first chapter.
- [ ] Subsequent chapters follow `demo-storyboard.md` §"5-minute technical walkthrough" structure.
- [ ] tcpdump privacy-proof clip from Story 6.2 is embedded as its own chapter.
- [ ] Chapter markers are included in the video (YouTube native chapters or equivalent — depending on hosting decision in Story 6.5).
- [ ] Audio mixing is clean: no clipping, narration audible over any in-app audio, no abrupt cuts.
- [ ] Video format is appropriate for dev.to embed and GitHub linking (likely MP4 H.264).
- [ ] Cover image (Story 6.7) is set as the video thumbnail.
- [ ] No music in v1 unless it explicitly serves the brand (atmospheric/walking-through-mist) without distracting from the technical content. Default: no music. Add only if it lands.

**Notes / risks:** Edit twice: a fast cut to verify the structure works, then a polish pass for transitions and audio levels. If you only edit once, the polish suffers.

---

### Story 6.4 — Dev.to post body

**As** the AI implementor, **I need** the dev.to post drafted following `blog-template.md`, with the canonical tagline and hook from `concept-locked.md`, the model-choice argument grounded in actual STT outcomes from Phases 1–3, and the required submission tags, **so that** the post lands the judging criteria and the submission is valid.

**Done when:**
- [ ] Post follows the structure in `blog-template.md`.
- [ ] Hook from `concept-locked.md` opens the post: *"I built a brain tracker that doesn't blow smoke up your ass."*
- [ ] Tagline from `concept-locked.md` lands in the lede or near it: *"Vestige (n.) — a trace, mark, or visible evidence of something no longer present. Your brain keeps leaving traces. This app catches them."*
- [ ] **Model-choice section** answers the judging criterion: which Gemma 4 variant (E4B), why it was chosen (native audio multimodal as the headline), and what STT outcomes shaped the architecture. If STT-D failed and multi-lens dropped, this is acknowledged honestly — *"We bet on multi-lens convergence; the architecture didn't earn its keep on the size of data we tested. Single-pass extraction shipped, with the multi-lens design moving to v1.5"* — judges respect honest engineering more than fabricated claims.
- [ ] **Privacy section** explains the on-device claim, references the tcpdump clip, and uses the headline "Your voice never leaves the device."
- [ ] **Brand section** uses the in-app "dump" / capture vocabulary only when intentionally quoting microcopy. Public-facing copy uses "voice entry," "capture," "cognitive event" per the README's brand-legibility rule.
- [ ] **What's deferred** section briefly mentions v1.5 / v2 backlog highlights (handle, TTS, video input, audio retention, Reading screen if cut, Roast bottom sheet if cut, agentic tool-calling, etc.) — framed as roadmap, not apology.
- [ ] Required dev.to tags: `devchallenge`, `gemmachallenge`, `gemma`.
- [ ] Submission category: `Build with Gemma 4` only (one of five $500 + DEV++ + badge slots).
- [ ] APK download link points to the GitHub release from Story 6.6.
- [ ] Demo video embedded or linked from Story 6.3.
- [ ] At least one architecture diagram or screenshot per `design-guidelines.md` (if Mermaid renders on dev.to natively, use it; otherwise PNG).

**Notes / risks:** The "intentional and effective use of the chosen model" criterion is answered by what the model actually did, not what we wished it did. If multi-lens dropped, the post is more honest if it says so — and it's still a winning entry because the audio multimodal use is intentional and the privacy story is real.

---

### Story 6.5 — README final pass

**As** the AI implementor, **I need** the repo's `README.md` (the GitHub-facing one, not `docs/README.md`) polished into a public-facing pitch with installation, screenshots, the architecture summary, the model-choice rationale, and the known limitations, **so that** anyone who lands on the GitHub repo from the dev.to post or the demo video gets a coherent picture in 60 seconds.

**Done when:**
- [ ] Repo root `README.md` exists (separate from `docs/README.md` which is the CLI handoff doc — the public README points at `docs/` for engineers but is itself audience-facing).
- [ ] Title + tagline + hook (matching `concept-locked.md`).
- [ ] Demo video embedded or linked.
- [ ] **Quickstart**: how to install the APK on Android. Min spec callout (Android 14+, 8 GB RAM, 6 GB free storage).
- [ ] **Architecture summary**: the four-module split, the multi-lens pipeline (or single-pass if STT-D failed), the embedding layer (or absence if STT-E failed). One Mermaid diagram showing data flow.
- [ ] **Model-choice rationale**: matching the dev.to post's section.
- [ ] **Privacy claim**: "Your voice never leaves the device" + the tcpdump-clip reference.
- [ ] **Known limitations**: what didn't ship and why. Honest.
- [ ] **Acknowledgments**: Anthropic Claude for the spec/design pairing (per the dev.to challenge being a hackathon), Google for Gemma 4 and LiteRT-LM, ObjectBox, etc.
- [ ] **License**: MIT or Apache 2.0 (pick one — Apache 2.0 matches Gemma 4's license and is the safer default for "this app uses Gemma" attribution).
- [ ] Screenshots from the polished UX in Phase 4: at minimum the capture screen, a pattern detail, and the local model status surface.

**Notes / risks:** The repo README is the lasting artifact. The dev.to post has a publish date and falls off the front page in days; the GitHub README is what people find six months from now. Make it a real README, not a "submission landing page."

---

### Story 6.6 — Final signed release APK + GitHub release

**As** the demo recorder, **I need** the final signed release-variant APK installed and verified on the reference device + at least one secondary device, then published as a GitHub release asset, **so that** the dev.to post has a working download link and judges can sideload the app if they want.

**Done when:**
- [ ] Final release-variant build with the latest commits passes through the same signing pipeline validated in Story 1.11.
- [ ] APK installs cleanly on the reference S24 Ultra. Onboarding runs end-to-end. Capture session works. Pattern detection runs. No regressions vs Phase 5's dry-runs state.
- [ ] APK installs cleanly on at least one secondary 2024+ flagship Android (Pixel 8/9 Pro, Galaxy S23+/S24, etc.). Same smoke test passes — onboarding, capture, pattern detection, settings.
- [ ] APK is signed with the release keystore (not the debug keystore — debug-signed APKs install but don't carry the integrity signal users expect for sideload).
- [ ] GitHub release is created with a version tag (e.g., `v1.0.0`).
- [ ] APK is uploaded as a release asset.
- [ ] Release notes summarize: what's in v1, what's deferred to v1.5, the min device spec, install instructions.
- [ ] SHA-256 of the APK is included in release notes for integrity verification.

**Notes / risks:** The GitHub release URL is what the dev.to post links. Don't change the tag or asset filename after publishing the post — broken links during judging are unforced errors.

---

### Story 6.7 — App icon and cover image

**As** the AI implementor, **I need** the app icon (rendered per `design-guidelines.md` §"App icon" — a single partial footprint dissolving into mist at its edges) and a cover image for dev.to / video thumbnail, **so that** the visual identity is consistent across all touchpoints.

**Done when:**
- [ ] App icon is designed per `design-guidelines.md` §"App icon": single partial footprint, fading at edges, outline-only or with a subtle gradient from cool white to nothing.
- [ ] Icon is rendered in the standard Android adaptive-icon sizes (foreground + background layers).
- [ ] Icon ships in the final APK from Story 6.6.
- [ ] Cover image (1200×630 for dev.to, video thumbnail aspect for the demo video) uses the locked palette: deep blue-black background, the partial footprint motif, the title `Vestige`, and the tagline (or the hook if the tagline is too long for the layout).
- [ ] No purple or blue in the icon itself unless they earn the moment per `design-guidelines.md` §"Where each accent lives" (icon is generally a low-saturation moment; the accent lives in-app where the user is acting).
- [ ] Cover image embedded in the dev.to post (Story 6.4) and used as the video thumbnail (Story 6.3).

**Notes / risks:** The icon is one of the four things every install begins with. A generic icon undercuts every other piece of brand work. The forensic-procedural / wellness-app traps from `design-guidelines.md` §"Iconography — wrong register" still apply: no fingerprints, no magnifiers, no brains, no leaves, no clipboards.

---

### Story 6.8 — Final QA + submission file

**As** the AI implementor, **I need** a final smoke-test pass on the reference device + secondary device with the submitted APK, the demo video reviewed end-to-end, the dev.to post proofread, and then the submission filed on dev.to, **so that** the submission is valid before deadline.

**Done when:**
- [ ] Reference device fresh-install of the GitHub release APK: onboarding completes, capture works, pattern detection runs, settings work, export works, delete-all works.
- [ ] Secondary device fresh-install of the GitHub release APK: same smoke test passes.
- [ ] Demo video watched end-to-end at least once for tone, pacing, and any forgotten edits.
- [ ] Dev.to post proofread for: correct hook + tagline, correct STT outcomes acknowledged, correct submission category (Build with Gemma 4), correct tags (`devchallenge`, `gemmachallenge`, `gemma`).
- [ ] APK download link verified.
- [ ] Demo video link verified.
- [ ] GitHub repo link verified.
- [ ] Post submitted on dev.to before **2026-05-24, 23:59 PDT**.

**Notes / risks:** The single-most-important time check: don't aim for 23:59. Aim for 18:00. Submitting at 23:55 means a 5-minute window for any technical hiccup with dev.to itself.

---

## What is explicitly NOT in Phase 6

- No new product features. Phase 6 is the submission package — not the place to fix bugs unless they're show-stoppers in QA.
- No backlog grooming. The post mentions deferred features as roadmap, not as items being worked on.
- No retroactive spec edits to make the build look prettier than it shipped. Document what shipped, honestly.

If a Phase 6 story surfaces a show-stopping bug, file it as a Phase 7 buffer task and address before submitting. Don't ship a broken APK because the deadline forced it.

---

## Phase 6 exit checklist

Submission is complete when all the following are true:

- [ ] Stories 6.1 – 6.8 are Done.
- [ ] Dev.to post is published, not in draft, with the correct submission category and tags.
- [ ] APK is downloadable from the GitHub release.
- [ ] Demo video plays from the dev.to post embed and from any external link.
- [ ] Submission deadline `2026-05-24, 23:59 PDT` has not passed yet — and ideally, several hours of buffer remain.
