// Vestige · SCOREBOARD direction — tokens, type, primitives.
// Warm dark, electric lime as the single signal, coral as the alarm,
// Anton for huge condensed numbers. ADHD-on-speed without burning eyes.

const E = {
  // ─── Surfaces — warm espresso, NOT blue ────────────────────────
  floor:  'oklch(13% 0.012 55)',   // page floor
  deep:   'oklch(15% 0.014 55)',   // device interior
  s1:     'oklch(19% 0.016 55)',   // card base
  s2:     'oklch(24% 0.018 55)',   // raised
  s3:     'oklch(30% 0.020 55)',   // hover

  // ─── Ink — warm cream, never pure white ────────────────────────
  ink:    'oklch(95% 0.015 85)',
  dim:    'oklch(70% 0.020 75)',
  faint:  'oklch(55% 0.018 70)',
  ghost:  'oklch(45% 0.015 65 / 0.6)',
  hair:   'oklch(70% 0.020 70 / 0.12)',
  hair2:  'oklch(70% 0.020 70 / 0.06)',

  // ─── Accents — used SPARINGLY, never together on one element ──
  lime:    'oklch(89% 0.19 115)',           // signal — live, active, "on"
  limeDim: 'oklch(89% 0.19 115 / 0.20)',
  limeSoft:'oklch(89% 0.19 115 / 0.55)',

  coral:   'oklch(72% 0.21 28)',            // heat — recording, roast, danger
  coralDim:'oklch(72% 0.21 28 / 0.20)',
  coralSoft:'oklch(72% 0.21 28 / 0.55)',

  teal:    'oklch(77% 0.12 195)',           // cool — resolved, settled
  tealDim: 'oklch(77% 0.12 195 / 0.20)',

  ember:   'oklch(82% 0.16 65)',            // warm gold — secondary stats

  // ─── Radii — sharper than mist, no pillows ─────────────────────
  rPill: 9999, rXL: 18, rL: 12, rM: 8, rS: 4, rXS: 2,
};

// Type stacks
const ET = {
  display: '"Anton", "Oswald", "Arial Narrow", sans-serif',  // huge condensed
  sans:    '"Space Grotesk", system-ui, sans-serif',
  mono:    '"JetBrains Mono", ui-monospace, Menlo, monospace',
};

// ─── Keyframes ────────────────────────────────────────────────────
if (typeof document !== 'undefined' && !document.getElementById('scoreboard-keyframes')) {
  const s = document.createElement('style');
  s.id = 'scoreboard-keyframes';
  s.textContent = `
    @keyframes sbPulse  { 0%,100% { opacity: 1; } 50% { opacity: 0.4; } }
    @keyframes sbBlink  { 0%,49% { opacity: 1; } 50%,100% { opacity: 0.15; } }
    @keyframes sbScroll { 0% { transform: translateX(0); } 100% { transform: translateX(-50%); } }
    @keyframes sbTick   { 0%, 90% { transform: translateY(0); } 95% { transform: translateY(-2px); } 100% { transform: translateY(0); } }
    @keyframes sbBars   { 0%,100% { transform: scaleY(0.3); } 50% { transform: scaleY(1); } }
    @keyframes sbSweep  { 0% { background-position: -200% 0; } 100% { background-position: 200% 0; } }
    @keyframes sbWobble { 0%,100% { transform: rotate(-1.5deg); } 50% { transform: rotate(1.5deg); } }
    @keyframes sbRise   { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
  `;
  document.head.appendChild(s);
}

// ─── Tape rule — horizontal grain that reads like a printed receipt ─
const TAPE_BG = `repeating-linear-gradient(
  to bottom,
  oklch(70% 0.020 70 / 0.03) 0px,
  oklch(70% 0.020 70 / 0.03) 1px,
  transparent 1px,
  transparent 4px
)`;

// ─── Halftone — printed-newspaper texture for callouts ─────────────
const HALFTONE_BG = `radial-gradient(
  oklch(70% 0.020 70 / 0.15) 0.7px,
  transparent 1.2px
)`;

// ─── Primitives ────────────────────────────────────────────────────

function EyebrowE({ children, color, style }) {
  return (
    <div style={{
      fontFamily: ET.mono, fontSize: 9.5, fontWeight: 600,
      letterSpacing: '0.20em', textTransform: 'uppercase',
      color: color || E.dim, lineHeight: 1.2, ...style,
    }}>{children}</div>
  );
}

function StatusDot({ color = E.lime, blink = false, size = 7 }) {
  return (
    <span style={{
      display: 'inline-block', width: size, height: size, borderRadius: '50%',
      background: color,
      boxShadow: `0 0 8px ${color}`,
      animation: blink ? 'sbPulse 1.4s ease-in-out infinite' : undefined,
    }} />
  );
}

// Big stat number — Anton condensed, screams from across the room.
function BigStat({ value, label, color, size = 56, style }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', ...style }}>
      <div style={{
        fontFamily: ET.display, fontSize: size, lineHeight: 0.85,
        fontWeight: 400, color: color || E.ink,
        letterSpacing: '-0.01em', fontVariantNumeric: 'tabular-nums',
      }}>{value}</div>
      {label && (
        <div style={{
          marginTop: 6, fontFamily: ET.mono, fontSize: 9,
          letterSpacing: '0.18em', textTransform: 'uppercase',
          color: E.dim, fontWeight: 600,
        }}>{label}</div>
      )}
    </div>
  );
}

// Status pill — capsule with mono label + optional dot.
function Pill({ children, color, fill, dot, blink, style }) {
  const c = color || E.lime;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '4px 9px 4px 9px', borderRadius: E.rPill,
      background: fill ? c : 'transparent',
      border: `1px solid ${fill ? c : c + (c.includes('/') ? '' : '')}`,
      color: fill ? E.deep : c,
      fontFamily: ET.mono, fontSize: 9.5, fontWeight: 700,
      letterSpacing: '0.16em', textTransform: 'uppercase',
      lineHeight: 1,
      ...style,
    }}>
      {dot && <StatusDot color={fill ? E.deep : c} blink={blink} size={6} />}
      {children}
    </span>
  );
}

// Delta tag — +4, -2, etc. Lime for positive, coral for negative.
function Delta({ value, label, style }) {
  const positive = value >= 0;
  const c = positive ? E.lime : E.coral;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'baseline', gap: 4,
      fontFamily: ET.mono, fontSize: 11, fontWeight: 600,
      color: c, lineHeight: 1, ...style,
    }}>
      <span style={{ fontFamily: ET.display, fontSize: 14, letterSpacing: '0.02em' }}>
        {positive ? '▲' : '▼'}{Math.abs(value)}
      </span>
      {label && (
        <span style={{
          fontFamily: ET.mono, fontSize: 9, color: E.dim, fontWeight: 500,
          textTransform: 'uppercase', letterSpacing: '0.16em',
        }}>{label}</span>
      )}
    </span>
  );
}

// ─── TraceBar — denser, sharper, with peak markers ─────────────────
function TraceBarE({ days = 30, hits = [], height = 28, accent = E.lime, peak = true }) {
  const set = new Set(hits);
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height }}>
      {Array.from({ length: days }).map((_, i) => {
        const on = set.has(i);
        return (
          <div key={i} style={{
            flex: 1, height: on ? '100%' : '18%',
            background: on ? accent : E.hair,
            opacity: on ? 1 : 0.85,
            boxShadow: on && peak ? `0 0 6px ${accent}` : 'none',
          }} />
        );
      })}
    </div>
  );
}

// ─── Stat ribbon — newsroom-style row of mini-stats ────────────────
function StatRibbon({ items, accent }) {
  return (
    <div style={{
      display: 'grid', gridTemplateColumns: `repeat(${items.length}, 1fr)`,
      gap: 0,
      border: `1px solid ${E.hair}`,
      background: E.s1,
      backgroundImage: TAPE_BG,
    }}>
      {items.map((it, i) => (
        <div key={i} style={{
          padding: '10px 8px 8px',
          borderRight: i < items.length - 1 ? `1px solid ${E.hair}` : 'none',
          display: 'flex', flexDirection: 'column', gap: 4,
        }}>
          <div style={{
            fontFamily: ET.display, fontSize: 28, lineHeight: 0.85,
            color: it.color || E.ink,
            fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em',
          }}>{it.value}</div>
          <div style={{
            fontFamily: ET.mono, fontSize: 8, letterSpacing: '0.18em',
            textTransform: 'uppercase', color: E.dim, fontWeight: 600,
          }}>{it.label}</div>
        </div>
      ))}
    </div>
  );
}

// ─── Tick row — small repeating tick marks (like a ruler) ──────────
function TickRule({ count = 30, marks = [], style }) {
  const set = new Set(marks);
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', height: 8, gap: 1, ...style }}>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} style={{
          flex: 1,
          height: set.has(i) ? '100%' : '40%',
          background: set.has(i) ? E.ink : E.hair,
        }} />
      ))}
    </div>
  );
}

// ─── Phone frame — Android, warm dark, status bar at top ───────────
function PhoneE({ children, label, sub, accent, w = 380, h = 800 }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {/* Label above the phone */}
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, paddingLeft: 6 }}>
        <span style={{
          fontFamily: ET.display, fontSize: 22, letterSpacing: '0.02em',
          color: accent || E.ink, lineHeight: 1,
        }}>{label}</span>
        {sub && <span style={{
          fontFamily: ET.mono, fontSize: 9, letterSpacing: '0.20em',
          textTransform: 'uppercase', color: E.dim, fontWeight: 600,
        }}>{sub}</span>}
      </div>
      <div style={{
        width: w, height: h, borderRadius: 32,
        background: E.deep, overflow: 'hidden',
        border: `1px solid ${E.hair}`,
        boxShadow: `0 30px 80px oklch(0% 0 0 / 0.5), 0 0 0 6px oklch(8% 0.008 55), 0 0 0 7px oklch(16% 0.012 55)`,
        display: 'flex', flexDirection: 'column',
        position: 'relative',
      }}>
        {/* Status bar */}
        <div style={{
          padding: '12px 22px 4px',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          fontFamily: ET.mono, fontSize: 11, fontWeight: 600,
          color: E.ink, flexShrink: 0,
        }}>
          <span>9:41</span>
          <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center', color: E.dim }}>
            <svg width="11" height="9" viewBox="0 0 14 10" fill="none"><path d="M1 9 L7 1 L13 9" stroke="currentColor" strokeWidth="1.2" fill="none"/></svg>
            <svg width="13" height="9" viewBox="0 0 16 10" fill="none"><rect x="0.5" y="2" width="13" height="6" stroke="currentColor" strokeWidth="0.8" fill="none"/><rect x="2" y="3.5" width="8" height="3" fill="currentColor"/></svg>
          </span>
        </div>
        {/* Body */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, position: 'relative' }}>
          {children}
        </div>
      </div>
    </div>
  );
}

// ─── App shell top — Local status + persona ────────────────────────
function AppTop({ persona = 'WITNESS', recording = false, onTimer }) {
  return (
    <div style={{
      padding: '10px 18px 8px',
      display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      borderBottom: `1px solid ${E.hair}`,
    }}>
      <div style={{
        display: 'inline-flex', alignItems: 'center', gap: 7,
        padding: '5px 10px 5px 9px',
        border: `1px solid ${recording ? E.coralSoft : E.hair}`,
        background: recording ? E.coralDim : 'transparent',
      }}>
        <StatusDot color={recording ? E.coral : E.lime} blink size={6} />
        <span style={{
          fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
          letterSpacing: '0.20em', textTransform: 'uppercase',
          color: recording ? E.coral : E.ink,
        }}>{recording ? 'ON AIR · LIVE' : 'LOCAL · GEMMA 4'}</span>
      </div>
      {recording ? onTimer : (
        <div style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          padding: '5px 10px', border: `1px solid ${E.hair}`,
          background: 'transparent',
        }}>
          <span style={{
            width: 5, height: 5, background: E.lime, transform: 'rotate(45deg)',
          }} />
          <span style={{
            fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
            letterSpacing: '0.20em', textTransform: 'uppercase', color: E.ink,
          }}>{persona} ▾</span>
        </div>
      )}
    </div>
  );
}

Object.assign(window, {
  E, ET, TAPE_BG, HALFTONE_BG,
  EyebrowE, StatusDot, BigStat, Pill, Delta, TraceBarE, StatRibbon, TickRule,
  PhoneE, AppTop,
});
