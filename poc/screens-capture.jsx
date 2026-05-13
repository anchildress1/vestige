// Vestige · Capture (home + recording overlay) and Typed (fallback).
// Recording is NOT a separate page — it's an in-place state of the
// Capture screen. Tapping the moonstone toggles it. While recording
// we show an audio level meter (not LLM feedback): the model reads
// AFTER the session is filed.

const { useState: useSC, useEffect: useEC, useRef: useRC } = React;

// ─── Mist Hero — the moonstone record button ─────────────────────
// Single component that owns its full visual surface. The center mark
// swaps based on `state`, never gets layered over.
function MistHero({ state = 'idle', level = 0, onClick, ariaLabel = 'start recording' }) {
  const size = 168;
  const recording = state === 'recording';
  // Outer halo gently amplifies with audio level while recording
  const haloAmp = recording ? 1 + level * 0.18 : 1;
  return (
    <button onClick={onClick} aria-label={ariaLabel} style={{
      position: 'relative', width: size, height: size,
      borderRadius: '50%', border: 'none',
      background: 'transparent', cursor: 'pointer', padding: 0,
    }}>
      {/* Outer halo — slow drift, audio-reactive when recording */}
      <div aria-hidden style={{
        position: 'absolute', inset: -36, borderRadius: '50%',
        background: `radial-gradient(closest-side, ${V.glowDim}, transparent 70%)`,
        filter: 'blur(8px)',
        transform: `scale(${haloAmp})`,
        transition: 'transform 90ms linear',
        animation: `vesBreath ${recording ? '1.6s' : '4.2s'} ease-in-out infinite`,
      }} />
      {/* Mid ring — moonstone wash */}
      <div aria-hidden style={{
        position: 'absolute', inset: -8, borderRadius: '50%',
        background: `conic-gradient(from 220deg, ${V.glowSoft}, oklch(70% 0.075 245 / 0.4), ${V.glowSoft}, oklch(50% 0.05 245 / 0.3), ${V.glowSoft})`,
        filter: 'blur(14px)', opacity: recording ? 0.95 : 0.85,
        animation: `vesSpin ${recording ? '12s' : '18s'} linear infinite`,
      }} />
      {/* Stone body — frosted glass */}
      <div style={{
        position: 'absolute', inset: 0, borderRadius: '50%',
        background: [
          'radial-gradient(120% 90% at 30% 25%, oklch(95% 0.05 245 / 0.55), transparent 55%)',
          'radial-gradient(140% 110% at 70% 80%, oklch(40% 0.06 245 / 0.85), transparent 60%)',
          'oklch(28% 0.04 245)',
        ].join(', '),
        boxShadow: [
          `0 0 ${60 + level * 40}px ${V.glowDim}`,
          `inset 0 1px 0 oklch(95% 0.04 245 / 0.45)`,
          `inset 0 -22px 36px oklch(15% 0.02 245 / 0.6)`,
        ].join(', '),
        overflow: 'hidden',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        transition: 'box-shadow 120ms linear',
      }}>
        <div style={noiseStyle(0.18)} />
        {/* Center mark — owned by MistHero, swaps by state */}
        {state === 'idle' && (
          <div style={{
            position: 'relative', zIndex: 2,
            width: 14, height: 14, borderRadius: 7,
            background: V.ink,
            boxShadow: `0 0 16px ${V.glowSoft}`,
          }} />
        )}
        {state === 'recording' && (
          <div style={{
            position: 'relative', zIndex: 2,
            width: 22, height: 22, borderRadius: 4,
            background: V.deep,
            boxShadow: `inset 0 0 0 1px oklch(15% 0.020 252 / 0.6), 0 0 18px ${V.glowSoft}`,
          }} />
        )}
      </div>
    </button>
  );
}

// ─── Audio Level Meter ────────────────────────────────────────────
// Horizontal bar strip — past N samples scroll left, newest on the
// right. Reads as a continuous voice trace, not a generic loader.
function AudioMeter({ history = [], live = false }) {
  return (
    <div aria-hidden style={{
      display: 'flex', alignItems: 'center', gap: 3,
      height: 38, padding: '0 4px',
    }}>
      {history.map((h, i) => {
        const recent = i >= history.length - 6;
        const alpha = live ? (0.35 + h * 0.65) : 0.18;
        return (
          <div key={i} style={{
            flex: 1,
            height: `${Math.max(10, h * 100)}%`,
            minHeight: 2,
            borderRadius: 1.5,
            background: live ? V.glow : V.hair,
            opacity: alpha,
            boxShadow: live && recent && h > 0.45 ? `0 0 6px ${V.glowDim}` : 'none',
            transition: 'height 90ms linear, opacity 90ms linear',
          }} />
        );
      })}
    </div>
  );
}

// ─── Capture (home + recording) ──────────────────────────────────
function CaptureScreen({
  persona = 'witness',
  autoRecord = false,
  onPatterns, onStatus, onPersona, onTyped, onError,
}) {
  const personaName = persona.toUpperCase();
  const [recording, setRecording] = useSC(!!autoRecord);
  const [elapsed, setElapsed] = useSC(0);
  const [level, setLevel] = useSC(0);
  const BAR_COUNT = 38;
  const [history, setHistory] = useSC(() => Array.from({ length: BAR_COUNT }, () => 0));
  const levelRef = useRC(0);

  // If autoRecord toggles via dev nav, reflect it
  useEC(() => { if (autoRecord) setRecording(true); }, [autoRecord]);

  // Simulated mic levels — wandering with occasional bursts
  useEC(() => {
    if (!recording) {
      setLevel(0);
      setHistory(Array.from({ length: BAR_COUNT }, () => 0));
      levelRef.current = 0;
      return;
    }
    const id = setInterval(() => {
      // base activity with bursts, smoothed
      const burst = Math.random() > 0.86 ? 1.5 : 1;
      const target = (0.18 + Math.random() * 0.55) * burst;
      levelRef.current = levelRef.current * 0.55 + target * 0.45;
      const v = Math.max(0, Math.min(1, levelRef.current));
      setLevel(v);
      setHistory((h) => {
        const nh = h.slice(1);
        nh.push(Math.max(0, Math.min(1, v + (Math.random() - 0.5) * 0.18)));
        return nh;
      });
    }, 85);
    return () => clearInterval(id);
  }, [recording]);

  // Elapsed timer
  useEC(() => {
    if (!recording) { setElapsed(0); return; }
    const t0 = Date.now();
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - t0) / 1000)), 250);
    return () => clearInterval(id);
  }, [recording]);

  function toggleStone() { setRecording((r) => !r); }
  function fileEntry() { setRecording(false); onPatterns && onPatterns(); }
  function cancelEntry() { setRecording(false); }

  const mm = String(Math.floor(elapsed / 60)).padStart(2, '0');
  const ss = String(elapsed % 60).padStart(2, '0');

  // Top-right swaps: persona pill (idle) ↔ elapsed time pill (recording)
  const topRight = recording ? (
    <span style={{
      fontFamily: V.mono, fontSize: 13, fontWeight: 500,
      color: V.glow, letterSpacing: '0.06em',
      fontVariantNumeric: 'tabular-nums',
      padding: '6px 12px', borderRadius: V.rPill,
      background: V.glowDim, border: `1px solid ${V.glowSoft}`,
      display: 'inline-flex', alignItems: 'center', gap: 8,
    }}>
      <span style={{
        width: 6, height: 6, borderRadius: 3, background: V.glow,
        boxShadow: `0 0 6px ${V.glow}`,
        animation: 'vesPulse 1.4s ease-in-out infinite',
      }} />
      {mm}:{ss}
    </span>
  ) : (
    <button onClick={onPersona} aria-label={`switch persona, currently ${persona}`} style={{
      display: 'flex', alignItems: 'center', gap: 8,
      padding: '8px 12px 8px 8px', borderRadius: V.rPill,
      background: 'oklch(28% 0.024 252 / 0.5)',
      border: `1px solid ${V.hair}`,
      cursor: 'pointer',
      fontFamily: V.mono, fontSize: 10, letterSpacing: '0.18em',
      color: V.ink, textTransform: 'uppercase',
    }}>
      <span style={{
        width: 14, height: 14, borderRadius: 7,
        background: `radial-gradient(closest-side, ${V.glowSoft}, ${V.glowDim} 60%, transparent 80%)`,
        boxShadow: `0 0 8px ${V.glowDim}`,
      }} />
      <span>{personaName}</span>
      <svg width="9" height="9" viewBox="0 0 24 24" fill="none">
        <path d="M6 9l6 6 6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
      </svg>
    </button>
  );

  // Hero copy swaps by state
  const heroKicker = recording ? 'Listening · on-device' : 'Hold the stone · speak';
  const heroSub = recording
    ? 'Speak freely. The model reads after.'
    : '30s chunks · audio discarded after extraction';

  return (
    <>
      <style>{`/* keyframes are in tokens.jsx */`}</style>

      <AppShellTop modelState="ready" onStatusTap={onStatus} right={topRight} />

      <div style={{
        flex: 1, display: 'flex', flexDirection: 'column',
        padding: '6px 22px 24px',
      }}>
        {/* Today's mist — atmospheric eyebrow */}
        <div style={{
          marginTop: 6, marginBottom: 18,
          opacity: recording ? 0.45 : 1,
          transition: 'opacity 240ms ease',
        }}>
          <Eyebrow>
            {recording
              ? `Recording · started ${new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}`
              : 'Thursday · 9:41 · 31 entries kept'}
          </Eyebrow>
          <div style={{
            marginTop: 8, fontFamily: V.display, fontStyle: 'italic',
            fontWeight: 400, fontSize: 26, lineHeight: 1.18,
            letterSpacing: '-0.01em', color: V.ink, textWrap: 'balance',
          }}>
            {recording ? 'Whatever lingered.' : 'What lingered from yesterday?'}
          </div>
        </div>

        {/* Hero — moonstone + (when recording) audio meter directly below */}
        <div style={{
          flex: 1, display: 'flex', flexDirection: 'column',
          alignItems: 'center', justifyContent: 'center', gap: 18,
          minHeight: 220,
        }}>
          <MistHero
            state={recording ? 'recording' : 'idle'}
            level={level}
            onClick={toggleStone}
            ariaLabel={recording ? 'stop recording' : 'start recording'}
          />

          {recording && (
            <div style={{
              width: '100%', maxWidth: 280,
              animation: 'vesIn .35s ease-out both',
            }}>
              <AudioMeter history={history} live />
            </div>
          )}

          <div style={{ textAlign: 'center', maxWidth: 280 }}>
            <Eyebrow color={recording ? V.glow : V.glow}>{heroKicker}</Eyebrow>
            <div style={{
              marginTop: 6, fontFamily: V.sans, fontSize: 13, color: V.mist,
              lineHeight: 1.5,
            }}>{heroSub}</div>
          </div>
        </div>

        {/* IDLE-only chrome — Type instead + Patterns teaser. Hidden while recording
            so the focus stays on voice. */}
        {!recording && (
          <>
            <button onClick={onTyped} style={{
              marginTop: 8, padding: '14px 18px', borderRadius: V.rPill,
              background: 'oklch(22% 0.022 252 / 0.7)',
              border: `1px solid ${V.hair}`,
              color: V.mist, cursor: 'pointer',
              fontFamily: V.sans, fontSize: 14, fontWeight: 500,
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
              backdropFilter: 'blur(8px)',
            }}>
              {Icons.type}
              <span>Type instead</span>
            </button>

            <button onClick={onPatterns} style={{
              marginTop: 14, padding: 16,
              background: 'oklch(22% 0.022 252 / 0.6)',
              border: `1px solid ${V.hair}`,
              borderRadius: V.rL, cursor: 'pointer',
              display: 'flex', flexDirection: 'column', gap: 12,
              textAlign: 'left', position: 'relative', overflow: 'hidden',
            }}>
              <div style={noiseStyle(0.05)} />
              <div style={{
                position: 'relative', zIndex: 1,
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              }}>
                <Eyebrow color={V.glow}>3 active traces</Eyebrow>
                <span style={{
                  fontFamily: V.mono, fontSize: 10, letterSpacing: '0.18em',
                  color: V.faint, textTransform: 'uppercase',
                }}>last 30d ↗</span>
              </div>
              <div style={{ position: 'relative', zIndex: 1 }}>
                <TraceBar days={30} hits={(window.VESTIGE_DATA.PATTERNS.find((p) => p.id === 'tuesday-meetings') || {}).traceHits || []} />
              </div>
              <div style={{
                position: 'relative', zIndex: 1,
                fontFamily: V.sans, fontSize: 14, fontWeight: 500, color: V.ink,
                lineHeight: 1.35,
              }}>
                Tuesday Meetings · The Email · "Tired"
              </div>
            </button>
          </>
        )}

        {/* RECORDING-only chrome — file / cancel */}
        {recording && (
          <div style={{
            display: 'flex', flexDirection: 'column', gap: 10,
            animation: 'vesIn .3s ease-out both',
          }}>
            <button onClick={fileEntry} style={{
              padding: '14px 18px', borderRadius: V.rPill,
              background: V.ink, color: V.deep,
              border: 'none', cursor: 'pointer',
              fontFamily: V.sans, fontSize: 15, fontWeight: 600,
              boxShadow: `0 0 32px ${V.glowDim}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
            }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M5 12.5l4.5 4.5L19 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
              File entry
            </button>
            <button onClick={cancelEntry} style={{
              padding: '10px 14px', background: 'transparent',
              border: 'none', cursor: 'pointer',
              fontFamily: V.mono, fontSize: 10, letterSpacing: '0.20em',
              color: V.faint, textTransform: 'uppercase',
            }}>Cancel · discard</button>
          </div>
        )}

        {/* Footer ledger — fades when recording */}
        <div style={{
          marginTop: recording ? 14 : 14, paddingTop: 12,
          borderTop: `1px solid ${V.hair2}`,
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          opacity: recording ? 0.45 : 1,
          transition: 'opacity 240ms ease',
          pointerEvents: recording ? 'none' : 'auto',
        }}>
          <Eyebrow>Last · May 7, 11:08p · 4m 02s</Eyebrow>
          <Eyebrow>{(window.VESTIGE_DATA.PATTERNS.filter((p) => p.status === 'active').length)} active patterns</Eyebrow>
        </div>
      </div>
    </>
  );
}

window.CaptureScreen = CaptureScreen;
// Recording is no longer a separate screen. Keep a thin alias so the
// dev nav's "02 Recording" button still works — it just opens Capture
// in autoRecord mode.
window.RecordingScreen = function RecordingScreenAlias(props) {
  return <CaptureScreen {...props} autoRecord />;
};

// ─── Typed Entry ─────────────────────────────────────────────────
function TypedEntryScreen({ persona = 'witness', onClose, onSave }) {
  const [text, setText] = useSC('');
  const [now, setNow] = useSC(() => new Date());
  useEC(() => {
    const t = setInterval(() => setNow(new Date()), 30 * 1000);
    return () => clearInterval(t);
  }, []);
  const stamp = now.toLocaleString('en-US', {
    weekday: 'short', month: 'short', day: 'numeric',
    hour: 'numeric', minute: '2-digit',
  });
  const wc = text.trim() ? text.trim().split(/\s+/).length : 0;
  const can = text.trim().length >= 3;

  return (
    <>
      <AppShellTop
        modelState="ready"
        right={<IconBtn onClick={onClose} ariaLabel="close">{Icons.close}</IconBtn>}
      />
      <div style={{
        flex: 1, display: 'flex', flexDirection: 'column',
        padding: '4px 22px 22px', minHeight: 0,
      }}>
        <Eyebrow>Typed entry · {stamp}</Eyebrow>
        <div style={{
          marginTop: 10, fontFamily: V.display, fontStyle: 'italic',
          fontWeight: 400, fontSize: 28, lineHeight: 1.15,
          letterSpacing: '-0.01em', color: V.ink,
        }}>What did you notice?</div>

        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          autoFocus
          placeholder="Just the observation. Behavior, not feeling. The mind file-clerks the rest."
          style={{
            marginTop: 14, flex: 1, minHeight: 200,
            background: 'oklch(22% 0.022 252 / 0.6)', color: V.ink,
            border: `1px solid ${V.hair}`, borderRadius: V.rL,
            padding: '14px 16px',
            fontFamily: V.sans, fontSize: 16, lineHeight: 1.55,
            resize: 'none', outline: 'none', textWrap: 'pretty',
            backdropFilter: 'blur(6px)',
          }}
          onFocus={(e) => { e.currentTarget.style.borderColor = V.glowSoft; }}
          onBlur={(e) => { e.currentTarget.style.borderColor = V.hair; }}
        />

        <div style={{
          marginTop: 10,
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        }}>
          <Eyebrow>{wc} {wc === 1 ? 'word' : 'words'}</Eyebrow>
          <span style={{
            fontFamily: V.mono, fontSize: 10, letterSpacing: '0.18em',
            color: V.faint, textTransform: 'uppercase',
          }}>local · not synced</span>
        </div>

        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 18 }}>
          <button
            onClick={can ? onSave : undefined}
            disabled={!can}
            aria-label="file entry"
            style={{
              position: 'relative',
              width: 96, height: 96, borderRadius: 48,
              background: can ? V.ink : 'transparent',
              border: can ? `1px solid ${V.glowSoft}` : `1.5px dashed ${V.hair}`,
              cursor: can ? 'pointer' : 'not-allowed',
              boxShadow: can ? `0 0 32px ${V.glowDim}` : 'none',
              display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
              gap: 4,
              transition: 'all .2s',
              color: can ? V.glow : V.faint,
            }}
            onMouseDown={(e) => { if (can) e.currentTarget.style.transform = 'scale(0.97)'; }}
            onMouseUp={(e) => { if (can) e.currentTarget.style.transform = 'scale(1)'; }}
          >
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M5 12.5l4.5 4.5L19 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
            <span style={{
              fontFamily: V.mono, fontSize: 9, letterSpacing: '0.20em',
              textTransform: 'uppercase', fontWeight: 600,
            }}>File</span>
          </button>
        </div>

        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 14 }}>
          <button onClick={onClose} style={{
            background: 'transparent', border: 'none', cursor: 'pointer',
            fontFamily: V.sans, fontSize: 13, fontWeight: 500, color: V.mist,
            padding: '6px 10px',
          }}>Discard</button>
        </div>
      </div>
    </>
  );
}

window.TypedEntryScreen = TypedEntryScreen;
