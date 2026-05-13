# Phase 4 — UX Surface

**Status:** In progress (Story 4.1 shipped Mist tokens via PR #25; pivoted to Scoreboard direction per ADR-011 on 2026-05-13 — Story 4.1.5 carries the rebuild)
**Dates:** TBD — kicks off after Phase 3 exits with STT-E resolved (Story 3.4 either Done or explicitly skipped)
**References:** `PRD.md` §Phase 4, `concept-locked.md`, `adrs/ADR-011-design-language-scoreboard-pivot.md` (visual language — supersedes the Mist sections of `design-guidelines.md`), `design-guidelines.md` (product framing + forbidden vocabulary + screen list — visual sections superseded by ADR-011), `ux-copy.md` (entire — status-row label flips from `LOCAL · READY` to `LOCAL · GEMMA 4` / `ON AIR · LIVE` per ADR-011, rest holds), `poc/Energy Direction.html` + `poc/energy-tokens.jsx` + `poc/energy-screens.jsx` (canonical visual sources, replace deleted `poc/design-review.md`), `AGENTS.md`, `architecture-brief.md`, `adrs/ADR-002-multi-lens-extraction-pattern.md` §Q3 (re-eval cost story), `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` (entire — notification permission, tap target, fallback evaluation)

> **⚠ Design pivot — 2026-05-13.** Stories below were authored against the Mist direction (`poc/design-review.md`, now deleted). Per [ADR-011](../adrs/ADR-011-design-language-scoreboard-pivot.md), the visual language pivots to Scoreboard: warm espresso surfaces, electric lime as the single signal, alarm coral as recording / heat, Anton condensed display, Space Grotesk body, tape-grain on cards, no fog drift, "ON AIR · LIVE" recording chrome, box-score pattern cards with `▲N` / `▼N` deltas. References to `MistHero`, `Newsreader`, `glow`, `vapor`, `pulse` (Mist), `fog drift`, and `poc/design-review.md` §-anything below should be read as superseded — implement against `poc/energy-tokens.jsx` primitives (`BigStat`, `Pill`, `Delta`, `TraceBarE`, `StatRibbon`, `TickRule`, `EyebrowE`, `StatusDot`, `AppTop`) instead. Behavior bullets (what each screen *does*) are unchanged; primitive names and palette tokens are not.

---

## Goal

Wrap the app in a coherent, dark, atmospheric UX that meets the 10-second judge test — opening the app makes it obvious this is a *local AI cognition tracker*, not another journaling app. Onboarding handles the 3.66 GB model download gracefully. Capture, History, Patterns, and Settings are all reachable, navigable, and styled to the locked palette and typography. Three top error states have polished handling. Empty states use the locked microcopy. P1 features (per-capture persona selection, Re-eval/Reading screen if STT-D passed, Roast me bottom sheet if pattern evidence is solid) ship if scope holds.

**Output of this phase:** the demo-ready app on the reference device. Every primary surface from `design-guidelines.md` §"Screen Specs" is implemented to spec. Phase 5 (demo optimization) starts with a stable, polished app — not a half-styled scaffold.

---

## Phase-level acceptance criteria

- [ ] Design language pass complete per ADR-011 §"Token additions": Scoreboard palette (`floor` / `deep` / `s1` / `s2` / `s3` / `ink` / `dim` / `faint` / `ghost` / `hair` / `lime` / `coral` / `teal` / `ember` + soft variants), three-font system (`Anton` display / `Space Grotesk` body / `JetBrains Mono` forensic), `sb*` motion keyframes, tape-grain surface texture on cards (halftone on call-outs), Scoreboard primitives (`BigStat` / `Pill` / `Delta` / `TraceBarE` / `StatRibbon` / `TickRule` / `EyebrowE` / `StatusDot` / `AppTop`) applied across all screens. Mist symbols deleted from `:app`. Contrast targets unchanged: body ≥4.5:1 (WCAG AA), primary content ≥7:1 (AAA).
- [ ] Onboarding 8-screen flow per `ux-copy.md` §Onboarding (includes Screen 3.5 notification permission per `adrs/ADR-004-app-backgrounding-and-model-handle-lifecycle.md` §"Permission Flow") works end-to-end on a fresh install on the reference S24 Ultra.
- [ ] Model download UX handles Wi-Fi gating, real progress, retry on stall/failure, and survives app restart mid-download.
- [ ] Persistent Local Model Status surface exists and is reachable from app shell or settings; status is accurate.
- [ ] Capture screen polished per `poc/Energy Direction.html` capture frames + `poc/screenshots/{capture-still,capture-running}.png` — `AppTop` shell with `LOCAL · GEMMA 4` ↔ `ON AIR · LIVE` swap, big "ON AIR" record button (idle: outline; recording: coral fill + pulsing `StatusDot` + live timer + `TickRule` 30s countdown), `sbBars` audio meter primitive while recording, transcription appearing post-inference (latency budget per `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Latency budget" — 1–5 s target unmet on E4B CPU, currently ~24–33 s per `docs/stories/phase-2-core-loop.md` §Story 2.3 device record), entry transcript with muted user transcription + primary model follow-up (single-turn-per-capture per the STT-B v1 scope choice; see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`). The Mist `MistHero` / `AudioMeter` halo composition is **not** built — superseded by ADR-011.
- [ ] History list, Entry Detail, Pattern List, and Pattern Detail are all polished and navigable per their `design-guidelines.md` specs.
- [ ] Settings screen P0 scope works: persona default, export all entries (zip of markdown), delete all data, model status / re-download / delete.
- [ ] Empty states across major screens use the locked microcopy from `ux-copy.md` §"Empty states".
- [ ] Top three error states polished: download fail/stall, inference timeout/fail, mic permission denied/unavailable.
- [ ] Notification permission flow ships in onboarding (Screen 3.5 per ADR-004 §"Permission Flow"); notification tap target lands on the entry detail of the most-recent-in-flight extraction. Lifecycle fallback evaluation per ADR-004 §"Fallback Trigger" recorded by end of Phase 4 day 1 if invoked.
- [ ] P1 stories shipped or explicitly punted to v1.5 with a recorded reason (scope held / didn't hold).

---

## Stories

### Story 4.1 — Design language pass

**As** the AI implementor, **I need** the canonical visual system from `poc/design-review.md` applied as Compose theme tokens, primitives, and reusable styles, **so that** every Phase 4 UI story implements against shared tokens and primitives instead of re-deciding colors, type, or shapes per screen.

**Done when:**
- [x] `:app` Compose theme defines color tokens for the full palette per `poc/design-review.md` §2.1: `void` / `deep` (`#0A0E1A`), `bg` (`#0E1124`), `s1` (`#161A2E`), `s2` (`#1E2238`), `s3` (`#2A2E48`), `ink` (`#E8ECF4`), `mist` (`#7B8497`), `glow` (`#A855F7`), `vapor` (`#2563EB`), `pulse` (`#38A169`), `error` (`#B3261E`). (`ui/theme/Color.kt`; hex-locked via `DesignTokensTest`.)
- [x] Three font families wired per `design-review.md` §2.2: `Inter` (UI body), `Newsreader` italic with opsz axis (display moments only — app name and hero titles like the `What lingered from yesterday?` line in `poc/screenshots/capture.png`), `JetBrains Mono` (forensic labels, eyebrows, persona names). Compose translation notes in `design-review.md` §8. (`VestigeFonts` aliases `FontFamily.SansSerif` / `FontFamily.Serif` / `FontFamily.Monospace` per §2.2's explicit "system sans / Inter-like typography" authorization. Real .ttf bundling is a non-call-site swap.)
- [x] Type primitives implemented: `HDisplay` (display sans 38px), `H1` (sans 26px), `P` (15px), `PersonaLabel` (mono 10px / 0.24em), `Eyebrow` (mono 10px / 0.20em). Contrast targets per `design-guidelines.md` §"Color rules": body ≥4.5:1 (WCAG AA), primary content ≥7:1 (AAA target). (`VestigeTextStyles`; sizes + tracking locked via test. AA contrast holds on `Ink` (`#E8ECF4`) over `Bg` (`#0E1124`) at 16:1.)
- [x] Radius scale per `design-review.md` §2.3 (`rPill: 9999`, `rXL: 8`, `rL: 8`, `rM: 6`, `rS: 4`, `rXS: 4`) as a `RadiusTokens` object. No raw `dp` for corner shapes. (`ui/theme/RadiusTokens.kt`; values locked via test. Primitives consume `RadiusTokens.XL` / `Pill`.)
- [x] Atmospheric layer per `design-review.md` §2.4 implemented: noise grain (pre-baked tile, `BlendMode.Overlay`, opacity 0.05–0.18) and fog drift (two animated radial gradients, 22s/28s alternate, via `rememberInfiniteTransition`). Both layers persist on the currently reachable shell + pattern surfaces inside the phone frame. **Atmospheric is the single visual system per `design-review.md` §7.3 — no flat counterpoint.** (`Modifier.noiseGrain` tiles via `ImageShader(TileMode.Repeated)` per §8; `FogDrift` runs the 22 s / 28 s loop at the activity root; `VestigeSurface` applies grain on every higher-level primitive so screen-level work doesn't need to remember.)
- [x] Three shared Compose primitives built per `design-review.md` §7.3: `Surface` (glass + noise card), `Row` (key/value line), `ListCard` (selectable Surface variant). Reachable shell + pattern surfaces now compose against these; broader screen-by-screen polish remains in later Phase 4 stories. (`VestigeSurface`, `VestigeRow`, `VestigeListCard`. `PhaseOneShell`, pattern list/detail, and entry placeholder now consume them.)
- [x] Each accent's allowed scope per `design-guidelines.md` §"Where each accent lives" is encoded as Compose modifier conventions (e.g., `.glowLeftRule()` for active patterns, `.vaporHaloOnRecording()` for `MistHero`, `.pulseDotForReady()` for `LOCAL · READY`, `.errorFillForDestructive()` for wipe confirmations). (`ui/components/AccentModifiers.kt`. Halo guards NaN / negative levels; destructive fill has zero overrides — call-site can't dress brand styling as a wipe.)
- [x] No light theme. Dark mode is the only theme. The Compose theme does not branch on `isSystemInDarkTheme()`. (`VestigeTheme` uses a single `darkColorScheme(...)`; no `isSystemInDarkTheme()` call exists in `:app`.)
- [x] Motion: only functional state transitions and the atmospheric drift from `design-review.md` §2.5 keyframe set (`vesPulse`, `vesIn`, `vesFade`, `vesSlide`, `vesShimmer`, `vesBreath`, `vesSpin`, `vesDrift1`, `vesDrift2`). Material 3 expressive motion suppressed. (`VestigeMotion` + `rememberVes*` cover Pulse / Breath / Shimmer / Spin; `In` / `Out` / `Fade` / `Slide` specs cover the one-shots; `vesDrift1` / `vesDrift2` live inside `FogDrift`. M3 expressive variants are not consumed anywhere in the diff.)
- [x] Predictive back gestures are wired per `design-guidelines.md` §"Shape, Spacing, Motion" — restrained, native, no fighting the system default. (Android 15+ system default. `MainActivity` keeps the existing `BackHandler` for the in-app patterns nav unwind; nothing in the design-system foundation intercepts predictive back.)

**Notes / risks:** Material 3's expressive features default to bolder motion and shapes. We use the system but suppress expressivity per `design-guidelines.md`. If a Material 3 component has a "subtle" / "standard" / "expressive" variant, pick subtle/standard. No carousels, no FAB menus, no vertical floating toolbars unless they earn their keep.

**Addendum (2026-05-13) — superseded by ADR-011 + Story 4.1.5.**
Checked bullets above are the historical record that the Mist tokens shipped to `:app` in PR #25. The visual language has pivoted to Scoreboard ([ADR-011](../adrs/ADR-011-design-language-scoreboard-pivot.md)); the Mist palette, `Newsreader` italic, `MistHero`, fog drift, glow / vapor / pulse(Mist) accents, `VestigeSurface` / `VestigeRow` / `VestigeListCard` (Mist), and the `Modifier.noiseGrain` / `FogDrift` atmospheric pair are all replaced. Do not unwind 4.1 — Story 4.1.5 carries the rebuild and deletes the Mist symbols. This story stays checked as evidence that the Mist direction was implemented end-to-end before it was retired.

---

### Story 4.1.5 — Scoreboard design language re-pass

**As** the AI implementor, **I need** the Scoreboard visual language from `poc/energy-tokens.jsx` + `poc/Energy Direction.html` translated into Compose theme tokens, primitives, and motion specs, **so that** every Phase 4 UI story from 4.2 onward implements against the Scoreboard system instead of the retired Mist system — and so the Mist symbols stop existing in `:app`.

**Done when:**
- [ ] `:app` Compose theme replaces the Mist palette with Scoreboard tokens per ADR-011 §"Token additions": `floor`, `deep`, `s1`, `s2`, `s3`, `ink`, `dim`, `faint`, `ghost`, `hair`, `lime` (+ `limeDim` / `limeSoft`), `coral` (+ `coralDim` / `coralSoft`), `teal` (+ `tealDim`), `ember`. oklch source values in token comments; sRGB values land in `Color.kt` literals. (`ui/theme/Color.kt`; values locked via `DesignTokensTest` rewrite.)
- [ ] Three font families wired per ADR-011 §"Type stack": `Anton` (display — condensed, huge numbers and headers), `Space Grotesk` (sans body), `JetBrains Mono` (forensic labels — retained). `VestigeFonts` aliases stay (`FontFamily.SansSerif` / `FontFamily.Serif` / `FontFamily.Monospace`) until `.ttf` bundling earns its keep in Phase 5; the alias scheme remaps so `display` resolves to a condensed-fallback-friendly family and `sans` resolves to SansSerif. Type primitives: `DisplayBig` (Anton, 56–96px display), `H1` (Space Grotesk, 26px), `P` (Space Grotesk, 15px / line-height 1.55), `PersonaLabel` (mono 9.5px / 0.20em), `Eyebrow` (mono 9.5px / 0.18–0.24em). Contrast targets unchanged (body ≥4.5:1 WCAG AA; primary ≥7:1 AAA). `Newsreader` family removed.
- [ ] Radius tokens replaced per ADR-011: `rPill: 9999`, `rXL: 18`, `rL: 12`, `rM: 8`, `rS: 4`, `rXS: 2`. `RadiusTokens` test rewritten.
- [ ] Atmospheric layer per ADR-011 §"Surface texture": `TAPE_BG` (1px horizontal grain, 4px period, 3% opacity) on cards; `HALFTONE_BG` (0.7px dot at 1.2px period, 15% opacity) on call-out blocks. **`FogDrift` and `NoiseGrain` are deleted** — the activity root no longer mounts an atmospheric layer; tape-grain lives on the primitive (cards), not the screen.
- [ ] New Compose primitives shipped (translating `poc/energy-tokens.jsx`): `BigStat`, `Pill`, `Delta`, `TraceBarE`, `StatRibbon`, `TickRule`, `EyebrowE`, `StatusDot`, `AppTop`. `PhoneE` is POC-only and **not** translated. Primitives live under `ui/components/` (or `ui/scoreboard/` if a parallel package is cleaner during the migration). Each has a unit / Compose test covering layout invariants (tabular nums on stats, mono tracking on labels, accent boundedness — one accent per element).
- [ ] Motion keyframes replaced (`sbPulse`, `sbBlink`, `sbScroll`, `sbTick`, `sbBars`, `sbSweep`, `sbWobble`, `sbRise`). `VestigeMotion` rewritten. The old `vesPulse` / `vesBreath` / `vesIn` / `vesFade` / `vesSlide` / `vesShimmer` / `vesSpin` / `vesDrift1` / `vesDrift2` symbols are removed. M3 expressive motion suppression retained.
- [ ] Accent modifier conventions rewritten: replace `glowLeftRule` / `vaporHaloOnRecording` / `pulseDotForReady` with `limeLeftRuleForActive` / `coralHaloOnRecording` / `limeDotForReady` (or equivalent). `errorFillForDestructive` retained; re-themed to coral (alarm coral and destructive share the heat semantic — that is intentional per ADR-011's accent system).
- [ ] Mist symbols deleted (no compat layer per AGENTS.md rule 16): `Glow`, `Vapor`, `Pulse` (Mist), `Mist`, `MistHero` (was not yet built; just the symbol reservation goes), `FogDrift`, `NoiseGrain`, `Modifier.noiseGrain`, `Modifier.glowLeftRule` / `vaporHaloOnRecording` / `pulseDotForReady`, the Mist `VestigeSurface` / `VestigeRow` / `VestigeListCard` definitions (re-implemented against Scoreboard tokens — same call sites work).
- [ ] No light theme. Single `darkColorScheme(...)` only. `isSystemInDarkTheme()` not called in `:app`.
- [ ] Existing screens (`PhaseOneShell`, `PatternsListScreen`, `PatternDetailScreen`, `EntryDetailPlaceholderScreen`) re-skinned to compile against the new primitives. Behavior unchanged.
- [ ] Tests rewritten / replaced: `DesignTokensTest`, `VestigePrimitivesTest`, `AtmosphereTest`, `FogDriftDrawScopeTest`, `FogDriftComposeTest` (deleted), `AccentDrawScopeTest`, `VestigeMotionTest`, `VestigeMotionComposeTest`. Coverage gate per `:app/build.gradle.kts` does not regress. New Scoreboard primitive tests included.
- [ ] Predictive back gestures unchanged.

**Notes / risks:** This is the forcing-function story for the design pivot. The rebuild deletes Mist symbols outright — every screen file that imports them fails to compile until updated. That is intentional. `MainActivity`'s mount of `FogDrift` at the activity root is deleted with this story; subsequent stories (4.2 onboarding, 4.5 capture) consume `AppTop` and the new primitives directly. If Anton at condensed display sizes reads as too aggressive on device, soften with letter-spacing only — do not roll back to Newsreader.

---

### Story 4.2 — Onboarding flow

**As** a first-time user, **I need** to walk through the 8 onboarding screens per `ux-copy.md` §Onboarding without dead-ends, **so that** I can pick a persona, grant mic and notification permissions, see a typed-fallback affordance, and start the model download — and so that judges installing the APK have a credible first 60 seconds.

**Done when:**
- [ ] Screen 1 — Persona pick: three persona cards (Witness default highlighted, Hardass, Editor) with the locked one-line descriptions per `ux-copy.md`. Tap-to-select + Continue.
- [ ] Screen 2 — Local processing explainer: copy from `ux-copy.md` §"Screen 2". One primary action.
- [ ] Screen 3 — Microphone permission: copy from `ux-copy.md` §"Screen 3". Allow + Skip-typing affordance. Denied path lands on the correct error state (Story 4.11).
- [ ] Screen 3.5 — Notification permission per ADR-004 §"Permission Flow." Copy from `ux-copy.md` §"Screen 3.5" (added alongside this story). On Android 13+, requests `POST_NOTIFICATIONS` runtime permission. Allow + Skip affordances. Skip path is supported: extractions only complete while the app is in the foreground; cold-start sweep from ADR-001 Q3 recovers the rest. No degraded copy on skip.
- [ ] Screen 4 — Typed fallback explainer: copy from `ux-copy.md` §"Screen 4".
- [ ] Screen 5 — Wi-Fi check: branches per `ux-copy.md` §"Screen 5" based on connectivity.
- [ ] Screen 6 — Model download: hands off to Story 4.3.
- [ ] Screen 7 — First entry scaffold: copy from `ux-copy.md` §"Screen 7". Lands on the polished Capture screen (Story 4.5).
- [ ] Each onboarding screen uses one primary action and the design tokens from Story 4.1.
- [ ] Onboarding state survives backgrounding — closing the app between screens resumes at the same step.
- [ ] After completion, opening the app skips onboarding and lands directly on Capture.

**Notes / risks:** No "Welcome to your journey" copy anywhere, ever. `ux-copy.md` §"Things to NEVER Write" is the litmus test. Screen 3.5 copy must clear the same bar — single-status framing, not "we'll keep you posted." The notification permission and channel registration plumbing land in Phase 2 Story 2.6.5; this story owns the user-facing ask flow only.

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
- [ ] App shell uses the `AppShellTop` primitive per `poc/design-review.md` §3.2. Status pill renders the `modelState` string (`ready` / `downloading` / `stalled` / `updating` / `off`) per `ux-copy.md` §"Capture Screen / Status row".
- [ ] `pulse` token (`#38A169`) drives the `LOCAL · READY` dot when `modelState=ready` (the dot glows per `design-review.md` §3.2). `vapor` (`#2563EB`) drives the dot during active recording or active downloading state. Other states use neutral `mist` until they need an accent.
- [ ] Tapping the status pill dispatches the `vestige:open-status` event (per `design-review.md` §3.2) and opens the Local Model Status full screen. Listener is scoped post-onboarding only; the pill is non-interactive (`interactive=false`) during onboarding to prevent first-run trap.
- [ ] Full screen shows: model name (`Gemma 4 E4B`), runtime (`Running locally`), version, on-device storage size, plus action affordances `Re-download model` / `Delete model` per `ux-copy.md` §"Local Model Status — Settings actions".
- [ ] Re-download confirms with the destructive flow per `ux-copy.md` §"Re-download model" (`error` token on confirm). Delete model confirms with `ux-copy.md` §"Delete model".
- [ ] Status accurately reflects state across all transitions (loading → ready, ready → downloading on re-download, downloading → ready on success, etc.).
- [ ] The in-app `LOCAL · READY` status indicator and the transient ADR-004 system-shade notification serve different roles and must not visually duplicate each other. The indicator is the always-visible in-app surface; the notification is a system-shade transient that only appears during active extraction work (`extraction_status=RUNNING`). No system-shade `LOCAL · READY` notification when nothing is running, and no in-app icon that says "background extraction running" while the notification already does.

**Notes / risks:** This is a 10-second-judge-test feature. If a judge installs the APK and never sees `LOCAL · READY` in their first minute of the demo video, the local-AI claim is harder to land verbally. The indicator earns its persistence.

---

### Story 4.4.5 — Lifecycle fallback evaluation gate

**As** the AI implementor at the end of Phase 4 day 1, **I need** to evaluate the conditional foreground service state machine (Phase 2 Story 2.6.5) against the ADR-004 §"Fallback Trigger" criteria, **so that** if the state machine has bugs or eats more time than the Phase 4 budget allows, we switch to the Option 1 always-on fallback before it derails the rest of Phase 4.

**Done when:**
- [ ] At end of Phase 4 day 1 (or when Story 2.6.5 first surfaces a bug blocking Phase 4 UI work, whichever comes first), evaluate against ADR-004 §"Fallback Trigger" criteria 1–3.
- [ ] If none of the trigger criteria fire: record `Trigger recorded: not invoked, evaluated {date}` inline in ADR-004 and proceed with the conditional state machine.
- [ ] If any trigger criterion fires: record `Trigger recorded: invoked {date}, reason: {one-line reason}` inline in ADR-004 and apply the §"Fallback action" steps:
  - Replace conditional state machine with `startForeground()` in `Application.onCreate()`.
  - Replace `stopForeground()` calls with no-ops.
  - Update notification text to `Local model active.` (and update `ux-copy.md` §"Loading States" + Screen 3.5 onboarding copy in the same change).
- [ ] If the fallback fires, also update Story 4.2 Screen 3.5 done-when bullet copy and `ux-copy.md` §"Screen 3.5" to match the always-on framing.
- [ ] Phase 4 day 1 ends with the lifecycle decision recorded one way or the other — no third state of "we'll figure it out later."

**Notes / risks:** This is a small but mandatory gate. The point is to prevent the state-machine implementation from quietly slipping into Phase 4 day 3 while UI work waits. If it's broken, switch fast and ship with the simpler model. ADR-004 explicitly designed for this fallback — using it is on-brand, not a regression.

---

### Story 4.5 — Capture screen polish

**As** the user opening the capture screen, **I need** the polished capture surface per `design-guidelines.md` §"Capture Screen" + `poc/screenshots/capture.png` — `MistHero` capture stone, hero title, live `AudioMeter` during recording, 30 s cap indicator, entry transcript with the locked muted-user / primary-model treatment — **so that** the demo's primary surface lands and recording feels alive instead of a static button on a dark background.

**Done when:**
- [ ] `MistHero` primitive per `poc/design-review.md` §3.3 + `design-guidelines.md` §"Component Conventions / MistHero":
  - 168px hero, five-layer composition (outer halo, conic moonstone ring, frosted-glass body, inner noise, center mark).
  - Idle: stone with subtle internal gradient, no outer halo amplitude.
  - Active recording: outer halo scales with audio `level`, `vapor` (`#2563EB`) tint on halo and ring, `AudioMeter` renders below.
  - Post-stop / review: halo collapses to outline state with thin `vapor` rim. `Reading the entry.` placeholder shows in transcript area (per `ux-copy.md` §"Loading States").
  - Approaching 30s chunk boundary (~25s): thin progress arc on the ring, no copy.
- [ ] Inference placeholder lifecycle (absorbed from Story 2.11):
  - Placeholder appears on foreground-call start; holds **≥ 200 ms** to avoid instant-replace flash; otherwise tracks call duration.
  - On `ForegroundResult.Success`: muted/dimmed transcription per `design-guidelines.md` §"Entry transcript"; model follow-up renders below in primary weight.
  - On `ForegroundResult.ParseFailure` or `CancellationException` (sealed pair is `Success | ParseFailure` per Story 2.2; cancellation surfaces via throw, not a third variant): error copy from `ux-copy.md` §"Error States". Not the placeholder text. Not blank.
  - Token streaming is on-deck. Gate: parser survival under incremental LiteRT-LM token delivery (ADR-002 §Q1). Not a vibe check.
- [ ] Hero title slot above `MistHero` renders an editorial italic line (`Newsreader`) — pulled from `ux-copy.md` §Capture Screen, never invented inline. Example from `poc/screenshots/capture.png`: `What lingered from yesterday?`
- [ ] Mono tagline strip below `MistHero` per `poc/screenshots/capture.png`: directive (`HOLD THE STONE · SPEAK`-style) and privacy tagline (`30s chunks · audio discarded after extraction`-style). `JetBrains Mono`, eyebrow scale. Strings from `ux-copy.md`.
- [ ] Entry transcript per `design-guidelines.md` §"Entry transcript" (single-turn-per-capture per the STT-B v1 scope choice — exactly one USER turn + one MODEL turn per entry, no scroll across multiple exchanges):
  - User transcription: `JetBrains Mono` `YOU` label + transcribed text in `mist` tone.
  - Model follow-up: `JetBrains Mono` `WITNESS` (or active persona name) label + body text in `ink` weight.
  - No chat bubbles. Left-rule indicators distinguish speakers.
- [ ] Type affordance: small `Type` button at the bottom of the screen per `ux-copy.md` §"Capture Screen / Type affordance". Expanding opens a text input with placeholder `What just happened.` and a `Log entry` action.
- [ ] Persona switcher chrome: top-right pill `WITNESS ▾` (or active persona) per `ux-copy.md` §"Capture Screen / Status row". Tapping opens the persona selector. Per-capture selection (Story 4.12) hooks into this — the chosen persona applies to the next capture's foreground call.
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
- [ ] Body shows: the entry transcript (one USER turn + one MODEL turn per the v1 single-turn lifecycle, user transcription muted, model follow-up primary, same treatment as Story 4.5).
- [ ] A "Tags" row below the transcript shows the model-extracted tags as quiet chips.
- [ ] A "Observation" section shows the 1-2 per-entry observations (from Story 2.13) with their evidence references.
- [ ] An overflow menu offers `Delete entry` (destructive flow per `ux-copy.md` §"Destructive Confirmations / Delete single entry").
- [ ] Reading / Re-eval section is **deferred to Story 4.13** as P1 contingent.
- [ ] Vocabulary chip cloud below the observation is **deferred to Story 4.13** if it ships at all (it ships only if STT-E passed; otherwise the observation copy carries the vocabulary observation in plain text).
- [ ] Source-link integration: tapping a pattern source from Story 3.10's pattern detail navigates here and the entry is highlighted briefly.
- [ ] Notification tap target deep-links here per ADR-004 §"Notification Contract": tapping the system-shade `Reading the entry.` notification opens this screen scrolled to the most recent entry whose `extraction_status` is non-terminal. Falls back to History if no in-flight entry exists at tap time (e.g., the user tapped the notification right as the keep-alive expired).

**Notes / risks:** Don't show audio waveform or play-back controls — audio doesn't persist. The entry detail is the *text* view. Per `design-guidelines.md` §"Entry transcript", the user's transcribed words show but never as a waveform.

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
- [ ] Pattern card uses the `glow` left-rule treatment per `design-guidelines.md` §"Pattern card" only on cards with `state=active`. Snoozed/resolved/dismissed cards lose the rule.
- [ ] Pattern card embeds the `TraceBar` primitive per `poc/design-review.md` §3.4 (30 cells, lit cells = days the pattern showed up, full-height + glow on lit, 34% height + hairline on unlit). Single source of truth for "how often does this return" visual. Sourced from `Pattern.traceHits[]`.
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

**As** the user, **I need** the three most-likely failure modes — model download fail/stall, inference timeout/fail, mic permission denied/unavailable — to render clear error states with retry affordances per `ux-copy.md` §"Error States", **so that** the most predictable failure paths in the demo (or in real use) don't end the capture without a clear recovery path.

**Done when:**
- [ ] **Model download fail/stall**: handled within Story 4.3's flow. `ux-copy.md` strings: `Download stalled. Retry.` / `Network choked.`. Retry button works. After 3 failed attempts, shows the failed state and surfaces the user to settings → re-download.
- [ ] **Inference timeout / fail**: appears in the entry transcript when the foreground or background extraction call fails. `ux-copy.md` strings: `Model timed out. Try a shorter chunk.` / `Model couldn't read that. Try again.`. Retry option re-runs the same call.
- [ ] **Mic permission denied or unavailable**: capture screen shows `Mic permission required to record. Settings → Permissions.` per `ux-copy.md`. Tapping the deep-link opens system settings. If the mic is unavailable (hardware), shows `Mic unavailable. Try typing.`.
- [ ] All three error states use the design tokens from Story 4.1 (no orange-warning convention; system status colors only).
- [ ] Other error states from `ux-copy.md` §"Error States — catalog" are implemented as strings/handlers but get *unpolished* presentations (toast / generic dialog) only when naturally encountered. We polish only the top three for v1; the rest defer to v1.5 per the scope rule.

**Notes / risks:** No "Oops" anywhere. No "Something went wrong". `ux-copy.md` §"Rules" item 9 — say what broke.

---

### Story 4.12 — P1: Per-capture persona selection

**Reframed from "P1: Per-session persona override"** by the STT-B v1 scope choice (see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md`, which amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior"). The original story assumed mid-session persona switches on a multi-turn `CaptureSession`; v1 makes each `CaptureSession` single-use, so persona is selected *before* a fresh capture, not switched *during* an ongoing one.

**As** the user, **I need** to choose a persona before each capture by tapping the persona dropdown chrome from Story 4.5, **so that** I can pick a different tone (Witness / Hardass / Editor) for an entry without leaving the capture screen and without affecting any prior entry's recorded persona.

**Done when:**
- [ ] Tapping the persona dropdown in the capture screen chrome opens a small selector (segmented control or list) with the three personas and a checkmark on the currently-selected one.
- [ ] Selecting a different persona updates the next capture's active persona — the UI constructs a fresh `CaptureSession(defaultPersona = selectedPersona)` when the user next taps record (Story 2.3's `setPersona(persona)` API can also be called on an idle session before `startRecording`; either path is acceptable).
- [ ] The chrome label updates to reflect the newly-selected persona (`WITNESS ▾` → `HARDASS ▾`).
- [ ] Selection does not affect prior entries. Each saved entry's `Turn.persona` records the persona that authored it (Story 2.3 invariant).
- [ ] The selection does not persist as a default — that's a separate Settings action (Story 4.9). It's per-capture-scoped.
- [ ] Per-capture selection is **P1**: ships only if Phase 4 scope holds. If we're behind, this drops to v1.5 with the note "captures always use the Settings default persona; switch from Settings between captures."

**Notes / risks:** Story 2.3 (post-fallback) already exposes the per-capture persona API. This is purely UI plumbing. The "switch mid-session" behavior the original story assumed no longer exists at the lifecycle level — the persona selector applies prospectively, not retroactively.

---

### Story 4.13 — P1: Reading / Re-eval on entry detail

**Skipped if STT-D failed in Phase 2** (multi-lens architecture was dropped). If STT-D passed, this story is **P1**: ships only if Phase 4 scope holds.

**As** the user, **I need** an expandable "Reading" section on the entry detail screen that re-runs the 3-lens pipeline against the saved transcript and shows the diff per surface field, letting me accept the new shape or keep the original, **so that** I can re-process old entries with the latest prompts and see *how the model got here* per `concept-locked.md` §"Re-eval (\"Reading\")".

**Done when:**
- [ ] Entry detail screen shows a collapsed "Reading" section by default. Expanding opens the per-lens output view per `design-guidelines.md` §"Pattern Detail" (similar treatment).
- [ ] An action affordance `Re-read this entry` (label per `ux-copy.md` §"Re-eval / Reading") triggers a fresh 3-lens pass via Story 2.6's background worker on the existing `entry_text`.
- [ ] **Re-eval cost confirmation per `adrs/ADR-002-multi-lens-extraction-pattern.md` §Q3:** on the *second* re-read tap within 60 seconds, show the soft-confirm copy `Costs ~30s of inference. Continue?` from `ux-copy.md` §"Re-eval / Reading" before triggering another pipeline pass. First tap of the session goes through without the prompt.
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
- [ ] Tapping `Roast me` from Story 4.8's pattern list header opens the `Sheet` primitive per `poc/design-review.md` §3.1 (`ModalBottomSheet` in Compose, scrim with backdrop blur, `vesSlide` in). `glow` accent.
- [ ] Sheet header shows `{Persona} · Roast · {date}` in `JetBrains Mono` per design-review §2.2.
- [ ] Body shows 3-5 lines (per `design-guidelines.md` §"The Roast — body") generated by a single model call composing the pattern data + persona system prompt with the explicit Roast tone instructions.
- [ ] Lines are *cuts*, not data recitations per the test in `design-guidelines.md` §"The Roast — distinction" — *"Tuesday meetings have a body count"* not *"Tuesday meetings: four entries"*.
- [ ] Footer: `Drawn from {N} entries · Last 30 days`, plus `Close` action only. Wipe-and-start-over is **not** in the Roast footer per `ux-copy.md` §"The Roast (modal bottom sheet)" — destructive flows live in Settings, not Roast.
- [ ] Roast is ephemeral — not saved as a separate artifact. Regenerates fresh on each tap.
- [ ] Insufficient data fallback: if fewer than 10 entries, show `Insufficient data. Come back when you've left more behind.` per `ux-copy.md` §"The Roast — empty fallback".

**Notes / risks:** The hardest part is the system prompt that gets the model to produce *cuts*, not stats. Tune this against actual pattern data; if the model keeps producing flat data recitations, the prompt is wrong, not the architecture.

---

## What is explicitly NOT in Phase 4

- No demo storyboard work — Phase 5 owns it.
- No agentic tool-calling beat anywhere — cut entirely.
- No light theme — dark only.
- No notifications beyond the single ADR-004 `vestige.local_processing` channel from Phase 2 Story 2.6.5 (Story 4.2 Screen 3.5 owns the user-facing permission ask). Reminders, scheduled-pattern surfaces, and any other notification channels are `backlog.md` candidates.
- No filter chips on history list — v1 has chronological only; filter by tag/template/date is P2.
- No vocabulary chip cloud on entry detail unless STT-E passed (Story 3.4) and Story 4.13 ships.
- No advanced settings (pattern threshold, cooldown tuning, default-input toggle, transcription-visibility toggle). Per the PRD note, these are removed from v1 `ux-copy.md`.
- No app icon / cover image design — Phase 6 owns it.
- No video / audio / vision input (Phase 4 is UX only, model capabilities are Phase 1/2).

If a Phase 4 story starts pulling a backlog entry or a Phase 5/6 task, stop. Reference the scope rule.

---

## Phase 4 exit checklist

Phase 5 starts when all the following are true:

- [ ] Stories 4.1 – 4.11 plus 4.4.5 are Done. (Stories 4.12 – 4.14 are P1; their state is recorded as Done or Punted-to-v1.5 with a reason.)
- [ ] ADR-004 lifecycle decision recorded inline in the ADR (conditional state machine kept, or fallback applied with date + reason).
- [ ] Onboarding flow runs end-to-end on a fresh install on the reference S24 Ultra.
- [ ] Capture screen, History list, Entry Detail, Pattern List, Pattern Detail, Local Model Status, and Settings all load and navigate correctly.
- [ ] Top 3 error states render correctly when triggered intentionally.
- [ ] No regressions to STT-A / STT-B / STT-C / STT-D / STT-E outcomes from Phases 1–3.
- [ ] APK installs cleanly on the reference device + at least one secondary device (any 2024+ flagship Android with 8+ GB RAM, 6+ GB free storage).
- [ ] No new entries logged to `backlog.md` from Phase 4 work that change the v1 contract.

If P1 stories were cut: log them in `backlog.md` (Story 4.13 → `reading-on-entry-detail` if it didn't ship; Story 4.14 → `roast-bottom-sheet` if it didn't ship). Update the dev.to post draft (Phase 6 work) to mention the v1.5 path for any cut features.
