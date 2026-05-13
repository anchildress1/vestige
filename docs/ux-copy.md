# Vestige — UX Copy

All microcopy for the v1 app. Pull from this when generating mockups or implementing UI.

For voice and tone rules, see `design-guidelines.md` § Voice & Microcopy. Summary:
- Behavioral vocabulary, not feelings vocabulary.
- Functional acknowledgment is fine; performed validation is not.
- Imperatives over invitations. One word if possible.
- No exclamation points. No emoji in chrome. No therapy-speak.
- Honest about failure. Say what broke.

---

## Persona Labels (chrome treatment)

Persona names appear in two contexts:
- **Caps + monospace label** above transcript turns and Roast headers: `WITNESS`, `HARDASS`, `EDITOR`
- **Normal case** in body copy and settings: Witness, Hardass, Editor

Keep these consistent. Don't mix.

---

## Onboarding (8 screens, sequential)

### Screen 1 — Pick a persona

Header:
> **Pick a persona.**

Subhead:
> Three voices. Same product. Pick the one that fits today. You can switch.

Persona cards (default Witness highlighted):
- **Witness** — Observes. Names the pattern. Keeps quiet otherwise.
- **Hardass** — Sharper. Less padding. More action.
- **Editor** — Cuts vague words until they confess.

Primary action:
> **Continue**

Footer link:
> Change later in settings.

---

### Screen 2 — Local processing explainer

Header:
> **Everything stays on your phone.**

Body:
> Vestige runs Gemma 4 directly on this device. No cloud processing, no servers for your entries, no telemetry. Your voice never leaves the device.

Subhead detail:
> The model is ~3.7 GB. We download it once, on Wi-Fi, in the next few steps.

Primary action:
> **Got it**

---

### Screen 3 — Microphone permission

Header:
> **Mic permission.**

Body:
> Vestige needs the microphone to record your dumps. Audio is processed by Gemma 4 on this device, then discarded. The transcription stays as text — like any other note.

Primary action:
> **Allow microphone**

Secondary action:
> **Skip — I'll type instead**

If denied:
> Mic permission required to record. Settings → Permissions.

---

### Screen 3.5 — Notification permission

Header:
> **One status notification.**

Body:
> Vestige briefly shows a status when it's working locally on an entry. That's the only notification it will ever send. It disappears when work is done.

Primary action:
> **Allow notifications**

Secondary action:
> **Skip — work runs in foreground only**

If skipped:
> *(no error state — extractions only complete while the app is in the foreground; the cold-start sweep recovers the rest)*

Notification text when posted (per `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` §"Notification Contract"):
> Reading the entry.

This string is the same one used for mid-capture inference loading copy (see §"Loading States" below — single source of truth for both surfaces).

---

### Screen 4 — Typed fallback

Header:
> **Or type.**

Body:
> Voice is the default. Typing works the same way. Same patterns, same persona. Pick whichever the day is asking for.

Primary action:
> **Continue**

---

### Screen 5 — Wi-Fi check

If on Wi-Fi:

Header:
> **Wi-Fi connected.**

Body:
> Ready to download the model. ~3.7 GB.

Primary action:
> **Download model**

If not on Wi-Fi:

Header:
> **Wi-Fi required.**

Body:
> The model is ~3.7 GB. Connect to Wi-Fi to download. We'll wait.

Primary action:
> **Open Wi-Fi settings**

Secondary action:
> **I'll come back**

---

### Screen 6 — Model download (in progress)

Header:
> **Downloading model.**

Body line 1 (active):
> {bytes downloaded} / {total} · {ETA}

Body line 2 (status):
> Quiet for a minute. This takes a while.

Cancel:
> **Pause**

If stalled:
> Download stalled.
>
> **Retry**

If failed:
> Network choked.
>
> **Try again**

---

### Screen 7 — First entry scaffold

Header:
> **Ready.**

Body:
> Everything's local. The model's loaded. Talk into the mic when you've got something to dump, or type. Witness is selected.

Primary action:
> **Open Vestige**

---

## Capture Screen

### Status row (top)

- Local model status indicator: `LOCAL · READY` (when idle, model loaded)
- Persona dropdown label: `WITNESS ▾` (or active persona)

### Patterns peek (below status)

Card title:
> **{N} active patterns**

Card body (one-line teaser):
> {pattern_name_1} · {pattern_name_2} · {pattern_name_3}

If no active patterns:
> Nothing repeating yet.

### Center — record action

Persona name above record button: *(removed — see design-guidelines.md, the centered persona label was forbidden)*

Record button label (when idle, screen-reader content description):
> Record

Record button label (when recording):
> Stop

Hint text under button (idle, optional, very subtle):
> Tap to talk.

Approaching chunk boundary (~25s):
> *(visual cue only, no copy)*

After tap-stop, while transcribing (1-5 sec target per ADR-002 §"Latency budget" — measurement-driven, not a contractual promise):
> Reading the entry.

### Type affordance (bottom)

Button:
> Type

When expanded into text input, placeholder:
> What just happened.

Send action:
> Log entry

### Footer metadata (small text)

> Last entry · May 7 · 4m 02s · **History**

---

## Local Model Status (standalone screen)

Reachable from settings or status indicator chevron in app shell.

Header:
> **Model status.**

Status states:

- **Ready:** `Model ready. Running locally.`
- **Loading:** `Loading model.`
- **Downloading:** `Downloading model. Wi-Fi only.` + progress
- **Stalled:** `Download stalled.` + Retry button
- **Failed:** `Network choked.` + Retry button
- **Updating:** `Updating model.` + progress

Detail line (always visible when loaded):
> Gemma 4 E4B · 3.66 GB · v{version} · On-device

Settings actions:
- **Re-download model**
- **Delete model**

Re-download confirm:
> This downloads ~3.7 GB again. Wi-Fi recommended.
>
> **Re-download** / Cancel

Delete confirm:
> Deletes the model file. The app won't work until re-downloaded.
>
> **Delete model** / Cancel

---

## Persona Selector (settings)

Header:
> **Persona.**

Subhead:
> Default voice. Changes how the model talks back. You can override per capture.

Persona descriptions (same as onboarding):
- **Witness** — Observes. Names the pattern. Keeps quiet otherwise.
- **Hardass** — Sharper. Less padding. More action.
- **Editor** — Cuts vague words until they confess.

Primary action:
> **Save**

---

## Pattern List

Header:
> **Patterns**

Action button (top right):
> **Roast me**

Section headers (uppercase, mono eyebrow — one per non-empty section per `poc/screens-patterns.jsx`):
> ACTIVE
> SNOOZED · STILL DRIFTING
> RESOLVED · FADED
> DISMISSED

Filter chips (small, secondary text — Phase 4 polish on top of the section structure):
> All · Active · Snoozed · Resolved

Pattern card structure:

> **{Pattern name}**
> {Agent-emitted label — Aftermath / Tunnel exit / Concrete shoes / Decision spiral / Goblin hours / Audit}
> {One-line observation}
> {N} of {M} entries · Last seen {date}

Card actions (per card, in overflow menu):
- **Dismiss**
- **Snooze 7 days**
- **Mark resolved**

Empty states:

- No entries yet: `Insufficient data.`
- Has entries, no patterns: `Nothing repeating yet.`
- All dismissed: `No active patterns.`
- Filter returns nothing: `Nothing matches.`

---

## Pattern Detail

Header:
> **{Pattern name}**

Subhead (agent-emitted template label):
> {Aftermath / Tunnel exit / Concrete shoes / Decision spiral / Goblin hours / Audit}

Summary observation (one line, primary text):
> {The card's one-line observation, expanded slightly with timing}

Stats row:
> {N} of {M} entries · {timing detail, e.g., "All on Tuesdays in the last 6 weeks"}

Intensity strip eyebrow (above the hero TraceBar):
> INTENSITY · 30 DAYS

Source section header:
> **Seen in:**

Source list (date · short snippet, tappable to full entry):
> Apr 12 — crashed after standup
> Apr 18 — wired until 2am
> Apr 26 — same concrete shoes again

Vocabulary section header (when relevant):
> **Words you used:**

Vocabulary tags (small chips):
> tired · fine · crashed · meeting

Action row (bottom):
- **Dismiss**
- **Snooze 7 days**
- **Mark resolved**

If resolved:
> Marked resolved {date}.

---

## The Roast (modal bottom sheet)

P1 conditional. Do not implement before the normal Pattern List and Pattern Detail are working with sourced evidence.

Header:
> **{Persona} · Roast · {date}**

Body — the roast itself, 3-5 lines, persona-specific. Examples already in `design-guidelines.md`. Persona-flavored, not data recitation.

Source line at bottom:
> *Drawn from {N} entries · Last 30 days*

Footer actions:
- **Close**

If no roast available (insufficient data):
> Insufficient data. Come back when you've left more behind.

---

## Re-eval / Reading (P1 — conditional on scope)

Per `PRD.md` §P1, Re-eval ships if scope holds; otherwise it lands in v1.5. Stub copy below; expand when the feature is scheduled.

Action label on entry detail:
> **Re-read this entry**

Confirmation on second tap within 60 seconds (per ADR-002 Q3 — battery cost):
> Costs ~30s of inference. Continue?

Result — agreement:
> Confirmed. Same shape.

Result — disagreement:
> Different. Show diff.

Diff actions:
- **Accept new shape**
- **Keep original**

---

## Error States (catalog)

| Surface | Copy |
|---|---|
| Generic transient error | `Something failed. Try again.` |
| Model download failed | `Network choked.` |
| Model download stalled | `Download stalled. Retry.` |
| Model file corrupt | `Model file unreadable. Re-download from settings.` |
| Mic permission denied | `Mic permission required to record. Settings → Permissions.` |
| Mic hardware unavailable | `Mic unavailable. Try typing.` |
| Inference timeout | `Model timed out. Try a shorter chunk.` |
| Inference failed | `Model couldn't read that. Try again.` |
| Storage full | `Phone storage full. Free up space and try again.` |
| Audio recording failed | `Recording failed. Try again.` |
| Entry save failed | `Entry not saved. Try again.` |
| Pattern detection failed | `Pattern read failed. Patterns reload on next entry.` |
| Background killed mid-capture | `Capture interrupted. Last entry saved up to {timestamp}.` |
| Device thermal throttle | `Device running hot. Inference may be slow.` |
| Explicit self-harm help request | `Vestige is not a crisis tool. If you might hurt yourself or someone else, contact local emergency services or a crisis hotline now.` |

---

## Destructive Confirmations

### Delete single entry

Title:
> **Delete this entry?**

Body:
> The entry, its transcription, and any tags extracted from it. Patterns referencing it will be recalculated.

Actions:
- **Delete** *(system error/destructive style)*
- **Cancel**

### Delete all data

Title:
> **This deletes everything.**

Body:
> Every entry, every pattern, every tag. Nothing is sent anywhere. Nothing is recoverable.
>
> Type **DELETE** to confirm.

Confirm field placeholder:
> DELETE

Actions (only enabled when field reads `DELETE`):
- **Wipe everything. No backup.** *(system error/destructive style)*
- **Cancel**

### Re-download model

Title:
> **Re-download model?**

Body:
> ~3.7 GB on Wi-Fi. The model file is replaced. Your entries are not touched.

Actions:
- **Re-download**
- **Cancel**

### Delete model

Title:
> **Delete model file?**

Body:
> The app won't work until you re-download. Your entries stay where they are.

Actions:
- **Delete model** *(system error/destructive style)*
- **Cancel**

---

## Settings (v1 P0 scope)

The v1 settings screen ships with this scope only. Toggles and editable values listed in earlier drafts (default input, transcription visibility, pattern threshold, cooldown) are deferred — they don't visibly improve the demo and they let the UI violate P0 acceptance behavior.

Section: **Persona**
- Default persona: {Witness / Hardass / Editor}

Section: **Data**
- Export all entries (zip of markdown)
- Delete all data

Section: **Model**
- Status (link to Local Model Status screen)
- Re-download
- Delete model

Section: **About**
- Version
- Source code (link to GitHub)
- License

### Locked v1 behavior (not configurable)

- **Default input:** voice. Typed fallback is always available but voice is the entry-point per product positioning. No setting toggle.
- **Transcription visibility:** always shown in the transcript per P0 acceptance criteria. No setting toggle.
- **Pattern detection threshold:** every 10 entries, hardcoded for v1.
- **Pattern callout cooldown:** 3 entries after a callout, hardcoded for v1.

These are deferred to v1.5 along with the rest of the configurable-settings work.

---

## System Messages (snackbars / toasts)

Use sparingly. Only for actions where the user needs confirmation that something happened off-screen.

| Action | Snackbar |
|---|---|
| Entry saved | *(no snackbar — the transcript appearing is the confirmation)* |
| Persona changed for next capture | `Active persona: {name}.` |
| Pattern dismissed | `Dismissed.` *(with Undo)* |
| Pattern snoozed | `Snoozed 7 days.` *(with Undo)* |
| Pattern marked resolved | `Marked resolved.` *(with Undo)* |
| Export complete | `Exported {N} entries to Downloads.` |
| Model re-download started | `Downloading model.` *(opens status screen)* |
| Model deleted | `Model deleted.` |

No snackbars for: opening a screen, scrolling, normal navigation, successful inference (the response itself is the confirmation).

---

## Tooltips & Helpers

Mostly forbidden. The product respects the user's intelligence. Tooltips only allowed when a control's purpose is genuinely non-obvious *and* there's no room to label it inline.

Permitted tooltips:
- Status indicator dot (`LOCAL · READY` already labels it; tooltip on long-press shows full Local Model Status)
- Pattern card overflow menu icon (`More actions`)
- Settings gear icon (`Settings`)

Forbidden tooltips:
- Anything explaining a button label that's already clear ("Click Record to record")
- Anything emotionally framing a feature ("Your safe space for journaling")
- "Tip of the day" anywhere

---

## Loading States (catalog)

| Surface | Copy |
|---|---|
| Initial app load (model loading from disk) | `Loading.` |
| First-run model download | `Downloading model.` *(see Onboarding 6)* |
| Mid-session inference (after tap-stop, awaiting transcription + response) | `Reading the entry.` |
| Pattern recalculation after entry | *(silent, background — no copy unless it fails)* |
| Roast generation | `Reading the file.` |
| Settings save | *(silent — control state changes inline)* |
| Export running | `Packing entries.` |

---

## Things to NEVER Write

A short forbidden-copy list. If any of these end up in a build, it's a regression.

- "Welcome to Vestige!"
- "Your journey starts here"
- "How are you feeling today?"
- "Let's reflect together"
- "You're doing great!"
- "Way to go!"
- "Thanks for sharing"
- "Take a moment to breathe"
- "Honor your truth"
- "Show up for yourself"
- "Tip of the day"
- "Did you know?"
- "Pro tip:"
- Anything ending in `!`
- Anything starting with `Oops`
- Anything containing `journey`, `growth`, `wellness`, `mindful`, `intentional`, `holding space`

---

## Locked UX Decisions

- Pattern names are model-generated in v1. No user rename/edit affordance.
- Snooze duration is fixed at 7 days in v1.
- Export format is a zip of per-entry markdown files only. Rolled-up `.md` and PDF are v1.5+.
- No first-time mock data. Empty means empty; demo seed data is a dev/demo setup concern, not user-facing fiction.
- Loading copy stays distinct: `Reading the entry.` for single-entry inference, `Reading the file.` for Roast generation.
- No user name or handle in onboarding. Anonymity is on-brand and the feature didn't pass the demo-impact test. Handle system deferred to v1.5 (see `backlog.md`).
