# Vestige — Design Guidelines

For consumption by Claude Design (Anthropic's prompt-to-prototype tool) and other design AIs producing visual mockups, microcopy, iconography, and component direction.

This document is design-only. Engineering, runtime, and model details live in `concept-locked.md` and `PRD.md` — out of scope for mockups.

---

## Product Frame

Vestige is an on-device cognition and pattern-tracking app for ADHD-flavored adults.

It is **not**:
- a wellness journal
- a therapy app
- a generic AI chat app

Vestige observes behavioral traces over time and surfaces patterns without performing emotional validation, diagnosis, or motivational coaching.

**Core feeling:** Quiet. Observational. Local. Precise. Slightly biting in the words. Restrained in the visuals.

**Core metaphor:** A trace left behind.

**Use as visual vocabulary:**
- partial footprints
- worn impressions
- sediment layers
- ghosted marks
- negative space
- faint residue
- mist
- pressure left in a surface

**Avoid as visual vocabulary:**
- brains, hearts, journals, suns, mountains, water (wellness app)
- sparkles, mascots, badges, streaks (gamification)

---

## Target Output

Produce one cohesive Android mobile design direction for the following screens:

1. Capture screen
2. First-run onboarding (sequential — see screen spec for step list)
3. Local model status screen (persistent, not just onboarding)
4. Persona selector
5. Pattern list
6. Pattern detail
7. The Roast (P1 user-initiated deep pattern surface; design direction exists, but it does not block P0)
8. Error state
9. Destructive confirmation

Plus:
- One app icon direction (with 2 alternates)
- Core component styling (`MistHero`, transcript turn, pattern card with `TraceBar`, persona toggle, `AppShellTop` status pill — see `poc/design-review.md` §3 for primitives)
- Embedded microcopy examples per screen

---

## Refinement Sliders

Three knobs for design iteration. Each defaults to roughly center; nudge based on context.

- **Atmospheric ↔ Utilitarian** — how much mood vs. how much pure-function
- **Sharper copy ↔ Quieter copy** — how much bite vs. how much restraint in the words
- **Denser evidence ↔ Cleaner capture-first** — how much information per screen vs. how much breathing room

Pattern detail and Roast lean denser. Capture screen leans cleaner. Onboarding leans utilitarian (one decision per screen). Roast is P1; do not let it pull oxygen from capture, storage, patterns, or the privacy proof.

---

## Challenge-Facing Requirement

A judge should understand within **10 seconds** that Vestige is a **local AI cognition/pattern app**, not a normal journaling app.

Show this visually through:
- visible local model status
- pattern cards with source counts
- persona labels in the transcript
- capture-first layout
- restrained, on-brand inference/loading states
- evidence behind every pattern claim

Do not:
- explain model architecture
- invent backend behavior
- over-design technical diagrams
- produce a marketing site, only the app

---

## Visual System

### Source of truth

The canonical visual system — color tokens, typography, primitives, atmospheric layers — lives in **`../poc/design-review.md`** alongside the JSX reference and the screenshots in `../poc/screenshots/`. Token values mirror below for quick reference; if anything diverges, `design-review.md` wins.

### Palette tokens (from `poc/design-review.md` §2.1)

| Token | Value | Use |
|---|---|---|
| `void` / `deep` | `#0A0E1A` | Page / device floor |
| `bg` | `#0E1124` | Deep surface |
| `s1` | `#161A2E` | Card base |
| `s2` | `#1E2238` | Raised / interactive |
| `s3` | `#2A2E48` | Hover / pressed |
| `ink` | `#E8ECF4` | Primary text |
| `mist` | `#7B8497` | Secondary text |
| `glow` | `#A855F7` | Purple — identity / pattern / depth |
| `vapor` | `#2563EB` | Blue — active recording / focus |
| `pulse` | `#38A169` | Ready status dot |
| `error` | `#B3261E` | Destructive actions only |

Dark only — no light theme in v1.

### Color rules

- Never pure black (`#000000`) — vibration, harder for astigmatic users.
- Never pure white text on dark — softens visual fatigue.
- WCAG AA minimum (4.5:1) for body text. AAA target (7:1) for primary content.
- `glow` and `vapor` are rare punctuation in their own domains. Neither becomes wallpaper.
- Each accent owns a semantic role: **`glow` = identity / pattern / depth**; **`vapor` = active / interaction / recording state**.
- Both accents *may* appear in the same component **when they carry different roles on different elements.** Example: a pattern card with a `glow` left-rule (identity) and a `vapor` focus ring when keyboard-focused (interaction state). Example: a Roast sheet with a `glow` header and a `vapor` active-recording indicator inside.
- **Forbidden:** a single element rendered in both colors. No `glow → vapor` gradients, no half-and-half buttons, no two-tone icons. One element, one accent at a time.
- **Forbidden:** the same semantic role rendered in different colors on the same screen. Two "active patterns" on one screen are both `glow`; don't alternate for variety.
- No gradients between accents. No additional glows or halos beyond the atmospheric layer defined in `design-review.md` §2.4.

### Where each accent lives

**`glow` — primary accent. Patterns, identity, depth.**
- Active pattern indicator (`glow` left-rule on cards with active patterns)
- `Roast me` action button + Roast bottom-sheet header (per `Sheet` primitive in `design-review.md` §3.1)
- Currently-active persona pill in the persona selector
- Pattern detail screen accent

**`vapor` — active accent. Recording, focus, "this is on."**
- Active recording state on `MistHero` (per `design-review.md` §3.3)
- Live audio waveform tint via `AudioMeter` during recording
- Selected/focused control (subtle ring on focus, not on default selection)
- Links and ghost button outlines

**`pulse` — ready-status dot only.**
- `LOCAL · READY` dot on `AppShellTop` per `design-review.md` §3.2 (`modelState=ready`).
- No other use. The dot glows on idle-ready; it does not appear elsewhere.

Everything else stays in the cool blue-gray atmosphere of `void` / `bg` / `s1` / `s2` / `s3`.

**`error` — destructive actions only.**
- Delete entry, delete all data, delete model, and unrecoverable wipe confirmations use the `error` token, not `glow`.
- `glow` is identity/depth. It is not a delete affordance. A destructive button disguised as brand styling is how users lose data and then correctly hate us.

---

## Typography

Three families per `poc/design-review.md` §2.2:

- **`Inter`** — UI body, sans
- **`Newsreader`** (italic, opsz axis) — display moments only: app name, hero titles ("What lingered from yesterday?", "What keeps returning.", "This deletes everything.")
- **`JetBrains Mono`** — forensic-instrument labels, eyebrows, persona names (`WITNESS`, `LOCAL · READY`, `25 DAYS`, `DESTRUCTIVE`)

Type primitives (`HDisplay`, `H1`, `P`, `PersonaLabel`, `Eyebrow`) and Compose translation notes live in `design-review.md` §2.2 and §8. Don't redefine them here.

Avoid: display fonts other than Newsreader for hero moments, script, rounded "friendly" sans, serif wellness typography (Newsreader's editorial italic is the chosen serif — anything else reads as wellness).

Must read cleanly at 3am on a 6.8-inch phone.

---

## Shape, Spacing, Motion

Use Android **Material 3** structure and accessibility conventions, with **expressiveness suppressed.** We use the system, we choose restraint within it.

**Components feel:** native, restrained, precise, quiet, slightly worn. **Atmospheric is the single visual system** per `design-review.md` §7.3 — no flat counterpoint.

**Radii:** Use the `RadiusTokens` scale from `design-review.md` §2.3 (`rPill`, `rXL`, `rL`, `rM`, `rS`, `rXS`). No raw `dp` for corner shapes that map to the scale.

**Texture:** Two ambient layers per `design-review.md` §2.4 carry across nearly every surface — noise grain (`feTurbulence` 180×180 tile, `mix-blend-mode: overlay`, opacity ~0.05–0.18) and fog drift (two animated radial gradients, `vesDrift1`/`vesDrift2`, 22s/28s alternate).

**Motion:**
- minimal and functional only
- atmospheric drift on loading states is handled by the fog-drift layer above (think fog moving past a window)
- predictive back gesture (Android 15+ default) — restrained and native, no fighting the system
- no bounces, springs, celebrations, reward animations, confetti, sparkle transitions
- keyframe set defined in `design-review.md` §2.5: `vesPulse`, `vesIn`, `vesFade`, `vesSlide`, `vesShimmer`, `vesBreath`, `vesSpin`, `vesDrift1`, `vesDrift2`. Compose translation notes in §8.

---

## Voice & Microcopy

The bite lives in the *words*. The visuals stay quiet. If the visuals soften, the copy has to bite harder to compensate.

### Rules

1. **Behavioral vocabulary, not feelings vocabulary.** "Crashed" not "felt sad." "Stuck" not "frustrated." "Wired" not "anxious."
2. **No performed validation.** Refuse praise, performed empathy, therapy-coded affirmation. Functional acknowledgment is fine ("yeah, that tracks given the sleep gap"). The line: information vs. performance.
3. **Interpretation rules.** Interprets behavior, vocabulary, and pattern (the product). Does not interpret feelings, motivations, or psychological causation. Forbidden openings: "you might be feeling," "it seems you're avoiding," "this could indicate." Encouraged: counts, co-occurrences, vocabulary observations.
4. **No therapy-speak.** No "journey," "growth," "self-care," "mindful," "intentional," "honoring."
5. **No wellness-speak.** No "wellbeing," "balance," "checking in," "showing up," "holding space."
6. **No platform default warmth.** No "Hang tight," no exclamation points anywhere except user entries.
7. **Imperatives over invitations.** "Record." Not "Tap when you're ready."
8. **Short.** One word if possible. Two if needed. Three is suspicious.
9. **Honest about failure.** Say what broke. No "Oops."

### Validation distinction

**Refused (performed):** Great job journaling today / Thanks for sharing / You're being so brave / I'm so sorry you're going through this / That sounds really hard

**Allowed (functional):** Yeah, that tracks given the sleep gap / Makes sense after a meeting like that / That's the third time this month / You said you'd do it. You haven't. That's data.

### Interpretation distinction

**Allowed (the product):**
- Behavioral: "this happens after that"
- Statistical: "fourth time this month"
- Vocabulary: "you used three words for the same state"
- Pattern: "this matches a recurring shape"

**Forbidden (therapy overreach):**
- Emotional: "you might be feeling…"
- Causal-psychological: "this could be because…"
- Diagnostic: "this sounds like…"
- Motivational: "it seems you're avoiding…"

### Persona voices

Three personas. Default is **Witness**. Tunable in settings. Voice differs in *tone*, not *function* — all three observe behavior, refuse performed validation, interpret pattern but not psychology.

**Witness** *(default)* — Observes. Names the pattern. Keeps quiet otherwise.
> "Fourth entry mentions Tuesday meetings. State before is cruising. State after is crashed. Worth noting."

**Hardass** — Sharper. Less padding. More action.
> "You logged the same blocked task three times. What changes before the fourth."

**Editor** — Cuts vague words until they confess.
> "You used 'tired' six ways this month. Three mean crashed. Name it cleaner."

Forbidden across all personas: "thank you for sharing," "how does that make you feel," reassurance, consolation, references to "growth" or "progress," emoji, apology for being direct, interpretation of feelings/motivations.

---

## Screen Specs

### Capture Screen

**Goal:** fast entry. Capture is the primary surface — when the user opens the app, they should be one tap from talking.

**Stack order (top → bottom).** Cross-reference `ux-copy.md` §Capture Screen for canonical strings; this section owns layout and visual treatment.

1. **Top status row**
   - Left: Local Model Status indicator (`LOCAL · READY`-style chip per `ux-copy.md`). The chip is **clickable**; tap opens the persistent Local Model Status screen. Contributes to the 10-second judge test — a visible "this is on-device" signal lives on the primary surface.
   - Right: Persona dropdown pill (`WITNESS ▾`-style per `ux-copy.md`). Tap opens the per-capture persona selector (P1 — see PRD; the prior "per-session override" framing was retired with the STT-B fallback).
2. **Patterns peek card** *(below status row, per `ux-copy.md` §"Patterns peek (below status)")*
   - Compact card with `{N} active patterns` title, one-line teaser of pattern names, subtle. No purple left-rule here — that's reserved for the full Patterns list. Empty-state copy comes from `ux-copy.md`.
3. **Hero title (above MistHero)**
   - One short editorial line in `Newsreader` italic per `poc/design-review.md` §2.2 (e.g., the `What lingered from yesterday?` strings visible in `poc/screenshots/capture.png`). Hero copy belongs in `ux-copy.md` §Capture Screen — pull from there, do not invent.
   - This is the *editorial* register: a short, observational, present-tense line. It is **not** a journal prompt or a wellness question. The `Newsreader` italic + restrained content distinguishes it from `How are you feeling today?`-class prompts (which remain forbidden — see below).
4. **MistHero capture stone (dominant, center for hero presence)**
   - The `MistHero` primitive per `poc/design-review.md` §3.3. 168px hero, five-layer moonstone composition. Behavior per the "MistHero" entry in §"Component Conventions" below.
5. **Tagline strip (below MistHero)**
   - Two short mono lines per `poc/screenshots/capture.png`: a directive (`HOLD THE STONE · SPEAK`-style) and a privacy-tagline (`30s chunks · audio discarded after extraction`-style). `JetBrains Mono`, eyebrow scale. Strings live in `ux-copy.md`.
6. **Type-instead affordance**
   - Small button below the tagline. Expands inline to a text input (placeholder + send copy from `ux-copy.md`). Never buried.
7. **Patterns peek card** *(below the type affordance, per `poc/screenshots/capture.png` and `ux-copy.md` §"Patterns peek")*
   - Compact card with `{N} ACTIVE TRACES`-style title, one-line teaser of pattern names, subtle `TraceBar` mini-strips. No `glow` left-rule here — that's reserved for the full Patterns list. Empty-state copy comes from `ux-copy.md`.
8. **Footer metadata strip**
   - Small dim text — last entry timestamp + duration + `PATTERNS` link. Strings per `ux-copy.md` §"Footer metadata."

**Recording-state changes to the stack:**
- The persona dropdown pill is **replaced** by a chunk-timer pill (`00:04`-style — counts up, `vapor`-tinted, accent only on the indicator dot). The persona is fixed for the duration of a recording; switching mid-recording is forbidden.
- The patterns peek card and footer metadata can dim or fade out during active recording — capture is the only surface that matters in that state. Do not collapse the layout; just lower contrast.
- The hero title can swap to a recording-state line per `ux-copy.md`, or stay; do not invent here.

**Transcription appears after inference returns** (Phase 1/2 measures the real S24 Ultra latency; target 1-5 seconds per `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Latency budget" — not a guarantee). Until it arrives, the placeholder copy from `ux-copy.md` sits in the user-turn slot. Streaming-as-you-speak transcription is v2; not in this design.

**Forbidden on capture:**
- No mood prompt, no suggested topics, no daily question header (the editorial hero title above is *not* a daily prompt — it's a fixed editorial line that doesn't ask the user to perform reflection), no chat bubbles, no waveform when idle.
- No motivational or wellness-coded framings: `How are you feeling today?`, `What would you like to journal about today?`, `Let's reflect together`, `Tip of the day`, anything ending in `!`. The user opens the app to dump, not to be coached.
- The hero title is `Newsreader` italic by design — that *one* serif moment is the only place editorial typography appears. No serif body copy, no serif buttons.

**Microcopy:** pull every string from `ux-copy.md` §Capture Screen. The forbidden-copy list above + `ux-copy.md` §"Things to NEVER Write" are the litmus test.

### First-Run Onboarding

One decision per screen, sequential.

1. Choose persona (default Witness highlighted, brief one-line descriptions)
2. Explain local processing — visual moment establishing the on-device identity. Use plain language: "Everything runs on your phone. Your voice never leaves the device."
3. Request microphone permission (with rationale)
4. Show typed fallback (so non-voice users aren't stranded)
5. Wi-Fi check
6. Download local model (real progress, real ETA, real bytes)
7. Start first entry (empty Capture screen with a one-line nudge)

Tone: plain, short, no emotional hand-holding. No "Welcome to your journey."

### Local Model Status

Persistent screen, accessible from settings or a small status indicator in the app shell.

Shows:
- Local model status — "Model ready. Running locally." / "Downloading…" / "Stalled." / "Updating."
- Download progress, bytes remaining, estimated time when active
- Retry control on failure

**Microcopy:**
- Use: `Downloading model. Wi-Fi only.`, `Quiet for a minute.`, `Model ready.`, `Running locally.`, `Download stalled. Retry.`, `Network choked.`
- Avoid: `Preparing your personalized AI experience`, `Hang tight`, `Almost there!`

This screen is part of how a 10-second judge realizes "this is a local AI app, not a cloud chatbot." Make it visible.

### Persona Selector

Three options, segmented control or simple list. No avatars, no carousel, no character illustrations.

**Witness** — Observes. Names the pattern. Keeps quiet otherwise.
**Hardass** — Sharper. Less padding. More action.
**Editor** — Cuts vague words until they confess.

Default is highlighted. Selection changes the active persona for the next capture (per-capture override allowed; STT-B fallback retired the per-session framing). No celebration animation on switch.

### Pattern List

Cards present pattern observations on dark surfaces. Restrained, atmospheric, sourced. No clinical or procedural-drama styling.

**Card structure:**
- Short title (the pattern name, e.g., `Tuesday Meetings`)
- Category (agent-emitted label: `Aftermath`, `Tunnel exit`, `Concrete shoes`, `Decision spiral`, `Goblin hours`, `Audit`)
- Observation (one short sentence)
- Source count (e.g., `4 of 12 entries`)
- Last seen (date)
- Actions (`Dismiss`, `Snooze`, `Mark resolved`)

**Purple left-rule** (`#A855F7`) on cards with active patterns. Resolved/snoozed cards lose the rule.

**Example card:**
> **Tuesday Meetings**
> Aftermath
> Fourth entry mentions Tuesday meetings. State before: cruising. State after: crashed.
> 4 of 12 entries · Last seen May 7

**Avoid copy:** `Explore deeper`, `Celebrate progress`, `Good news`, `Concern`.

A `Roast me` button is available in the **header** of the patterns list only if P1 Roast ships, not per card. This is the global on-demand roast across all history.

### Pattern Detail

Tap a card → detail screen. Make the pattern claim visually sourceable.

**Layout:**
- Header: pattern title + category
- Summary observation (the same one-line claim from the card)
- Count + recurrence timing ("4 of 12 entries, all on Tuesdays in the last 6 weeks")
- Source snippets (dated, short, clickable to the full entry)
- Related vocabulary (the actual words the user used across these entries)
- Action controls (`Dismiss`, `Snooze`, `Mark resolved`)

**Source section example:**
> **Seen in:**
> Apr 12 — crashed after standup
> Apr 18 — wired until 2am
> Apr 26 — same concrete shoes again

Do not style this like a medical chart or therapy dashboard. Cards-on-dark, restrained, with the purple left-rule on the active claim.

### The Roast — P1

User-initiated. Triggered by `Roast me` in the patterns list header if the feature ships after normal pattern evidence is solid.

**Distinction from the patterns list:**
- **Patterns list = the count.** Data, sourced cards, recurrences, vocabulary observations. The serious surface.
- **The Roast = the cut.** Persona-flavored lines that *land* on the absurdity of the data. Stand-up, not status report. Uses the data but never just recites it.

**Test for a Roast line:** if it's just stating data, it's a pattern — rewrite it as a cut. *"Tuesday meetings: four entries"* is a stat. *"Tuesday meetings have a body count"* is a roast. Same data, different surface.

**Surface:** **Modal Bottom Sheet** (Material 3 in Compose). Drags up from the bottom, swipe-to-dismiss, expandable to half or full height. Native Android. Not a separate destination.

**Layout inside the sheet:**
- Header: persona name + "Roast" + timestamp (e.g., `Witness · Roast · May 8`)
- Body: 3-5 short lines as a flat list (not cards), purple left-rule on the body
- Source line at bottom: `Drawn from 31 entries · Last 30 days`
- Footer action: `Close`

**Witness example (deadpan, lands the absurd):**
> **Witness · Roast · May 8**
>
> Tuesday meetings have a body count.
>
> She got mentioned. Then she got buried.
>
> "Tired" has three jobs. None of them pay.
>
> Same project, same dread, three times. The project remains undefeated.
>
> *Drawn from 31 entries · Last 30 days*

**Hardass example (sharper, scoreboard energy):**
> **Hardass · Roast · May 8**
>
> Tuesday meetings: 4–0.
>
> Project: 3. You: 0.
>
> "Tired" 23 times, three meanings, zero clarity.
>
> Four mentions, then radio silence. The radio's still off.
>
> *Drawn from 31 entries · Last 30 days*

**Editor example (linguistic precision as the punchline):**
> **Editor · Roast · May 8**
>
> "Tired" — 23 occurrences, three meanings. Tireduction needed.
>
> "Fine" lives in seven entries with "crashed." Pick a vocabulary.
>
> Mentioned in four entries, then filed under "didn't happen."
>
> *Drawn from 31 entries · Last 30 days*

The Roast is **ephemeral** — not saved. Regenerates fresh each time the user taps. The patterns it draws from are persistent; the Roast itself is a moment, not a trophy. No streaks of roasts, no roast history, no shareable roast.

The user opted in by tapping `Roast me`. The line still holds: behavioral and pattern observation, no diagnostic interpretation, no motivation, no "you should." A roast may *land* sharper than the patterns list, but it does not *push* the user toward anything.

### Error States

Errors say what broke.

**Use:**
- `Download stalled. Retry.`
- `Network choked.`
- `Mic permission required to record. Settings → Permissions.`
- `Model timed out. Try a shorter chunk.`
- `Entry not saved.`

**Avoid:**
- `Oops`, `Something went wrong`, `We're having trouble understanding`, `Please try again later!`

### Destructive Confirmation

Serious, sparse, unmistakable. No drama theater.

**Copy:**
> **This deletes everything.**
> Type **DELETE** to confirm.
>
> [ Cancel ] [ **Wipe everything. No backup.** ]

Use the `error` token (`#B3261E` per `poc/design-review.md` §2.1) on the destructive button. The confirm field requires typing `DELETE` — no checkbox, no slider, no "Are you sure?" yes/no.

---

## Component Conventions

### MistHero (capture screen primary)
- The central capture surface is the `MistHero` moonstone primitive per `poc/design-review.md` §3.3, not a flat record button. Five-layer composition: outer halo, conic moonstone ring, frosted-glass body, inner noise, center mark.
- Single primary action. ≥168px hero size (per the POC `MistHero` 168px spec).
- Idle: stone with subtle internal gradient, no outer halo amplitude.
- Active recording: outer halo scales with audio level (`level` prop), `vapor` (#2563EB) tint on the halo and ring, `AudioMeter` renders below, stop affordance center.
- Post-stop / review: halo collapses to outline state with a thin `vapor` rim, `Reading the entry.` placeholder copy from `ux-copy.md` shows in the transcript area.
- Approaching chunk boundary (25s of 30s): thin progress arc on the ring, soft visual cue, no copy.

Compose translation notes for `MistHero` live in `design-review.md` §8 (radial gradients, conic ring, infinite-transition halo).

### Entry transcript
- **Single-turn-per-capture** per the STT-B fallback (`adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior")): one entry contains exactly one `YOU` turn (your transcription) and one `WITNESS`/`HARDASS`/`EDITOR` turn (the model's follow-up). No scroll across multiple exchanges, no session thread — the entry IS the exchange.
- **Avoid messenger-style chat bubbles** — too consumer-coded.
- Treatment: left-rule indicators in different tones per speaker, monospace label (`WITNESS` / `YOU`) above each turn, body text in regular sans.
- User's transcribed words shown in **muted/dimmed tone** (visually secondary). Model's response in primary text weight. User can verify; model stays the focal point.
- No waveform shown for past audio — only the transcription.
- The history list (Phase 4) is a list of completed entries, not a list of conversation threads. Tapping an entry opens a single-exchange view, not a chat log.

### Pattern card
- Short title, category, observation, source count, last seen, actions.
- Purple left-rule on active patterns; no rule on dismissed/snoozed/resolved.

### Persona toggle
- Segmented control or simple list, three options.
- Currently-active persona uses purple fill or purple-outlined indicator.
- No avatars, no characters, no animation flourish.

---

## Iconography

### Right register (atmospheric, worn, present)
- Footprint (singular, partial, fading at edges) — etymological match for "vestige"
- Worn impression — wear marks, indents in grass, smudges, dust patterns with cleared spots, depressions
- Faded watermark, palimpsest, ghosted text under newer text
- Negative space — outline of where something was
- Sediment lines, geological strata
- Mist drifting past partial silhouettes

### Wrong register (do not use)
- Brains, hearts, lightbulbs, suns, flowers, smileys, journals, swirls, lotuses (wellness)
- Sparkles, AI-glow effects, neural-network diagrams (generic AI)
- Investigation-themed iconography of any kind

### App icon

**Primary direction is locked: a single partial footprint dissolving into mist at its edges.** Per `concept-locked.md` §Open decisions, this is the chosen metaphor. Outline-only or filled with a subtle gradient from cool white to nothing. Small, quiet, recognizable at app-icon size.

**Alternates may vary execution only, not metaphor family.** All alternates must remain in the partial-footprint-and-mist family — different angles, different stroke weights, different mist densities, different framing. Alternates that swap the metaphor (pressed marks in surfaces, sediment lines, ghosted outlines of unrelated objects) are off-direction and not what we want.

Examples of in-family execution variation:
- The same footprint at a different angle, more dissolved at the heel
- Lighter stroke on the footprint outline, heavier mist
- Footprint cropped tighter, edges fading more aggressively
- Cooler mist tone vs. warmer mist tone within the locked palette

---

## Accessibility

- WCAG AA minimum contrast (4.5:1) for all text. AAA target (7:1) for primary content.
- Touch targets ≥48dp. Record control larger than minimum.
- Every interactive element has a screen-reader label. `contentDescription` on persona toggle, `MistHero` (label: `Record` / `Stop` per `ux-copy.md` §"Capture Screen"), pattern actions.
- **Typed fallback must exist** for users who cannot or will not speak. Not buried.
- Predictive back gesture must not disable system accessibility navigation.
- Dimmed whites and grays preferred over pure white to reduce visual vibration for astigmatic users.

---

## Anti-Patterns (do not design)

- Mood emoji slider, mood color wheel
- Journal prompt cards, gratitude prompts
- Streaks, badges, achievements, trophies
- Mascots, cartoon characters, illustrated guides
- Social sharing, share-to-Twitter
- Sparkle AI iconography, neural-glow effects
- Chat bubbles
- Therapy dashboard, lab UI
- Confetti, celebration animations
- "Tip of the day" overlay
- Satisfaction survey, NPS prompt
- Soft pastel cards, sunrise imagery, mountain imagery, water imagery, brain imagery
- Light theme

---

## Design Success Test

A mockup succeeds if:

- It cannot be mistaken for Calm, Headspace, Reflectly, Rosebud, or ChatGPT.
- Capture is obviously the primary action.
- Local AI/model presence is visible without generic AI sparkle language.
- Pattern evidence is visually sourceable.
- `glow` and `vapor` (with `pulse` and `error` reserved for ready-status and destructive respectively) each appear rarely, meaningfully, and in their own domains. None becomes wallpaper. `glow` and `vapor` never share a single component.
- The words are sharper than the visuals.
- The UI feels Android-native without becoming playful.
- The app feels observational, not therapeutic.
- The design respects the user's intelligence.
- A judge understands within 10 seconds that this is a local AI cognition tracker, not a journaling app.
