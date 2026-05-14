// Vestige · SCOREBOARD direction — onboarding screens.
// 8 ux-copy steps compressed to 5 phones. The "wiring" screen is the
// how-to: it explains local processing, mic, notifications, and typed
// fallback all on one board, with row-level actions.

const { useState: useOnbState, useEffect: useOnbEffect } = React;

// ═══ 01 · PERSONA PICK ════════════════════════════════════════════
function OnbPersona({ selected = 'WITNESS' }) {
  const personas = [
    { id: 'WITNESS', tag: 'DEFAULT · QUIET',  line: 'Observes. Names the pattern. Keeps quiet otherwise.', accent: E.lime },
    { id: 'HARDASS', tag: 'LESS PADDING',     line: 'Sharper. Less padding. More action.',                  accent: E.coral },
    { id: 'EDITOR',  tag: 'TIGHTENS VOCAB',   line: 'Cuts vague words until they confess.',                 accent: E.ink },
  ];
  return (
    <>
      {/* Custom top: step rule */}
      <div style={{
        padding: '12px 18px 10px',
        borderBottom: `1px solid ${E.hair}`,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
          <StatusDot color={E.lime} blink size={6} />
          <EyebrowE>SETUP · 01 OF 05</EyebrowE>
        </div>
        <EyebrowE>SKIP NONE</EyebrowE>
      </div>

      {/* Tick rule progress */}
      <div style={{ padding: '8px 18px 0' }}>
        <TickRule count={30} marks={[0,1,2,3,4,5]} />
      </div>

      {/* Hero */}
      <div style={{ padding: '16px 18px 4px' }}>
        <EyebrowE color={E.lime}>● VOICE · PICK ONE</EyebrowE>
        <div style={{
          marginTop: 8, fontFamily: ET.display, fontSize: 44, lineHeight: 0.88,
          color: E.ink, letterSpacing: '-0.005em', textTransform: 'uppercase',
        }}>
          PICK A<br />PERSONA<span style={{ color: E.lime }}>.</span>
        </div>
        <p style={{
          margin: '12px 0 0', fontFamily: ET.sans, fontSize: 13, lineHeight: 1.45,
          color: E.dim, maxWidth: 300,
        }}>
          Three voices. Same product. Pick the one that fits today.
          <span style={{ color: E.ink }}> You can switch.</span>
        </p>
      </div>

      {/* Persona cards — vertical box-score */}
      <div style={{
        flex: 1, padding: '16px 18px 0',
        display: 'flex', flexDirection: 'column', gap: 8, minHeight: 0,
      }}>
        {personas.map((p) => {
          const on = p.id === selected;
          return (
            <div key={p.id} style={{
              position: 'relative', overflow: 'hidden',
              border: `1px solid ${on ? p.accent : E.hair}`,
              background: on ? E.s1 : 'transparent',
              backgroundImage: on ? TAPE_BG : 'none',
            }}>
              {/* Left rule when selected */}
              {on && <div style={{
                position: 'absolute', left: 0, top: 0, bottom: 0, width: 4,
                background: p.accent,
              }} />}
              <div style={{
                padding: on ? '12px 14px 14px 18px' : '12px 14px',
                display: 'flex', alignItems: 'flex-start', gap: 12,
              }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{
                    display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 4,
                  }}>
                    <span style={{
                      fontFamily: ET.display, fontSize: 24, lineHeight: 0.9,
                      color: on ? E.ink : E.dim, letterSpacing: '0.02em',
                    }}>{p.id}</span>
                    <EyebrowE color={on ? p.accent : E.faint} style={{ fontSize: 8.5 }}>
                      {p.tag}
                    </EyebrowE>
                  </div>
                  <div style={{
                    fontFamily: ET.sans, fontSize: 12.5, lineHeight: 1.4,
                    color: on ? E.ink : E.faint,
                  }}>{p.line}</div>
                </div>
                {/* Selection mark */}
                <div style={{
                  width: 22, height: 22, flexShrink: 0,
                  border: `1px solid ${on ? p.accent : E.hair}`,
                  background: on ? p.accent : 'transparent',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontFamily: ET.display, fontSize: 16, color: E.deep, lineHeight: 1,
                }}>{on ? '✓' : ''}</div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Footer actions */}
      <div style={{
        padding: '14px 18px 16px', borderTop: `1px solid ${E.hair}`,
        background: E.deep,
      }}>
        <button style={{
          width: '100%', padding: '16px',
          background: E.ink, color: E.deep, border: 'none', cursor: 'pointer',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          fontFamily: ET.display, fontSize: 22, letterSpacing: '0.04em',
        }}>
          <span>CONTINUE</span>
          <span style={{ fontFamily: ET.mono, fontSize: 10, opacity: 0.65 }}>WITNESS →</span>
        </button>
        <div style={{
          marginTop: 8, textAlign: 'center',
          fontFamily: ET.mono, fontSize: 9, color: E.faint, fontWeight: 600,
          letterSpacing: '0.18em', textTransform: 'uppercase',
        }}>CHANGE LATER IN SETTINGS</div>
      </div>
    </>
  );
}

// ═══ 02 · WIRING (privacy + mic + notif + typed combined) ═════════
function OnbWiring() {
  const rows = [
    {
      id: 'LOCAL',
      label: 'LOCAL · GEMMA 4',
      desc: 'No cloud. No servers. No telemetry. Voice never leaves the device.',
      detail: '~3.7 GB model · downloads once',
      status: 'LOCKED · ALWAYS ON',
      statusColor: E.lime,
      action: null,
    },
    {
      id: 'MIC',
      label: 'MIC · INPUT',
      desc: 'Records dumps. Audio is read locally, then discarded. Transcription stays as text.',
      detail: 'Required for voice · optional otherwise',
      status: 'PENDING',
      statusColor: E.ink,
      action: { primary: 'ALLOW', secondary: 'TYPE INSTEAD' },
    },
    {
      id: 'NOTIFY',
      label: 'NOTIFY · STATUS',
      desc: 'One line, posted while the model reads an entry. Disappears when work is done.',
      detail: 'Single-status only · nothing else, ever',
      status: 'PENDING',
      statusColor: E.ink,
      action: { primary: 'ALLOW', secondary: 'SKIP' },
    },
    {
      id: 'TYPE',
      label: 'TYPE · FALLBACK',
      desc: 'Voice is the default. Typing works the same. Same patterns. Same persona.',
      detail: 'Available on every capture screen',
      status: 'READY',
      statusColor: E.lime,
      action: null,
    },
  ];

  return (
    <>
      {/* Top */}
      <div style={{
        padding: '12px 18px 10px',
        borderBottom: `1px solid ${E.hair}`,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
          <StatusDot color={E.lime} blink size={6} />
          <EyebrowE>SETUP · 02 OF 05</EyebrowE>
        </div>
        <EyebrowE color={E.lime}>● 2 / 4 LIVE</EyebrowE>
      </div>
      <div style={{ padding: '8px 18px 0' }}>
        <TickRule count={30} marks={[0,1,2,3,4,5,6,7,8,9,10,11]} />
      </div>

      {/* Hero */}
      <div style={{ padding: '14px 18px 6px' }}>
        <EyebrowE color={E.lime}>● PRE-FLIGHT</EyebrowE>
        <div style={{
          marginTop: 8, fontFamily: ET.display, fontSize: 40, lineHeight: 0.88,
          color: E.ink, letterSpacing: '-0.005em', textTransform: 'uppercase',
        }}>
          WIRING<br />THIS UP<span style={{ color: E.lime }}>.</span>
        </div>
        <p style={{
          margin: '10px 0 0', fontFamily: ET.sans, fontSize: 12.5, lineHeight: 1.45,
          color: E.dim,
        }}>
          Four switches. Two are already on. Flip the rest if you want voice.
        </p>
      </div>

      {/* Stat ribbon */}
      <div style={{ padding: '10px 18px 0' }}>
        <StatRibbon items={[
          { value: '2', label: 'LIVE',    color: E.lime },
          { value: '2', label: 'PENDING', color: E.ink },
          { value: '0', label: 'BLOCKED' },
          { value: '0', label: 'CLOUD',   color: E.coral },
        ]} />
      </div>

      {/* Rows — the "scoreboard" */}
      <div style={{
        flex: 1, padding: '12px 18px 0', overflowY: 'auto',
        display: 'flex', flexDirection: 'column', gap: 6,
      }}>
        {rows.map((r, i) => {
          const live = r.status === 'LOCKED · ALWAYS ON' || r.status === 'READY';
          return (
            <div key={r.id} style={{
              position: 'relative', overflow: 'hidden',
              border: `1px solid ${live ? E.hair : E.hair}`,
              background: live ? E.s1 : E.s1,
              backgroundImage: TAPE_BG,
            }}>
              {/* Left rule */}
              <div style={{
                position: 'absolute', left: 0, top: 0, bottom: 0, width: 3,
                background: live ? E.lime : 'transparent',
                opacity: live ? 1 : 0,
              }} />
              <div style={{ padding: live ? '10px 12px 10px 14px' : '10px 12px' }}>
                {/* Top row: number, label, status */}
                <div style={{
                  display: 'flex', alignItems: 'baseline',
                  justifyContent: 'space-between', gap: 8, marginBottom: 3,
                }}>
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, minWidth: 0 }}>
                    <span style={{
                      fontFamily: ET.display, fontSize: 13, color: E.faint,
                      letterSpacing: '0.02em',
                    }}>#{String(i + 1).padStart(2, '0')}</span>
                    <span style={{
                      fontFamily: ET.display, fontSize: 18, lineHeight: 0.95,
                      color: E.ink, letterSpacing: '0.01em',
                    }}>{r.label}</span>
                  </div>
                  <Pill color={r.statusColor} dot={live} blink={live}>
                    {live ? 'ON' : 'OFF'}
                  </Pill>
                </div>
                {/* Desc */}
                <div style={{
                  fontFamily: ET.sans, fontSize: 11.5, lineHeight: 1.4,
                  color: E.dim, marginTop: 2,
                }}>{r.desc}</div>
                <div style={{
                  marginTop: 4,
                  fontFamily: ET.mono, fontSize: 9, fontWeight: 600,
                  letterSpacing: '0.16em', textTransform: 'uppercase',
                  color: E.faint,
                }}>{r.detail}</div>

                {/* Inline action */}
                {r.action && (
                  <div style={{
                    marginTop: 8, paddingTop: 8,
                    borderTop: `1px dashed ${E.hair}`,
                    display: 'flex', gap: 6,
                  }}>
                    <button style={{
                      flex: 1, padding: '8px 10px',
                      background: E.ink, color: E.deep, border: 'none', cursor: 'pointer',
                      fontFamily: ET.display, fontSize: 14, letterSpacing: '0.04em',
                    }}>{r.action.primary}</button>
                    <button style={{
                      flex: 1, padding: '8px 10px',
                      background: 'transparent', color: E.dim,
                      border: `1px solid ${E.hair}`, cursor: 'pointer',
                      fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
                      letterSpacing: '0.16em', textTransform: 'uppercase',
                    }}>{r.action.secondary}</button>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Footer */}
      <div style={{
        padding: '12px 18px 16px', borderTop: `1px solid ${E.hair}`,
        background: E.deep,
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        <div style={{ flex: 1 }}>
          <EyebrowE>FINISH 2 MORE TO CONTINUE</EyebrowE>
        </div>
        <button style={{
          padding: '12px 18px',
          background: E.s2, color: E.faint, border: `1px solid ${E.hair}`,
          cursor: 'not-allowed',
          fontFamily: ET.display, fontSize: 18, letterSpacing: '0.04em',
        }}>NEXT →</button>
      </div>
    </>
  );
}

// ═══ 03 · WI-FI CHECK ═════════════════════════════════════════════
function OnbWifi() {
  return (
    <>
      <div style={{
        padding: '12px 18px 10px',
        borderBottom: `1px solid ${E.hair}`,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
          <StatusDot color={E.lime} blink size={6} />
          <EyebrowE>SETUP · 03 OF 05</EyebrowE>
        </div>
        <EyebrowE color={E.lime}>● WI-FI · GOOD</EyebrowE>
      </div>
      <div style={{ padding: '8px 18px 0' }}>
        <TickRule count={30} marks={Array.from({length:18}, (_,i)=>i)} />
      </div>

      {/* Hero */}
      <div style={{ padding: '16px 18px 6px' }}>
        <EyebrowE color={E.lime}>● READY TO PULL</EyebrowE>
        <div style={{
          marginTop: 8, fontFamily: ET.display, fontSize: 44, lineHeight: 0.86,
          color: E.ink, letterSpacing: '-0.005em', textTransform: 'uppercase',
        }}>
          WI-FI<br />CONNECTED<span style={{ color: E.lime }}>.</span>
        </div>
        <p style={{
          margin: '10px 0 0', fontFamily: ET.sans, fontSize: 13, lineHeight: 1.45,
          color: E.dim,
        }}>
          One file. Pulled once. Stays on the device after.
        </p>
      </div>

      {/* The "package" — what's about to download */}
      <div style={{ padding: '14px 18px 0' }}>
        <div style={{
          border: `1px solid ${E.hair}`, background: E.s1,
          backgroundImage: TAPE_BG, padding: '14px 14px 12px',
        }}>
          <div style={{
            display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
            marginBottom: 8,
          }}>
            <EyebrowE color={E.lime}>● PACKAGE</EyebrowE>
            <EyebrowE>VERIFIED</EyebrowE>
          </div>
          <div style={{
            fontFamily: ET.display, fontSize: 64, lineHeight: 0.85,
            color: E.ink, letterSpacing: '-0.01em',
            fontVariantNumeric: 'tabular-nums',
          }}>3.66<span style={{ fontSize: 24, color: E.dim, marginLeft: 6 }}>GB</span></div>
          <div style={{
            marginTop: 6, fontFamily: ET.mono, fontSize: 10, color: E.dim,
            letterSpacing: '0.10em',
          }}>GEMMA 4 E4B · INT4 · ON-DEVICE</div>
        </div>
      </div>

      {/* Receipt — what happens */}
      <div style={{ padding: '14px 18px 0', flex: 1 }}>
        <EyebrowE>SEQUENCE</EyebrowE>
        <div style={{
          marginTop: 8,
          border: `1px solid ${E.hair}`, background: E.s1,
        }}>
          {[
            ['01', 'PULL',    '~12 min on this network',   E.lime],
            ['02', 'VERIFY',  'SHA-256 · integrity check', E.ink],
            ['03', 'UNPACK',  'Prepare for inference',     E.ink],
            ['04', 'IDLE',    'You start dumping',         E.lime],
          ].map(([n, label, sub, c], i) => (
            <div key={n} style={{
              padding: '9px 12px',
              borderBottom: i < 3 ? `1px dashed ${E.hair}` : 'none',
              display: 'flex', alignItems: 'baseline', gap: 12,
            }}>
              <span style={{
                fontFamily: ET.display, fontSize: 16, color: E.faint,
                width: 22, flexShrink: 0,
              }}>{n}</span>
              <span style={{
                fontFamily: ET.display, fontSize: 16, color: c,
                letterSpacing: '0.02em', width: 70, flexShrink: 0,
              }}>{label}</span>
              <span style={{
                fontFamily: ET.mono, fontSize: 10.5, color: E.dim,
                flex: 1,
              }}>{sub}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Footer actions */}
      <div style={{
        padding: '12px 18px 16px', borderTop: `1px solid ${E.hair}`,
        background: E.deep,
        display: 'flex', flexDirection: 'column', gap: 8,
      }}>
        <button style={{
          width: '100%', padding: '16px',
          background: E.lime, color: E.deep, border: 'none', cursor: 'pointer',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          fontFamily: ET.display, fontSize: 22, letterSpacing: '0.04em',
        }}>
          <span>DOWNLOAD MODEL</span>
          <span style={{ fontFamily: ET.mono, fontSize: 10, opacity: 0.7 }}>3.66 GB →</span>
        </button>
        <button style={{
          padding: '8px', background: 'transparent', border: 'none',
          color: E.faint, cursor: 'pointer',
          fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
          letterSpacing: '0.20em', textTransform: 'uppercase',
        }}>I'LL COME BACK</button>
      </div>
    </>
  );
}

// ═══ 04 · MODEL DOWNLOAD (live) ═══════════════════════════════════
function OnbDownload() {
  const [pct, setPct] = useOnbState(38);
  useOnbEffect(() => {
    const id = setInterval(() => setPct((x) => (x >= 99 ? 38 : x + 1)), 600);
    return () => clearInterval(id);
  }, []);
  const downloaded = (3.66 * pct / 100).toFixed(2);
  const remaining = (3.66 - downloaded).toFixed(2);
  const etaMin = Math.max(1, Math.round((100 - pct) * 0.12));

  return (
    <>
      <div style={{
        padding: '12px 18px 10px',
        borderBottom: `1px solid ${E.hair}`,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
          <StatusDot color={E.lime} blink size={6} />
          <EyebrowE>SETUP · 04 OF 05</EyebrowE>
        </div>
        <EyebrowE color={E.lime}>● PULLING · LIVE</EyebrowE>
      </div>
      <div style={{ padding: '8px 18px 0' }}>
        <TickRule count={30} marks={Array.from({length:24}, (_,i)=>i)} />
      </div>

      {/* Massive number */}
      <div style={{
        padding: '20px 18px 0', display: 'flex',
        justifyContent: 'space-between', alignItems: 'flex-start',
      }}>
        <div>
          <EyebrowE color={E.lime}>● DOWNLOADING</EyebrowE>
          <div style={{
            fontFamily: ET.display, fontSize: 124, lineHeight: 0.78,
            color: E.ink, letterSpacing: '-0.02em', marginTop: 6,
            fontVariantNumeric: 'tabular-nums',
          }}>{pct}<span style={{ color: E.lime, fontSize: 64, marginLeft: 4 }}>%</span></div>
        </div>
        <div style={{ textAlign: 'right', marginTop: 6 }}>
          <EyebrowE>ETA</EyebrowE>
          <div style={{
            fontFamily: ET.display, fontSize: 44, lineHeight: 0.85, color: E.ink,
            marginTop: 4, fontVariantNumeric: 'tabular-nums',
          }}>{etaMin}</div>
          <EyebrowE style={{ marginTop: 4 }}>MIN LEFT</EyebrowE>
        </div>
      </div>

      {/* Progress bar with chunks */}
      <div style={{ padding: '16px 18px 0' }}>
        <div style={{
          height: 10, background: E.s2, position: 'relative', overflow: 'hidden',
          border: `1px solid ${E.hair}`,
        }}>
          <div style={{
            position: 'absolute', left: 0, top: 0, bottom: 0,
            width: `${pct}%`, background: E.lime,
            boxShadow: `0 0 12px ${E.limeSoft}`,
          }} />
          {[10,20,30,40,50,60,70,80,90].map((s) => (
            <div key={s} style={{
              position: 'absolute', top: 0, bottom: 0, width: 1,
              left: `${s}%`, background: E.deep,
            }} />
          ))}
        </div>
        <div style={{
          marginTop: 4, display: 'flex', justifyContent: 'space-between',
          fontFamily: ET.mono, fontSize: 9, color: E.faint, fontWeight: 600,
          letterSpacing: '0.16em',
        }}>
          <span>{downloaded} GB</span>
          <span>{remaining} GB LEFT</span>
        </div>
      </div>

      {/* Stat ribbon — live */}
      <div style={{ padding: '14px 18px 0' }}>
        <StatRibbon items={[
          { value: `${(4.2 + (pct % 7) * 0.3).toFixed(1)}`, label: 'MB/S', color: E.lime },
          { value: '1',  label: 'STREAM' },
          { value: '0',  label: 'STALLS' },
          { value: '✓',  label: 'WI-FI',   color: E.lime },
        ]} />
      </div>

      {/* Reassurance */}
      <div style={{ padding: '16px 18px 0', flex: 1 }}>
        <div style={{
          padding: '14px',
          border: `1px solid ${E.hair}`, background: E.s1,
          backgroundImage: TAPE_BG,
        }}>
          <EyebrowE color={E.lime}>● WHILE YOU WAIT</EyebrowE>
          <div style={{
            marginTop: 8, fontFamily: ET.display, fontSize: 22, lineHeight: 0.95,
            color: E.ink, letterSpacing: '0.005em', textTransform: 'uppercase',
          }}>QUIET FOR<br />A MINUTE<span style={{ color: E.lime }}>.</span></div>
          <div style={{
            marginTop: 8, fontFamily: ET.sans, fontSize: 12.5, lineHeight: 1.45,
            color: E.dim,
          }}>
            <span style={{ color: E.ink }}>This takes a while.</span> Background the
            app, lock the phone, take a walk. We hold the line.
          </div>
        </div>
      </div>

      {/* Footer */}
      <div style={{
        padding: '12px 18px 16px', borderTop: `1px solid ${E.hair}`,
        background: E.deep,
        display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8,
      }}>
        <button style={{
          padding: '12px', background: 'transparent',
          border: `1px solid ${E.hair}`, color: E.ink, cursor: 'pointer',
          fontFamily: ET.mono, fontSize: 10, fontWeight: 700,
          letterSpacing: '0.18em', textTransform: 'uppercase',
        }}>PAUSE</button>
        <button style={{
          padding: '12px', background: 'transparent',
          border: `1px solid ${E.hair}`, color: E.faint, cursor: 'pointer',
          fontFamily: ET.mono, fontSize: 10, fontWeight: 700,
          letterSpacing: '0.18em', textTransform: 'uppercase',
        }}>BACKGROUND IT</button>
      </div>
    </>
  );
}

// ═══ 05 · READY (first entry primer) ══════════════════════════════
function OnbReady() {
  return (
    <>
      <div style={{
        padding: '12px 18px 10px',
        borderBottom: `1px solid ${E.hair}`,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
          <StatusDot color={E.lime} blink size={6} />
          <EyebrowE color={E.lime}>● ALL SET · 05 OF 05</EyebrowE>
        </div>
        <EyebrowE>DAY 00</EyebrowE>
      </div>
      <div style={{ padding: '8px 18px 0' }}>
        <TickRule count={30} marks={Array.from({length:30}, (_,i)=>i)} />
      </div>

      {/* Hero */}
      <div style={{ padding: '16px 18px 6px' }}>
        <EyebrowE color={E.lime}>● MODEL LOADED · WITNESS ARMED</EyebrowE>
        <div style={{
          marginTop: 8, fontFamily: ET.display, fontSize: 64, lineHeight: 0.82,
          color: E.ink, letterSpacing: '-0.01em', textTransform: 'uppercase',
        }}>
          READY<span style={{ color: E.lime }}>.</span>
        </div>
        <p style={{
          margin: '10px 0 0', fontFamily: ET.sans, fontSize: 13, lineHeight: 1.45,
          color: E.dim, maxWidth: 280,
        }}>
          Everything's local. The model's loaded. Talk into the mic when you've got
          something to dump, or type. <span style={{ color: E.ink }}>Witness is selected.</span>
        </p>
      </div>

      {/* How-to box scores — three small primers */}
      <div style={{ padding: '14px 18px 0' }}>
        <EyebrowE>HOW THIS WORKS</EyebrowE>
        <div style={{
          marginTop: 8,
          display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 0,
          border: `1px solid ${E.hair}`,
          background: E.s1, backgroundImage: TAPE_BG,
        }}>
          {[
            { n: '30s', l: 'CHUNKS', c: E.coral, sub: 'Talk. It chunks.' },
            { n: '10', l: 'TO PATTERN', c: E.lime, sub: 'Entries before signal.' },
            { n: '0', l: 'CLOUD', c: E.ink, sub: 'Nothing leaves.' },
          ].map((it, i) => (
            <div key={i} style={{
              padding: '10px 10px',
              borderRight: i < 2 ? `1px solid ${E.hair}` : 'none',
            }}>
              <div style={{
                fontFamily: ET.display, fontSize: 32, lineHeight: 0.85,
                color: it.c, letterSpacing: '-0.005em',
                fontVariantNumeric: 'tabular-nums',
              }}>{it.n}</div>
              <div style={{
                marginTop: 4, fontFamily: ET.mono, fontSize: 8, fontWeight: 700,
                letterSpacing: '0.18em', textTransform: 'uppercase', color: E.dim,
              }}>{it.l}</div>
              <div style={{
                marginTop: 6, fontFamily: ET.sans, fontSize: 11, lineHeight: 1.3,
                color: E.faint,
              }}>{it.sub}</div>
            </div>
          ))}
        </div>
      </div>

      {/* The first prompt — preview */}
      <div style={{ padding: '14px 18px 0', flex: 1 }}>
        <EyebrowE>FIRST PROMPT · WAITING</EyebrowE>
        <div style={{
          marginTop: 8, padding: '14px 14px',
          border: `1px dashed ${E.limeSoft}`,
          background: 'transparent',
          position: 'relative', overflow: 'hidden',
        }}>
          <div style={{
            position: 'absolute', inset: 0,
            backgroundImage: HALFTONE_BG, backgroundSize: '5px 5px', opacity: 0.15,
          }} />
          <div style={{ position: 'relative' }}>
            <EyebrowE color={E.lime}>● PROMPT 01</EyebrowE>
            <div style={{
              marginTop: 6, fontFamily: ET.display, fontSize: 26, lineHeight: 0.95,
              color: E.ink, letterSpacing: '0.005em', textTransform: 'uppercase',
            }}>WHAT JUST<br /><span style={{ color: E.lime }}>HAPPENED?</span></div>
            <div style={{
              marginTop: 8, fontFamily: ET.mono, fontSize: 10, color: E.faint,
              fontWeight: 600, letterSpacing: '0.16em', textTransform: 'uppercase',
            }}>TAP REC · TALK 30s · DONE</div>
          </div>
        </div>
      </div>

      {/* Footer — single big primary */}
      <div style={{
        padding: '12px 18px 16px', borderTop: `1px solid ${E.hair}`,
        background: E.deep,
      }}>
        <button style={{
          width: '100%', padding: '18px',
          background: E.lime, color: E.deep, border: 'none', cursor: 'pointer',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          fontFamily: ET.display, fontSize: 24, letterSpacing: '0.04em',
        }}>
          <span>OPEN VESTIGE</span>
          <span style={{ fontFamily: ET.mono, fontSize: 10, opacity: 0.65 }}>→</span>
        </button>
        <div style={{
          marginTop: 8, textAlign: 'center',
          fontFamily: ET.mono, fontSize: 9, color: E.faint, fontWeight: 600,
          letterSpacing: '0.18em', textTransform: 'uppercase',
        }}>NO TUTORIAL · YOU FIGURE IT OUT</div>
      </div>
    </>
  );
}

Object.assign(window, {
  OnbPersona, OnbWiring, OnbWifi, OnbDownload, OnbReady,
});
