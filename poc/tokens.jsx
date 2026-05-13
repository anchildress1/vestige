// Vestige · design tokens, type, texture, expressive shape primitives
// "Mist & Trace" — a misty/shadow tracker. One cohesive cool monochrome
// palette in oklch, varied through a single moonstone-glow accent. All
// surfaces wear texture: SVG grain + drifting fog auras.

const V = {
  // ─── Surfaces ───────────────────────────────────────────────────
  // Cool blue-violet undertone. Each step is a layer of fog thickening.
  void:  'oklch(13% 0.018 252)', // page floor — out beyond the phone
  deep:  'oklch(15% 0.020 252)', // device interior base
  bg:    'oklch(17% 0.020 252)', // alias of deep for legacy props
  s1:    'oklch(22% 0.022 252)', // card base
  s2:    'oklch(28% 0.024 252)', // raised / interactive
  s3:    'oklch(36% 0.026 252)', // hover / pressed

  // ─── Ink ────────────────────────────────────────────────────────
  ink:   'oklch(96% 0.010 250)',
  mist:  'oklch(70% 0.022 250)',
  faint: 'oklch(55% 0.020 250)',
  ghost: 'oklch(45% 0.018 250 / 0.55)',
  hair:  'oklch(75% 0.020 250 / 0.10)',
  hair2: 'oklch(75% 0.020 250 / 0.05)',

  // ─── Accent — single moonstone glow ────────────────────────────
  // Rare. Marks the trace: a pattern returning, recording live, cuts that bite.
  // Same hue family as the surfaces; just lifted into luminous range.
  glow:    'oklch(86% 0.105 245)',          // pale moonstone — primary accent
  glowDim: 'oklch(86% 0.105 245 / 0.20)',
  glowSoft:'oklch(86% 0.105 245 / 0.55)',
  vapor:   'oklch(70% 0.075 245)',          // calmer chrome accent
  vaporDim:'oklch(70% 0.075 245 / 0.22)',
  pulse:   'oklch(82% 0.090 218)',          // health/alive — same family, half-step warmer
  pulseDim:'oklch(82% 0.090 218 / 0.22)',
  glowRule:'oklch(86% 0.105 245 / 0.85)',   // hard rule edge

  // ─── Radii — Material 3 Expressive: deliberate variation ───────
  rPill: 9999,
  rXL:   28,
  rL:    20,
  rM:    14,
  rS:    8,
  rXS:   6,

  // ─── Type ───────────────────────────────────────────────────────
  // Inter for UI. Newsreader (variable serif w/ italic + opsz axis) for
  // atmospheric display moments — the "vestige" name itself, hero titles.
  // JetBrains Mono for forensic-instrument labels.
  display: '"Newsreader", "Iowan Old Style", Georgia, serif',
  sans:    'Inter, system-ui, -apple-system, sans-serif',
  mono:    '"JetBrains Mono", ui-monospace, "SF Mono", Menlo, monospace',
};

// ─── Texture — SVG noise grain, dropped on any surface ────────────
// Inline base64 of a small fractalNoise tile. Used as background-image
// over solid fills to give every surface a faint, photographic grain.
const NOISE_SVG = `<svg xmlns='http://www.w3.org/2000/svg' width='180' height='180'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 0.62  0 0 0 0 0.66  0 0 0 0 0.78  0 0 0 0.55 0'/></filter><rect width='100%' height='100%' filter='url(%23n)' opacity='0.85'/></svg>`;
const NOISE_URL = `url("data:image/svg+xml;utf8,${NOISE_SVG.replace(/#/g, '%23').replace(/"/g, "'")}")`;

function noiseStyle(opacity = 0.06) {
  return {
    position: 'absolute', inset: 0, pointerEvents: 'none',
    backgroundImage: NOISE_URL,
    backgroundSize: '180px 180px',
    mixBlendMode: 'overlay',
    opacity,
  };
}

// Drifting fog aura — soft animated radial gradient blobs. Plops behind
// content. Use sparingly: hero areas, persistent ambient layers.
function FogDrift({ intensity = 0.45, hueA = 245, hueB = 220, style }) {
  return (
    <div aria-hidden style={{
      position: 'absolute', inset: 0, pointerEvents: 'none',
      overflow: 'hidden', ...style,
    }}>
      <div style={{
        position: 'absolute', width: '120%', height: '120%',
        left: '-10%', top: '-30%',
        background: `radial-gradient(closest-side, oklch(70% 0.10 ${hueA} / ${intensity}), transparent 70%)`,
        filter: 'blur(40px)',
        animation: 'vesDrift1 22s ease-in-out infinite alternate',
      }} />
      <div style={{
        position: 'absolute', width: '100%', height: '100%',
        right: '-20%', bottom: '-30%',
        background: `radial-gradient(closest-side, oklch(60% 0.08 ${hueB} / ${intensity * 0.8}), transparent 70%)`,
        filter: 'blur(50px)',
        animation: 'vesDrift2 28s ease-in-out infinite alternate',
      }} />
    </div>
  );
}

// ─── Global keyframes — injected once so components reference by name ─
// Components no longer ship their own copies; this is the single source.
if (typeof document !== 'undefined' && !document.getElementById('vestige-keyframes')) {
  const s = document.createElement('style');
  s.id = 'vestige-keyframes';
  s.textContent = `
    @keyframes vesPulse  { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
    @keyframes vesIn     { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
    @keyframes vesFade   { from { opacity: 0; } to { opacity: 1; } }
    @keyframes vesSlide  { from { transform: translateY(100%); } to { transform: translateY(0); } }
    @keyframes vesShimmer{ 0% { background-position: -120% 0; } 100% { background-position: 220% 0; } }
    @keyframes vesBreath { 0%,100% { transform: scale(1); } 50% { transform: scale(1.04); } }
    @keyframes vesSpin   { to { transform: rotate(360deg); } }
    @keyframes vesDrift1 { 0% { transform: translate(0,0) scale(1); } 100% { transform: translate(8%, 6%) scale(1.15); } }
    @keyframes vesDrift2 { 0% { transform: translate(0,0) scale(1); } 100% { transform: translate(-6%, -10%) scale(1.1); } }
  `;
  document.head.appendChild(s);
}

// ─── Sheet primitive — modal bottom sheet with scrim + drag handle ──
// Replaces the hand-rolled "stack two screens in a fragment" pattern.
// Children render inside the sheet body; pass header/footer for chrome.
function Sheet({ open = true, onClose, header, footer, children, accent }) {
  if (!open) return null;
  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 50 }}>
      <div onClick={onClose} style={{
        position: 'absolute', inset: 0,
        background: 'rgba(7,9,13,0.72)',
        backdropFilter: 'blur(6px)',
        animation: 'vesFade .25s both',
      }} />
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        background: V.deep,
        borderTopLeftRadius: V.rXL, borderTopRightRadius: V.rXL,
        borderTop: `1px solid ${accent || V.hair}`,
        boxShadow: '0 -28px 70px rgba(0,0,0,0.6)',
        maxHeight: '88%',
        display: 'flex', flexDirection: 'column',
        animation: 'vesSlide .32s cubic-bezier(.2,.7,.3,1)',
      }}>
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <div style={{ width: 36, height: 4, borderRadius: 2, background: V.ghost }} />
        </div>
        {header}
        <div className="ves-scroll" style={{
          flex: 1, overflowY: 'auto', padding: '8px 22px 4px',
        }}>{children}</div>
        {footer}
      </div>
    </div>
  );
}
window.Sheet = Sheet;

// ─── Type primitives ──────────────────────────────────────────────

function PersonaLabel({ name = 'WITNESS', color, opacity = 0.75 }) {
  return (
    <div style={{
      fontFamily: V.mono, fontSize: 10, letterSpacing: '0.24em',
      textTransform: 'uppercase', color: color || V.mist,
      opacity, lineHeight: 1, fontWeight: 500,
    }}>{name}</div>
  );
}

function Eyebrow({ children, color }) {
  return (
    <div style={{
      fontFamily: V.mono, fontSize: 10, letterSpacing: '0.20em',
      textTransform: 'uppercase', color: color || V.faint,
      lineHeight: 1.4, fontWeight: 500,
    }}>{children}</div>
  );
}

// Atmospheric display headline — uses the serif italic for moments where
// the app name or hero copy should breathe. Optical-size axis tuned for
// large display.
function HDisplay({ children, style }) {
  return (
    <h1 style={{
      margin: 0, fontFamily: V.display, fontStyle: 'italic',
      fontWeight: 400, fontSize: 38, lineHeight: 1.05,
      letterSpacing: '-0.015em', color: V.ink, textWrap: 'balance',
      fontOpticalSizing: 'auto', ...style,
    }}>{children}</h1>
  );
}

// Standard sans heading
function H1({ children, style }) {
  return (
    <h1 style={{
      margin: 0, fontFamily: V.sans, fontWeight: 500,
      fontSize: 26, lineHeight: 1.2, letterSpacing: '-0.01em',
      color: V.ink, textWrap: 'balance', ...style,
    }}>{children}</h1>
  );
}

function P({ children, dim = false, style }) {
  return (
    <p style={{
      margin: 0, fontFamily: V.sans, fontWeight: 400,
      fontSize: 15, lineHeight: 1.55,
      color: dim ? V.mist : V.ink, textWrap: 'pretty', ...style,
    }}>{children}</p>
  );
}

// ─── Buttons ──────────────────────────────────────────────────────

function PrimaryBtn({ children, onClick, disabled, danger, style }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{
      width: '100%', minHeight: 52, padding: '14px 22px',
      borderRadius: V.rPill,
      background: disabled ? V.s2 : (danger ? V.glow : V.ink),
      color: disabled ? V.faint : (danger ? V.deep : V.deep),
      border: 'none', cursor: disabled ? 'default' : 'pointer',
      fontFamily: V.sans, fontSize: 15, fontWeight: 600,
      letterSpacing: '0.005em',
      transition: 'transform .12s ease, background .15s',
      ...style,
    }}
    onMouseDown={(e) => { if (!disabled) e.currentTarget.style.transform = 'scale(0.98)'; }}
    onMouseUp={(e) => { e.currentTarget.style.transform = 'scale(1)'; }}
    onMouseLeave={(e) => { e.currentTarget.style.transform = 'scale(1)'; }}
    >{children}</button>
  );
}

function GhostBtn({ children, onClick, style, subtle = false }) {
  return (
    <button onClick={onClick} style={{
      minHeight: 48, padding: '12px 20px', borderRadius: V.rPill,
      background: 'transparent',
      color: subtle ? V.mist : V.ink,
      border: `1px solid ${subtle ? V.hair : V.ghost}`,
      cursor: 'pointer',
      fontFamily: V.sans, fontSize: 14, fontWeight: 500,
      transition: 'background .15s, border-color .15s',
      ...style,
    }}
    onMouseEnter={(e) => { e.currentTarget.style.background = 'oklch(75% 0.020 250 / 0.06)'; }}
    onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
    >{children}</button>
  );
}

function IconBtn({ children, onClick, ariaLabel, style }) {
  return (
    <button onClick={onClick} aria-label={ariaLabel} style={{
      width: 42, height: 42, borderRadius: V.rPill,
      background: 'transparent', border: 'none', padding: 0,
      cursor: 'pointer', color: V.ink,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      transition: 'background .15s',
      ...style,
    }}
    onMouseEnter={(e) => { e.currentTarget.style.background = 'oklch(75% 0.020 250 / 0.07)'; }}
    onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
    >{children}</button>
  );
}

// ─── App shell top — model status + optional left/right slots ────
// `left` overrides the model-status pill (e.g. detail screens with a
// back button on the left, the Android convention). Pass `interactive={false}`
// to make the status pill a no-op (for first-run onboarding).
function AppShellTop({ modelState = 'ready', onStatusTap, left, right, interactive = true }) {
  const stateMap = {
    ready:       { dot: V.pulse,  label: 'Local · Listening' },
    downloading: { dot: V.vapor,  label: 'Downloading…' },
    stalled:     { dot: V.glow,   label: 'Stalled' },
    updating:    { dot: V.vapor,  label: 'Updating' },
    off:         { dot: V.mist,   label: 'Offline' },
  };
  const s = stateMap[modelState] || stateMap.ready;
  const StatusPill = (
    <button
      onClick={interactive ? (onStatusTap || (() => window.dispatchEvent(new CustomEvent('vestige:open-status')))) : undefined}
      disabled={!interactive}
      style={{
        display: 'flex', alignItems: 'center', gap: 8,
        background: 'transparent', border: 'none', padding: 0,
        cursor: interactive ? 'pointer' : 'default',
      }}
      aria-label="model status"
    >
      <span style={{
        width: 7, height: 7, borderRadius: '50%',
        background: s.dot,
        boxShadow: modelState === 'ready' ? `0 0 10px ${s.dot}` : 'none',
      }} />
      <Eyebrow>{s.label}</Eyebrow>
    </button>
  );
  return (
    <div style={{
      padding: '14px 18px 10px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      gap: 12, flexShrink: 0, position: 'relative', zIndex: 2,
    }}>
      {left || StatusPill}
      {left && (
        <div style={{ flex: 1, display: 'flex', justifyContent: 'center' }}>{StatusPill}</div>
      )}
      <div style={{ minWidth: right ? 'auto' : 80, display: 'flex', justifyContent: 'flex-end' }}>
        {right}
      </div>
    </div>
  );
}

// ─── IconSlot — palimpsest placeholder ──────────────────────────
// Reads as a faded watermark — a "trace" of an icon under a fresher
// surface — rather than a literal dashed bounding box. Soft halo,
// barely-there edge, ghost glyph at center.
function IconSlot({ size = 36, label = 'icon', tint = V.vapor, style }) {
  return (
    <div style={{
      width: size, height: size,
      borderRadius: Math.round(size * 0.32),
      background: `radial-gradient(closest-side, oklch(86% 0.105 245 / 0.10), oklch(28% 0.024 252 / 0.55) 75%)`,
      boxShadow: `inset 0 0 0 1px oklch(86% 0.105 245 / 0.14)`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: tint, position: 'relative', flexShrink: 0,
      overflow: 'hidden',
      ...style,
    }}>
      <span style={{
        fontFamily: V.mono, fontSize: Math.max(7, Math.round(size * 0.18)),
        letterSpacing: '0.20em', textTransform: 'uppercase', opacity: 0.55,
        fontWeight: 500,
      }}>{label}</span>
    </div>
  );
}

// ─── Trace bar — visual density of how often a pattern returns ───
// 30 cells, lit cells are days the pattern showed up. Reads as misty
// vapor punctuated by glow. Keystone of the "vestige tracker" UI.
function TraceBar({ days = 30, hits = [], height = 22, accent }) {
  const set = new Set(hits);
  const lit = accent || V.glow;
  return (
    <div style={{
      display: 'flex', alignItems: 'flex-end', gap: 2, height,
    }}>
      {Array.from({ length: days }).map((_, i) => {
        const on = set.has(i);
        return (
          <div key={i} style={{
            flex: 1, height: on ? '100%' : '34%',
            borderRadius: 1.5,
            background: on ? lit : V.hair,
            boxShadow: on ? `0 0 6px ${V.glowDim}` : 'none',
            opacity: on ? 1 : 0.7,
          }} />
        );
      })}
    </div>
  );
}

// ─── Icons ─────────────────────────────────────────────────────────
// Right register: footprint partials, worn impressions, palimpsest layers,
// sediment lines, mist behind silhouette. Two-pass strokes — one ghosted
// (older trace) plus one foreground (current mark) — to literalize the
// "vestige" reading. All use currentColor so callers can re-tint.
const Icons = {
  // partial footprint — heel + toe pads, fading at edges
  footprint: (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
      <ellipse cx="11" cy="14" rx="5" ry="6" fill="currentColor" opacity="0.18"/>
      <ellipse cx="11" cy="14" rx="5" ry="6" stroke="currentColor" strokeOpacity="0.55" strokeWidth="1.2" strokeDasharray="2 3"/>
      <circle cx="7"  cy="6.5" r="1.4" fill="currentColor" opacity="0.85"/>
      <circle cx="11" cy="5"   r="1.6" fill="currentColor"/>
      <circle cx="15" cy="6"   r="1.3" fill="currentColor" opacity="0.7"/>
      <circle cx="17" cy="9"   r="1.1" fill="currentColor" opacity="0.5"/>
    </svg>
  ),
  // sediment / strata — geological layers, oldest at bottom
  strata: (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
      <path d="M3 7c4 -2 8 2 12 0s4 -2 6 0" stroke="currentColor" strokeWidth="1.2" strokeOpacity="0.45" strokeLinecap="round"/>
      <path d="M3 12c4 -1.5 8 1.5 12 0s4 -1.5 6 0" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/>
      <path d="M3 17c4 -1 8 1 12 0s4 -1 6 0" stroke="currentColor" strokeWidth="1.2" strokeOpacity="0.65" strokeLinecap="round"/>
    </svg>
  ),
  // palimpsest — ghosted older glyph behind a fresher one
  palimpsest: (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
      <path d="M5 4l5 9-3 5" stroke="currentColor" strokeOpacity="0.32" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
      <path d="M9 6h10M11 12h8M9 18h9" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/>
    </svg>
  ),
  // mist past silhouette
  silhouette: (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
      <path d="M2 17c3 -2 5 -2 7 0M15 17c3 -2 5 -2 7 0" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.2" strokeLinecap="round"/>
      <path d="M9 20V10a3 3 0 016 0v10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/>
      <circle cx="12" cy="6" r="2.4" stroke="currentColor" strokeWidth="1.4"/>
    </svg>
  ),
  // negative-space outline of an absent thing
  absence: (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
      <rect x="4" y="4" width="16" height="16" rx="3" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.2" strokeDasharray="2 3"/>
      <path d="M9 12h6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"/>
    </svg>
  ),

  // working glyphs — kept utilitarian; double-stroke ghost where it fits
  back: (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
      <path d="M16 18l-6-6 6-6" stroke="currentColor" strokeOpacity="0.30" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
      <path d="M14 18l-6-6 6-6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  ),
  close: (<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg>),
  more:  (<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="5"  r="1.4" fill="currentColor" opacity="0.7"/><circle cx="12" cy="12" r="1.4" fill="currentColor"/><circle cx="12" cy="19" r="1.4" fill="currentColor" opacity="0.5"/></svg>),
  settings: (<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="2.5" stroke="currentColor" strokeWidth="1.4"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg>),
  type: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
      <path d="M5 7h14M9 7v11M5 18h8" stroke="currentColor" strokeOpacity="0.30" strokeWidth="1.2" strokeLinecap="round"/>
      <path d="M5 6h14M9 6v12M5 18h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    </svg>
  ),
  mic: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
      <rect x="9" y="3" width="6" height="12" rx="3" stroke="currentColor" strokeWidth="1.5"/>
      <path d="M5 11a7 7 0 0014 0M12 18v3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    </svg>
  ),
  wifi: (<svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M2 8.5a16 16 0 0120 0M5 12a12 12 0 0114 0M8 15.5a8 8 0 018 0" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/><circle cx="12" cy="19" r="1.2" fill="currentColor"/></svg>),
  retry: (<svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M21 12a9 9 0 11-3-6.7M21 4v5h-5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/></svg>),
  check: (<svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M5 12.5l4.5 4.5L19 7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>),
  // trace — wisp curling back on itself, ghosted earlier sweep
  trace: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
      <path d="M3 18c3-6 6-6 9 0s6 0 9-6" stroke="currentColor" strokeOpacity="0.30" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
      <path d="M3 16c3-6 6-6 9 0s6 0 9-6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  ),
  arrowR: (<svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/></svg>),
};

Object.assign(window, {
  V, NOISE_URL, noiseStyle, FogDrift,
  PersonaLabel, Eyebrow, HDisplay, H1, P,
  PrimaryBtn, GhostBtn, IconBtn, AppShellTop, IconSlot, TraceBar, Icons,
});
