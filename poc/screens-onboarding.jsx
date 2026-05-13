// Vestige · Onboarding (7 sequential steps), Local Model Status, Persona Selector

const { useState: useSO, useEffect: useEO } = React;

// ─── Onboarding ───────────────────────────────────────────────────
function OnboardingScreen({ onDone }) {
  const [step, setStep] = useSO(0);
  const [persona, setPersona] = useSO('witness');
  const [progress, setProgress] = useSO(0);
  const next = () => setStep((s) => Math.min(6, s + 1));
  const prev = () => setStep((s) => Math.max(0, s - 1));

  // Simulate download progress on step 5 (download model)
  useEO(() => {
    if (step !== 5) return;
    setProgress(0);
    const id = setInterval(() => {
      setProgress((p) => {
        const np = p + 0.018;
        if (np >= 1) { clearInterval(id); return 1; }
        return np;
      });
    }, 180);
    return () => clearInterval(id);
  }, [step]);

  const onboardingPersonas = window.VESTIGE_DATA.PERSONAS;
  const onboardingShellLeft = (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8,
      fontFamily: V.mono, fontSize: 10, letterSpacing: '0.18em',
      color: V.faint, textTransform: 'uppercase',
    }}>Vestige</div>
  );

  const Steps = [
    // 0 — choose persona
    () => (
      <Step eyebrow="01 of 07" title="Pick a voice." subtitle="Default is Witness. Change it later in settings.">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {onboardingPersonas.map((p) => (
            <PersonaCard key={p.id} p={p} active={persona === p.id} onClick={() => setPersona(p.id)} />
          ))}
        </div>
      </Step>
    ),
    // 1 — local processing
    () => (
      <Step eyebrow="02 of 07" title="Everything runs on your phone." subtitle="Your voice never leaves the device. No account. No cloud.">
        <DiagramLocal />
      </Step>
    ),
    // 2 — mic permission
    () => (
      <Step eyebrow="03 of 07" title="Microphone." subtitle="Required to record. Audio is transcribed locally, then discarded.">
        <div style={{
          padding: 18, background: V.s1, border: `1px solid ${V.hair}`,
          borderRadius: 6, display: 'flex', alignItems: 'center', gap: 14,
        }}>
          <div style={{
            width: 44, height: 44, borderRadius: 22, background: V.s2,
            display: 'flex', alignItems: 'center', justifyContent: 'center', color: V.ink,
          }}>{Icons.mic}</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontFamily: V.sans, fontSize: 14, fontWeight: 500, color: V.ink }}>Allow microphone</div>
            <div style={{ fontFamily: V.sans, fontSize: 12, color: V.mist, marginTop: 2 }}>Used only while recording.</div>
          </div>
        </div>
      </Step>
    ),
    // 3 — typed fallback
    () => (
      <Step eyebrow="04 of 07" title={"Don’t want to talk."} subtitle="Typed entries work the same way. The button stays one tap from the home screen.">
        <div style={{
          padding: 18, background: V.s1, border: `1px solid ${V.hair}`,
          borderRadius: 6, display: 'flex', alignItems: 'center', gap: 14,
        }}>
          <div style={{
            width: 44, height: 44, borderRadius: 22, background: V.s2,
            display: 'flex', alignItems: 'center', justifyContent: 'center', color: V.ink,
          }}>{Icons.type}</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontFamily: V.sans, fontSize: 14, fontWeight: 500, color: V.ink }}>Type</div>
            <div style={{ fontFamily: V.sans, fontSize: 12, color: V.mist, marginTop: 2 }}>Same field. No separate mode.</div>
          </div>
        </div>
      </Step>
    ),
    // 4 — wifi check
    () => (
      <Step eyebrow="05 of 07" title="Wi-Fi." subtitle="Model download is 2.1 GB. Wi-Fi only. Once.">
        <div style={{
          padding: 18, background: V.s1, border: `1px solid ${V.hair}`,
          borderRadius: 6, display: 'flex', alignItems: 'center', gap: 14,
        }}>
          <div style={{ width: 44, height: 44, borderRadius: 22, background: V.s2, display: 'flex', alignItems: 'center', justifyContent: 'center', color: V.ink }}>{Icons.wifi}</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontFamily: V.sans, fontSize: 14, fontWeight: 500, color: V.ink }}>Network: “Guest 5G”</div>
            <div style={{ fontFamily: V.sans, fontSize: 12, color: V.mist, marginTop: 2 }}>Connected.</div>
          </div>
        </div>
      </Step>
    ),
    // 5 — download model
    () => (
      <Step eyebrow="06 of 07" title="Downloading model." subtitle="Quiet for a minute.">
        <ModelDownload progress={progress} />
      </Step>
    ),
    // 6 — first entry
    () => (
      <Step eyebrow="07 of 07" title="Record." subtitle="One short entry. Doesn\u2019t have to be coherent.">
        <div style={{
          padding: '32px 18px', background: V.s1, border: `1px solid ${V.hair}`,
          borderRadius: 6, textAlign: 'center',
        }}>
          <div style={{
            width: 80, height: 80, borderRadius: 40, margin: '0 auto',
            border: `1.5px solid ${V.ghost}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <div style={{ width: 24, height: 24, borderRadius: 12, background: V.ink, opacity: 0.85 }} />
          </div>
          <Eyebrow><span style={{ display: 'block', marginTop: 14 }}>Tap to begin.</span></Eyebrow>
        </div>
      </Step>
    ),
  ];

  const CurrStep = Steps[step];
  const allowNext = step === 5 ? progress >= 1 : true;

  return (
    <>
      <AppShellTop
        modelState={step >= 5 && progress >= 1 ? 'ready' : (step === 5 ? 'downloading' : 'off')}
        left={onboardingShellLeft}
        right={null}
        interactive={false}
      />
      <div style={{
        flex: 1, display: 'flex', flexDirection: 'column',
        padding: '8px 24px 24px',
      }}>
        {/* Step pips */}
        <div style={{ display: 'flex', gap: 4, paddingBottom: 18 }}>
          {[0,1,2,3,4,5,6].map((i) => (
            <div key={i} style={{
              flex: 1, height: 2, borderRadius: 1,
              background: i <= step ? V.ink : V.hair,
              opacity: i <= step ? 0.65 : 1,
              transition: 'background .3s',
            }} />
          ))}
        </div>

        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <CurrStep />
        </div>

        <div style={{ display: 'flex', gap: 10, paddingTop: 14 }}>
          {step > 0 && (
            <GhostBtn onClick={prev} style={{ flex: 'none', minWidth: 88 }}>Back</GhostBtn>
          )}
          {step < 6 ? (
            <PrimaryBtn onClick={next} disabled={!allowNext} style={{ flex: 1 }}>
              {step === 5 ? (progress >= 1 ? 'Continue' : `${Math.round(progress * 100)}%`) : 'Continue'}
            </PrimaryBtn>
          ) : (
            <PrimaryBtn onClick={onDone} style={{ flex: 1 }}>Start</PrimaryBtn>
          )}
        </div>
      </div>
    </>
  );
}

function Step({ eyebrow, title, subtitle, children }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18, flex: 1 }}>
      <Eyebrow>{eyebrow}</Eyebrow>
      <H1>{title}</H1>
      {subtitle && <P dim>{subtitle}</P>}
      <div style={{ marginTop: 8 }}>{children}</div>
    </div>
  );
}

function PersonaCard({ p, active, onClick }) {
  const turns = p.transcript || [];
  const youLine = (turns[0] || {}).text || '';
  const cutLine = (turns[1] || {}).text || '';
  return (
    <button onClick={onClick} style={{
      textAlign: 'left', padding: 0,
      background: active ? V.s2 : V.s1,
      border: `1px solid ${active ? V.vapor : V.hair}`,
      borderLeft: `3px solid ${active ? V.glowRule : V.hair2}`,
      borderRadius: V.rXS, cursor: 'pointer',
      display: 'flex', flexDirection: 'column',
      transition: 'border-color .15s, background .15s',
      overflow: 'hidden',
    }}>
      {/* Header: name + tagline + ACTIVE tag, all on one row */}
      <div style={{
        padding: '14px 18px 10px',
        display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12,
      }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flex: 1, minWidth: 0 }}>
          <div style={{
            fontFamily: V.sans, fontSize: 16, fontWeight: 600,
            color: active ? V.ink : V.mist, letterSpacing: '-0.005em',
          }}>{p.name}</div>
          <div style={{
            fontFamily: V.sans, fontSize: 13, color: V.mist, fontWeight: 400,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>{p.tagline}</div>
        </div>
        {active && (
          <div style={{
            fontFamily: V.mono, fontSize: 9, letterSpacing: '0.18em',
            color: V.vapor, textTransform: 'uppercase', fontWeight: 600,
            whiteSpace: 'nowrap',
          }}>Active</div>
        )}
      </div>

      {/* Example block — no inner left rule; the card's own left edge is the rule */}
      <div style={{
        padding: '0 18px 14px',
        display: 'flex', flexDirection: 'column', gap: 8,
      }}>
        <div style={{
          fontFamily: V.sans, fontSize: 13, lineHeight: 1.5,
          color: V.faint, fontStyle: 'italic', textWrap: 'pretty',
        }}>
          <span style={{
            fontFamily: V.mono, fontSize: 9, letterSpacing: '0.22em',
            fontStyle: 'normal', color: V.faint, marginRight: 8,
            textTransform: 'uppercase', fontWeight: 500,
          }}>You</span>
          {youLine}
        </div>
        <div style={{
          fontFamily: V.sans, fontSize: 14.5, lineHeight: 1.5,
          color: active ? V.ink : V.mist,
          fontWeight: 500, textWrap: 'pretty',
        }}>{cutLine}</div>
      </div>
    </button>
  );
}

function DiagramLocal() {
  return (
    <div style={{
      padding: '28px 18px', background: V.s1, border: `1px solid ${V.hair}`,
      borderRadius: 6, display: 'flex', flexDirection: 'column', gap: 14, alignItems: 'center',
    }}>
      <div style={{
        width: 96, height: 96, borderRadius: 48,
        background: V.s2, border: `1px solid ${V.vapor}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        position: 'relative',
        boxShadow: `0 0 32px ${V.vaporDim}`,
      }}>
        <div style={{ fontFamily: V.mono, fontSize: 11, letterSpacing: '0.18em', color: V.vapor }}>GEMMA</div>
        <div style={{
          position: 'absolute', inset: -10, borderRadius: '50%',
          border: `1px solid ${V.vaporDim}`,
        }} />
        <div style={{
          position: 'absolute', inset: -22, borderRadius: '50%',
          border: `1px solid ${V.hair2}`,
        }} />
      </div>
      <div style={{
        fontFamily: V.mono, fontSize: 10, letterSpacing: '0.16em',
        color: V.vapor, textTransform: 'uppercase', opacity: 0.85,
      }}>on-device · no network</div>
    </div>
  );
}

function ModelDownload({ progress = 0 }) {
  const mb = Math.round(progress * 2147);
  return (
    <div style={{
      padding: 18, background: V.s1, border: `1px solid ${V.hair}`, borderRadius: 6,
      display: 'flex', flexDirection: 'column', gap: 14,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <div style={{ fontFamily: V.sans, fontSize: 14, fontWeight: 500, color: V.ink }}>gemma-3n-2b-it</div>
        <div style={{ fontFamily: V.mono, fontSize: 11, color: V.mist, fontVariantNumeric: 'tabular-nums' }}>
          {mb} / 2147 MB
        </div>
      </div>
      <div style={{ height: 4, background: V.s2, borderRadius: 2, overflow: 'hidden' }}>
        <div style={{
          width: `${progress * 100}%`, height: '100%',
          background: V.vapor, transition: 'width .2s linear',
        }} />
      </div>
      <Eyebrow>{progress >= 1 ? 'Done. Ready.' : `${Math.round(progress * 100)}% · Wi-Fi only · ETA ${Math.max(1, Math.round((1 - progress) * 90))}s`}</Eyebrow>
    </div>
  );
}

// ─── Local Model Status ──────────────────────────────────────────
function ModelStatusScreen({ state = 'ready', onClose, onRetry }) {
  const map = {
    ready:       { eyebrow: 'Status', title: 'Model ready.',       sub: 'Running locally. No network.', tone: V.ink },
    downloading: { eyebrow: 'Downloading', title: 'Downloading model.', sub: 'Wi-Fi only. Quiet for a minute.', tone: V.vapor },
    stalled:     { eyebrow: 'Error',  title: 'Download stalled.',  sub: 'Network choked. Try again.', tone: V.glow },
    updating:    { eyebrow: 'Update', title: 'Updating model.',    sub: 'New weights. Same shape.', tone: V.vapor },
  };
  const s = map[state] || map.ready;
  const fakeProgress = state === 'downloading' ? 0.41 : (state === 'stalled' ? 0.62 : (state === 'updating' ? 0.18 : 1));

  return (
    <>
      <AppShellTop
        modelState={state}
        right={<IconBtn onClick={onClose} ariaLabel="close">{Icons.close}</IconBtn>}
      />
      <div style={{ padding: '4px 24px 24px', flex: 1, display: 'flex', flexDirection: 'column', gap: 18 }}>
        <Eyebrow color={s.tone}>{s.eyebrow}</Eyebrow>
        <H1 style={{ fontSize: 30 }}>{s.title}</H1>
        <P dim>{s.sub}</P>

        <div style={{
          marginTop: 6, padding: 18, background: V.s1, border: `1px solid ${V.hair}`,
          borderRadius: 6, display: 'flex', flexDirection: 'column', gap: 12,
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <div style={{ fontFamily: V.sans, fontSize: 14, fontWeight: 500, color: V.ink }}>gemma-3n-2b-it</div>
            <div style={{ fontFamily: V.mono, fontSize: 11, color: V.mist, fontVariantNumeric: 'tabular-nums' }}>
              {state === 'ready' ? '2147 MB · v1.2' : `${Math.round(fakeProgress * 2147)} / 2147 MB`}
            </div>
          </div>
          <div style={{ height: 4, background: V.s2, borderRadius: 2, overflow: 'hidden' }}>
            <div style={{
              width: `${fakeProgress * 100}%`, height: '100%',
              background: state === 'stalled' ? V.glow : (state === 'ready' ? V.pulse : V.vapor),
            }} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <Eyebrow color={V.faint}>{state === 'stalled' ? 'Stuck at 62%.' : (state === 'ready' ? 'Local. No telemetry.' : `ETA ~${Math.max(5, Math.round((1 - fakeProgress) * 120))}s`)}</Eyebrow>
            {state === 'stalled' && (
              <button onClick={onRetry} style={{
                background: 'transparent', border: 'none', color: V.glow,
                fontFamily: V.mono, fontSize: 10, letterSpacing: '0.16em',
                textTransform: 'uppercase', cursor: 'pointer',
                display: 'flex', alignItems: 'center', gap: 6,
              }}>{Icons.retry} Retry</button>
            )}
          </div>
        </div>

        {/* Detail rows — sourceable, technical-but-restrained */}
        <div style={{
          background: V.s1, border: `1px solid ${V.hair}`, borderRadius: 6,
        }}>
          {[
            ['Runtime', 'On-device · MediaPipe GenAI'],
            ['Network', 'Not used after download'],
            ['Telemetry', 'None'],
            ['Storage', '2.1 GB · /data/models/gemma'],
            ['Last run', state === 'ready' ? '24 ms ago' : '—'],
          ].map((row, i) => (
            <div key={i} style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              padding: '12px 16px',
              borderBottom: i < 4 ? `1px solid ${V.hair2}` : 'none',
            }}>
              <Eyebrow>{row[0]}</Eyebrow>
              <div style={{ fontFamily: V.sans, fontSize: 13, color: V.ink }}>{row[1]}</div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ─── Persona Selector ─────────────────────────────────────────────
function PersonaScreen({ persona = 'witness', onPick, onClose }) {
  const personas = window.VESTIGE_DATA.PERSONAS;

  return (
    <>
      <AppShellTop
        modelState="ready"
        right={<IconBtn onClick={onClose} ariaLabel="close">{Icons.close}</IconBtn>}
      />
      <div style={{ padding: '4px 24px 24px', flex: 1, display: 'flex', flexDirection: 'column', gap: 14, overflowY: 'auto' }} className="ves-scroll">
        <Eyebrow>Persona · pick a voice</Eyebrow>
        <H1>Same observations. Different cut.</H1>
        <P dim>All three watch the same record. None perform empathy. Tap one to switch.</P>

        {/* Three persona cards — each shows that voice cutting a real entry. */}
        <div style={{ marginTop: 4, display: 'flex', flexDirection: 'column', gap: 10 }}>
          {personas.map((p) => {
            const isActive = persona === p.id;
            return <PersonaCard key={p.id} p={p} active={isActive} onClick={() => onPick(p.id)} />;
          })}
        </div>

        <div style={{ marginTop: 4 }}>
          <Eyebrow>Note</Eyebrow>
          <P dim style={{ marginTop: 6 }}>Switching persona changes wording, never weight. The same patterns surface either way.</P>
        </div>
      </div>
    </>
  );
}

window.OnboardingScreen = OnboardingScreen;
window.ModelStatusScreen = ModelStatusScreen;
window.PersonaScreen = PersonaScreen;
