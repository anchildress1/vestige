# ADR-005 — STT-B test scope correction & v1 single-turn scope decision (amends ADR-002)

**Status:** Accepted
**Date:** 2026-05-10
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Depends on:** `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior", §Q5, Action Item #1
**Validated by:** STT-B Round 3 device-test 2026-05-09 + GPT review of test methodology 2026-05-10

---

## Context

ADR-002 §"Multi-turn behavior" was added to capture the on-device STT-B device-test results as Phase 2 ran. Round 3 was recorded as "in flight"; the prose around it (and the related Q5 + Action Item #1 sections) characterized the result trajectory in language that implied a definitive E4B capability verdict.

Two events overtook that draft:

1. **Round 3 completed.** Result: `retention=0.0/3 sessions` under the explicit-instruction try (same as Round 2). 24 turns total across all three rounds, 18 turn-≥2 lookups, zero cross-turn anchor hits under the pattern measured.
2. **GPT review (2026-05-10) caught a methodology overclaim.** All three rounds exercised one specific multi-turn pattern: per turn, `LiteRtLmEngine.sendMessageContents` opens `engine.createConversation().use { … }` (a fresh SDK conversation handle) and stuffs prior turns' transcribed text into the system prompt as a JSON `## RECENT TURNS` block. The LiteRT-LM SDK's stateful path — one persistent `Conversation` instance receiving multiple `sendMessage` calls so the SDK's native KV cache + dialogue context carry across turns — was **not** measured. The verdict-shaped language in the original drafts conflated "the prompt-stuffing pattern fails" with "E4B cannot do multi-turn"; only the former was measured.

Per AGENTS.md, ADRs are historical artifacts and must not be rewritten. The corrections + the closing decision land here as a new ADR that amends specific sections of ADR-002 by reference.

---

## Decision (summary)

1. **STT-B verdict is scoped to the prompt-stuffing pattern.** The three rounds' `retention=0.0` result speaks to that pattern only. The SDK's stateful Conversation path remains unmeasured.
2. **v1 ships single-turn-per-capture as a scope decision, not a capability conclusion.** Each tap of record opens a fresh `CaptureSession`; the foreground prompt carries no prior-turn context; no `## RECENT TURNS` block in the system prompt; `CaptureSession` is single-use (terminal at RESPONDED / ERROR).
3. **ADR-002 §Q5 is superseded** for the v1 timeframe. The "last 4 turns of session context" plan does not ship in v1.
4. **ADR-002 Action Item #1 is closed as PARTIALLY MEASURED.** The structured-output reliability sub-question rolls forward to STT-C (unchanged).
5. **Future revival path is recorded** so a later round doesn't retrace the same prompt-stuffing dead end.

---

## Round 3 device-test record (closing the in-flight entry from ADR-002 §"Multi-turn behavior")

**Round 3 (2026-05-09 — explicit-instruction try, S24 Ultra, E4B CPU):** added a `RECENT_TURNS_INSTRUCTION` block to `ForegroundInference.composeSystemPrompt` — a prose paragraph emitted between `## RECENT TURNS` and the JSON lines, telling the model the JSON below is conversation context and that follow-ups must explicitly name prior facts when the new audio relates to them. Same audio set (`stt-b1.wav` … `stt-b4.wav`), same corrected anchor manifest from Round 2, same per-turn fresh-conversation pattern.

Result: `retention=0.0/3 sessions`. **Zero cross-turn anchor hits across 18 turn-≥2 lookups across all three rounds (24 turns total) under the prompt-stuffing pattern.**

The instruction did slow inference (per-turn 34.9–65.3 s vs Round 2's 33.3–43.3 s — longer prompt = more CPU tokens) without changing model behavior under the pattern. Every follow-up across all three rounds references only the audio Gemma just heard; `launch doc`, `standup`, `the doc`, and `roadmap call` are never echoed after introduction under the pattern measured.

Latency record across all rounds, E4B CPU on S24 Ultra: per-turn 32.7–65.3 s. ADR-002 §"Latency budget" 1–5 s target remains unmet; latency tuning is Phase 4/5 territory and is independent of the multi-turn pattern question.

---

## Scope of what was measured (corrects ADR-002 §"Multi-turn behavior" prose)

All three STT-B rounds exercised exactly this pattern:

```kotlin
// LiteRtLmEngine.sendMessageContents
val response = active.createConversation().use { conversation ->
    conversation.sendMessage(Contents.of(*parts.toTypedArray())).toString()
}
```

The conversation handle is opened fresh per call and closed immediately after. Prior turns' transcribed text was carried across turns by the caller stuffing it into the system prompt as a JSON `## RECENT TURNS` block. The SDK never saw the prior turns as conversation state; from the SDK's perspective each turn was a brand-new conversation receiving one large multimodal message.

**The LiteRT-LM SDK's stateful path was not measured.** That path is one persistent `Conversation` instance receiving multiple `sendMessage` calls within the same handle, letting the SDK manage the KV cache + dialogue context natively. Whether E4B sustains multi-turn context under that path is open.

The Round 1 false-positive lesson on substring-anchor design is preserved verbatim from ADR-002 §"Multi-turn behavior" — it generalizes beyond STT-B and is worth keeping as reference for future stop-and-test work. (Anchors must be substrings introduced in PRIOR turns AND absent from the CURRENT turn's audio. Same-turn echo trips substring matching and looks like context retention; it is not.)

---

## ADR-002 §Q5 supersession (v1 timeframe)

**ADR-002 §Q5** ("Foreground call's session context — last 4 turns by default") is **superseded** for v1 by this ADR. The foreground prompt no longer composes a recent-turns block; `historyTurnLimit` is removed from `ForegroundInference`; the v1 signature is `runForegroundCall(audio: AudioChunk, persona: Persona): ForegroundResult`.

Q5 stays unmodified in ADR-002 as the historical decision record for what was originally planned. This supersession does not delete the original plan from the repo's memory; it just marks that v1 went a different way for the documented reason.

If a future revival of multi-turn re-establishes session context, the surface to design against is the SDK's stateful `Conversation` instance — not a return to the prompt-stuffing pattern Q5 originally described.

---

## ADR-002 Action Item #1 closure

**ADR-002 Action Item #1** ("STT-B — verify foreground call returns transcription + follow-up reliably as structured output across multi-turn") is **closed as PARTIALLY MEASURED 2026-05-10**:

- The prompt-stuffing pattern was measured across three rounds and produced `retention=0.0` (cross-turn context retention failed under the pattern).
- The SDK's stateful Conversation path was not measured.
- v1 scopes to single-turn-per-capture, so the structured-output reliability that Action Item #1 originally cared about (parse rate of `<transcription>` + `<follow_up>` blocks under multi-turn) folds into the single-turn case STT-C measures.

Action Item #1 stays unticked in ADR-002 itself per the no-rewrite-ADRs rule. This ADR is the record that the action item is closed for the v1 timeframe.

---

## v1-scope decision executed (already shipped on this branch)

The v1-scope decision landed in code + docs across multiple commits on `docs/stt-b-fallback-and-adr-004` (`d305543` AudioCapture cap, `1095ec6` ForegroundInference simplification, `07dc1e4` CaptureSession single-use, `2352cb7` STT-B harness cleanup, `dd29a76` PerCapturePersonaSmokeTest, plus the doc-correction bundle). This ADR retroactively records the rationale at the spec level.

| Surface | Change | Anchor commit |
|---|---|---|
| `ForegroundInference` | `runForegroundCall(audio, persona)`; no transcript param; no `## RECENT TURNS` block | `1095ec6` |
| `CaptureSession` | Single-use; RESPONDED + ERROR terminal; no `acknowledgeResponse` / `clearError` | `07dc1e4` |
| `AudioCapture` | 30 s hard cap; one `isFinal=true` chunk per recording | `d305543` |
| Story 2.3 | Reframed "persona switching during session" → "per-capture persona selection" | `e3be74f` |
| Story 2.4 | Verdict ticked as PARTIALLY MEASURED + v1-scope decision | `9c7e3bd` |
| `concept-locked.md` §"Two-tier processing" | Single-turn-per-capture as v1 scope choice | `c0ad1d7` |
| `PRD.md` Multi-turn AC + STT-B row + Open Questions | Reframed to PARTIALLY MEASURED | `3930971` |
| `design-guidelines.md` | "Conversation transcript" renamed "Entry transcript" | `e0e74dc` |
| `backlog.md` | `smart-turn-boundaries` collapsed; `multi-chunk-foreground` retained | `4cadf95` / earlier |

---

## Future revival path (post-v1)

If multi-turn comes back into scope post-v1, the test surface to exercise is **NOT** the prompt-stuffing pattern this ADR closes out. The minimum reproduction is:

1. Open `engine.createConversation()` once at the start of the user's session and keep that `Conversation` instance alive for the whole session.
2. For each turn, call `conversation.sendMessage(Contents.of(...))` on the same instance — letting the SDK's native KV cache + dialogue context carry across turns.
3. Re-author the STT-B harness to drive that loop. The Round-2 anchor design (substrings introduced in PRIOR turns AND absent from CURRENT turn's audio) generalizes; that part is reusable.
4. If `retention >= 0.80` on that path, re-open ADR-002 §Q5 and re-design the session-context contract around the SDK's stateful Conversation lifecycle (not prompt-stuffing).

This entry is also why backlog row `multi-chunk-foreground` (the deferred >30 s within-one-capture orchestration) stays open — both deferred items would naturally pair under a future Conversation-based session model.

---

## Trade-off Analysis

The v1 scope decision trades **demo polish** (a "real conversation" beat where the model references prior turns) for **build velocity + spec honesty**. We did not measure the SDK path that might have made multi-turn work; we shipped the simpler thing because the deadline doesn't have room for an SDK refactor + re-test cycle.

The two trade-offs that could go wrong:

- **A future contributor reads ADR-002's pre-amendment language** and concludes "E4B can't multi-turn" without reading this ADR. Mitigation: every doc I touched in the doc-correction bundle pointed at this ADR explicitly; the canonical scope-of-test paragraph lives here, not in ADR-002.
- **Demo audience expects a conversation and sees disconnected exchanges.** Mitigation: `design-guidelines.md` §"Entry transcript" already reframes the UI from "scrolling conversation" to "single exchange per entry"; the entry-list view is the conversation surrogate.

Pretending the v1 decision was forced by capability rather than scope would have been the bigger trade-off cost — it would have closed off a real avenue for v1.5 / v2 work and locked the wrong premise into every downstream design conversation.

---

## Consequences

**Easier:**
- v1 ships with one less moving piece (no session-state UX, no recent-turns prompt block).
- Future work has a clear pointer to the unmeasured SDK path.
- Doc honesty: the spec says what was measured and what wasn't.

**Harder:**
- v1.5 / v2 multi-turn work has to start by exercising a new SDK pattern, not extending what shipped.
- Anyone reading ADR-002 alone gets a misleading impression; this ADR has to be reachable from there. (It is — `concept-locked.md`, `PRD.md`, the Story 2.4 done-when, and the relevant KDoc all link to this ADR.)

**Revisit when:**
- Multi-turn comes back into scope post-v1. Re-open Q5 and re-author STT-B against the SDK stateful path per the revival pointer above.
- LiteRT-LM ships a major-version bump that changes the `Conversation` lifecycle. The revival path may need re-anchoring.

---

### Addendum (2026-05-15) — user-expectation note: pattern callouts are the cross-entry surface

This ADR closes the multi-turn question for engineers. It does not close the user-expectation
question. A demo viewer (and a real user past entry one) will reasonably assume "ask another
follow-up about the previous entry" works — that is the default mental model for any
chat-shaped UI, and Vestige's Capture screen renders the transcription + follow-up in a
chat-shape per `design-guidelines.md` §"Entry transcript".

**The product-side answer:** the foreground follow-up sees only this turn's audio by design;
**pattern callouts are how Vestige references prior entries**, not the follow-up. The
ADR-002 (2026-05-15) personality addendum's three-beat follow-up shape (declarative +
adjacent-observable + persona question) deliberately operates on this turn's surfaces only.
Cross-entry intelligence — "fourth Aftermath in twelve, all post-meeting" — lives on the
pattern-callout surface (ADR-003 §"Cooldown" + ADR-002 §3 deterministic append step), which
is appended to the per-entry observation, not to the follow-up.

`concept-locked.md` §"Two-tier processing" gets a one-line note under the v1 single-turn
scope: *Each follow-up sees only this turn — pattern callouts are how Vestige references
prior entries, not the follow-up.* Same anti-pushy invariant as everything else in the
spec; this is preempting one demo question that would otherwise eat 30 seconds of stage
time.

**What does not change.**

- The v1 single-turn scope decision and the executed code path (one fresh
  `engine.createConversation()` per call, no `## RECENT TURNS` block, single-use
  `CaptureSession`).
- The future-revival path described in §"Future revival path (post-v1)". The user-expectation
  note above clarifies the *current* product framing; it does not foreclose the SDK-stateful
  path returning later.

---

## Action Items

1. [x] Land the v1 single-turn scope decision across docs + code. _(Done across the `docs/stt-b-fallback-and-adr-004` branch commits enumerated above.)_
2. [ ] When the docs branch merges to main, the next round of doc work re-reading ADR-002 should land here first via the cross-references already embedded in `concept-locked.md`, `PRD.md`, Story 2.4, and the inference-class KDocs.
3. [ ] If multi-turn revival begins post-v1, treat this ADR as the predecessor; supersede it with a new ADR documenting whichever SDK-stateful test outcome lands.
