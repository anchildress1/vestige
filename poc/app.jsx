// Vestige · root app — atmospheric outer chrome, mist drift, dev nav

const { useState: useSA, useEffect: useEA } = React;

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "persona": "witness",
  "modelState": "ready"
}/*EDITMODE-END*/;

const SCREENS = [
  { id: 'capture',        label: '01 Capture' },
  { id: 'recording',      label: '02 Capture · recording' },
  { id: 'typed',          label: '03 Typed' },
  { id: 'onboarding',     label: '04 Onboarding' },
  { id: 'status',         label: '05 Local Model' },
  { id: 'persona',        label: '06 Persona' },
  { id: 'patterns',       label: '07 Patterns' },
  { id: 'pattern-detail', label: '08 Pattern Detail' },
  { id: 'reading',        label: '09 Reading' },
  { id: 'roast',          label: '10 Roast' },
  { id: 'wiped',          label: '11 Wiped' },
  { id: 'error',          label: '12 Error' },
  { id: 'destructive',    label: '13 Destructive' },
];

function App() {
  const t = (window.useTweaks || ((d) => [d, () => {}]))(TWEAK_DEFAULTS);
  const [tweaks, setTweak] = t;

  const [screen, setScreen] = useSA('capture');
  const [openPattern, setOpenPattern] = useSA('tuesday-meetings');
  // Local pattern-status overrides — the lifecycle (Snooze / Resolved /
  // Reactivate) actually mutates state for the session. Builders should
  // hand this off to a real store.
  const [statusOverrides, setStatusOverrides] = useSA({});

  // Patch live data with overrides so every screen sees the same lifecycle.
  useEA(() => {
    const base = window.__VESTIGE_DATA_BASE = window.__VESTIGE_DATA_BASE || window.VESTIGE_DATA;
    window.VESTIGE_DATA = {
      ...base,
      PATTERNS: base.PATTERNS.map((p) => statusOverrides[p.id] ? { ...p, status: statusOverrides[p.id] } : p),
    };
  }, [statusOverrides]);

  useEA(() => {
    const h = () => setScreen('status');
    window.addEventListener('vestige:open-status', h);
    return () => window.removeEventListener('vestige:open-status', h);
  }, []);

  const persona = tweaks.persona;
  const modelState = tweaks.modelState;
  const labelFor = (id) => (SCREENS.find((s) => s.id === id) || {}).label || id;
  const updateStatus = (id, next) => setStatusOverrides((o) => ({ ...o, [id]: next }));

  const renderScreen = () => {
    switch (screen) {
      case 'capture':
        return <CaptureScreen persona={persona}
          onPatterns={() => setScreen('patterns')}
          onStatus={() => setScreen('status')}
          onPersona={() => setScreen('persona')}
          onTyped={() => setScreen('typed')}
          onError={() => setScreen('error')} />;
      case 'typed':
        return <TypedEntryScreen persona={persona}
          onClose={() => setScreen('capture')}
          onSave={() => setScreen('patterns')} />;
      case 'recording':
        return <CaptureScreen persona={persona} autoRecord
          onPatterns={() => setScreen('patterns')}
          onStatus={() => setScreen('status')}
          onPersona={() => setScreen('persona')}
          onTyped={() => setScreen('typed')}
          onError={() => setScreen('error')} />;
      case 'onboarding':
        return <OnboardingScreen onDone={() => setScreen('capture')} />;
      case 'status':
        return <ModelStatusScreen state={modelState}
          onClose={() => setScreen('capture')}
          onRetry={() => setTweak('modelState', 'downloading')} />;
      case 'persona':
        return <PersonaScreen persona={persona}
          onPick={(id) => setTweak('persona', id)}
          onClose={() => setScreen('capture')} />;
      case 'patterns':
        return <PatternsScreen persona={persona}
          onClose={() => setScreen('capture')}
          onOpen={(id) => { setOpenPattern(id); setScreen('pattern-detail'); }}
          onRoast={() => setScreen('roast')} />;
      case 'pattern-detail':
        return <PatternDetailScreen patternId={openPattern}
          onClose={() => setScreen('patterns')}
          onUpdateStatus={updateStatus} />;
      case 'reading':
        return <ReadingScreen
          onClose={() => setScreen('capture')}
          onPattern={(id) => { setOpenPattern(id); setScreen('pattern-detail'); }} />;
      case 'roast':
        // Roast is now a real Sheet primitive — it overlays Patterns
        // properly without rendering two screens stacked.
        return (<>
          <PatternsScreen persona={persona}
            onClose={() => setScreen('capture')}
            onOpen={(id) => { setOpenPattern(id); setScreen('pattern-detail'); }}
            onRoast={() => setScreen('roast')} />
          <RoastScreen persona={persona}
            onClose={() => setScreen('patterns')}
            onWipe={() => setScreen('destructive')} />
        </>);
      case 'wiped':
        return <WipedScreen onContinue={() => setScreen('onboarding')} />;
      case 'error':
        return <ErrorScreen kind="network"
          onClose={() => setScreen('capture')}
          onRetry={() => setScreen('capture')} />;
      case 'destructive':
        return <DestructiveScreen onCancel={() => setScreen('roast')} onConfirm={() => setScreen('wiped')} />;
      default:
        return null;
    }
  };

  return (
    <div style={{
      minHeight: '100vh', width: '100%',
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      padding: '40px 24px 60px',
      background: V.void,
      fontFamily: V.sans,
      color: V.ink,
      position: 'relative',
      overflowX: 'hidden',
    }}>
      <div style={{ position: 'fixed', inset: 0, pointerEvents: 'none' }}>
        <FogDrift intensity={0.5} hueA={245} hueB={210} />
        <div style={noiseStyle(0.05)} />
      </div>

      <div style={{
        width: '100%', maxWidth: 920, marginBottom: 28,
        display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between',
        gap: 24, position: 'relative', zIndex: 2,
      }}>
        <div>
          <Eyebrow>android · on-device · personal vestige tracker</Eyebrow>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, marginTop: 8 }}>
            <h1 style={{
              margin: 0, fontFamily: V.display, fontStyle: 'italic',
              fontWeight: 400, fontSize: 62, lineHeight: 0.95,
              letterSpacing: '-0.025em', color: V.ink,
            }}>Vestige</h1>
            <span style={{
              fontFamily: V.mono, fontSize: 11, color: V.faint,
              letterSpacing: '0.18em', textTransform: 'uppercase',
            }}>/ ˈvɛstɪdʒ /</span>
          </div>
          <p style={{
            margin: '10px 0 0', maxWidth: 420,
            fontFamily: V.sans, fontSize: 13, lineHeight: 1.55, color: V.mist,
          }}>
            A faint trace of something that once was. This app catches what keeps
            returning — Tuesday's mood, the email you won't send, the word you
            keep re-using. Mist that lingers in the room.
          </p>
        </div>

        <div style={{
          display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'flex-end',
          fontFamily: V.mono, fontSize: 10, letterSpacing: '0.16em',
          color: V.faint, textTransform: 'uppercase', textAlign: 'right',
        }}>
          <div>prototype · pixel 8 · 412×892</div>
          <div>persona · model state</div>
          <div style={{ color: V.mist }}>— live in tweaks</div>
        </div>
      </div>

      <div style={{
        width: '100%', maxWidth: 920, marginBottom: 24, position: 'relative', zIndex: 2,
        display: 'flex', flexWrap: 'wrap', gap: 0,
        borderTop: `1px solid ${V.hair}`,
        borderBottom: `1px solid ${V.hair}`,
        padding: '10px 0',
      }}>
        {SCREENS.map((s, i) => (
          <button key={s.id} onClick={() => setScreen(s.id)} style={{
            padding: '6px 12px',
            borderRight: i < SCREENS.length - 1 ? `1px solid ${V.hair2}` : 'none',
            background: 'transparent', border: 'none', cursor: 'pointer',
            color: screen === s.id ? V.glow : V.mist,
            fontFamily: V.mono, fontSize: 10, letterSpacing: '0.18em',
            textTransform: 'uppercase', fontWeight: screen === s.id ? 600 : 400,
            transition: 'color .15s',
            textShadow: screen === s.id ? `0 0 12px ${V.glowDim}` : 'none',
          }}>{s.label}</button>
        ))}
      </div>

      <div data-screen-label={labelFor(screen)} style={{ position: 'relative', zIndex: 2 }}>
        <VestigePhone>{renderScreen()}</VestigePhone>
      </div>

      {window.TweaksPanel && (() => {
        const TP = window.TweaksPanel;
        const TS = window.TweakSection;
        const TR = window.TweakRadio;
        const TSel = window.TweakSelect;
        return (
          <TP title="Tweaks">
            <TS label="Persona">
              <TR value={persona} options={['witness', 'hardass', 'editor']}
                onChange={(v) => setTweak('persona', v)} />
            </TS>
            <TS label="Local model status">
              <TSel value={modelState} options={['ready', 'downloading', 'stalled', 'updating']}
                onChange={(v) => setTweak('modelState', v)} />
            </TS>
            <TS label="Jump to screen">
              <TSel value={screen} options={SCREENS.map((s) => s.id)}
                onChange={(v) => setScreen(v)} />
            </TS>
          </TP>
        );
      })()}
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
