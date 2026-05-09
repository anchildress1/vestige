# Vestige · POC Design Spec

Implementation guide for the Compose port. Source-of-truth for visual system, primitives, data shapes, and screens.
The JSX in this folder is the visual reference; this doc names what to copy and what to fix.

Audience: AI implementers. No human niceties.

---

## 0 · Status

- POC stack: React 18 UMD + Babel-standalone. Throwaway.
- Target stack: Kotlin 2.3.x, Compose, AGP 9, JVM 25 / Java 17 src target. See `AGENTS.md` `VERSIONS`.
- The Compose port rebuilds from scratch. JSX is reference, not migration source.
- All POC screens are static — no real audio capture, no LiteRT-LM, no ObjectBox, no markdown writes.

## 1 · POC file map

| File | Role | Port-relevant content |
|---|---|---|
| `tokens.jsx` | Design system | `V` palette, type, radii, `noiseStyle`, `FogDrift`, `Sheet`, `AppShellTop`, `PrimaryBtn/GhostBtn/IconBtn`, `IconSlot`, `TraceBar`, `Icons` SVGs |
| `data.jsx` | Static fixtures | `PERSONAS[]`, `PATTERNS[]`, `ROAST_META` — canonical shapes |
| `screens-capture.jsx` | 01 / 02 / 03 | `MistHero`, `AudioMeter`, `CaptureScreen`, `TypedEntryScreen` |
| `screens-onboarding.jsx` | 04 / 05 / 06 | `OnboardingScreen` (7 steps), `ModelStatusScreen`, `PersonaScreen`, `PersonaCard`, `DiagramLocal`, `ModelDownload` |
| `screens-patterns.jsx` | 07 / 08 | `PatternsScreen` partition (active/snoozed/resolved), `PatternCard`, `PatternDetailScreen` lifecycle buttons |
| `screens-reading.jsx` | 09 | `ReadingScreen`, `FieldCard` |
| `screens-roast.jsx` | 10 / 11 / 12 / 13 | `RoastScreen`, `WipedScreen`, `ErrorScreen`, `DestructiveScreen` |

## 2 · Visual system

### 2.1 · Palette (`V` in `tokens.jsx`)

Prototype tokens now mirror the challenge docs: cool dark surfaces, purple for pattern/depth, blue for active recording/focus, system error red for destructive actions. Some CSS remains atmospheric because this is a POC, not production chrome.

| Token | Value | Use |
|---|---|---|
| `void` / `deep` | `#0A0E1A` | Page/device floor |
| `bg` | `#0E1124` | Deep surface |
| `s1` | `#161A2E` | Card base |
| `s2` | `#1E2238` | Raised / interactive |
| `s3` | `#2A2E48` | Hover / pressed |
| `ink` | `#E8ECF4` | Primary text |
| `mist` | `#7B8497` | Secondary text |
| `glow` | `#A855F7` | Purple: identity / pattern / depth |
| `vapor` | `#2563EB` | Blue: active recording / focus |
| `pulse` | `#38A169` | Ready status dot |
| `error` | `#B3261E` | Destructive actions only |

### 2.2 · Type

| Family | Use | CSS `font-family` |
|---|---|---|
| Inter | UI body, sans | `Inter, system-ui, -apple-system, sans-serif` |
| Newsreader (italic, opsz axis) | Display moments — app name, hero titles | `"Newsreader", "Iowan Old Style", Georgia, serif` |
| JetBrains Mono | Forensic-instrument labels, eyebrows, persona name | `"JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace` |

Type primitives in `tokens.jsx`: `HDisplay` (display-scale sans, 38px), `H1` (sans 26px), `P` (15px), `PersonaLabel` (mono 10px / 0.24em), `Eyebrow` (mono 10px / 0.20em). Compose port should use system sans / Inter-like typography, not the earlier wellness-adjacent serif direction.

### 2.3 · Radii scale

`rPill: 9999`, `rXL: 8`, `rL: 8`, `rM: 6`, `rS: 4`, `rXS: 4`. No raw radius literals in the Compose port.

### 2.4 · Texture

Two ambient layers carried across nearly every surface:

- **Noise grain.** Inline SVG `feTurbulence` (180×180 tile), `mix-blend-mode: overlay`, opacity ~0.05–0.18 depending on layer. Defined as `NOISE_URL` + `noiseStyle(opacity)`.
- **Fog drift.** Two animated radial-gradient blobs (`vesDrift1`, `vesDrift2` keyframes, 22s/28s alternate). Used as ambient layer behind hero areas. `FogDrift({ intensity, hueA, hueB })`.

Both layers persist inside the phone frame, plus a global one outside on the page background.

### 2.5 · Keyframes

Single global block injected once from `tokens.jsx`. Names: `vesPulse`, `vesIn`, `vesFade`, `vesSlide`, `vesShimmer`, `vesBreath`, `vesSpin`, `vesDrift1`, `vesDrift2`. Components reference by name. In Compose: translate to `Animatable` / `rememberInfiniteTransition` equivalents.

## 3 · Primitives

### 3.1 · `Sheet` — modal bottom sheet

`Sheet({ open, onClose, header, footer, children, accent })`. Scrim with `backdropFilter: blur(6px)`, `vesFade` in. Body slides up via `vesSlide` 0.32s ease. Top corners `rXL`. Drag-handle bar (36×4) at top center. Body scrolls (`ves-scroll`, no scrollbar).

Roast consumes this primitive in the POC. Compose port should use `ModalBottomSheet`, not a peer route.

### 3.2 · `AppShellTop`

`AppShellTop({ modelState, onStatusTap, left, right, interactive })`.

- `modelState`: one of `ready | downloading | stalled | updating | off`. Maps to dot color + label string. `ready` glows.
- `left`: optional override for the leading slot (e.g. detail screens with a back `IconBtn`). When `left` is passed, the status pill is centered.
- `right`: trailing slot for kebabs / actions.
- `interactive`: when `false`, status pill is non-tappable. Required during onboarding to prevent first-run trap.
- Default tap on pill dispatches `vestige:open-status` window event. App shell listens and routes to status. Listener must be scoped to post-onboarding state.

### 3.3 · `MistHero` — capture stone

`MistHero({ state, level, onClick })`. Owns its full surface. `state ∈ idle | recording`. Center mark swaps by state (no overlay layering). 168px, halo radial outside, conic moonstone ring, frosted-glass body, inner noise. `level` modulates outer halo scale + box-shadow when recording.

### 3.4 · `TraceBar`

`TraceBar({ days = 30, hits: number[], height, accent })`. 30 cells, lit cells = days the pattern showed up. Lit: full-height + glow. Unlit: 34% height + hairline. Single source of truth for "how often does this return" visual.

### 3.5 · Buttons

- `PrimaryBtn` — pill, `ink` fill on `deep` text. Min height 52. `danger` flips to system error red.
- `GhostBtn` — pill, transparent, `ghost` border, `ink` text. `subtle` swap dims to `mist` + `hair` border. Min height 48.
- `IconBtn` — 42×42 round, transparent, hover bg `oklch(75% 0.020 250 / 0.07)`.

### 3.6 · `IconSlot` (placeholder only)

Faded watermark stand-in for unfinished icons. Soft halo + ghost-glyph label. **Not for production chrome** — never use in user-visible flow.

### 3.7 · `Icons` SVG set

Two-pass strokes (ghost trace + foreground mark) where conceptually fitting. All `currentColor`. Set: `footprint`, `strata`, `palimpsest`, `silhouette`, `absence`, `back`, `close`, `more`, `settings`, `type`, `mic`, `wifi`, `retry`, `check`, `trace`, `arrowR`. Port targets: vector drawables or Compose `ImageVector`.

## 4 · Data shapes (canonical)

### 4.1 · `PERSONAS[]`

```
{
  id: 'witness' | 'hardass' | 'editor',
  name: string,            // display
  tagline: string,
  transcript: [{ who, dim: bool, text }],   // sample voice — onboarding + persona screen
  roast: string[]                            // lines for roast sheet
}
```

One record per persona. No forks. Three records ship.

### 4.2 · `PATTERNS[]`

```
{
  id: kebab,
  title: string,
  category: string,                         // Aftermath | Tunnel exit | Concrete shoes | Decision spiral | Goblin hours | Audit
  observation: string,                      // single sentence summary
  count: string,                            // pre-formatted 'N of M entries' / 'N mentions'
  lastSeen: string,                         // 'May 7'
  status: 'active' | 'snoozed' | 'resolved',
  traceHits: number[],                      // days 0..29 the pattern showed up — single source for TraceBar
  sources: [{ date, snippet }],
  vocab: string[]
}
```

Five records ship.

### 4.3 · `ROAST_META`

```
{ date: string, sourceLine: string }
```

### 4.4 · Lifecycle override flow (POC)

POC patches `window.VESTIGE_DATA` from `app.jsx` via a `statusOverrides` map so transitions persist across screens for one session. In the Compose port: ObjectBox-backed mutation + Flow.

## 5 · Screens

Order is canonical (see `SCREENS[]` in `app.jsx`).

| # | id | Screen | Notes |
|---|---|---|---|
| 01 | `capture` | Capture | Home. `MistHero` idle, persona pill, footer ledger (last entry · active pattern count). |
| 02 | `recording` | Capture · recording | Same component, `autoRecord` prop. `MistHero` recording, `AudioMeter` live, elapsed pill replaces persona pill. |
| 03 | `typed` | Typed entry | Same field semantics, no separate mode. |
| 04 | `onboarding` | Onboarding | 7 steps: persona pick · local-only · mic · typed fallback · wifi · model download · first entry. Step 5 simulates download progress. |
| 05 | `status` | Local Model | Model state, retry, storage info. Opens via status-pill event. |
| 06 | `persona` | Persona Selector | List of PERSONAS, sample transcript inline. |
| 07 | `patterns` | Patterns list | Partitioned by `status`. `PatternCard` per row, `TraceBar` inside. |
| 08 | `pattern-detail` | Pattern Detail | Full observation, sources, vocab. Lifecycle buttons mutate `status`. |
| 09 | `reading` | Reading | P1 detailed read-out for an entry, aligned to the v1 content schema. Static sample only. |
| 10 | `roast` | Roast | P1 bottom sheet over Patterns. 3–5 cuts per persona from `PERSONAS[i].roast`. |
| 11 | `wiped` | Wiped beat | Single-line mono confirmation after destructive wipe. ~1.5s before onboarding. |
| 12 | `error` | Error | Network / generic. Retry + dismiss. |
| 13 | `destructive` | Destructive confirm | "Wipe everything" — destructive primary. |

## 6 · Navigation graph

```
capture ─┬─ patterns ─┬─ pattern-detail ─┬─ reading
         │            └─ roast (P1 sheet)
         ├─ status
         ├─ persona
         ├─ typed
         ├─ destructive ── wiped → onboarding
         └─ error

onboarding (first-run only) → capture
```

Nav rules:

- Status pill on `AppShellTop` opens `status` from any post-onboarding screen (window event).
- `roast` is a P1 sheet over `patterns`; port must use a real overlay.
- `pattern-detail` lifecycle buttons (Snooze / Resolved / Reactivate) mutate status and stay on the screen.
- `destructive` confirm → `wiped` (acknowledgment beat) → `onboarding`. Cancel returns to the caller.

## 7 · Open issues for the Compose port

### 7.1 · Roast scope

Roast is P1. Do not port it before Capture, Onboarding, Local Model Status, History/Reading basics, Pattern List, Pattern Detail, export/delete, and the privacy proof are credible.

### 7.2 · ReadingScreen entry argument

`ReadingScreen` currently renders a static schema-aligned sample. Port must:

- Either accept an entry id and read from a real readings collection (markdown-file-backed in Vestige's case), or hide the source-row tap target until that data exists.

### 7.3 · Single visual system

Onboarding, Status, Persona, Roast, Error, Destructive use quieter `s1` surfaces while Capture, Patterns, and Reading carry more atmosphere. Port must:

- Pick **atmospheric** as the single system.
- Build three Compose primitives: `Surface` (glass + noise card), `Row` (key/value line), `ListCard` (selectable Surface variant).
- Migrate the flat screens onto them. The current flat treatment reads as unfinished, not as deliberate counterpoint.

### 7.4 · Radii hygiene

The POC uses tokenized radii after cleanup. In Compose: a `RadiusTokens` object — never raw `dp` for corner shapes that map to the scale.

## 8 · Compose translation notes

- **Surfaces.** `Modifier.background(Brush.radialGradient(...))` for moonstone halos. `androidx.compose.material3.Surface` with custom `Shape` per radius token.
- **Texture.** Noise grain: pre-bake the SVG to a tileable PNG/WebP at app build, use `Modifier.drawBehind { drawImage(... tileMode = Repeat) }` with `BlendMode.Overlay`. FogDrift: `rememberInfiniteTransition` driving two `Brush.radialGradient` offsets.
- **Type.** Three font families. Inter + JetBrains Mono → `androidx.compose.ui.text.font.FontFamily(Font(...))` from `res/font/`. Newsreader is variable; bundle the variable font and apply `FontVariation` axes for italic + opsz.
- **Sheet.** `androidx.compose.material3.ModalBottomSheet` with custom `SheetState`. Scrim blur via `Modifier.blur(6.dp)` on the underlying content (Android 12+, fits minSdk 31).
- **AppShellTop.** Single composable, slots for `left` / `right`. Status event becomes a `SharedFlow` in a `ChromeViewModel` scoped above onboarding so the listener can be gated.
- **TraceBar.** `Row` of 30 weighted `Box`es, `Modifier.fillMaxHeight(if (lit) 1f else 0.34f)`. Glow via `Modifier.shadow(elevation, ambientColor = glowDim)`.
- **MistHero.** Five layers: outer halo (animated scale via `level`), conic ring (`Brush.sweepGradient` + rotation animation), stone body (radial gradients composed), grain, center mark. Center mark is its own `Box` swapped by `state`, never overlaid externally.
- **Pattern lifecycle.** ObjectBox entity with a `PatternStatus` enum. Repository exposes a `Flow<List<Pattern>>`; partition in the screen.
