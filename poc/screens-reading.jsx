// Vestige · Reading — Gemma's structured extraction of one entry.
// Three readers (Literal, Inferential, Skeptical) converge on a Canonical.
// Reached by tapping a specific entry in history. This is the receipt:
// you can see how the model arrived at the value before the pattern.

const { useState: useSR } = React;

// ─── Sample reading — what Gemma extracted from this entry ───────
const SAMPLE_READING = {
  entry: "Crashed at 3pm after the meeting. Tired but wired.",
  stamp: 'May 7 · 3:08p · 0m 22s · typed',
  fields: [
    {
      key: 'state_descriptor',
      readers: [
        { who: 'Literal',     value: '"crashed"',                                       tone: 'plain' },
        { who: 'Inferential', value: '"crashed"',                                       tone: 'plain' },
        { who: 'Skeptical',   value: '"crashed" — flagged: contradicts "tired but wired"', tone: 'flag' },
      ],
      canonical: { value: 'crashed', confidence: 'high', flag: 'state-vs-arousal mismatch' },
    },
    {
      key: 'trigger',
      readers: [
        { who: 'Literal',     value: '"the meeting"', tone: 'plain' },
        { who: 'Inferential', value: '"the meeting"', tone: 'plain' },
        { who: 'Skeptical',   value: '"the meeting"', tone: 'plain' },
      ],
      canonical: { value: 'the meeting', confidence: 'high', flag: null },
    },
    {
      key: 'stated_commitment',
      readers: [
        { who: 'Literal',     value: 'none',                                             tone: 'absence' },
        { who: 'Inferential', value: 'none',                                             tone: 'absence' },
        { who: 'Skeptical',   value: 'none',                                             tone: 'absence' },
      ],
      canonical: { value: null, confidence: 'high', flag: 'absence confirmed' },
    },
    {
      key: 'last_sleep',
      readers: [
        { who: 'Literal',     value: 'not stated',                                                       tone: 'absence' },
        { who: 'Inferential', value: 'null — no evidence',                                               tone: 'absence' },
        { who: 'Skeptical',   value: 'flagged: "asked to infer state without sleep data"',               tone: 'flag' },
      ],
      canonical: { value: null, confidence: 'high', flag: null },
    },
  ],
  patternsTouched: [
    { id: 'tuesday-meetings', label: 'Tuesday Meetings',        hits: 4 },
    { id: 'tired',            label: '"Tired" — vague-word run', hits: 7 },
  ],
};

// ─── Confidence dots — visual ledger marker ──────────────────────
function ConfidenceDots({ level = 'high' }) {
  const map = { low: 1, medium: 2, high: 3 };
  const filled = map[level] || 0;
  return (
    <span style={{ display: 'inline-flex', gap: 3, alignItems: 'center' }}>
      {[0, 1, 2].map((i) => (
        <span key={i} style={{
          width: 5, height: 5, borderRadius: 3,
          background: i < filled ? V.glow : 'transparent',
          border: `1px solid ${i < filled ? V.glow : V.hair}`,
          boxShadow: i < filled ? `0 0 4px ${V.glowDim}` : 'none',
        }} />
      ))}
      <span style={{
        marginLeft: 6, fontFamily: V.mono, fontSize: 9, letterSpacing: '0.18em',
        color: V.faint, textTransform: 'uppercase',
      }}>{level}</span>
    </span>
  );
}

// ─── Reader row — one perspective on the field ──────────────────
function ReaderRow({ who, value, tone }) {
  // Visual treatment — Literal is solid, Inferential is half, Skeptical dashed.
  const isFlag = tone === 'flag';
  const isAbsence = tone === 'absence';
  const ruleStyle =
    who === 'Literal'     ? `2px solid ${V.hair}` :
    who === 'Inferential' ? `2px solid ${V.hair2}` :
                            `2px dashed ${V.hair}`;
  return (
    <div style={{
      paddingLeft: 14, borderLeft: ruleStyle,
      display: 'flex', flexDirection: 'column', gap: 4,
    }}>
      <div style={{
        fontFamily: V.mono, fontSize: 9, letterSpacing: '0.22em',
        color: isFlag ? V.glow : V.faint, textTransform: 'uppercase',
      }}>
        {who}{isFlag ? ' · flagged' : ''}
      </div>
      <div style={{
        fontFamily: V.sans, fontSize: 14, lineHeight: 1.5,
        color: isAbsence ? V.faint : V.mist,
        fontStyle: isAbsence ? 'italic' : 'normal',
        textWrap: 'pretty',
      }}>{value}</div>
    </div>
  );
}

// ─── Canonical row — the convergence ─────────────────────────────
function CanonicalRow({ value, confidence, flag }) {
  const displayValue = value === null
    ? <span style={{ fontStyle: 'italic', color: V.faint }}>null · absence</span>
    : <span>{value}</span>;
  return (
    <div style={{
      marginTop: 12,
      padding: '12px 14px',
      borderRadius: V.rM,
      background: 'oklch(20% 0.022 252 / 0.55)',
      border: `1px solid ${V.glowSoft}`,
      boxShadow: `0 0 22px ${V.glowDim}, inset 0 1px 0 oklch(95% 0.04 245 / 0.04)`,
      position: 'relative', overflow: 'hidden',
    }}>
      <div style={noiseStyle(0.05)} />
      <div style={{ position: 'relative', display: 'flex', alignItems: 'baseline', gap: 10 }}>
        <span style={{
          fontFamily: V.mono, fontSize: 10, letterSpacing: '0.22em',
          color: V.glow, textTransform: 'uppercase',
        }}>→ Canonical</span>
        <ConfidenceDots level={confidence} />
      </div>
      <div style={{
        position: 'relative',
        marginTop: 6, fontFamily: V.display, fontStyle: 'italic',
        fontWeight: 400, fontSize: 22, lineHeight: 1.2,
        letterSpacing: '-0.005em', color: V.ink,
      }}>{displayValue}</div>
      {flag && (
        <div style={{
          position: 'relative',
          marginTop: 8, paddingTop: 8,
          borderTop: `1px dashed ${V.hair}`,
          display: 'flex', alignItems: 'center', gap: 8,
        }}>
          <span style={{
            fontFamily: V.mono, fontSize: 9, letterSpacing: '0.18em',
            color: V.glow, textTransform: 'uppercase',
          }}>flag</span>
          <span style={{
            fontFamily: V.sans, fontSize: 12, color: V.mist, lineHeight: 1.4,
          }}>{flag}</span>
        </div>
      )}
    </div>
  );
}

// ─── Field card ──────────────────────────────────────────────────
function FieldCard({ field }) {
  return (
    <section style={{
      padding: '16px 16px 18px',
      borderRadius: V.rL,
      background: 'oklch(18% 0.020 252 / 0.55)',
      border: `1px solid ${V.hair2}`,
      position: 'relative', overflow: 'hidden',
    }}>
      <div style={noiseStyle(0.04)} />
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', gap: 14 }}>
        <header style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          gap: 10, paddingBottom: 8, borderBottom: `1px solid ${V.hair2}`,
        }}>
          <span style={{
            fontFamily: V.mono, fontSize: 12, letterSpacing: '0.16em',
            color: V.ink, textTransform: 'uppercase',
          }}>{field.key}</span>
          <span style={{
            fontFamily: V.mono, fontSize: 9, letterSpacing: '0.22em',
            color: V.faint, textTransform: 'uppercase',
          }}>3 readers</span>
        </header>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {field.readers.map((r) => <ReaderRow key={r.who} {...r} />)}
        </div>

        <CanonicalRow {...field.canonical} />
      </div>
    </section>
  );
}

// ─── Reading Screen ──────────────────────────────────────────────
function ReadingScreen({ onClose, onPattern }) {
  const r = SAMPLE_READING;
  const [howOpen, setHowOpen] = useSR(false);

  return (
    <>
      <AppShellTop
        modelState="ready"
        left={<IconBtn onClick={onClose} ariaLabel="back">{Icons.back}</IconBtn>}
        right={(
          <span style={{
            fontFamily: V.mono, fontSize: 10, letterSpacing: '0.22em',
            color: V.faint, textTransform: 'uppercase',
          }}>Reading</span>
        )}
      />

      <div className="ves-scroll" style={{
        flex: 1, overflowY: 'auto',
        padding: '4px 22px 26px',
        display: 'flex', flexDirection: 'column', gap: 18,
      }}>
        {/* Entry header */}
        <div>
          <Eyebrow>{r.stamp}</Eyebrow>
          <blockquote style={{
            margin: '12px 0 0',
            padding: '14px 16px 16px 18px',
            borderLeft: `2px solid ${V.glowSoft}`,
            background: 'oklch(20% 0.022 252 / 0.45)',
            borderRadius: `0 ${V.rM} ${V.rM} 0`,
            position: 'relative', overflow: 'hidden',
          }}>
            <div style={noiseStyle(0.04)} />
            <div style={{
              position: 'relative',
              fontFamily: V.display, fontStyle: 'italic',
              fontWeight: 400, fontSize: 24, lineHeight: 1.25,
              letterSpacing: '-0.005em', color: V.ink, textWrap: 'balance',
            }}>“{r.entry}”</div>
          </blockquote>
        </div>

        {/* What this view is — small explainer */}
        <div style={{
          padding: '12px 14px',
          borderRadius: V.rM,
          background: 'oklch(16% 0.018 252 / 0.55)',
          border: `1px solid ${V.hair2}`,
        }}>
          <button onClick={() => setHowOpen((v) => !v)} style={{
            background: 'transparent', border: 'none', padding: 0, cursor: 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            width: '100%', color: 'inherit', textAlign: 'left',
          }}>
            <div>
              <Eyebrow color={V.glow}>How this was read</Eyebrow>
              <div style={{
                marginTop: 6, fontFamily: V.sans, fontSize: 13, lineHeight: 1.5, color: V.mist,
              }}>
                Three readers run on-device. They diverge — then converge. You see the seams.
              </div>
            </div>
            <span style={{
              fontFamily: V.mono, fontSize: 10, color: V.faint,
              letterSpacing: '0.18em', textTransform: 'uppercase',
              transform: howOpen ? 'rotate(180deg)' : 'none',
              transition: 'transform .2s',
            }}>▾</span>
          </button>
          {howOpen && (
            <div style={{
              marginTop: 12, paddingTop: 12,
              borderTop: `1px dashed ${V.hair}`,
              display: 'grid', gridTemplateColumns: '1fr', gap: 10,
              fontFamily: V.sans, fontSize: 12, lineHeight: 1.5, color: V.mist,
            }}>
              <div><strong style={{ color: V.ink, fontWeight: 600 }}>Literal —</strong> only what the words say.</div>
              <div><strong style={{ color: V.ink, fontWeight: 600 }}>Inferential —</strong> what the words imply.</div>
              <div><strong style={{ color: V.ink, fontWeight: 600 }}>Skeptical —</strong> what the words don't quite support.</div>
              <div style={{ color: V.faint, marginTop: 4 }}>The Canonical is what gets filed against patterns. Disagreement is kept as a flag, not erased.</div>
            </div>
          )}
        </div>

        {/* Field cards */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Eyebrow>Extracted · {r.fields.length} fields</Eyebrow>
          {r.fields.map((f) => <FieldCard key={f.key} field={f} />)}
        </div>

        {/* Patterns this entry touched — link out */}
        <div>
          <Eyebrow>Filed against</Eyebrow>
          <div style={{
            marginTop: 10, display: 'flex', flexDirection: 'column', gap: 8,
          }}>
            {r.patternsTouched.map((p) => (
              <button key={p.id} onClick={() => onPattern && onPattern(p.id)} style={{
                padding: '12px 14px',
                borderRadius: V.rM,
                background: 'oklch(20% 0.022 252 / 0.5)',
                border: `1px solid ${V.hair2}`,
                cursor: 'pointer', textAlign: 'left',
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                gap: 12,
              }}>
                <div>
                  <div style={{
                    fontFamily: V.sans, fontSize: 14, fontWeight: 500, color: V.ink,
                  }}>{p.label}</div>
                  <div style={{
                    marginTop: 3, fontFamily: V.mono, fontSize: 10,
                    letterSpacing: '0.18em', color: V.faint, textTransform: 'uppercase',
                  }}>{p.hits} hits · last 30d</div>
                </div>
                <span style={{ color: V.glow }}>↗</span>
              </button>
            ))}
          </div>
        </div>

        {/* Footer ledger — provenance */}
        <div style={{
          paddingTop: 14, borderTop: `1px solid ${V.hair2}`,
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
          <Eyebrow>On-device · gemma 4 · 30s chunks</Eyebrow>
          <span style={{
            fontFamily: V.mono, fontSize: 9, letterSpacing: '0.22em',
            color: V.faint, textTransform: 'uppercase',
          }}>audio discarded</span>
        </div>
      </div>
    </>
  );
}

window.ReadingScreen = ReadingScreen;
