// Vestige · SCOREBOARD direction — five screen variants.
// Each renders inside PhoneE. State is static for the comp.

const { useState: useEsc, useEffect: useEsk } = React;

// ═══ 1. CAPTURE · IDLE ════════════════════════════════════════════
function EnergyCapture() {
  return (
    <>
      <AppTop persona="WITNESS" />

      {/* Date strip — "Thursday, week 19" energy */}
      <div style={{
        padding: '12px 18px 10px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
        borderBottom: `1px solid ${E.hair}`,
      }}>
        <div>
          <EyebrowE color={E.lime}>NOW · THU MAY 8</EyebrowE>
          <div style={{
            fontFamily: ET.display, fontSize: 32, lineHeight: 0.9,
            color: E.ink, letterSpacing: '0.01em', marginTop: 4,
          }}>09:41 <span style={{ color: E.dim }}>· DAY 134</span></div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <EyebrowE>STREAK</EyebrowE>
          <div style={{
            fontFamily: ET.display, fontSize: 32, color: E.lime,
            lineHeight: 0.9, marginTop: 4, letterSpacing: '0.01em',
          }}>12<span style={{ fontSize: 14, color: E.dim, marginLeft: 4 }}>d</span></div>
        </div>
      </div>

      {/* Today's tape — top stat ribbon */}
      <div style={{ padding: '12px 18px 0' }}>
        <StatRibbon items={[
          { value: '31', label: 'KEPT' },
          { value: '3',  label: 'ACTIVE', color: E.lime },
          { value: '47', label: 'HITS/MO' },
          { value: '0',  label: 'CLOUD', color: E.coral },
        ]} />
      </div>

      {/* Hero question */}
      <div style={{ padding: '20px 18px 8px' }}>
        <EyebrowE>PROMPT · 03 OF 04</EyebrowE>
        <div style={{
          marginTop: 8, fontFamily: ET.display, fontSize: 38, lineHeight: 0.92,
          color: E.ink, letterSpacing: '-0.005em', textTransform: 'uppercase',
        }}>
          WHAT JUST<br />
          <span style={{ color: E.lime }}>HAPPENED?</span>
        </div>
      </div>

      {/* ON AIR button — hero */}
      <div style={{
        flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center',
        justifyContent: 'center', padding: '4px 18px',
      }}>
        <button style={{
          width: 168, height: 168, borderRadius: '50%',
          background: E.deep,
          border: `2px solid ${E.coral}`,
          boxShadow: `0 0 0 6px ${E.coralDim}, 0 0 50px ${E.coralDim}, inset 0 0 30px oklch(20% 0.020 40 / 0.6)`,
          color: E.ink, cursor: 'pointer', padding: 0,
          position: 'relative', overflow: 'hidden',
        }}>
          <div style={{
            position: 'absolute', inset: 0,
            backgroundImage: HALFTONE_BG, backgroundSize: '6px 6px',
            opacity: 0.25,
          }} />
          <div style={{
            position: 'relative', display: 'flex', flexDirection: 'column',
            alignItems: 'center', gap: 6,
          }}>
            <div style={{
              width: 18, height: 18, borderRadius: '50%', background: E.coral,
              animation: 'sbPulse 1.8s ease-in-out infinite',
              boxShadow: `0 0 12px ${E.coral}`,
            }} />
            <div style={{
              fontFamily: ET.display, fontSize: 38, lineHeight: 0.9,
              letterSpacing: '0.04em', color: E.ink,
            }}>REC</div>
            <div style={{
              fontFamily: ET.mono, fontSize: 8, fontWeight: 700,
              letterSpacing: '0.30em', color: E.dim,
            }}>TAP · TALK · 30s</div>
          </div>
        </button>

        {/* Or type */}
        <button style={{
          marginTop: 18, padding: '8px 14px',
          background: 'transparent', border: `1px solid ${E.hair}`,
          color: E.dim, cursor: 'pointer',
          fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
          letterSpacing: '0.20em', textTransform: 'uppercase',
        }}>OR TYPE →</button>
      </div>

      {/* Pattern ticker — scrolling */}
      <div style={{
        margin: '0 18px 12px',
        border: `1px solid ${E.hair}`,
        background: E.s1,
        overflow: 'hidden', position: 'relative',
      }}>
        <div style={{
          padding: '8px 12px',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          borderBottom: `1px solid ${E.hair}`, background: E.s2,
        }}>
          <EyebrowE color={E.lime}>● LIVE PATTERNS</EyebrowE>
          <EyebrowE>TAP →</EyebrowE>
        </div>
        <div style={{
          padding: '10px 12px',
          display: 'flex', alignItems: 'center', gap: 10,
          whiteSpace: 'nowrap',
        }}>
          <span style={{ fontFamily: ET.display, fontSize: 22, color: E.lime, lineHeight: 1 }}>3</span>
          <span style={{ fontFamily: ET.sans, fontSize: 13, color: E.ink, fontWeight: 600, flex: 1 }}>
            TUE MEETINGS · THE EMAIL · "TIRED"×23
          </span>
          <Delta value={4} />
        </div>
        <div style={{ padding: '0 12px 8px' }}>
          <TraceBarE days={30} hits={[3,10,17,24,26,28]} height={14} accent={E.lime} peak={false} />
        </div>
      </div>
    </>
  );
}

// ═══ 2. CAPTURE · LIVE ════════════════════════════════════════════
function EnergyCaptureLive() {
  const [t, setT] = useEsc(14);
  useEsk(() => { const id = setInterval(() => setT((x) => (x + 1) % 30), 1000); return () => clearInterval(id); }, []);
  const mm = String(Math.floor(t / 60)).padStart(2, '0');
  const ss = String(t % 60).padStart(2, '0');
  const remaining = 30 - t;

  // Live audio bars
  const bars = Array.from({ length: 42 }, (_, i) => {
    const phase = (i + t * 3) * 0.3;
    return 0.25 + (Math.sin(phase) * 0.35 + Math.cos(phase * 1.7) * 0.25 + 0.5) / 2;
  });

  return (
    <>
      <AppTop persona="WITNESS" recording onTimer={
        <div style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          padding: '4px 10px', background: E.coral,
        }}>
          <span style={{
            fontFamily: ET.mono, fontSize: 11, fontWeight: 700,
            fontVariantNumeric: 'tabular-nums', color: E.deep,
          }}>{mm}:{ss}</span>
        </div>
      } />

      {/* Massive timer */}
      <div style={{
        padding: '18px 18px 0', display: 'flex', justifyContent: 'space-between',
        alignItems: 'flex-start',
      }}>
        <div>
          <EyebrowE color={E.coral}>● RECORDING · CHUNK 1/1</EyebrowE>
          <div style={{
            fontFamily: ET.display, fontSize: 124, lineHeight: 0.78,
            color: E.ink, letterSpacing: '-0.02em', marginTop: 6,
            fontVariantNumeric: 'tabular-nums',
          }}>{mm}:{ss}</div>
        </div>
        <div style={{ textAlign: 'right', marginTop: 6 }}>
          <EyebrowE>REMAIN</EyebrowE>
          <div style={{
            fontFamily: ET.display, fontSize: 44, lineHeight: 0.85, color: E.coral,
            marginTop: 4, fontVariantNumeric: 'tabular-nums',
          }}>{remaining}</div>
          <EyebrowE style={{ marginTop: 4 }}>SECONDS</EyebrowE>
        </div>
      </div>

      {/* Chunk progress bar */}
      <div style={{ padding: '14px 18px 0' }}>
        <div style={{
          height: 6, background: E.s2, position: 'relative', overflow: 'hidden',
          border: `1px solid ${E.hair}`,
        }}>
          <div style={{
            position: 'absolute', left: 0, top: 0, bottom: 0,
            width: `${(t / 30) * 100}%`, background: E.coral,
            boxShadow: `0 0 12px ${E.coral}`,
          }} />
          {/* Tick marks at 5s intervals */}
          {[5, 10, 15, 20, 25].map((s) => (
            <div key={s} style={{
              position: 'absolute', top: 0, bottom: 0, width: 1,
              left: `${(s / 30) * 100}%`, background: E.deep,
            }} />
          ))}
        </div>
        <div style={{
          marginTop: 4, display: 'flex', justifyContent: 'space-between',
          fontFamily: ET.mono, fontSize: 8, color: E.faint, fontWeight: 600,
          letterSpacing: '0.20em',
        }}>
          <span>0s</span><span>10s</span><span>20s</span><span>30s ▲</span>
        </div>
      </div>

      {/* Big audio bars */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '0 18px' }}>
        <EyebrowE color={E.coral} style={{ marginBottom: 10 }}>● LEVEL · LIVE</EyebrowE>
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          gap: 2, height: 80,
        }}>
          {bars.map((h, i) => (
            <div key={i} style={{
              flex: 1, height: `${h * 100}%`, minHeight: 4,
              background: E.coral,
              opacity: 0.3 + h * 0.7,
              boxShadow: h > 0.7 ? `0 0 8px ${E.coral}` : 'none',
              transition: 'height 80ms linear',
            }} />
          ))}
        </div>
        <div style={{
          marginTop: 14, padding: '10px 12px',
          background: E.s1, border: `1px solid ${E.hair}`,
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
          <EyebrowE>WORD COUNT · EST</EyebrowE>
          <div style={{ fontFamily: ET.display, fontSize: 26, color: E.ink, lineHeight: 0.9 }}>
            {Math.round(t * 2.3)}
          </div>
        </div>
      </div>

      {/* Actions */}
      <div style={{ padding: '12px 18px 18px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        <button style={{
          padding: '16px', background: E.ink, color: E.deep,
          border: 'none', cursor: 'pointer',
          fontFamily: ET.display, fontSize: 22, letterSpacing: '0.04em',
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
        }}>
          <span style={{ width: 14, height: 14, background: E.coral }} />
          STOP · FILE IT
        </button>
        <button style={{
          padding: '8px', background: 'transparent', border: 'none',
          color: E.faint, cursor: 'pointer',
          fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
          letterSpacing: '0.20em', textTransform: 'uppercase',
        }}>DISCARD · NO SAVE</button>
      </div>
    </>
  );
}

// ═══ 3. PATTERNS · BOX SCORES ═════════════════════════════════════
function EnergyPatterns() {
  const data = window.VESTIGE_DATA.PATTERNS;
  const active = data.filter((p) => p.status === 'active');
  return (
    <>
      <AppTop persona="WITNESS" />

      {/* Header bar */}
      <div style={{
        padding: '14px 18px 12px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end',
        borderBottom: `1px solid ${E.hair}`,
      }}>
        <div>
          <EyebrowE color={E.lime}>● TRACKING · LAST 30D</EyebrowE>
          <div style={{
            fontFamily: ET.display, fontSize: 40, lineHeight: 0.85,
            color: E.ink, marginTop: 4, letterSpacing: '0.005em',
          }}>PATTERNS</div>
        </div>
        <button style={{
          padding: '8px 12px', background: E.coral, color: E.deep,
          border: 'none', cursor: 'pointer',
          fontFamily: ET.display, fontSize: 16, letterSpacing: '0.04em',
        }}>ROAST ME</button>
      </div>

      {/* Week ribbon */}
      <div style={{ padding: '12px 18px 0' }}>
        <StatRibbon items={[
          { value: '+12', label: 'HITS THIS WK', color: E.lime },
          { value: '3',   label: 'ACTIVE' },
          { value: '1',   label: 'SNOOZED' },
          { value: '1',   label: 'RESOLVED', color: E.teal },
        ]} />
      </div>

      {/* Section header */}
      <div style={{
        padding: '16px 18px 8px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
      }}>
        <EyebrowE color={E.lime}>▼ ACTIVE — STILL HITTING</EyebrowE>
        <EyebrowE>3</EyebrowE>
      </div>

      {/* Cards */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 18px 18px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {active.slice(0, 3).map((p, idx) => (
          <div key={p.id} style={{
            border: `1px solid ${E.hair}`,
            background: E.s1,
            position: 'relative', overflow: 'hidden',
          }}>
            {/* Rank stripe */}
            <div style={{
              position: 'absolute', left: 0, top: 0, bottom: 0, width: 4,
              background: E.lime,
            }} />
            <div style={{ padding: '12px 14px 14px 18px' }}>
              {/* Top row */}
              <div style={{
                display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
                gap: 8, marginBottom: 8,
              }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{
                    display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 4,
                  }}>
                    <span style={{
                      fontFamily: ET.display, fontSize: 13, color: E.faint,
                      letterSpacing: '0.02em',
                    }}>#{String(idx + 1).padStart(2, '0')}</span>
                    <EyebrowE color={E.dim}>{p.category}</EyebrowE>
                  </div>
                  <div style={{
                    fontFamily: ET.display, fontSize: 22, lineHeight: 0.95,
                    color: E.ink, letterSpacing: '0.01em',
                    textTransform: 'uppercase',
                  }}>{p.title}</div>
                </div>
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div style={{
                    fontFamily: ET.display, fontSize: 38, lineHeight: 0.85, color: E.lime,
                    fontVariantNumeric: 'tabular-nums',
                  }}>{p.count.split(' ')[0]}</div>
                  <EyebrowE style={{ marginTop: 2, fontSize: 8 }}>
                    {p.count.includes('of') ? `OF ${p.count.split(' ')[2]}` : 'HITS'}
                  </EyebrowE>
                </div>
              </div>

              {/* Trace bar */}
              <TraceBarE days={30} hits={p.traceHits} height={22} accent={E.lime} />

              {/* Bottom row */}
              <div style={{
                marginTop: 10, paddingTop: 10,
                borderTop: `1px dashed ${E.hair}`,
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <Delta value={[4, 7, 3][idx] || 2} label="THIS WK" />
                </div>
                <EyebrowE>LAST · {p.lastSeen.toUpperCase()}</EyebrowE>
              </div>
            </div>
          </div>
        ))}

        {/* Resolved section preview */}
        <div style={{ marginTop: 4 }}>
          <div style={{
            padding: '4px 0 8px',
            display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
          }}>
            <EyebrowE color={E.teal}>▼ RESOLVED — FADED</EyebrowE>
            <EyebrowE>1</EyebrowE>
          </div>
          <div style={{
            border: `1px solid ${E.hair}`, background: 'transparent',
            padding: '10px 14px',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            opacity: 0.6,
          }}>
            <div>
              <EyebrowE color={E.teal}>SAID-NOT-DONE</EyebrowE>
              <div style={{
                fontFamily: ET.display, fontSize: 16, color: E.ink, marginTop: 2,
                textTransform: 'uppercase',
              }}>CONVERSATION WITH HER</div>
            </div>
            <Pill color={E.teal}>0 in 14d</Pill>
          </div>
        </div>
      </div>
    </>
  );
}

// ═══ 4. PATTERN DETAIL ════════════════════════════════════════════
function EnergyPatternDetail() {
  const p = window.VESTIGE_DATA.PATTERNS[0]; // tuesday meetings
  return (
    <>
      {/* Custom top with back */}
      <div style={{
        padding: '10px 18px 8px',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        borderBottom: `1px solid ${E.hair}`,
      }}>
        <button style={{
          background: 'transparent', border: `1px solid ${E.hair}`,
          color: E.ink, padding: '5px 9px', cursor: 'pointer',
          fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
          letterSpacing: '0.20em', textTransform: 'uppercase',
        }}>← PATTERNS</button>
        <EyebrowE>SHEET · 01 OF 03</EyebrowE>
      </div>

      {/* Hero */}
      <div style={{ padding: '14px 18px 8px' }}>
        <EyebrowE color={E.lime}>● ACTIVE · AFTERMATH</EyebrowE>
        <div style={{
          fontFamily: ET.display, fontSize: 44, lineHeight: 0.88,
          color: E.ink, marginTop: 6, letterSpacing: '0.005em',
          textTransform: 'uppercase',
        }}>TUESDAY<br />MEETINGS<span style={{ color: E.lime }}>.</span></div>
        <p style={{
          margin: '10px 0 0', fontFamily: ET.sans, fontSize: 13,
          lineHeight: 1.45, color: E.dim,
        }}>State before: <span style={{ color: E.ink, fontWeight: 600 }}>cruising</span>.
        State after: <span style={{ color: E.coral, fontWeight: 600 }}>crashed</span>. Every time.</p>
      </div>

      {/* Stat slab */}
      <div style={{ padding: '12px 18px 0' }}>
        <StatRibbon items={[
          { value: '4', label: 'OF 12 ENT', color: E.lime },
          { value: '100%', label: 'ON TUES' },
          { value: '6w',   label: 'STREAK' },
          { value: '~85m', label: 'TO CRASH', color: E.coral },
        ]} />
      </div>

      {/* Intensity hero — bigger trace */}
      <div style={{ padding: '16px 18px 0' }}>
        <div style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
          marginBottom: 8,
        }}>
          <EyebrowE>INTENSITY · 30D</EyebrowE>
          <EyebrowE color={E.lime}>● 6 HITS</EyebrowE>
        </div>
        <TraceBarE days={30} hits={p.traceHits} height={48} accent={E.lime} />
        <div style={{
          marginTop: 4, display: 'flex', justifyContent: 'space-between',
          fontFamily: ET.mono, fontSize: 8, color: E.faint, fontWeight: 600,
          letterSpacing: '0.18em',
        }}>
          <span>APR 09</span><span>APR 23</span><span>MAY 08 ▲</span>
        </div>
      </div>

      {/* Sources receipt */}
      <div style={{ padding: '16px 18px 0', flex: 1, overflowY: 'auto' }}>
        <div style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
          marginBottom: 8,
        }}>
          <EyebrowE color={E.lime}>▼ SEEN IN — 4 ENT</EyebrowE>
          <EyebrowE>OLDEST →</EyebrowE>
        </div>
        <div style={{
          background: E.s1, border: `1px solid ${E.hair}`,
          backgroundImage: TAPE_BG,
        }}>
          {p.sources.map((s, i) => (
            <div key={i} style={{
              padding: '10px 12px',
              borderBottom: i < p.sources.length - 1 ? `1px dashed ${E.hair}` : 'none',
              display: 'flex', gap: 12, alignItems: 'flex-start',
            }}>
              <span style={{
                fontFamily: ET.display, fontSize: 18, color: E.lime, lineHeight: 1,
                width: 50, flexShrink: 0,
              }}>{s.date.split(' ')[1]}</span>
              <span style={{
                fontFamily: ET.sans, fontSize: 12, lineHeight: 1.4, color: E.ink, flex: 1,
              }}>{s.snippet}</span>
            </div>
          ))}
        </div>

        {/* Vocab */}
        <div style={{ marginTop: 14, marginBottom: 8 }}>
          <EyebrowE>WORDS YOU USED</EyebrowE>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {p.vocab.map((v) => (
            <span key={v} style={{
              padding: '4px 8px', border: `1px solid ${E.hair}`,
              background: E.s2,
              fontFamily: ET.mono, fontSize: 11, fontWeight: 600, color: E.ink,
            }}>{v}</span>
          ))}
        </div>
      </div>

      {/* Sticky actions */}
      <div style={{
        padding: '10px 18px 14px', borderTop: `1px solid ${E.hair}`,
        display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 6,
        background: E.deep,
      }}>
        {['DISMISS', 'SNOOZE 7D', 'RESOLVED'].map((label, i) => (
          <button key={label} style={{
            padding: '10px 4px',
            background: i === 2 ? E.teal : 'transparent',
            color: i === 2 ? E.deep : E.ink,
            border: `1px solid ${i === 2 ? E.teal : E.hair}`,
            cursor: 'pointer',
            fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
            letterSpacing: '0.16em', textTransform: 'uppercase',
          }}>{label}</button>
        ))}
      </div>
    </>
  );
}

// ═══ 5. ROAST · SCORECARD ═════════════════════════════════════════
function EnergyRoast() {
  const persona = window.VESTIGE_DATA.PERSONAS[1]; // hardass for the heat
  return (
    <>
      <AppTop persona="HARDASS" />

      {/* Banner header */}
      <div style={{
        margin: '14px 18px 0',
        padding: '14px 14px',
        background: E.coral, color: E.deep,
        position: 'relative', overflow: 'hidden',
      }}>
        <div style={{
          position: 'absolute', inset: 0,
          backgroundImage: HALFTONE_BG, backgroundSize: '5px 5px', opacity: 0.25,
        }} />
        <div style={{ position: 'relative' }}>
          <div style={{
            fontFamily: ET.mono, fontSize: 9, fontWeight: 700,
            letterSpacing: '0.24em', opacity: 0.75,
          }}>HARDASS · ROAST · MAY 8</div>
          <div style={{
            fontFamily: ET.display, fontSize: 56, lineHeight: 0.82,
            color: E.deep, letterSpacing: '0.005em', marginTop: 4,
          }}>THE<br />SCORECARD<span style={{ opacity: 0.4 }}>.</span></div>
        </div>
      </div>

      {/* Stat slab */}
      <div style={{ padding: '12px 18px 0' }}>
        <StatRibbon items={[
          { value: '31', label: 'ENTRIES PULLED' },
          { value: '4',  label: 'CUTS', color: E.coral },
          { value: '30', label: 'DAYS' },
        ]} />
      </div>

      {/* Roast lines as plays */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '14px 18px 0' }}>
        {persona.roast.map((line, i) => (
          <div key={i} style={{
            paddingBottom: 14, marginBottom: 14,
            borderBottom: i < persona.roast.length - 1 ? `1px solid ${E.hair}` : 'none',
            display: 'flex', gap: 12, alignItems: 'flex-start',
          }}>
            <div style={{
              fontFamily: ET.display, fontSize: 30, color: E.coral, lineHeight: 0.85,
              flexShrink: 0, width: 40,
            }}>{String(i + 1).padStart(2, '0')}</div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{
                fontFamily: ET.sans, fontSize: 17, fontWeight: 700,
                lineHeight: 1.25, color: E.ink, letterSpacing: '-0.005em',
              }}>{line}</div>
              <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Pill color={E.coral}>RECEIPT</Pill>
                <span style={{
                  fontFamily: ET.mono, fontSize: 9, color: E.faint, fontWeight: 600,
                  letterSpacing: '0.16em', textTransform: 'uppercase',
                }}>
                  {['4 ENT · APR 9—MAY 7', '7 ENT · LAST APR 24', '23 USES · 3 SENSES', '3 ENT · UNDEFEATED'][i]}
                </span>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Footer */}
      <div style={{
        padding: '12px 18px 16px', borderTop: `1px solid ${E.hair}`,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <EyebrowE>EPHEMERAL · NOT SAVED</EyebrowE>
        <button style={{
          padding: '8px 14px', background: E.ink, color: E.deep,
          border: 'none', cursor: 'pointer',
          fontFamily: ET.display, fontSize: 18, letterSpacing: '0.04em',
        }}>CLOSE</button>
      </div>
    </>
  );
}

Object.assign(window, {
  EnergyCapture, EnergyCaptureLive, EnergyPatterns, EnergyPatternDetail, EnergyRoast,
});
