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

## Onboarding (3 screens, hub flow)

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

### Screen 2 — Wiring hub

Header:
> **Wiring.**

Rows:
- **Persona** — `Voice picked on the previous screen. Change it later in Settings if {persona} doesn't fit.`
- **Local** — `No cloud. No servers. No telemetry. Voice never leaves the device.`
- **Mic** — `Records dumps. Audio is read locally, then discarded. Transcription stays as text.`
- **Notify** — `One line, posted while the model reads an entry. Disappears when work is done.`
- **Type** — `Voice is the default. Typing works the same. Same patterns. Same persona.`

Local row helper states:
- Absent on Wi-Fi: `Tap Local to start download`
- Partial on Wi-Fi: `Download still running · back up to resume`
- Corrupt on Wi-Fi: `Artifact corrupt · tap to retry`
- No Wi-Fi: `Network down · tap for Wi-Fi settings`

Mic row helper states:
- Pending: `Required for voice · optional otherwise`
- Denied: `Denied · tap again or Settings → Permissions`

Notify row helper state:
- Pending: `Single-status only · nothing else, ever`

Primary action:
> **Open Vestige**

Gate:
> Enabled only when the Local row is green.

Notification text when posted (per `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` §"Notification Contract"):
> Reading the entry.

This string is the same one used for mid-capture inference loading copy (see §"Loading States" below — single source of truth for both surfaces).

---

### Screen 3 — Model download

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

If artifact corrupt (post-download SHA-256 mismatch):
> Model file unreadable. Re-downloading.

This is the onboarding **auto-recovery** surface: the bad payload is wiped and one clean re-pull runs automatically, no tap required. Distinct from the error-catalog row `Model file unreadable. Re-download from settings.`, which is the *manual* settings path for a corrupt artifact found later. Both are correct; they serve different moments. (Reconciliation added per Story 4.3 — onboarding had no copy for the auto-retrigger case.)

---

Primary action:
> **Continue**

Behavior:
- Disabled until the artifact verifies.
- Auto-returns to **Wiring** once complete.
- If restored without Wi-Fi, return to **Wiring** and use the Local row's Wi-Fi-settings affordance instead of starting a dead-end download screen.

---

## Capture Screen

### Status row (top)

- Local model status indicator: `GEMMA 4 · LOCAL ONLY` (when idle, model loaded) / `GEMMA 4 · LISTENING LIVE` (when recording) — pill color stays lime in both states; coral is reserved for the REC button heat + destructive flows (see `design-guidelines.md` §"Capture Screen / AppTop status pill")
- Persona dropdown label: `WITNESS ▾` (or active persona)

### Patterns peek (below status)

Card title:
> **{N} active patterns**

Card body (one-line teaser):
> {pattern_name_1} · {pattern_name_2} · {pattern_name_3}

If model still downloading (record + typed both disabled — ADR-013):
> Model loading. Hang tight.

Status pill during download: `DOWNLOADING · {N}%`
Status pill if paused (no Wi-Fi): `MODEL PAUSED`
Below button if paused: `Reconnect to Wi-Fi to resume.`

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

### Capture Screen — Discard

Recording-state secondary affordance — sits below `STOP · FILE IT` per `design-guidelines.md` §"Capture Screen / Discard."

Button label:
> DISCARD · NO SAVE

Behavior (per `adrs/ADR-001-stack-and-build-infra.md` §Q8):
- Single tap. No confirmation dialog, no long-press, no two-tap arming.
- Screen returns to idle immediately. No snackbar, no `Discarded.` confirmation, no `Undo` affordance.
- Visible only while `CaptureSession.state == RECORDING`. Hidden once the user has tapped `STOP · FILE IT` (foreground call is in flight).

There is no error copy, no destructive confirmation copy, no post-discard toast. Silent dismissal is the contract.

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

Action button (top right) — persona-aware:
- Witness: **Roast me**
- Hardass: **Run the numbers.**
- Editor: **Audit my vocabulary.**

Section headers (uppercase, mono eyebrow — one per non-empty section per `poc/screens-patterns.jsx`):
> ACTIVE
> SKIPPED · ON HOLD
> CLOSED · DONE
> DROPPED

Filter chips (small, secondary text — Phase 4 polish on top of the section structure):
> All · Active · Skipped · Closed · Dropped

Pattern card structure:

> **{Pattern name}**
> {Agent-emitted label — Aftermath / Tunnel exit / Concrete shoes / Decision spiral / Goblin hours / Audit}
> {One-line observation}
> {N} of {M} entries · Last seen {date}

Card actions (per card, in overflow menu):
- **Skip**
- **Drop**

Card actions (non-active cards, in overflow menu):
- **Restart**

Note: Closed is model-detected — not set by a user tap. Pattern auto-close is deferred to v1.5 (`pattern-auto-close` backlog entry), but a done card can still be restarted.

Empty states:

- **Fewer than 10 entries (Day 1):**
  - Eyebrow: `VESTIGES · 0 ENTRIES · 30 DAYS`
  - Header: `Nothing to read yet.`
  - Body: `Patterns surface after 10 entries. Keep recording.`
- **Enough entries, no pattern detected:**
  - Header: `No repeating pattern detected.`
  - Body: `The model looked. Nothing came back twice.`
- **Active tab empty (all skipped or closed):**
  - Eyebrow: `ACTIVE`
  - Header: `Nothing active.`
  - Sub: `{N} skipped · {N} closed` (live counts)
- **Filter returns nothing:** `Nothing matches.`

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
- **Skip**
- **Drop**

Action row (non-active state):
- **Restart**

If model-detected Closed (read-only state — no action row shown):
> Closed {date}. No new entries matched in {N} days.

---

## The Roast (modal bottom sheet)

P1 conditional. Do not implement before the normal Pattern List and Pattern Detail are working with sourced evidence.

Header (all-caps mono eyebrow — consistent with other eyebrow labels):
> `{PERSONA} · ROAST · {DATE}`

Body — the roast itself, 3-5 lines, persona-specific. Examples already in `design-guidelines.md`. Persona-flavored, not data recitation.

Source line at bottom:
> *Drawn from {N} entries · Last 30 days*

Footer actions:
- **Close**
- **Wipe everything.** *(destructive — routes to DestructiveScreen)*

If no roast available (insufficient data) — persona-aware:
- Witness: `Not enough entries yet. Come back when there's something to observe.`
- Hardass: `{N} entries. Not enough to work with. Keep recording.`
- Editor: `Vocabulary sample too thin. {N} entries logged. Need more.`

Loading state (model generating):
> Eyebrow: `{PERSONA} · ROAST · {DATE}`
> Body: `Reading 30 days...`

---

## Empty States (additional)

### Capture history (no entries yet)
> Eyebrow: `HISTORY`
> Header: `No entries yet.`
> Body: `First one takes 30 seconds.`

### Pattern detail — no sources
Should not occur in normal flow (a pattern requires entries). If it renders:
> `No entries logged for this pattern yet.`

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
| Mic permission denied (first time) | `Mic permission required to record. Settings → Permissions.` |
| Mic permission permanently denied (system-level, "don't ask again") | `Mic blocked at the system level.` / `Settings → Apps → Vestige → Permissions → Microphone.` / secondary action: `Use typed entry instead` |
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

- **Default input:** voice. Typed entry is an always-available alternate input but, like voice, requires the local model to be Ready (ADR-013 — it runs the same foreground call and reviews identically). Voice is the entry-point per product positioning. No setting toggle.
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
| Pattern dropped | `Dropped.` *(with Undo)* |
| Pattern skipped | `Skipped.` *(with Undo)* |
| Pattern restarted | `Pattern is back.` *(with Undo)* |
| Pattern closed (model) | *(no snackbar — silent state change, visible on next list load)* |
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
- Skip duration is fixed at 7 days in v1.
- Pattern closure is model-detected only. Users cannot manually resolve or close a pattern. Closed is earned by the data, not declared.
- User-facing lifecycle actions are exactly two: Skip and Drop. No third option.
- Export format is a zip of per-entry markdown files only. Rolled-up `.md` and PDF are v1.5+.
- No first-time mock data. Empty means empty; demo seed data is a dev/demo setup concern, not user-facing fiction.
- Loading copy stays distinct: `Reading the entry.` for single-entry inference, `Reading the file.` for Roast generation.
- No user name or handle in onboarding. Anonymity is on-brand and the feature didn't pass the demo-impact test. Handle system deferred to v1.5 (see `backlog.md`).
