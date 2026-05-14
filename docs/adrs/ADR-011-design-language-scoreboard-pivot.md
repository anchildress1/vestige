## ADR-011 — Design language pivot from Mist to Scoreboard (supersedes design-guidelines.md visual sections + Story 4.1 tokens)

**Status:** Accepted
**Date:** 2026-05-13
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes:**
- `docs/design-guidelines.md` §"Product Frame" core-feeling line, §"Color rules", §"Type", §"Motion", §"Component Conventions / MistHero", §"Atmospheric layer", and every reference to the Mist palette / Newsreader italic / fog drift / glow-vapor accents downstream.
- `docs/stories/phase-4-ux-surface.md` Story 4.1 done-when bullets (Mist tokens, Newsreader, MistHero, fog drift). Story 4.1's checked bullets remain as the historical record that the Mist direction shipped to `:app` — they are not re-opened. Story 4.1.5 (added with this ADR) carries the rebuild.
- `docs/PRD.md` §"Phase 4 — UX surface" item 2 reference to `MistHero` + `poc/design-review.md` §3.3 / §3.4.
- All Phase 4 story references to `poc/design-review.md` and `poc/screenshots/{capture,recording,roast,reading,persona,local-model,destruction,wiped,typed,error}.png` (file is deleted; screenshots are deleted). New canonical POC: `poc/Energy Direction.html`, `poc/energy-tokens.jsx`, `poc/energy-screens.jsx`, `poc/screenshots/{hero,scorecard,capture-still,capture-running,design-changes,patterns,pattern-detail}.png`.

**Touched stories:** `phase-4-ux-surface.md` (every story — primitive names, palette tokens, motion specs). `phase-5-demo-optimization.md` + `phase-6-submission.md` (visual references only). `phase-2-core-loop.md` + `phase-3-memory-patterns.md` (POC link refs, no behavior change).

---

### Context

`design-guidelines.md` and `poc/design-review.md` (deleted with this ADR's commit) locked the Mist direction: cool blue-violet surfaces, Newsreader italic for hero titles, `Inter` for body, `JetBrains Mono` for forensic labels, glow-purple + vapor-blue accent system, `MistHero` capture stone, fog-drift atmospheric layer, and the core feeling "Quiet. Observational. Local. Precise. Slightly biting in the words. Restrained in the visuals."

Mist shipped through Story 4.1 (PR #25). On internal review the Mist surfaces read as Calm — adjacent to wellness-app vocabulary the product was supposed to be the opposite of. The bite was in the words; the visuals contradicted it.

The new direction — Scoreboard — keeps the bite in the words and adds matching bite to the visuals. Concept: **"the coach is a dick."** Warm espresso surfaces, electric lime as the single signal, alarm coral as the heat / on-air state, teal as the resolved / cool state, ember as secondary stats. Anton condensed for huge numbers, Space Grotesk for body, JetBrains Mono for forensic labels (retained). Tape-rule grain on cards (printed-receipt energy) replaces fog drift. "On Air" recording state with a live timer replaces the meditative MistHero halo. Patterns become box scores with `▲`/`▼` deltas. The Roast becomes a wobbled, halftone-stamp scorecard.

This is a visual-language pivot, not a behavioral one: extraction logic, persona behavior, pattern primitives, retrieval, storage, lifecycle — unchanged. ADR-002 multi-lens, ADR-003 pattern detection, ADR-004 backgrounding, ADR-005 single-turn, ADR-010 EmbeddingGemma runtime all hold. The pivot is `:app/src/main/kotlin/.../ui/**` only.

Per the rule in feedback memory ("architecture pivot, runtime swap, premise change → new ADR"), a design-language pivot of this scope is a new ADR, not an addendum on a prior ADR.

---

### What this ADR closes

1. **Visual language.** Scoreboard replaces Mist wholesale.
2. **Token deletion contract.** What gets removed from `:app`'s theme module on the rebuild.
3. **Token addition contract.** What lands in its place.
4. **Story-level supersession.** Story 4.1 stays checked as historical record; Story 4.1.5 carries the new tokens + primitives. Phase 4 stories 4.2–4.14 reference the new POC and primitives; the screen-level done-when bullets stay scoped to behavior, not to revoked Mist names.
5. **Core feeling rewrite.** `design-guidelines.md` §"Product Frame" core-feeling line is replaced.
6. **What does *not* change.** Concept, scope rules, dark-only theme, single-turn-per-capture lifecycle, all model and storage behavior, AGENTS.md guardrails.

---

### Decision

**Scoreboard is the v1 visual language.** All implementation work from this point references:

- `poc/Energy Direction.html` — the canonical direction walkthrough.
- `poc/energy-tokens.jsx` — palette, type stack, radii, keyframes, primitives.
- `poc/energy-screens.jsx` — screen-level reference compositions (capture idle, capture on-air, patterns, pattern detail, roast).
- `poc/screenshots/hero.png` + `poc/screenshots/scorecard.png` — fixed reference frames.

The Mist POC artifact (`poc/design-review.md`) and Mist-era screenshots are deleted from the repo with the commit that lands this ADR. No "legacy" path is kept — per AGENTS.md rule 16, the old design dies.

---

### New core feeling

**Loud. Observational. Local. Precise. Biting in the words and in the visuals.**

The product is not a journal. It is not a wellness app. It is a scoreboard for behavioral traces, and the coach is a dick. The visuals admit it.

---

### Token additions (Scoreboard)

Land in `:app/src/main/kotlin/dev/anchildress1/vestige/ui/theme/` per Story 4.1.5:

**Palette** (oklch values from `poc/energy-tokens.jsx`):

| Token | oklch | Role |
|---|---|---|
| `floor` | `oklch(13% 0.012 55)` | page floor |
| `deep` | `oklch(15% 0.014 55)` | device interior |
| `s1` | `oklch(19% 0.016 55)` | card base |
| `s2` | `oklch(24% 0.018 55)` | raised |
| `s3` | `oklch(30% 0.020 55)` | hover |
| `ink` | `oklch(95% 0.015 85)` | warm cream — body |
| `dim` | `oklch(70% 0.020 75)` | secondary text |
| `faint` | `oklch(55% 0.018 70)` | tertiary text |
| `ghost` | `oklch(45% 0.015 65 / 0.6)` | dropped text |
| `hair` | `oklch(70% 0.020 70 / 0.12)` | hairline border |
| `lime` | `oklch(89% 0.19 115)` | **signal** — live, active, "on" |
| `coral` | `oklch(72% 0.21 28)` | **heat** — recording, roast, danger |
| `teal` | `oklch(77% 0.12 195)` | cool — resolved, settled |
| `ember` | `oklch(82% 0.16 65)` | warm gold — secondary stats |

Soft / dim variants (`limeDim`, `limeSoft`, `coralDim`, `coralSoft`, `tealDim`) per the POC file. **One accent per element.** Lime and coral never co-occur on the same atom.

**Type stack:**

- Display: `Anton`, `Oswald`, `Arial Narrow` fallback. Huge condensed numbers and headers. Letter-spacing `-0.005em` to `-0.01em`. Tabular nums on stats.
- Sans (body): `Space Grotesk`, `system-ui` fallback. 15px default, line-height 1.55.
- Mono (forensic): `JetBrains Mono`, `ui-monospace`, `Menlo` fallback. 9–10px, letter-spacing `0.16em`–`0.24em`, uppercase, used for eyebrows / pill labels / persona names / status.

**Radii:** `rPill: 9999`, `rXL: 18`, `rL: 12`, `rM: 8`, `rS: 4`, `rXS: 2`. **Sharper than Mist; no pillows.**

**Motion keyframes** (replace `vesPulse` / `vesIn` / `vesFade` / `vesSlide` / `vesShimmer` / `vesBreath` / `vesSpin` / `vesDrift1` / `vesDrift2`):

- `sbPulse` — opacity 1 ↔ 0.4, 1.4s, on-air dot only.
- `sbBlink` — 49/50 step, hard cut, status indicator only.
- `sbScroll` — translateX(0 → -50%), ticker rows.
- `sbTick` — translateY 0 → -2px → 0 at 90/95/100%, stat number update.
- `sbBars` — scaleY 0.3 ↔ 1, audio meter bars.
- `sbSweep` — background-position -200% → 200%, loading shimmer.
- `sbWobble` — rotate -1.5° ↔ 1.5°, roast stamp only.
- `sbRise` — translateY 8px + opacity 0 → 1, single-shot entrance.

No fog drift. No breathing background.

**Surface texture:** `TAPE_BG` (1px horizontal grain, 4px period, 3% opacity) replaces noise grain on cards. `HALFTONE_BG` (0.7px dot at 1.2px period, 15% opacity) replaces noise grain on call-out blocks (roast banner, tension callouts).

### Token deletions (Mist — removed in Story 4.1.5)

- Palette: `void`, `bg` (Mist), `s1`/`s2`/`s3` (Mist values — re-keyed to Scoreboard values), `mist`, `glow`, `vapor`, `pulse` (Mist semantics — `lime` now drives "ready" / "on" semantics, `coral` drives recording).
- Type: `Newsreader` italic display family — gone. `Inter` body — replaced by `Space Grotesk`.
- Primitives: `MistHero`, `VestigeSurface` glass-card, `VestigeRow`, `VestigeListCard`, `FogDrift`, `NoiseGrain`, `AccentModifiers.glowLeftRule` / `vaporHaloOnRecording` / `pulseDotForReady`. `JetBrainsMono` stays. `errorFillForDestructive` stays (re-themed to coral).
- Motion: `vesPulse` / `vesBreath` / `vesIn` / `vesFade` / `vesSlide` / `vesShimmer` / `vesSpin` / `vesDrift1` / `vesDrift2` — replaced by the `sb*` set.

### New primitives (Story 4.1.5 — Compose translations of `poc/energy-tokens.jsx`)

- `BigStat(value, label, color, size)` — Anton condensed stat, tabular nums, mono label eyebrow.
- `Pill(children, color, fill, dot, blink)` — capsule with mono label, optional status dot. **Replaces** Mist's status row.
- `Delta(value, label)` — `▲N` / `▼N` tag, lime / coral by sign.
- `TraceBarE(days, hits, accent, peak)` — denser, peak-marker bar. **Replaces** `TraceBar`'s 30-cell 34/100 height pattern with 18/100 unlit + boxShadow on lit when `peak=true`.
- `StatRibbon(items, accent)` — newsroom-style row of mini-stats, tape-grain background.
- `TickRule(count, marks)` — ruler tick row (used for the 30s chunk countdown on Capture).
- `EyebrowE(children, color)` — mono eyebrow label (replaces Mist `Eyebrow`).
- `StatusDot(color, blink, size)` — pulsing or static dot, glow.
- `AppTop(persona, recording, onTimer)` — shell top bar with LOCAL · GEMMA 4 / ON AIR · LIVE swap. **Replaces** `AppShellTop`.
- `PhoneE` — POC-only phone frame, not shipped in the app.

### What does *not* change

- Concept (cognition tracker, anti-sycophant, behavioral, private). `concept-locked.md` unchanged.
- Single-turn-per-capture lifecycle (ADR-005). Entry transcript is still two items per entry.
- Persona model (Witness / Hardass / Editor — tone variants, no fork in extraction logic).
- Pattern primitives, retrieval, storage, lifecycle, backgrounding, model runtime — all unchanged.
- Dark mode only. No light theme branch.
- AGENTS.md guardrails. The 10-second judge test still holds; Scoreboard makes the local-AI claim more legible, not less.
- ux-copy.md — microcopy stays. Status row label flips from `LOCAL · READY` to `LOCAL · GEMMA 4` / `ON AIR · LIVE` per the new POC, and the persona pill chrome updates per Story 4.5. The forbidden-copy lists, error strings, and onboarding screen copy all hold.

### What this breaks (and why we accept it)

The new direction breaks four explicit rules in the prior `design-guidelines.md`:

1. *"Restrained in the visuals"* — Scoreboard is loud. The bite escaped the copy and got onto the surfaces.
2. *"Quiet. Observational."* — Observational stays; quiet dies.
3. *"No celebrations"* — `▲4 this week` celebrates a streak. Small, dry, mono — but it does celebrate. Trade accepted: streak deltas read as forensic, not gamified, when the lift is bounded by source counts and there are no badges / confetti / mascots.
4. The glow-vapor purple-and-blue accent system — replaced wholesale by lime + coral + teal + ember.

Forbidden visual vocabulary unchanged: no brains / hearts / journals / suns / mountains / water / sparkles / mascots / badges / streaks-with-flames. `▲N` deltas read as forensic data because the typography (mono, tabular, no halo) frames them as numbers, not awards.

### Story-level supersession

- **Story 4.1 (PR #25)** — done-when bullets stay checked as the historical record that Mist shipped. Add a dated Addendum block at the bottom of Story 4.1 pointing at ADR-011 + Story 4.1.5. Do not uncheck.
- **Story 4.1.5 (new)** — "Scoreboard design language re-pass." Done-when:
  - Palette tokens replaced per the Token additions table above.
  - Type primitives rebuilt: `DisplayBig` (Anton) replaces `HDisplay`; `H1` re-anchored to Space Grotesk; `P` re-anchored to Space Grotesk; `PersonaLabel` / `Eyebrow` stay mono with updated tracking.
  - Radius tokens replaced per the new scale.
  - Motion keyframes replaced (`sb*` set). M3 expressive motion suppression retained.
  - Surface texture swapped: tape-grain replaces noise-grain; halftone for call-outs; fog drift removed entirely from the activity root.
  - New primitives shipped: `BigStat`, `Pill`, `Delta`, `TraceBarE`, `StatRibbon`, `TickRule`, `EyebrowE`, `StatusDot`, `AppTop`. Compose translations live in `ui/components/` (or `ui/scoreboard/` if the rebuild prefers a parallel package while screens migrate).
  - `MistHero`, `FogDrift`, `NoiseGrain`, Mist-era `VestigeSurface`/`VestigeRow`/`VestigeListCard` deleted (per AGENTS.md rule 16 — no compat layer).
  - Existing screens (PhaseOneShell, PatternsList, PatternDetail, EntryDetailPlaceholder) re-skinned to compile against the new primitives. Behavior unchanged; visuals updated.
  - Tests for `DesignTokensTest`, `VestigePrimitivesTest`, `AtmosphereTest`, `AccentDrawScopeTest`, `VestigeMotionTest`, etc. rewritten to lock the Scoreboard values. Coverage doesn't regress.
- **Stories 4.2 – 4.14** — bullet references to `poc/design-review.md` / `MistHero` / `Newsreader` / glow / vapor / fog drift updated to reference Scoreboard primitives + new POC files. Done-when behavior unchanged. The Phase 4 story file gets a dated banner at the top flagging the pivot.

### Implementation timing

Story 4.1.5 lands **before** Story 4.2 onboarding implementation begins. Onboarding screens (Story 4.2) consume the Scoreboard primitives; without them, the rebuild would be screen-by-screen Mist→Scoreboard, which is exactly the "no compat layer" failure mode AGENTS.md rule 16 rejects.

### Risks

- **Anton / Space Grotesk bundling.** Story 4.1 shipped `VestigeFonts` aliasing `FontFamily.SansSerif` / `FontFamily.Serif` / `FontFamily.Monospace` as the no-call-site-swap stand-in for real `.ttf` bundling. Same approach holds — Anton aliases SansSerif (condensed isn't faithful but the call-site is invariant); real Anton + Space Grotesk + JetBrains Mono `.ttf` bundling is a non-call-site swap in Phase 5 if it earns its keep. Aliased fallback is the v1 ship state.
- **OKLCH in Compose.** Android Color does not natively express oklch; the Color.kt translation must convert each oklch literal to sRGB at compile time. Acceptable: the values are fixed, so a one-shot conversion in a `// oklch(...)` comment per token is honest and reviewable. No runtime oklch path.
- **Mist token churn in shipped code.** `:app` currently links Mist symbols. The rebuild deletes them. Any test or screen still importing `Glow` / `Vapor` / `Mist` / `MistHero` / `FogDrift` fails to compile until updated. That is the intended forcing function — no "deprecated Mist token kept for one release."

### Open questions

- Anton at small sizes is illegible — body text stays Space Grotesk. The display family is for ≥22px headers and ≥40px stats only. Cutover guidance lives in the token doc (`Color.kt` / `Type.kt` comments) so the rule doesn't drift.
- Persona pill copy in the new chrome: POC reads `WITNESS ▾` mono. ux-copy.md §"Capture Screen / Status row" stays the source. Update only if the POC and ux-copy disagree; in 2026-05-13 they agree.
- Roast stamp (`sbWobble`, halftone, -1.5°) is P1 contingent on Story 4.14 shipping. If Story 4.14 punts, the stamp ships as dead code-path or stays out of the primitives folder. Decision deferred to Story 4.14 gate.

### Future revisits

If Scoreboard reads as "loud" in the wrong way on device — i.e., judges read it as marketing-app, not forensic-app — the lever is to **tone the lime accent only**, not to roll back to Mist. The Mist palette is gone for v1.

### Addendum (2026-05-14) — AppTop status pill narrows coral semantic

On-device review of Story 4.5 surfaced that `ON AIR · LIVE` (coral) reading on the AppTop pill during recording landed as "alarm / error" instead of "we are listening." The pill chrome and the REC button were sharing the coral atom, which collapsed two distinct semantics — "the machine is hot" (REC button heat, halo, fill) vs. "the system is operational" (status chrome).

**Change:**

- `AppTopStatuses.Ready` text flips from `LOCAL · GEMMA 4` to `GEMMA 4 · LOCAL ONLY`. Pill color stays `colors.lime`.
- `AppTopStatuses.Recording` text flips from `ON AIR · LIVE` to `GEMMA 4 · LISTENING LIVE`. Pill color flips from `colors.coral` to `colors.lime`.
- Coral semantic narrows to: REC button (idle outline → recording fill), REC button halo (`coralHaloOnRecording`), destructive fill (`errorFillForDestructive`), and the `errorRed` alias for M3 `colorScheme.error`. Anywhere else previously labeled "recording = coral" reads as "REC button = coral."
- Contrast / readability: both pill states keep the lime / dim / hair / s1 contrast story unchanged. AA / AAA targets unaffected.

**Why this is an addendum, not a new ADR:** the broader design pivot is unchanged — Scoreboard primitives, token table, type stack, motion keyframes all hold. This refines one atom assignment without re-opening the pivot itself (per AGENTS.md rule 23).

**Affected files:** `ScoreboardPrimitives.kt` (AppTopStatuses Ready/Recording + KDoc), `AccentModifiers.kt` (KDoc for `coralHaloOnRecording` + `limeDotForReady`), `Color.kt` (Lime / Coral comment scopes), `strings.xml` (`onboarding_wiring_local_title`), `ux-copy.md` §"Capture Screen / Status row", `phase-4-ux-surface.md` Story 4.1 capture-screen bullet. Tests: `ScoreboardPrimitivesTest`, `OnboardingStepContentTest`.

### Addendum (2026-05-14) — Capture surface verified end-to-end; LiveLayout polish

On-device round-trip for Story 4.5 lands: REC tap → record → STOP → foreground call → transcript + persona follow-up render in `ReviewingPane`. The voice path is fully exercised against E4B CPU at the documented 24–33 s wall-clock per `docs/stories/phase-2-core-loop.md` §Story 2.3. Inference placeholder + Success transition match `ux-copy.md` §"Capture Screen / Center — record action."

**Verified:**

- Voice path: `Idle → Recording → Inferring → Reviewing` runs end-to-end on the reference device.
- Entry transcript: muted user transcription + primary persona follow-up in single-turn shape per ADR-005.
- Error chrome band (commit 6 of branch) makes `Idle(error=InferenceFailed)` visible — previously silent.
- AppTop pill stays lime in both states (per the 2026-05-14 addendum above).
- Discard contract (ADR-001 §Q8): no model bytes leak; `cancelAndJoin` semantics confirmed by the VM test suite.

**LiveLayout polish:**

- **Duplicate timer pill** — `AppTop.rightContent` was rendering a `TimerPill` with `mm:ss` while `TimerHeader` immediately below it carried the canonical 96sp display timer + the `REMAIN · N · SECONDS` countdown. Three time-related elements at the top of the recording surface. The `AppTop` right-slot pill is removed; the in-content timer header is the single source of recording duration. `AppTop` right-slot stays empty during recording — no persona switcher during a take (Story 4.12 fixes persona before the next capture, never mid-recording per `design-guidelines.md` §"Capture Screen / Recording-state changes").
- **30 s cap audio cue** — the 30-second hard cap currently fires silently (chunk completes in `ChunkBuilder`, recording job resolves the final chunk, UI flips to `Inferring`). The user needs an audible signal at cap so they know the recording closed without watching the countdown. Out of scope for this addendum to specify the audio asset — tracked as a follow-up commit with the asset choice (system tone vs. bundled chime) called out on landing.

**Why this is an addendum, not a new ADR:** the design pivot itself is unchanged; this records the verification result + the live-surface refinements that fell out of on-device review. Per AGENTS.md rule 23.

**Affected files (this addendum's scope):** `LiveLayout.kt` (TimerPill removal from `AppTop.rightContent`), `LiveLayoutTest.kt` (drop the duplicate-timer assertion if it was pinned), follow-up commit for 30 s audio cue. Story 4.5 done-when bullets tick the verified items.
