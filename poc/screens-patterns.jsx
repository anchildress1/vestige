// Vestige · Patterns list + Pattern detail
// Patterns are *traces* the model keeps catching. Each card carries a
// 30-day intensity bar showing how often the trace returns.
// traceHits and lifecycle status come from data.jsx — no local maps.

const { useState: useSP } = React;

function PatternsScreen({ persona = 'witness', onClose, onOpen, onRoast, onUpdateStatus }) {
  const patterns = window.VESTIGE_DATA.PATTERNS;
  const active   = patterns.filter((p) => p.status === 'active');
  const snoozed  = patterns.filter((p) => p.status === 'snoozed');
  const resolved = patterns.filter((p) => p.status === 'resolved');

  return (
    <>
      <AppShellTop
        modelState="ready"
        right={<IconBtn onClick={onClose} ariaLabel="close">{Icons.back}</IconBtn>}
      />
      <div className="ves-scroll" style={{ flex: 1, overflowY: 'auto', padding: '4px 22px 28px' }}>
        <Eyebrow>Vestiges · 31 entries · 30 days</Eyebrow>
        <div style={{
          marginTop: 10, fontFamily: V.display, fontStyle: 'italic',
          fontWeight: 400, fontSize: 34, lineHeight: 1.05,
          letterSpacing: '-0.015em', color: V.ink,
        }}>What keeps returning.</div>

        <button onClick={onRoast} style={{
          marginTop: 14, padding: '8px 16px', borderRadius: V.rPill,
          background: V.glowDim, border: `1px solid ${V.glowSoft}`,
          cursor: 'pointer',
          color: V.glow, fontFamily: V.mono, fontSize: 10,
          letterSpacing: '0.20em', textTransform: 'uppercase', fontWeight: 600,
          display: 'inline-flex', alignItems: 'center', gap: 8,
        }}>
          <span style={{
            width: 6, height: 6, borderRadius: '50%', background: V.glow,
            boxShadow: `0 0 8px ${V.glow}`,
          }} />
          Roast me
        </button>

        <div style={{ marginTop: 22, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {active.map((p) => (
            <PatternCard key={p.id} p={p} onClick={() => onOpen(p.id)} />
          ))}
        </div>

        {snoozed.length > 0 && (
          <>
            <div style={{ marginTop: 24, marginBottom: 10 }}>
              <Eyebrow>Snoozed · still drifting</Eyebrow>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {snoozed.map((p) => (
                <PatternCard key={p.id} p={p} onClick={() => onOpen(p.id)} />
              ))}
            </div>
          </>
        )}

        {resolved.length > 0 && (
          <>
            <div style={{ marginTop: 24, marginBottom: 10 }}>
              <Eyebrow>Resolved · faded</Eyebrow>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {resolved.map((p) => (
                <PatternCard key={p.id} p={p} onClick={() => onOpen(p.id)} />
              ))}
            </div>
          </>
        )}
      </div>
    </>
  );
}

function PatternCard({ p, onClick }) {
  const isActive = p.status === 'active';
  const isResolved = p.status === 'resolved';
  const hits = p.traceHits || [];
  return (
    <button onClick={onClick} style={{
      textAlign: 'left', padding: '14px 16px',
      background: 'oklch(22% 0.022 252 / 0.7)',
      border: `1px solid ${isActive ? 'oklch(86% 0.105 245 / 0.25)' : V.hair}`,
      borderRadius: V.rL, cursor: 'pointer',
      display: 'flex', flexDirection: 'column', gap: 10,
      opacity: isResolved ? 0.55 : (isActive ? 1 : 0.78),
      position: 'relative', overflow: 'hidden',
      backdropFilter: 'blur(6px)',
    }}>
      <div style={noiseStyle(0.05)} />
      {isActive && (
        <div aria-hidden style={{
          position: 'absolute', right: -40, top: -40,
          width: 120, height: 120, borderRadius: '50%',
          background: `radial-gradient(closest-side, ${V.glowDim}, transparent 70%)`,
          filter: 'blur(20px)', pointerEvents: 'none',
        }} />
      )}

      <div style={{ position: 'relative', zIndex: 1, display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
        <div>
          <Eyebrow color={V.faint}>{p.category}</Eyebrow>
          <div style={{
            marginTop: 4, fontFamily: V.sans, fontSize: 17, fontWeight: 600,
            color: V.ink, letterSpacing: '-0.005em',
          }}>{p.title}</div>
        </div>
        {isActive && (
          <span style={{
            fontFamily: V.mono, fontSize: 9, letterSpacing: '0.20em',
            color: V.glow, textTransform: 'uppercase', fontWeight: 600,
            padding: '4px 8px', borderRadius: V.rPill,
            background: V.glowDim, border: `1px solid ${V.glowSoft}`,
            whiteSpace: 'nowrap',
          }}>Active</span>
        )}
      </div>

      <div style={{
        position: 'relative', zIndex: 1,
        fontFamily: V.sans, fontSize: 14, lineHeight: 1.45, color: V.mist,
        textWrap: 'pretty',
      }}>{p.observation}</div>

      <div style={{ position: 'relative', zIndex: 1, marginTop: 4 }}>
        <TraceBar days={30} hits={hits} accent={isActive ? V.glow : V.vapor} />
      </div>

      <div style={{
        position: 'relative', zIndex: 1,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        paddingTop: 8, borderTop: `1px solid ${V.hair2}`,
      }}>
        <Eyebrow>{p.count}</Eyebrow>
        <Eyebrow>Last seen {p.lastSeen}</Eyebrow>
      </div>
    </button>
  );
}

// ─── Pattern Detail ──────────────────────────────────────────────
function PatternDetailScreen({ patternId, onClose, onOpenReading, onUpdateStatus }) {
  const p = window.VESTIGE_DATA.PATTERNS.find((x) => x.id === patternId) || window.VESTIGE_DATA.PATTERNS[0];
  const hits = p.traceHits || [];
  const setStatus = (next) => {
    if (onUpdateStatus) onUpdateStatus(p.id, next);
    onClose();
  };

  return (
    <>
      <AppShellTop
        modelState="ready"
        left={<IconBtn onClick={onClose} ariaLabel="back">{Icons.back}</IconBtn>}
        right={null}
      />
      <div className="ves-scroll" style={{ flex: 1, overflowY: 'auto', padding: '4px 22px 28px' }}>
        <Eyebrow>{p.category}</Eyebrow>
        <div style={{
          marginTop: 8, fontFamily: V.display, fontStyle: 'italic',
          fontWeight: 400, fontSize: 36, lineHeight: 1.0,
          letterSpacing: '-0.018em', color: V.ink,
        }}>{p.title}</div>

        {/* Trace intensity bar */}
        <div style={{
          marginTop: 22, padding: 16,
          background: 'oklch(22% 0.022 252 / 0.6)',
          border: `1px solid ${V.hair}`, borderRadius: V.rL,
          position: 'relative', overflow: 'hidden',
        }}>
          <div style={noiseStyle(0.05)} />
          <div style={{ position: 'relative', zIndex: 1, display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
            <Eyebrow color={V.glow}>Intensity · 30 days</Eyebrow>
            <Eyebrow>{p.count}</Eyebrow>
          </div>
          <div style={{ position: 'relative', zIndex: 1 }}>
            <TraceBar days={30} hits={hits} height={28} />
          </div>
        </div>

        {/* Observation */}
        <div style={{
          marginTop: 18, paddingLeft: 14,
          borderLeft: `2px solid ${V.glowSoft}`,
        }}>
          <P style={{ fontWeight: 500 }}>{p.observation}</P>
          <div style={{ marginTop: 8 }}>
            <Eyebrow>Last seen {p.lastSeen}</Eyebrow>
          </div>
        </div>

        {/* Sources */}
        <div style={{ marginTop: 22 }}>
          <Eyebrow>Seen in</Eyebrow>
          <P dim style={{ marginTop: 4, fontSize: 12 }}>Read the full entry from history.</P>
          <div style={{
            marginTop: 10, background: 'oklch(22% 0.022 252 / 0.6)',
            border: `1px solid ${V.hair}`, borderRadius: V.rL,
            position: 'relative', overflow: 'hidden',
          }}>
            <div style={noiseStyle(0.04)} />
            {p.sources.map((s, i) => (
              <div key={i} style={{
                position: 'relative', zIndex: 1, width: '100%',
                padding: '12px 14px',
                borderBottom: i < p.sources.length - 1 ? `1px solid ${V.hair2}` : 'none',
                display: 'flex', gap: 14, alignItems: 'flex-start',
              }}>
                <div style={{
                  fontFamily: V.mono, fontSize: 11, color: V.faint,
                  minWidth: 48, paddingTop: 2, letterSpacing: '0.05em',
                }}>{s.date}</div>
                <div style={{
                  flex: 1, fontFamily: V.sans, fontSize: 14, lineHeight: 1.45, color: V.ink,
                }}>{s.snippet}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Vocabulary */}
        <div style={{ marginTop: 22 }}>
          <Eyebrow>Words you used</Eyebrow>
          <div style={{ marginTop: 10, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {p.vocab.map((w, i) => (
              <span key={i} style={{
                padding: '6px 12px', borderRadius: V.rPill,
                background: 'oklch(28% 0.024 252 / 0.7)', color: V.ink,
                fontFamily: V.mono, fontSize: 12,
                border: `1px solid ${V.hair}`,
              }}>{w}</span>
            ))}
          </div>
        </div>

        {/* Actions — wired to lifecycle */}
        <div style={{ marginTop: 26, display: 'flex', gap: 8 }}>
          <GhostBtn onClick={() => setStatus('snoozed')} style={{ flex: 1 }} disabled={p.status === 'snoozed'}>Snooze</GhostBtn>
          <GhostBtn onClick={() => setStatus('resolved')} style={{ flex: 1 }} disabled={p.status === 'resolved'}>Resolved</GhostBtn>
          <GhostBtn onClick={() => setStatus('active')} subtle style={{ flex: 'none', padding: '10px 14px' }} disabled={p.status === 'active'}>Reactivate</GhostBtn>
        </div>
      </div>
    </>
  );
}

window.PatternsScreen = PatternsScreen;
window.PatternDetailScreen = PatternDetailScreen;
