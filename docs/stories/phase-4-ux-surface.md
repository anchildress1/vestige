# Phase 4 — UX Surface

**Status:** Not started
**Dates:** TBD — kicks off after Phase 3 exits with STT-E resolved (Story 3.4 either Done or explicitly skipped)
**References:** `PRD.md` §Phase 4, `concept-locked.md`, `design-guidelines.md` (entire), `ux-copy.md` (entire), `AGENTS.md`, `architecture-brief.md`, `adrs/ADR-002-multi-lens-extraction-pattern.md` §Q3 (re-eval cost story)

---

## Goal

Wrap the app in a coherent, dark, atmospheric UX that meets the 10-second judge test — opening the app makes it obvious this is a *local AI cognition tracker*, not another journaling app. Onboarding handles the 3.66 GB model download gracefully. Capture, History, Patterns, and Settings are all reachable, navigable, and styled to the locked palette and typography. Three top error states have polished handling. Empty states use the locked microcopy. P1 features (per-session persona override, Re-eval/Reading screen if STT-D passed, Roast me bottom sheet if pattern evidence is solid) ship if scope holds.

**Output of this phase:** the demo-ready app on the reference device. Every primary surface from `design-guidelines.md` §"Screen Specs" is implemented to spec. Phase 5 (demo optimization) starts with a stable, polished app — not a half-styled scaffold.

---

## Phase-level acceptance criteria

- [ ] Design language pass complete: locked palette (`#0A0E1A` background, `#A855F7` purple primary accent, `#2563EB` electric blue active accent) applied across all screens. Typography is system sans (Roboto/Inter) with the contrast targets from `design-guidelines.md` §"Visual System".
- [ ] Onboarding 7-screen flow per `ux-copy.md` §Onboarding works end-to-end on a fresh install on the reference S24 Ultra.
- [ ] Model download UX handles Wi-Fi gating, real progress, retry on stall/failure, and survives app restart mid-download.
- [ ] Persistent Local Model Status surface exists and is reachable from app shell or settings; status is accurate.
- [ ] Capture screen polished per `design-guidelines.md` §"Capture Screen" with active-record blue, live waveform during recording, transcription appearing 1-3 seconds post-chunk, conversation transcript with muted user turns + primary model turns.
- [ ] History list, Entry Detail, Pattern List, and Pattern Detail are all polished and navigable per their `design-guidelines.md` specs.
- [ ] Settings screen P0 scope works: persona default, export all entries (zip of markdown), delete all data, model status / re-download / delete.
- [ ] Empty states across major screens use the locked microcopy from `ux-copy.md` §"Empty states".
- [ ] Top three error states polished: download fail/stall, inference timeout/fail, mic permission denied/unavailable.
- [ ] P1 stories shipped or explicitly punted to v1.5 with a recorded reason (scope held / didn't hold).

---

## Stories

### Story 4.1 — Design language pass

**As** the AI implementor, **I need** the locked design system from `design-guidelines.md` (palette, typography, spacing, shape, motion, accent rules) applied as Compose theme tokens and reusable component styles, **so that** every Phase 4 UI story implements against shared tokens instead of re-deciding colors and sizes per screen.

**Done when:**
- [ ] `:app` Compose theme defines color tokens for the locked palette: `Background = #0A0E1A`, `DeepSurface = #0E1124`, `SurfaceLevels = [#161A2E, #1E2238, #2A2E48]`, `PrimaryText = #E8ECF4`, `MistGray = #7B8497`, `PrimaryAccent = #A855F7` (purple), `ActiveAccent = #2563EB` (blue).
- [ ] Typography uses system sans (Roboto on Android default) with the contrast ratios from `design-guidelines.md` §"Visual System" — body text ≥4.5:1 (WCAG AA), primary content ≥7:1 (WCAG AAA target).
- [ ] Shape tokens: 6–8px radius for cards and primary controls, 4px for small chrome.
- [ ] Motion tokens follow the restraint rule from `design-guidelines.md` §"Shape, Spacing, Motion" — Material 3 expressive motion *suppressed*; only functional state transitions remain.
- [ ] Each accent's allowed scope per `design-guidelines.md` §"Where each accent lives" is encoded as Compose modifier conventions (e.g., `.purpleLeftRule()` for active patterns, `.activeRecordingFill()` for the record button when recording).
- [ ] No light theme. Dark mode is the only theme. The Compose theme does not branch on `isSystemInDarkTheme()`.
- [ ] Predictive back gestures are wired per `design-guidelines.md` §"Shape, Spacing, Motion" — restrained, native, no fighting the system default.

**Notes / risks:** Material 3's expressive features default to bolder motion and shapes. We use the system but suppress expressivity per `design-guidelines.md`. If a Material 3 component has a "subtle" / "standard" / "expressive" variant, pick subtle/standard. No carousels, no FAB menus, no vertical floating toolbars unless they earn their keep.

---

### Story 4.2 — Onboarding flow

**As** a first-time user, **I need** to walk through the 7 onboarding screens per `ux-copy.md` §Onboarding without dead-ends, **so that** I can pick a persona, grant mic permission, see a typed-fallback affordance, and start the model download — and so that judges installing the APK have a credible first 60 seconds.

**Done when:**
- [ ] Screen 1 — Persona pick: three persona cards (Witness default highlighted, Hardass, Editor) with the locked one-line descriptions per `ux-copy.md`. Tap-to-select + Continue.
- [ ] Screen 2 — Local processing explainer: copy from `ux-copy.md` §"Screen 2". One primary action.
- [ ] Screen 3 — Microphone permission: copy from `ux-copy.md` §"Screen 3". Allow + Skip-typing affordance. Denied path lands on the correct error state (Story 4.11).
- [ ] Screen 4 — Typed fallback explainer: copy from `ux-copy.md` §"Screen 4".
- [ ] Screen 5 — Wi-Fi check: branches per `ux-copy.md` §"Screen 5" based on connectivity.
- [ ] Screen 6 — Model download: hands off to Story 4.3.
- [ ] Screen 7 — First entry scaffold: copy from `ux-copy.md` §"Screen 7". Lands on the polished Capture screen (Story 4.5).
- [ ] Each onboarding screen uses one primary action and the design tokens from Story 4.1.
- [ ] Onboarding state survives backgrounding — closing the app between screens resumes at the same step.
- [ ] After completion, opening the app skips onboarding and lands directly on Capture.

**Notes / risks:** No "Welcome to your journey" copy anywhere, ever. `ux-copy.md` §"Things to NEVER Write" is the litmus test.

---

### Story 4.3 — Model download UX

**As** the user during onboarding (or returning to settings), **I need** the model download to handle real progress, real ETA, retry on failure, pause/resume, and Wi-Fi gating gracefully, **so that** the 3.66 GB download — which is the literal first impression on every install — doesn't feel broken.

**Done when:**
- [ ] Model download screen uses the `ModelArtifactStore` contract from Story 1.9 (download, SHA-256 verify, retry policy).
- [ ] Real progress: bytes-downloaded / bytes-total, real ETA, current download speed.
- [ ] Wi-Fi gate: if the device is not on Wi-Fi, the download does not start; the user is shown the "Open Wi-Fi settings" affordance per `ux-copy.md` §"Screen 5" — without Wi-Fi.
- [ ] Stalled state: if no bytes flow for >30 seconds, show stalled state with retry per `ux-copy.md` §"Loading — first-run model download".
- [ ] Resume: if the artifact host supports HTTP `Range`, resume from the last byte after retry. Otherwise restart with confirmation.
- [ ] Backgrounding: closing the app mid-download pauses; reopening shows the partial state and the user can resume.
- [ ] On corrupt artifact (SHA-256 mismatch on load), the download is invalidated and re-triggered automatically with a one-time visible message ("Model file unreadable. Re-downloading.").
- [ ] Download success transitions to onboarding Screen 7.

**Notes / risks:** No spinner with "Preparing your experience". `ux-copy.md` §"Loading" enforces functional copy ("Quiet for a minute. ~2.5 GB downloading on Wi-Fi.") — it's "~3.66 GB" in v1 since that's the actual artifact size; update the copy if it's still showing 2.5.

---

### Story 4.4 — Persistent Local Model Status surface

**As** the user (and as a judge taking the 10-second test), **I need** the app shell to surface a local-AI status indicator that's always visible (or one-tap accessible from settings), **so that** "this is local AI" is legible at a glance, not hidden in a deep menu.

**Done when:**
- [ ] App shell shows a compact `LOCAL · READY` (or `· LOADING` / `· DOWNLOADING` / `· STALLED`) status indicator per `design-guidelines.md` §"Capture Screen / Status row" and `ux-copy.md` §"Capture Screen / Status row".
- [ ] The status indicator uses the cool-blue active accent only when in the active state per `design-guidelines.md` §"Where each accent lives". `LOCAL · READY` (idle, model loaded) uses the system convention green dot per the locked palette discussion (system status indicators stay system colors).
- [ ] Tapping the status indicator opens the Local Model Status full screen, reachable from settings as well per `ux-copy.md` §"Local Model Status".
- [ ] Full screen shows: model name (`Gemma 4 E4B`), runtime (`Running locally`), version, on-device storage size, plus action affordances `Re-download model` / `Delete model` per `ux-copy.md` §"Local Model Status — Settings actions".
- [ ] Re-download confirms with the destructive flow per `ux-copy.md` §"Re-download model" (purple accent on confirm). Delete model confirms with `ux-copy.md` §"Delete model".
- [ ] Status accurately reflects state across all transitions (loading → ready, ready → downloading on re-download, downloading → ready on success, etc.).

**Notes / risks:** This is a 10-second-judge-test feature. If a judge installs the APK and never sees `LOCAL · READY` in their first minute of the demo video, the local-AI claim is harder to land verbally. The indicator earns its persistence.

---

### Story 4.5 — Capture screen polish

**As** the user during a session, **I need** the polished capture screen per `design-guidelines.md` §"Capture Screen" — record button states, live waveform during recording, chunk boundary indicator, transcript with the locked muted-user / primary-model treatment — **so that** the demo's primary surface lands and recording feels alive instead of a static button on a dark background.

**Done when:**
- [ ] Record button per `design-guidelines.md` §"Record button":
  - Idle: outlined ring, neutral cool gray.
  - Active recording: filled with electric blue `#2563EB`, with a live blue-tinted amplitude waveform rendered around or above the button.
  - Approaching 30s chunk boundary (~25s): thin progress arc around the button.
  - Touch target ≥72px.
- [ ] Conversation transcript per `design-guidelines.md` §"Conversation transcript":
  - Vertical scroll, chronological.
  - User turns: monospace `YOU` label + transcribed text in muted/dimmed tone (`MistGray`).
  - Model turns: monospace `WITNESS` (or active persona name) label + body text in `PrimaryText` weight.
  - No chat bubbles. Left-rule indicators distinguish speakers.
- [ ] Type affordance: small `Type` button at the bottom of the screen per `ux-copy.md` §"Capture Screen / Type affordance". Expanding opens a text input with placeholder `What just happened.` and a `Log entry` action.
- [ ] Persona switcher chrome: top-right pill `WITNESS ▾` (or active persona) per `ux-copy.md` §"Capture Screen / Status row". Tapping opens the persona selector. Per-session override (Story 4.12) hooks into this.
- [ ] Patterns peek: card per `ux-copy.md` §"Capture Screen / Patterns peek" showing `{N} active patterns` with a one-line teaser. Empty state when none.
- [ ] Footer metadata: `Last entry · {date} · {duration}` per `ux-copy.md` §"Capture Screen / Footer metadata". History link.
- [ ] The dead-middle problem from earlier mockups is resolved: empty space carries faint mist-gradient atmosphere or surfaces ambient state (last entry summary, current pattern peek). No literal forgotten pixels.

**Notes / risks:** `design-guidelines.md` calls out the `WITNESS / Record.` precious-typography problem from the earlier mockup. Don't reintroduce centered persona labels above primary actions. The persona is named in the chrome dropdown; the center of the screen is for the action.

---

### Story 4.6 — History list

**As** the user, **I need** a history list that lets me browse my saved entries by reverse-chronological order with each row showing template label, date, and a snippet, **so that** I can see what I've captured and tap to read a past entry.

**Done when:**
- [ ] History list reachable from the Capture screen footer per Story 4.5 and from the app shell.
- [ ] Each row shows: timestamp (relative for recent, absolute for older), template label (the agent-emitted one), and a one-line snippet from `entry_text`.
- [ ] Rows are sorted reverse-chronologically by `timestamp`.
- [ ] Tapping a row opens the Entry Detail screen (Story 4.7).
- [ ] Empty state: per `ux-copy.md` §"Empty state — no entries yet" — `Nothing on file.`
- [ ] No filter / search affordance in v1. Filter chips are P2 / v1.5 (`backlog.md` candidate, not yet logged — add only if user explicitly asks).
- [ ] Performance: list renders smoothly on the reference device with at least 100 entries (smoke test with seeded data).

**Notes / risks:** The history list is the third-most-touched screen after Capture and Patterns. Don't bloat it with metadata chips or relative-time animations. Plain rows, restrained typography, fast scroll.

---

### Story 4.7 — Entry Detail screen

**As** the user, **I need** an entry detail screen that shows my full transcript, the agent-emitted template label, the extracted tags, and the per-entry observation per `design-guidelines.md` §"Entry detail" (and where defined, `ux-copy.md`), **so that** I can review what I said and what the model heard from a single entry without leaving History.

**Done when:**
- [ ] Header shows: timestamp + template label.
- [ ] Body shows: the full conversation transcript (user turns muted, model turns primary, same treatment as Story 4.5).
- [ ] A "Tags" row below the transcript shows the model-extracted tags as quiet chips.
- [ ] A "Observation" section shows the 1-2 per-entry observations (from Story 2.13) with their evidence references.
- [ ] An overflow menu offers `Delete entry` (destructive flow per `ux-copy.md` §"Destructive Confirmations / Delete single entry").
- [ ] Reading / Re-eval section is **deferred to Story 4.13** as P1 contingent.
- [ ] Vocabulary chip cloud below the observation is **deferred to Story 4.13** if it ships at all (it ships only if STT-E passed; otherwise the observation copy carries the vocabulary observation in plain text).
- [ ] Source-link integration: tapping a pattern source from Story 3.10's pattern detail navigates here and the entry is highlighted briefly.

**Notes / risks:** Don't show audio waveform or play-back controls — audio doesn't persist. The entry detail is the *text* view. Per `design-guidelines.md` §"Conversation transcript", the user's transcribed words show but never as a waveform.

---

### Story 4.8 — Polished Pattern List + Pattern Detail

**As** the user, **I need** the pattern list and pattern detail screens from Phase 3 (Stories 3.9 / 3.10) polished to spec — filter chips, persona-flavored empty states, action affordance microcopy, the "Roast me" anchor button per `ux-copy.md` §"Pattern List" — **so that** the demo's pattern moment is visually clean and the persona voice carries through into a surface that's not the capture screen.

**Done when:**
- [ ] Pattern list header includes the `Roast me` button per `ux-copy.md` §"Pattern List / Action button" (the Roast bottom sheet itself is Story 4.14, P1 contingent — the button can land here as a no-op or hidden state if Story 4.14 doesn't ship).
- [ ] Filter chips: `All · Active · Snoozed · Resolved` per `ux-copy.md` §"Pattern List / Filter chips". Default `Active`.
- [ ] Empty states per `ux-copy.md` §"Pattern List / Empty states":
  - No entries yet: `Insufficient data.`
  - Has entries, no patterns: `Nothing repeating yet.`
  - All dismissed: `All clear.`
  - Filter returns nothing: `Nothing matches.`
- [ ] Pattern card uses the purple left-rule treatment per `design-guidelines.md` §"Pattern card" only on cards with `state=active`. Snoozed/resolved/dismissed cards lose the rule.
- [ ] Pattern action overflow menu uses the locked microcopy: `Dismiss` / `Snooze 7 days` / `Mark resolved`.
- [ ] Snackbars after each action use `ux-copy.md` §"System Messages" copy with `Undo` affordance.
- [ ] Pattern detail screen polished per `design-guidelines.md` §"Pattern Detail":
  - Source list rows are clickable to entry detail (Story 4.7).
  - Action row at the bottom uses the same actions as the list.
  - Vocabulary chips below the observation **only if STT-E passed** (Story 3.4 ran). If STT-E failed, no chip cloud.
  - Resolved patterns show `Marked resolved {date}` per `ux-copy.md`.

**Notes / risks:** `Roast me` button visibility is gated on Story 4.14 shipping. If 4.14 doesn't ship in v1, hide the button rather than showing a button that does nothing. (Per `AGENTS.md` and the scope rule, don't ship dead UI.)

---

### Story 4.9 — Settings screen (P0 scope)

**As** the user, **I need** a settings screen with the v1 P0 scope from `PRD.md` §Phase 4 / `ux-copy.md` §Settings — persona default, export all entries (markdown zip), delete all data, model status / re-download / delete — **so that** the privacy and data-sovereignty claims have implementations a judge can poke.

**Done when:**
- [ ] Settings reachable from the app shell.
- [ ] Sections per `ux-copy.md` §Settings:
  - **Persona**: default persona (Witness / Hardass / Editor).
  - **Data**: Export all entries (zip of markdown) + Delete all data (destructive flow).
  - **Model**: Status (link to Story 4.4 full screen) + Re-download + Delete model.
  - **About**: version, GitHub source link, license.
- [ ] Export all entries: produces a zip of all markdown files from `MarkdownEntryStore` (Story 1.7) and hands the file to Android's system share/picker flow per the `AGENTS.md` constraint on storage permissions ("Do not request broad storage permission for internal markdown/ObjectBox; exports use Android's system picker/share flow").
- [ ] Delete all data: destructive flow per `ux-copy.md` §"Destructive Confirmations / Delete all data". Requires typing `DELETE` to confirm. Wipes ObjectBox + all markdown files. Returns the user to onboarding.
- [ ] **Settings explicitly NOT in v1 P0**: pattern threshold, cooldown, default-input toggle, transcription-visibility toggle. These are removed from `ux-copy.md` for v1 per the PRD note. Don't add them.

**Notes / risks:** Export-to-zip is the implementation of the data-sovereignty claim. If it doesn't work, the privacy story has a hole.

---

### Story 4.10 — Empty states across major screens

**As** the user, **I need** every primary screen to render gracefully when it has no data — first launch, no entries, no patterns, filter returning nothing — using the locked microcopy from `ux-copy.md` §"Empty States", **so that** the app never shows a blank surface or a generic "Nothing here yet :)".

**Done when:**
- [ ] Capture screen patterns peek empty: `Nothing repeating yet.` (Story 4.5 already covers this; verify the copy matches).
- [ ] History list empty: `Nothing on file.` (Story 4.6 already covers; verify).
- [ ] Pattern list empty (per state): all four states from Story 4.8 covered.
- [ ] Entry detail can never truly be empty (an entry always has at least transcribed text), but if the model returned zero observations, the Observation section displays in a non-broken way (e.g., omitted entirely — never shows "No observations" with a sad face).
- [ ] Settings sections are never empty (always have at least the action rows).
- [ ] Onboarding doesn't have empty states; it has the sequential-screen flow already.
- [ ] None of the empty states use exclamation points, emoji, or any forbidden copy from `ux-copy.md` §"Things to NEVER Write".

**Notes / risks:** Empty states are where wellness apps love to insert "Take a deep breath, your patterns will appear soon ✨". Aggressively forbid this. The locked copy is short and dry.

---

### Story 4.11 — Top three error states

**As** the user, **I need** the three most-likely failure modes — model download fail/stall, inference timeout/fail, mic permission denied/unavailable — to render clear error states with retry affordances per `ux-copy.md` §"Error States", **so that** the most predictable failure paths in the demo (or in real use) don't end the session.

**Done when:**
- [ ] **Model download fail/stall**: handled within Story 4.3's flow. `ux-copy.md` strings: `Download stalled. Retry.` / `Network choked.`. Retry button works. After 3 failed attempts, shows the failed state and surfaces the user to settings → re-download.
- [ ] **Inference timeout / fail**: appears in the conversation transcript when the foreground or background extraction call fails. `ux-copy.md` strings: `Model timed out. Try a shorter chunk.` / `Model couldn't read that. Try again.`. Retry option re-runs the same call.
- [ ] **Mic permission denied or unavailable**: capture screen shows `Mic permission required to record. Settings → Permissions.` per `ux-copy.md`. Tapping the deep-link opens system settings. If the mic is unavailable (hardware), shows `Mic unavailable. Try typing.`.
- [ ] All three error states use the design tokens from Story 4.1 (no orange-warning convention; system status colors only).
- [ ] Other error states from `ux-copy.md` §"Error States — catalog" are implemented as strings/handlers but get *unpolished* presentations (toast / generic dialog) only when naturally encountered. We polish only the top three for v1; the rest defer to v1.5 per the scope rule.

**Notes / risks:** No "Oops" anywhere. No "Something went wrong". `ux-copy.md` §"Rules" item 9 — say what broke.

---

### Story 4.12 — P1: Per-session persona override

**As** the user, **I need** to switch personas mid-session by tapping the persona dropdown chrome from Story 4.5 and the change to take effect on the next foreground call (without affecting prior turns), **so that** I can switch tones if Witness is being too gentle today without leaving capture.

**Done when:**
- [ ] Tapping the persona dropdown in the capture screen chrome opens a small selector (segmented control or list) with the three personas and a checkmark on the active one.
- [ ] Selecting a different persona updates the session's active persona via Story 2.3's `setPersona(persona)` API.
- [ ] The change takes effect on the next foreground call. Prior turns retain their original persona's voice.
- [ ] The chrome label updates to reflect the new active persona (`WITNESS ▾` → `HARDASS ▾`).
- [ ] The change does not persist as a default — that's a separate Settings action (Story 4.9). It's session-scoped.
- [ ] Per-session override is **P1**: ships only if Phase 4 scope holds. If we're behind, this drops to v1.5 with the note "session always uses default persona; switch from Settings between sessions."

**Notes / risks:** Story 2.3 already exists and works at the API level. This is purely UI plumbing.

---

### Story 4.13 — P1: Reading / Re-eval on entry detail

**Skipped if STT-D failed in Phase 2** (multi-lens architecture was dropped). If STT-D passed, this story is **P1**: ships only if Phase 4 scope holds.

**As** the user, **I need** an expandable "Reading" section on the entry detail screen that re-runs the 3-lens pipeline against the saved transcript and shows the diff per surface field, letting me accept the new shape or keep the original, **so that** I can re-process old entries with the latest prompts and see *how the model got here* per `concept-locked.md` §"Re-eval (\"Reading\")".

**Done when:**
- [ ] Entry detail screen shows a collapsed "Reading" section by default. Expanding opens the per-lens output view per `design-guidelines.md` §"Pattern Detail" (similar treatment).
- [ ] An action affordance `Re-read` triggers a fresh 3-lens pass via Story 2.6's background worker on the existing `entry_text`.
- [ ] During re-read, a placeholder per `ux-copy.md` §"Loading States — Roast generation" or similar shows. The user can leave the screen and the work continues in the background.
- [ ] When the re-read completes, the per-lens outputs are shown side-by-side with the original convergence-resolved fields. Differences are highlighted.
- [ ] User affordances: `Apply this read` (replaces the saved canonical fields with the new ones) or `Keep original` (discards the new read).
- [ ] If the re-read converges to the same shape, copy: `Confirmed. Same shape.`
- [ ] Vocabulary chips appear in the Reading section if STT-E passed and Story 3.4 shipped.

**Notes / risks:** This is the architecture's most legible demo moment. If it doesn't ship, the technical walkthrough loses that beat. If we're tight on time, prioritize this over Story 4.14.

---

### Story 4.14 — P1: Roast me bottom sheet

**Ships only if "normal pattern evidence is already solid"** (per `PRD.md` §P1) — meaning Story 4.8's pattern list and detail are demo-ready and we're not still chasing pattern-engine bugs. Otherwise, defer to v1.5 (`backlog.md` candidate, not yet logged — log if cut).

**As** the user, **I need** a Roast me bottom sheet from the patterns screen that produces 3-5 short, persona-flavored cuts on my full history per `design-guidelines.md` §"The Roast" and `ux-copy.md` §"The Roast", **so that** I have an on-demand "tell me what's repeating, sharper" moment that's distinct from the patterns list's data view.

**Done when:**
- [ ] Tapping `Roast me` from Story 4.8's pattern list header opens a Material 3 Modal Bottom Sheet per `design-guidelines.md` §"The Roast".
- [ ] Sheet header shows `{Persona} · Roast · {date}`.
- [ ] Body shows 3-5 lines (per `design-guidelines.md` §"The Roast — body") generated by a single model call composing the pattern data + persona system prompt with the explicit Roast tone instructions.
- [ ] Lines are *cuts*, not data recitations per the test in `design-guidelines.md` §"The Roast — distinction" — *"Tuesday meetings have a body count"* not *"Tuesday meetings: four entries"*.
- [ ] Footer: `Drawn from {N} entries · Last 30 days`, plus `Close` and `Wipe and start over` (destructive — purple accent) actions.
- [ ] Roast is ephemeral — not saved as a separate artifact. Regenerates fresh on each tap.
- [ ] Insufficient data fallback: if fewer than 10 entries, show `Insufficient data. Come back when you've left more behind.` per `ux-copy.md` §"The Roast — empty fallback".

**Notes / risks:** The hardest part is the system prompt that gets the model to produce *cuts*, not stats. Tune this against actual pattern data; if the model keeps producing flat data recitations, the prompt is wrong, not the architecture.

---

## What is explicitly NOT in Phase 4

- No demo storyboard work — Phase 5 owns it.
- No agentic tool-calling beat anywhere — cut entirely.
- No light theme — dark only.
- No notifications, reminders, or scheduled patterns surface — `backlog.md` candidates.
- No filter chips on history list — v1 has chronological only; filter by tag/template/date is P2.
- No vocabulary chip cloud on entry detail unless STT-E passed (Story 3.4) and Story 4.13 ships.
- No advanced settings (pattern threshold, cooldown tuning, default-input toggle, transcription-visibility toggle). Per the PRD note, these are removed from v1 `ux-copy.md`.
- No app icon / cover image design — Phase 6 owns it.
- No video / audio / vision input (Phase 4 is UX only, model capabilities are Phase 1/2).

If a Phase 4 story starts pulling a backlog entry or a Phase 5/6 task, stop. Reference the scope rule.

---

## Phase 4 exit checklist

Phase 5 starts when all the following are true:

- [ ] Stories 4.1 – 4.11 are Done. (Stories 4.12 – 4.14 are P1; their state is recorded as Done or Punted-to-v1.5 with a reason.)
- [ ] Onboarding flow runs end-to-end on a fresh install on the reference S24 Ultra.
- [ ] Capture screen, History list, Entry Detail, Pattern List, Pattern Detail, Local Model Status, and Settings all load and navigate correctly.
- [ ] Top 3 error states render correctly when triggered intentionally.
- [ ] No regressions to STT-A / STT-B / STT-C / STT-D / STT-E outcomes from Phases 1–3.
- [ ] APK installs cleanly on the reference device + at least one secondary device (any 2024+ flagship Android with 8+ GB RAM, 6+ GB free storage).
- [ ] No new entries logged to `backlog.md` from Phase 4 work that change the v1 contract.

If P1 stories were cut: log them in `backlog.md` (Story 4.13 → `reading-on-entry-detail` if it didn't ship; Story 4.14 → `roast-bottom-sheet` if it didn't ship). Update the dev.to post draft (Phase 6 work) to mention the v1.5 path for any cut features.
