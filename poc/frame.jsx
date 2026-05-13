// Vestige · Android device frame — atmospheric, noise-textured, soft
// "Mist & Trace" — the bezel is dark glass with a faint moonstone halo;
// the interior carries a persistent grain layer and quiet drift in the
// background.

const VFRAME = {
  bezel: 'oklch(20% 0.020 252)',
  bezelOuter: 'oklch(8% 0.010 252)',
  ink: 'oklch(96% 0.010 250)',
};

function VStatusBar() {
  const c = VFRAME.ink;
  return (
    <div style={{
      height: 38, padding: '0 22px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      fontFamily: 'Inter, system-ui, sans-serif',
      position: 'relative', flexShrink: 0, zIndex: 3,
    }}>
      <span style={{ fontSize: 13, fontWeight: 500, color: c, letterSpacing: '0.01em' }}>9:41</span>
      <div style={{
        position: 'absolute', left: '50%', top: 9, transform: 'translateX(-50%)',
        width: 22, height: 22, borderRadius: 11, background: '#000',
      }} />
      <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
        <svg width="14" height="11" viewBox="0 0 14 11" fill="none">
          <path d="M1 7.5 L4 4.5 L7 6.5 L13 1" stroke={c} strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <svg width="13" height="9" viewBox="0 0 13 9"><path d="M0 9L13 0V9H0Z" fill={c} /></svg>
        <svg width="22" height="11" viewBox="0 0 22 11">
          <rect x="0.5" y="0.5" width="18" height="10" rx="2.5" stroke={c} fill="none" />
          <rect x="2" y="2" width="13" height="7" rx="1" fill={c} />
          <rect x="19.5" y="3.5" width="2" height="4" rx="0.5" fill={c} />
        </svg>
      </div>
    </div>
  );
}

function VGestureBar() {
  return (
    <div style={{ height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, position: 'relative', zIndex: 3 }}>
      <div style={{ width: 124, height: 4, borderRadius: 2, background: VFRAME.ink, opacity: 0.32 }} />
    </div>
  );
}

function VestigePhone({ children }) {
  return (
    <div style={{
      width: 412, height: 892, borderRadius: 44, overflow: 'hidden',
      background: 'oklch(15% 0.020 252)',
      color: VFRAME.ink,
      border: `8px solid ${VFRAME.bezel}`,
      outline: `1px solid ${VFRAME.bezelOuter}`,
      boxShadow: [
        '0 60px 140px oklch(8% 0.010 252 / 0.75)',
        '0 0 0 1px oklch(75% 0.020 250 / 0.04)',
        'inset 0 0 0 1px oklch(75% 0.020 250 / 0.03)',
        '0 0 80px oklch(86% 0.105 245 / 0.06)',
      ].join(', '),
      display: 'flex', flexDirection: 'column',
      fontFamily: 'Inter, system-ui, sans-serif',
      position: 'relative',
    }}>
      {/* ambient drift behind everything */}
      <FogDrift intensity={0.32} hueA={245} hueB={218} />
      {/* persistent grain across the interior */}
      <div style={noiseStyle(0.07)} />

      <VStatusBar />
      <div style={{
        flex: 1, position: 'relative', overflow: 'hidden',
        display: 'flex', flexDirection: 'column', zIndex: 2,
      }}>
        {children}
      </div>
      <VGestureBar />
    </div>
  );
}

window.VestigePhone = VestigePhone;
