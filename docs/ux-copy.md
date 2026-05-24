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

> _Final-polish reconciliation (2026-05-17):_ the onboarding screens were brought to pixel
> parity with `poc/onboarding-{persona,wiring,download}-final.png`. Net copy/structure changes,
> all reflected below: Screen 1 subhead gains "later"; each Wiring row gains a sentence-case
> **title line** above its description and a status dot replaces the ON/OFF/BLOCKED pill;
> Wiring gains a subhead; the Type row was never a Wiring row (typed entry is ADR-013 product
> behavior); Screen 3's single status line is rendered as a card (`{pct}%`, `OF {total}`,
> `ETA {mm:ss}`, `{done} / {total}`, `~{mbps} MB/S · WI-FI`). The headline terminal square is
> coral (see `design-guidelines.md` §First-Run Onboarding addendum). Chrome (`SETUP · NN OF
> 0N` + tick rule) is unchanged — the comps' `STEP n OF 3` line was not adopted.

### Screen 1 — Pick a persona

Header:
> **Pick a persona.**

Subhead:
> Three voices. Same product. Pick the one that fits today. You can switch later.

Persona cards (default Witness highlighted):
- **Witness** — Observes. Names the pattern.
- **Hardass** — Sharper. Less padding. More action.
- **Editor** — Cuts vague words until they confess.

Primary action:
> **Select**

Footer link:
> Change later in settings.

---

### Screen 2 — Wiring hub

Header:
> **Wiring.**

Subhead:
> The Local row is the only one that gates entry. Mic and Notify are optional.

Rows (mono left-label · sentence-case title line · description · trailing status dot —
lime = ready, dim = pending, coral = blocked):
- **PERSONA** — title `{persona name}` · `Set on the previous screen. Change it in Settings.`
- **LOCAL** — title `Download Gemma` · `No cloud. No servers. Stays on the device.`
- **MIC** — title `Grant mic` · `Read locally, then discarded. Text stays.`
- **NOTIFY** — title `Grant process` · `One status line while the model reads. Gone after.`

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
> **Let's Go**

Gate:
> Enabled only when the Local row is green.
> Shows green background when all rows are green.

Notification text when posted (per `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` §"Notification Contract"):
> Reading the entry.

This string is the same one used for mid-capture inference loading copy (see §"Loading States" below — single source of truth for both surfaces).

---

### Screen 3 — Model download

> _Shared-card note (2026-05-18):_ the progress block here is the **same** `ModelDownloadCard`
> the Settings → Model Status downloading state renders (one component, two hosts) — see
> §"Local Model Status" Downloading-state reconciliation. Only the surrounding chrome differs
> (`STEP 3 OF 3` + no bottom nav here; scoreboard + bottom nav there).

Header:
> **Download model.**

Body line 1 (active):
> {bytes downloaded} / {total} · {ETA}

Body line 2 (status):
> Quiet for a minute. This could take a while.

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

### Hero

> **WHAT HAPPENED?**

Poster headline, same treatment as onboarding headlines but the trailing `HAPPENED?`
renders in lime (not a coral period-square). Idle shows it full-bright; when the type
sheet is open the modal scrim dims it. Source of truth for `CaptureCopy.HERO_QUESTION`.

### Status row (top)

- Local model status indicator: `GEMMA 4 · LOCAL ONLY` (when idle, model loaded) / `GEMMA 4 · LISTENING` (when recording) / `GEMMA 4 · LOADING` (engine warming) / `DOWNLOADING · {N}%` (active download) / `DOWNLOAD PAUSED` (Wi-Fi dropped mid-download) — the pill is lime in every state except LISTENING, which renders coral; coral is otherwise reserved for the REC button heat + destructive flows (see `design-guidelines.md` §"Capture Screen / AppTop status pill"). _(Story 4.4: `GEMMA 4 · LOADING` is the reconciled label — the doc previously named only the idle/recording strings; the pill now reflects all four `ModelReadiness` states and is tappable post-onboarding to open the Model Status screen.)_
- Persona dropdown label: `WITNESS ▾` (or active persona)

### Patterns peek (below status)

Peek (above the bottom nav; informational, not tappable — final-polish 2026-05-18, replaces
the old card-title/body):
> Eyebrow: `● {N} ACTIVE PATTERNS` (lime)
> Teaser: `{pattern_name_1}  ·  {pattern_name_2}  ·  {pattern_name_3}` (up to 3, cream)
> Union 30-day TraceBar (lime)

If no active patterns (the peek is replaced by a single dim line):
> `NO ENTRIES YET · FIRST ONE TAKES 30 SECONDS`

If the model isn't ready (deleted / loading / downloading / Wi-Fi-paused — record + typed
both disabled, ADR-013): a large centered spinner stands in for the REC button, with a line
under it saying what's pending:
> Loading: `Loading the model. One moment.`
> Downloading: `Downloading the model. {N}%.`
> Paused: `Wi-Fi dropped. Reconnect to finish the model download.`

The AppTop status pill still carries the state too.

Status pill during download: `DOWNLOADING · {N}%`
Status pill if paused (no Wi-Fi): `DOWNLOAD PAUSED`

> _Final-polish note (2026-05-18):_ the old in-content model **banner** (`Model loading.
> Hang tight.` and the `MODEL · WARMING` / `MODEL · PAUSED` band labels) no longer fit the
> redesign and were removed. The model-not-ready state is now a big page spinner + the line
> above; the diagnostic band is mic / inference only.

> _Readiness-meaning note (2026-05-18, ADR-013 §Addendum):_ `Loading` is now an **honest
> engine-warm** state, not a sub-frame flash. A full-size artifact on disk is no longer
> `Ready` on its own — readiness holds at `Loading` (showing `Loading the model. One
> moment.`) until `engine.initialize()` actually completes, so REC/typed stay gated while a
> cold first inference would still stall. `Loading` legitimately covers both "no artifact"
> and "artifact present, engine warming".

### Center — record action

Record button label (when idle green dot, screen-reader content description):
> Record

Record button label (when recording red dot):
> Stop

Hint text under button (idle, optional, very subtle):
> Tap to talk.

Approaching chunk boundary (~25s):
> *(visual cue only, no copy)*

After tap-stop, while transcribing (1-5 sec target per ADR-002 §"Latency budget" — measurement-driven, not a contractual promise):
Spinner icon.
> Reading the entry.


### Capture Screen — Discard

Recording-state secondary affordance — a coral-outlined button that sits *above* `STOP · FILE IT` per `design-guidelines.md` §"Capture Screen / Discard" (final-polish 2026-05-18; was muted text below STOP).

Button label:
> DISCARD · DON'T SAVE

Behavior (per `adrs/ADR-001-stack-and-build-infra.md` §Q8):
- Single tap. No confirmation dialog, no long-press, no two-tap arming.
- Screen returns to idle immediately. No snackbar, no `Discarded.` confirmation, no `Undo` affordance.
- Visible only while `CaptureUiState` is `Recording`. Hidden once the user has tapped `STOP · FILE IT` (foreground call is in flight).

There is no error copy, no destructive confirmation copy, no post-discard toast. Silent dismissal is the contract.

> _Recording-modal note (2026-05-18):_ while `CaptureUiState` is `Recording` the screen is
> **modal** — the AppTop hamburger menu and the bottom navigation are both removed (the
> AppTop right slot is empty). An active mic capture cannot be routed away from; the only
> exits are `STOP · FILE IT` and `DISCARD · DON'T SAVE`. The menu/nav return on idle.

### Type affordance (bottom)

Button:
> Type

When expanded into text input, placeholder:
> What happened.

Send action:
> Save entry

---

## Entry detail

Single-exchange view (`poc/entry-full-final.png` resolved, `entry-loading-final.png`
extracting). Top → bottom: AppTop pill + hamburger; `← BACK`; the filed **time of day**
(hero, e.g. `10:04 PM`); `{DATE} · {DURATION} · {N} WORDS` eyebrow; the
`{PERSONA} · FOLLOW-UP` card (lime left-rule); then —

- **Resolved:** `● THREE-LENS READ` + status, the LITERAL / INFERENTIAL / SKEPTICAL columns,
  and the BEHAVIOR / PROMISES / REPEAT field grid with tone tags
  (`CONSENSUS` lime · `CONFLICT` coral · `AMBIGUOUS` ember · `CANDIDATE` teal).
- **Extracting:** `● EXTRACTING · 3 LENSES` with an animated spinner +
  "Convergence resolves in the background. Open the entry later for the full read.", and the
  lens/field areas render as skeletons.
- **Unreadable receipt:** a stored lens receipt that exists but cannot be parsed reads
  `unreadable` (coral / CONFLICT tone) instead of `not run` — a corrupt blob must not be
  misrepresented as "the lens never ran".
- **No receipt payload:** completed debug / seeded entries with no stored lens receipts omit the
  three-lens read and field grid entirely. The detail page must not invent static `not run` /
  empty-field data to make the comp look populated.

Then `YOU · TRANSCRIPT` (dim user transcription), `▸ TAGS` chips, and the shared bottom nav
(HISTORY active). No +NEW-ENTRY action (Capture tab covers it); no stat ribbon; no reading
card.

> _Final-polish note (2026-05-19):_ the 3-lens read + field grid are wired from persisted
> parsed lens receipts and resolved fields.
>
> _Update (2026-05-22):_ each lens receipt also persists the verbatim model output, surfaced on
> the detail page as a collapsed `● RAW MODEL OUTPUT` disclosure (tap to expand per-lens text).
> This is a demo/tuning affordance — it reverses the earlier perf-driven exclusion of raw
> responses.

---

## Local Model Status (standalone screen)

Reachable from the Settings **Model status** row or the tappable AppTop status pill.

> _Final-polish reconciliation (2026-05-18):_ rebuilt to the scoreboard comp
> `poc/model-detail-final.png`. The plain `Model status.` header + the bullet status-state
> list below are superseded by the structure here. Strings are verbatim from
> `app/src/main/res/values/strings.xml` (`model_status_*`). The earlier _Story 4.4
> reconciliation_ note is retained immediately below as historical context for why the v1
> runtime has only four states.

> _Story 4.4 reconciliation (historical):_ the v1 runtime has four `ModelReadiness` states —
> `Ready` / `Loading` / `Downloading(percent)` / `Paused`. The screen renders `Paused` as
> **`Download stalled.`**; a user-initiated **Re-download** surfaces as **Downloading** (not a
> distinct `Updating`); a failed re-download falls back to `Loading`. `Stalled` / `Failed` /
> `Updating` are not separate runtime states in v1.

Chrome:
> Back eyebrow: `← SETTINGS · MODEL STATUS`
> Headline: `MODEL STATUS` (ink) + `.` (coral) — annotated, same treatment as `SETTINGS.`

Status band (lime border + eyebrow when Ready, coral otherwise; polite live region):
> Eyebrow — Ready: `● MODEL READY · RUNNING LOCALLY`
> Eyebrow — not Ready: `● MODEL · NOT READY`
> Body — Ready: `Gemma 4 E4B · {size} · v{version} · On-device`
> Body — Loading (engine warming, artifact present): `Loading model.`
> Body — Absent (model deleted / no artifact): `Model file unreadable. Re-download from settings.`
> Body — Downloading: `Downloading model. Wi-Fi only.` + ` {N}%`
> Body — Paused: `Download stalled.`

> _Absent reconciliation (2026-05-18):_ `Loading` overloads "engine warming" and "no model
> on disk". A deleted model isn't loading — the band uses the canonical §"Error States"
> catalog string `Model file unreadable. Re-download from settings.` (the only sanctioned
> model-gone copy; deleted and corrupt present the same actionable state). Detected via the
> route's no-bytes-on-disk signal.

Stat ribbon:
> `{on-disk size}` · `ON DISK` — the *actual* artifact size; reads `0` once the model is
> deleted (not the nominal 3.66 GB).
> `0` · `CLOUD CALLS` (coral value — it is always zero, by design)

On-device stack (`● ON-DEVICE STACK` eyebrow; rows carry a trailing dot — lime when Ready,
coral when the model is gone, matching the band):
> `Gemma 4 E4B` · `TRANSCRIBE + EXTRACT` · `{size}`
> `EmbeddingGemma 300M` · `VECTOR · HYBRID` · `210 MB`
> `LiteRT-LM 0.11.0` · `RUNTIME` · `NATIVE`

Network-gate band (coral border):
> Eyebrow: `● NETWORK GATE · SEALED`
> Body: `Allowlist: model artifact host only.`

Actions (outline buttons):
- **Re-download model**
- **Delete model**

> _Downloading-state reconciliation (2026-05-18, `poc/model-detail-downloading-final.png`):_
> while `readiness` is `Downloading` the screen swaps to the **shared download card** — the
> exact same hero-percent / ETA / progress-bar / bytes / MB·s block onboarding Screen 3 uses
> (one `ModelDownloadCard`, two hosts; the inner card is pixel-identical across both comps).
> Layout in this state: AppTop pill `● GEMMA 4 · DOWNLOADING · {N}%`; a lime band
> `● DOWNLOADING MODEL · WI-FI ONLY` wrapping the card (polite live region); the stat ribbon
> becomes `{GB pulled}` · `GB PULLED` + `0` · `CLOUD CALLS`; the network-gate band switches
> to `● NETWORK GATE · ALLOWLIST ACTIVE 1 HOST` / `Model artifact host only. Closes the
> moment the pull completes.` and its border/eyebrow render **lime** while the pull is
> active (active allowlisting reads as "this is on / working"; the sealed-at-rest gate stays
> coral) — an intentional deviation from the comp's red gate. The on-device stack is hidden;
> the action row is a single full-width **PAUSE** (the Re-download/Delete buttons keep their
> gray/coral register — the download surface is not outlined green). PAUSE cancels the
> in-flight pull and keeps the `.part` so a later Re-download resumes via HTTP-Range;
> readiness drops to `Paused`.

Both route through the shared scoreboard confirm card (`VestigeConfirmCard`) using the
canonical §"Destructive Confirmations" wording below — not a Material dialog, not a shorter
summary. Bottom nav is present (no tab active — Model Status is a menu destination).

---

## Persona Selector (settings)

> _Final-polish reconciliation (2026-05-18):_ persona is **not** a standalone screen with a
> Save button. It is the `PERSONA` section of the Settings screen — three name-only rows; the
> active one carries a `SELECTED` tag and a lime treatment; tapping a row commits immediately
> (no Save action, no descriptions — Settings is not the onboarding pitch). The header /
> subhead / Save copy below is superseded and kept only as historical context.

Header:
> **Persona.**

Subhead:
> Default voice. Changes how the model talks back. You can override per capture.

Selected-row tag:
> SELECTED

---

## Pattern List

Header:
> **Vestiges.** *(screen headline only — brand word; the nav tab + section headers stay "Patterns" / `ACTIVE` etc. Function in navigation, brand in the heading. Reconciled 2026-05-18.)*

Action button (top right) — persona-aware:
- Witness: **Roast me**
- Hardass: **Run the numbers.**
- Editor: **Audit my vocabulary.**

Section headers (uppercase, mono eyebrow — one per non-empty section per `poc/patterns-final.png`):
> ACTIVE
> SKIPPED · ON HOLD
> CLOSED · DONE
> DROPPED

Filter chips (small, secondary text — Phase 4 polish on top of the section structure):
> All · Active · Skipped · Closed · Dropped

Pattern card structure (top → bottom):

> {SEMANTIC LABEL — uppercase mono eyebrow, section-tone colored: lime active / ember skipped / teal closed-dropped}
> **{Pattern name}**
> {One-line observation}
> {30-day TraceBar}
> {N} of {M} entries · Last seen {date}

> _Data-slot reconciliation (2026-05-19):_ the eyebrow slot stays because it is part of the
> `poc/pattern-lifecycle-final.png` layout. Current v1 binding is stored `pattern.kind`
> because `template_label` is untrusted. If a redesigned, trusted `templateLabel` lands,
> it may replace `pattern.kind` in this same slot. Never use screenshot sample copy.
> The card is one shared `PatternCard` component so every surface stays identical.

Card actions (per card, in overflow menu):
- **Skip**
- **Drop**

Card actions (non-active cards, in overflow menu):
- **Restart**

Note: CLOSED · DONE is model-detected only — the model auto-closes a pattern when it stops appearing in entries (v1.5, `pattern-auto-close` backlog). The section exists in v1 but stays empty until that ships. There is no user Close action (`spec-pattern-action-buttons.md`). DROPPED is user exclusion. Both support Restart.

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

No inaccurate agent-emitted template label or archetype eyebrow appears in the UI. A future trusted semantic label may appear only where the POC layout has a label slot.

> _Update (2026-05-22):_ `template_label` is now trusted (single-pass 3-lens picks it, convergence-voted, proven on-device). It occupies the Entry Detail top label slot as a lime pill, by **display name** — `TemplateLabel.displayName`: Crashed / Deep Space / Busy Stalling / Nonstop Spiral / Goblin Hours / Brain Dump. The kebab serial stays internal (markdown / export). Pattern Detail still binds `pattern.kind` for its title.

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
> Apr 26 — stuck on it again

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

## Vocab Drift 🧩

Surfaces from Pattern Detail → "View vocab drift →" when a VOCAB_FREQUENCY pattern has 6+ supporting entries clustered by EmbeddingGemma. Proves the embedding payoff: same underlying state, distinct vocabulary framings that tag matching never connects.

### Affordance (PatternDetail)
> View vocab drift →

### Eyebrow (top of screen)
> VOCAB DRIFT

### Headline format
> "{root_token}" × {total_entries}

Example: `"tired" × 23`. The root token wraps in straight double quotes; total is the sum across clusters.

### Subtitle
> {K} distinct framings of the same underlying state.

If K = 1:
> One framing across these entries — vocabulary stayed consistent.

### Distribution bar (a11y)
Merged content description, no click action:
> Vocabulary distribution: {label1}: {pct1}%, {label2}: {pct2}%, {label3}: {pct3}%.

### Cluster card
- **Title** — comma-joined top distinctive tokens (≤24 chars, ellipsized if longer)
- **Subline** — `{N} entries · framings: a, b, c`
- **Example snippet** — first 140 chars of the centroid-nearest member, in straight quotes

### Empty / absent states

NotYetClustered (right pattern, no clusters yet):
> Not enough evidence yet. Vocab drift surfaces after the model finds at least six related entries.

NotFound (wrong pattern id / wrong kind / data invariant break):
> Vocab drift isn't available for this pattern.

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
> Header: `Nothing recorded yet.`
> Body: `First one takes 30 seconds.`

> _Reconciliation (2026-05-18):_ header was `No entries yet.`; now `Nothing recorded yet.`
> and rendered with the **same** treatment as the Patterns empty state — shared
> `accentedHeadline` (`displayBig`, uppercased, final token lime per
> `poc/patterns-empty-final.png`). One headline component, two screens.

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
| Inference failed (parse) | `Model couldn't read that. Try again.` |
| Inference failed (engine) | `Reading failed. Try again.` |
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
- Export all entries (zip of markdown + stored data snapshot)
- Delete all data

Section: **Model**
- Status (link to Local Model Status screen)
- Re-download
- Delete model

Section: **About**
- Version
- Source code (link to GitHub)
- License

> _Story 4.9 reconciliation:_ the screen header is `Settings.` (this section named no header string — derived to match the `Model status.` screen-header pattern). The **Model** section is a single **Model status** row that opens the Story 4.4 screen; Re-download / Delete model live there with their canonical confirm dialogs, so they are reached by delegation rather than duplicated here (one destructive-confirm implementation, per KISS / no-duplicate-flows). The **Persona** section lists the three names only (no descriptions — settings is not the onboarding pitch). Export uses the Storage Access Framework `CreateDocument` picker — no `FileProvider`, no storage permission (`AGENTS.md` storage constraint). The zip contains generated readable entry markdown plus `vestige-export.json`, a full stored-data snapshot for later recovery. Delete-all wipes ObjectBox (entry/pattern/tag/callout), legacy markdown sidecars from older builds, and onboarding prefs, then returns to the first-run flow.

> _Final-polish reconciliation (2026-05-18):_ shipped specifics on top of the above —
> back eyebrow `← BACK · SETTINGS`; section eyebrows `PERSONA` / `DATA` / `MODEL` / `ABOUT`
> each lead with a **gray** dot (neutral section marker, not a live-status signal); every
> row uses one uniform trailing `→` glyph (the Model status row matches the rest — no
> special chevron). The active persona row carries a `SELECTED` tag. The **About** section
> folds the license **under** the version line as a second dim line: `Version` / `v{version}`
> with `Polyform Shield 1.0.0` beneath it; `Source code` opens the GitHub repo. Delete-all
> uses the shared scoreboard `VestigeConfirmCard` (armed only when the field reads `DELETE`).
> Tapping the AppTop menu button while Settings is open **closes** Settings and returns to
> the screen it was opened from (Capture / Patterns / History) — the menu toggles.

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
| Export complete | `Entries exported.` |
| Export failed | `Export failed. Nothing was written.` |
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
| Mid-session inference (after tap-stop, awaiting call-1 transcription) | `Reading the entry.` |
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
- Export format is a zip with per-entry markdown plus `vestige-export.json` for ObjectBox rows, pattern links, vectors, cooldown state, and onboarding settings. Rolled-up `.md` and PDF are v1.5+.
- No first-time mock data. Empty means empty; demo seed data is a dev/demo setup concern, not user-facing fiction.
- Loading copy: `Reading the file.` for Roast generation.
- No user name or handle in onboarding. Anonymity is on-brand and the feature didn't pass the demo-impact test. Handle system deferred to v1.5 (see `backlog.md`).
