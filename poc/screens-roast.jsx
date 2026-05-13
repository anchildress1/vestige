// Vestige · The Roast (modal bottom sheet) + Error + Destructive + Wiped

const { useState: useSR } = React;

function RoastScreen({ persona = 'witness', onClose, onWipe }) {
  const personas = window.VESTIGE_DATA.PERSONAS;
  const meta = window.VESTIGE_DATA.ROAST_META;
  const p = personas.find((x) => x.id === persona) || personas[0];
  const lines = p.roast || [];

  const header = (
    <div style={{
      padding: '6px 22px 12px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <PersonaLabel name={p.name.toUpperCase()} color={V.glow} opacity={1} />
        <span style={{ color: V.faint, fontFamily: V.mono, fontSize: 11 }}>·</span>
        <Eyebrow>Roast</Eyebrow>
        <span style={{ color: V.faint, fontFamily: V.mono, fontSize: 11 }}>·</span>
        <Eyebrow>{meta.date}</Eyebrow>
      </div>
      <IconBtn onClick={onClose} ariaLabel="close">{Icons.close}</IconBtn>
    </div>
  );

  const footer = (
    <div style={{
      padding: '14px 22px 20px',
      display: 'flex', gap: 10,
      borderTop: `1px solid ${V.hair2}`,
    }}>
      <GhostBtn onClick={onClose} style={{ flex: 1 }}>Close</GhostBtn>
      <button onClick={onWipe} style={{
        flex: 1, minHeight: 44, padding: '10px 18px', borderRadius: V.rXS,
        background: 'transparent', color: V.glow,
        border: `1px solid ${V.glowRule}`, cursor: 'pointer',
        fontFamily: V.sans, fontSize: 13, fontWeight: 600,
      }}>Wipe and start over</button>
    </div>
  );

  return (
    <Sheet open onClose={onClose} header={header} footer={footer} accent={V.glowRule}>
      <div style={{
        paddingLeft: 16,
        borderLeft: `2px solid ${V.glowRule}`,
        display: 'flex', flexDirection: 'column', gap: 18,
      }}>
        {lines.map((line, i) => (
          <div key={i} style={{
            fontFamily: V.sans, fontSize: 19, lineHeight: 1.4,
            color: V.ink, fontWeight: 500, textWrap: 'pretty',
            letterSpacing: '-0.005em',
          }}>{line}</div>
        ))}
      </div>
      <div style={{
        marginTop: 22, paddingTop: 14,
        borderTop: `1px solid ${V.hair2}`,
      }}>
        <Eyebrow>{meta.sourceLine}</Eyebrow>
      </div>
    </Sheet>
  );
}

// ─── Error State ─────────────────────────────────────────────────
function ErrorScreen({ kind = 'mic', onClose, onRetry }) {
  const map = {
    mic: { eye: 'Mic', title: 'Mic permission required to record.', sub: 'Settings → Permissions.', action: 'Open settings' },
    network: { eye: 'Network', title: 'Network choked.', sub: 'Try again, or switch Wi-Fi.', action: 'Retry' },
    timeout: { eye: 'Model', title: 'Model timed out.', sub: 'Try a shorter chunk.', action: 'Retry' },
    save: { eye: 'Storage', title: 'Entry not saved.', sub: 'Local storage is full or write-locked.', action: 'Retry' },
  };
  const e = map[kind] || map.mic;
  return (
    <>
      <AppShellTop
        modelState={kind === 'network' || kind === 'timeout' ? 'stalled' : 'ready'}
        right={<IconBtn onClick={onClose} ariaLabel="close">{Icons.close}</IconBtn>}
      />
      <div style={{
        flex: 1, padding: '40px 24px 24px',
        display: 'flex', flexDirection: 'column', gap: 14,
      }}>
        <Eyebrow color={V.glow}>{e.eye}</Eyebrow>
        <H1 style={{ fontSize: 30 }}>{e.title}</H1>
        <P dim>{e.sub}</P>
        <div style={{ flex: 1 }} />
        <PrimaryBtn onClick={onRetry}>{e.action}</PrimaryBtn>
        <GhostBtn onClick={onClose} style={{ width: '100%' }}>Cancel</GhostBtn>
      </div>
    </>
  );
}

// ─── Destructive Confirmation ────────────────────────────────────
function DestructiveScreen({ onCancel, onConfirm }) {
  const [val, setVal] = useSR('');
  const ok = val.trim().toUpperCase() === 'DELETE';
  return (
    <>
      <AppShellTop
        modelState="ready"
        right={<IconBtn onClick={onCancel} ariaLabel="cancel">{Icons.close}</IconBtn>}
      />
      <div style={{
        flex: 1, padding: '32px 24px 24px',
        display: 'flex', flexDirection: 'column', gap: 14,
      }}>
        <Eyebrow color={V.glow}>Destructive</Eyebrow>
        <H1 style={{ fontSize: 32, lineHeight: 1.1 }}>This deletes everything.</H1>
        <P dim>All entries. All transcripts. All patterns. No backup. No undo.</P>
        <div style={{ marginTop: 14 }}>
          <Eyebrow>Type DELETE to confirm</Eyebrow>
          <input
            value={val}
            onChange={(e) => setVal(e.target.value)}
            placeholder="DELETE"
            style={{
              marginTop: 10, width: '100%', boxSizing: 'border-box',
              padding: '14px 16px', borderRadius: V.rXS,
              background: V.s1, border: `1px solid ${ok ? V.glowRule : V.hair}`,
              color: V.ink, fontFamily: V.mono, fontSize: 15, letterSpacing: '0.18em',
              outline: 'none', textTransform: 'uppercase',
            }}
          />
        </div>
        <div style={{ flex: 1 }} />
        <div style={{ display: 'flex', gap: 10 }}>
          <GhostBtn onClick={onCancel} style={{ flex: 1 }}>Cancel</GhostBtn>
          <PrimaryBtn onClick={onConfirm} disabled={!ok} danger style={{ flex: 1.4 }}>
            Wipe everything. No backup.
          </PrimaryBtn>
        </div>
      </div>
    </>
  );
}

// ─── Wiped beat — terminal confirmation after destructive flow ────
// The review flagged that DestructiveScreen's success path was vapor:
// the user hit "Wipe everything" and bounced straight back to Capture,
// the very screen the just-deleted records came from. This is the beat
// in between — proof-of-erasure, no scary chrome, just stillness.
function WipedScreen({ onContinue }) {
  return (
    <>
      <AppShellTop modelState="ready" right={null} interactive={false} />
      <div style={{
        flex: 1, padding: '24px 24px 24px',
        display: 'flex', flexDirection: 'column',
      }}>
        <div style={{
          flex: 1, display: 'flex', flexDirection: 'column',
          alignItems: 'center', justifyContent: 'center', gap: 22,
          textAlign: 'center',
        }}>
          <div style={{
            width: 88, height: 88, borderRadius: 44,
            border: `1px solid ${V.hair}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <div style={{
              width: 12, height: 12, borderRadius: '50%',
              background: V.mist,
            }} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxWidth: 280 }}>
            <H1 style={{ fontSize: 32, lineHeight: 1.1 }}>Wiped.</H1>
            <P dim>0 entries. 0 patterns. 0 transcripts.</P>
          </div>
          <div style={{
            display: 'grid', gridTemplateColumns: 'auto auto', gap: '8px 24px',
            fontFamily: V.mono, fontSize: 11, color: V.faint,
            letterSpacing: '0.04em', fontVariantNumeric: 'tabular-nums',
          }}>
            <span>entries</span><span style={{ textAlign: 'right' }}>0</span>
            <span>patterns</span><span style={{ textAlign: 'right' }}>0</span>
            <span>transcripts</span><span style={{ textAlign: 'right' }}>0</span>
            <span>storage</span><span style={{ textAlign: 'right' }}>0 KB</span>
          </div>
        </div>
        <PrimaryBtn onClick={onContinue}>Start over</PrimaryBtn>
      </div>
    </>
  );
}

window.RoastScreen = RoastScreen;
window.ErrorScreen = ErrorScreen;
window.DestructiveScreen = DestructiveScreen;
window.WipedScreen = WipedScreen;
