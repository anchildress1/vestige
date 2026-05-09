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
- Core component styling (record button, transcript turn, pattern card, persona toggle)
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

### Palette (dark only, no light theme in v1)

- Background: `#0A0E1A`
- Deep surface: `#0E1124`
- Surface levels: `#161A2E`, `#1E2238`, `#2A2E48`
- Primary text: `#E8ECF4`
- Secondary text / mist gray: `#7B8497`
- **Primary accent — vibrant purple: `#A855F7`** (luminous, slight magenta hint, "blacklight bloom")
- **Active accent — electric blue: `#2563EB`** (deep, denser, calmer than neon)

### Color rules

- Never pure black (`#000000`) — vibration, harder for astigmatic users.
- Never pure white text on dark — softens visual fatigue.
- WCAG AA minimum (4.5:1) for body text. AAA target (7:1) for primary content.
- Each accent is rare punctuation in its own domain. Neither becomes wallpaper.
- Each accent owns a semantic role: **purple = identity / pattern / depth**; **blue = active / interaction / recording state**.
- Both accents *may* appear in the same component **when they carry different roles on different elements.** Example: a pattern card with a purple left-rule (identity) and a blue focus ring when keyboard-focused (interaction state). Example: a Roast sheet with a purple header and a blue active-recording button inside.
- **Forbidden:** a single element rendered in both colors. No purple→blue gradients, no half-and-half buttons, no two-tone icons. One element, one accent at a time.
- **Forbidden:** the same semantic role rendered in different colors on the same screen. Two "active patterns" on one screen are both purple; don't alternate for variety.
- No gradients between accents. No glows, no halos.

### Where each accent lives

**Vibrant purple `#A855F7` — primary accent. Patterns, identity, depth.**
- Active pattern indicator (purple left-rule on cards with active patterns)
- "Roast me" action button + Roast bottom-sheet header
- Currently-active persona pill in the persona selector
- Pattern detail screen accent

**Electric blue `#2563EB` — active accent. Recording, focus, "this is on."**
- Active recording state (record button when user is talking)
- Live audio waveform tint (during recording)
- Selected/focused control (subtle ring on focus, not on default selection)
- Local Model Status indicator (when overriding system green for brand-identity reasons; see Local Model Status spec for the open question)
- Links and ghost button outlines

Everything else stays in the cool blue-gray atmosphere. System status conventions (e.g., default green "ready" indicators) stay system colors unless we have an explicit brand reason to override.

**System error red — destructive only.**
- Delete entry, delete all data, delete model, and unrecoverable wipe confirmations use Android/Material error color, not purple.
- Purple is identity/depth. It is not a delete affordance. A destructive button disguised as brand styling is how users lose data and then correctly hate us.

---

## Typography

System sans-serif. Roboto or Inter-like. Must read cleanly at 3am on a 6.8-inch phone.

Avoid: display fonts, script, rounded "friendly" sans, serif wellness typography.

---

## Shape, Spacing, Motion

Use Android **Material 3** structure and accessibility conventions, with **expressiveness suppressed.** We use the system, we choose restraint within it.

**Components feel:** native, restrained, precise, quiet, slightly worn.

**Radius:**
- cards and primary controls: 6–8px
- small chrome: 4px

**Motion:**
- minimal and functional only
- subtle atmospheric drift on loading states (think fog moving past a window)
- predictive back gesture (Android 15+ default) — restrained and native, no fighting the system
- no bounces, springs, celebrations, reward animations, confetti, sparkle transitions

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
   - Right: Persona dropdown pill (`WITNESS ▾`-style per `ux-copy.md`). Tap opens the per-session persona override (P1 — see PRD).
2. **Patterns peek card** *(below status row, per `ux-copy.md` §"Patterns peek (below status)")*
   - Compact card with `{N} active patterns` title, one-line teaser of pattern names, subtle. No purple left-rule here — that's reserved for the full Patterns list. Empty-state copy comes from `ux-copy.md`.
3. **Record action (dominant, center-low for thumb reach)**
   - One round primary control. ≥72px touch target. Behavior per "Record button states" below.
4. **Type-instead affordance**
   - Small button under or beside record. Expands inline to a text input (placeholder + send copy from `ux-copy.md`). Never buried.
5. **Footer metadata strip**
   - Small dim text — last entry timestamp + duration + History link. Strings per `ux-copy.md` §"Footer metadata."

**Recording-state changes to the stack:**
- The persona dropdown pill is **replaced** by a chunk timer pill (`00:04`-style — counts up, electric-blue-tinted, accent only on the indicator dot). The persona is fixed for the duration of a recording; switching mid-recording is forbidden.
- The patterns peek card and footer metadata can dim or fade out during active recording — capture is the only surface that matters in that state. Do not collapse the layout; just lower contrast.

**Record button states:**
- Idle: outlined ring, neutral cool gray. Center mark optional (small dot or no mark — restrained).
- Active recording: filled with electric blue `#2563EB`, live blue-tinted amplitude waveform around or below the control, stop affordance (square or similar) center.
- Post-stop / review (transcription pending): outlined ring with a thin electric-blue rim to signal "model is reading," no waveform. The "Reading the entry." placeholder copy from `ux-copy.md` shows in-line with the transcript area.
- Approaching 30s chunk boundary: thin progress arc on the ring, soft cue at 25s. No copy.

**Transcription appears after inference returns** (Phase 1/2 measures the real S24 Ultra latency; target 1-5 seconds per `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Latency budget" — not a guarantee). Until it arrives, the placeholder copy from `ux-copy.md` sits in the user-turn slot. Streaming-as-you-speak transcription is v2; not in this design.

**Forbidden on capture:**
- No mood prompt, no suggested topics, no daily question header, no chat bubbles, no waveform when idle.
- No motivational, journaling, or atmospheric question framings (`What would you like to journal about today?`, `What lingered from yesterday?`-style prompts). The user opens the app to dump, not to be prompted.
- No serif "thought" headers. Body copy stays system sans per the Typography section.

**Microcopy:** pull every string from `ux-copy.md` §Capture Screen. Avoid `Start your reflection`, `How are you feeling?`, `What would you like to journal about today?`, anything with `!`.

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

Default is highlighted. Selection changes the active persona (per-session override allowed). No celebration animation on switch.

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

Use Material/system error red on the destructive button. The confirm field requires typing `DELETE` — no checkbox, no slider, no "Are you sure?" yes/no.

---

## Component Conventions

### Record button (capture screen primary)
- Single primary action. ≥72px touch target.
- Idle: outlined ring, cool gray.
- Active: filled electric blue `#2563EB`, with live blue-tinted amplitude waveform around it.
- Approaching chunk boundary (25s of 30s): thin progress arc, soft visual cue.

### Conversation transcript
- Vertical scroll, chronological within session.
- **Avoid messenger-style chat bubbles** — too consumer-coded.
- Treatment: left-rule indicators in different tones per speaker, monospace label (`WITNESS` / `YOU`) above each turn, body text in regular sans.
- User's transcribed words shown in **muted/dimmed tone** (visually secondary). Model's response in primary text weight. User can verify; model stays the focal point.
- No waveform shown for past audio — only the transcription.

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
- Every interactive element has a screen-reader label. `contentDescription` on persona toggle, record button, pattern actions.
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
- Purple and electric blue each appear rarely, meaningfully, and in their own domains. Neither becomes wallpaper. They never share a single component.
- The words are sharper than the visuals.
- The UI feels Android-native without becoming playful.
- The app feels observational, not therapeutic.
- The design respects the user's intelligence.
- A judge understands within 10 seconds that this is a local AI cognition tracker, not a journaling app.
