# Concept — Locked

**Name:** Vestige

**Category:** On-device brain tracker (cognition tracker), not a journaling app and not a mental wellness app.

**Hook:** "I built a brain tracker that doesn't blow smoke up your ass."

**Tagline:** *Vestige (n.) — a trace, mark, or visible evidence of something no longer present. Your brain keeps leaving traces. This app catches them.*

**Pitch:** Strava for your attention. The coach is a dick. Your data never leaves the phone. ADHD/INTP-coded, anti-sycophant by design.

**Internal positioning rule:** Track what happened, not how you graded today. We log cognitive events. We do not do therapy, mental wellness, mood scoring, or anything that implies clinical framing.

## Voice rules
- Behavioral / attention-state vocabulary, not feelings vocabulary. "Crashed at 3" not "felt sad at 3."
- **No performed validation** (praise, performed empathy, therapy-coded affirmation). Functional acknowledgment is allowed ("yeah, that tracks given the sleep gap"). The line: information vs. performance.
- **Interpretation rule:** model interprets behavior, vocabulary, and pattern (that's the product). Does not interpret feelings, motivations, or psychological causation. Pattern callouts name the pattern; they do not diagnose the user. Forbidden openings: "you might be feeling," "it seems you're avoiding," "this could indicate." Encouraged: counts, co-occurrences, vocabulary observations.
- No therapy-speak, no gratitude reframes, no wellness vocabulary.

## Personas (three settable; default Witness; per-capture override allowed)
- **Witness** *(default)* — present at the events, observes without judgment, recounts what was seen. Quiet, undramatic, restrained. Doesn't perform expertise or care.
- **Hardass** — directive, action-focused, blunt. "You said this last Tuesday. Going to do something or just type about it again?"
- **Editor** — cuts linguistic bullshit. "You used 'tired' three different ways. Pick one." Bite via precision.

## Templates (agent-emitted labels, not user-facing modes)

Templates are no longer user-picked. Capture screen has no template grid. The user just records or types. The agent labels each entry post-extraction based on which surfaces dominate.

Six labels:
- **Aftermath** — energy crash (State surface signals: `state_descriptor` = "crashed" + state shift evidence)
- **Tunnel exit** — hyperfocus debrief (Behavioral surface: focus subject + extended duration + things-ignored mentions)
- **Concrete shoes** — task paralysis (Behavioral surface: stuck task + resistance markers)
- **Decision spiral** — rumination loop (State surface: decision-looping + iteration markers)
- **Goblin hours** — 3am spiral (Time-of-day context between midnight–5am + State surface late-night markers; **shorter follow-up cadence applied automatically by context-aware prompting**, not template selection)
- **Audit** — catch-all when no archetype dominates

Echoes is not a template — recurrence is pattern-engine output across entries.

## Multi-lens extraction architecture

Each entry runs through a **3-lens × 5-surface** extraction pipeline. Three lenses produce three full extractions; convergence between them is what gets saved.

**Five surfaces** (orthogonal extraction modules — what gets extracted):
1. **Behavioral** — activities, sequence, time-of-day, environmental context → contributes to `tags`
2. **State** — attention/energy markers → contributes to `energy_descriptor`
3. **Vocabulary** — recurring words, in-entry contradictions → contributes to `tags`
4. **Commitment** — things the user said they'd do → contributes to `stated_commitment`
5. **Recurrence** — match against history → contributes to `recurrence_link`

**Three lenses** (orthogonal framings — how each surface is extracted):
1. **Literal** — strict, only what's explicit
2. **Inferential** — charitable, explicit + reasonable contextual inference
3. **Skeptical** — adversarial, flag contradictions, missing pieces, what doesn't add up

**Mix-and-match prompt architecture:** surface modules and lens modules stored separately. Each vector pass = one lens + all five surface instructions composed into a single prompt. 3 model calls per entry (one per lens), each returning the full schema.

**Convergence rules:**
- ≥2 of 3 lenses agree on a field → **canonical**, saved as authoritative
- Only Inferential populates a field → **candidate**, lower confidence, not used by pattern engine until promoted
- Lenses disagree → **ambiguous**, saved null with a note
- Skeptical flags conflict even when others agree → **canonical with conflict marker**

**Two-tier processing:**
- *Foreground:* fast pass returns transcription + follow-up question only. **Single-turn-per-capture** as a v1 scope choice (the STT-B prompt-stuffing pattern was tested and produced retention=0.0; the LiteRT-LM SDK's stateful Conversation path was not measured — see `adrs/ADR-005-stt-b-scope-and-v1-single-turn.md` (amends `adrs/ADR-002-multi-lens-extraction-pattern.md` §"Multi-turn behavior")). Each tap of record begins a fresh exchange and the model never sees prior turns.
- *Background:* 3-lens multi-pass runs after the chunk is acknowledged. Canonical extraction populates over the next 30–90 seconds.

## Schema (minimal v1)

Eleven content fields total. Extracted fields are convergence-driven; `entry_observations` is generated after convergence from the stored transcript plus resolved fields. No archetype-specific quantification in v1.

- `entry_text` — substrate (transcription or typed)
- `follow_up` — saved model turn for single-turn voice captures; `null` for typed entries
- `persona` — the recorded authoring persona for the saved follow-up (`witness` / `hardass` / `editor`)
- `timestamp` — auto
- `template_label` — agent-emitted (Crashed / Deep Space / Busy Stalling / Nonstop Spiral / Goblin Hours / Brain Dump)
- `tags` — free-form, model-extracted (people, topics, activities, places)
- `energy_descriptor` — nullable; captured if user mentioned a state
- `recurrence_link` — nullable; pattern_id if entry matches a known pattern
- `stated_commitment` — nullable; tag-only tracking (text + entry_id + topic/person). Pattern engine surfaces "logged commitments about [topic] in N entries, last on [date]." No formal resolution logic in v1.
- `entry_observations` — 1–2 persisted observations from this entry alone, each with evidence text or a field reference. Generated after convergence; never freeform speculation.
- `confidence` — per-field convergence result (canonical / candidate / ambiguous / canonical_with_conflict)

These eleven are the **content schema** — what the agent extracts/generates and the user sees. The ObjectBox `Entry` entity also carries operational metadata (`extraction_status`, `attempt_count`, `last_error`) for the retry-based background-extraction recovery path. Operational fields are owned by `adrs/ADR-001-stack-and-build-infra.md` §Q3, not by this spec — they are storage concerns, not product concerns.

**Archetype-specific fields deferred to v2** (not v1.5 — further out): `state_before`, `onset`, `last_food_caffeine`, `last_sleep`, `intent_now`, `focus_subject`, `focus_duration`, `ignored_during_focus`, `output_produced`, `stuck_task`, `resistance_type`, `time_stuck`, `external_pressure`, `last_attempt`, `decision_looped`, `iterations`, `stakes`, `decision_missing`, `time_pressure`, `spiral_topic`, `bedtime_delta`, `body_state`. With templates becoming labels rather than prompt scaffolds, archetype-specific extractions lose their v1 justification — `entry_text` carries the substance, and the agent can re-extract these fields on demand in v2.

## Re-eval ("Reading") — P1 conditional

User-tappable affordance on the History entry detail screen if P1 scope holds. Re-runs the same 3-lens pipeline on the stored transcript. Compares new convergence to original.
- *Same:* "Confirmed. Same shape." Quality signal — model agrees with itself across time.
- *Different:* show the diff per surface field. User accepts new shape or keeps original.

The "Reading" debug-style section on entry detail shows each lens's output per surface and the resolved convergence underneath. Defaults collapsed; expand to inspect.

## Analysis (two-layer, not threshold-only)

**Per-entry observation (every session, no threshold).** Model surfaces 1-2 observations from the entry itself. Examples:
- Linguistic contradictions ("you said 'fine' and 'couldn't stand up' in the same minute")
- Stated commitments captured for later tracking ("you said you'd talk to her — flagged")
- Volunteered context with one observation ("Sleep was 4 hours. Worth noticing.")
- Theme noticing without history ("this dump is mostly about your boss")

The product produces useful observable signal from entry one — not validation, not "feels seen" framing, just specific behavioral or vocabulary observations the user can verify. The product visibly sharpens as data accumulates.

**Pattern-enhanced callout (after threshold).** When ≥10 entries exist AND a pattern with ≥3 supporting entries is detected, the per-entry observation is *appended* with a pattern callout. "Witness also noticed: this is the fourth Crashed entry in twelve — all post-meeting." Cooldown of 3 entries on the pattern-callout part only; per-entry observations continue normally during cooldown.

**Roast me button (P1)** — on-demand deep analysis across history, available in patterns view after the normal pattern list works. User-initiated, no hard threshold: button may be visible from entry one, but generation may return the insufficient-data fallback copy from `ux-copy.md` when there is not enough history to make a sourced roast. Output must always be sourced (counts, dates, quotes); never freeform speculation.

## Pattern persistence
- Surfaced patterns persist as their own list, skippable / droppable / user-closeable
- Own tab in the app
- Pattern interpretation allowed (counts, co-occurrences, vocabulary). Feelings/motivation interpretation forbidden. "Fourth Crashed in twelve, all post-meeting" — yes. "You might be feeling overwhelmed" — never.

## Memory architecture
- Markdown files = source of truth (one per entry, exportable, debuggable)
- ObjectBox = on-device storage + structured tag/pattern store **(P0)**
- **Vector index + EmbeddingGemma 300M ship only if STT-E passes.** EmbeddingGemma 300M via LiteRT (~200MB quantized, sub-15ms inference, pre-built `litert-community/embeddinggemma-300m`). Same runtime as the main model.
- Hybrid retrieval P0 baseline: keyword + Gemma-extracted tags + recency. Vector (semantic similarity) layer added on top **only if STT-E passes**.
- **If STT-E fails:** v1 ships with keyword + tags + recency only. EmbeddingGemma + vector index drop to v1.5 (see `backlog.md`).
- **Why both layers (when shipped):** tags are a modeling layer that drifts with vocabulary; embeddings are a measurement layer that stays stable. Vector layer protects pattern detection from user vocabulary drift over months.

## Stack
- **LLM:** Gemma 4 E4B (effective-parameter small model built for edge/mobile use; not the 26B MoE variant)
- **Runtime:** LiteRT-LM SDK (`litertlm-android`). Single inference runtime per `AGENTS.md` guardrail 13.
- **Model artifact:** `litert-community/gemma-4-E4B-it-litert-lm` from Hugging Face (pre-converted)
- **Platform:** Android, Kotlin + Jetpack Compose
- **Voice input:** Native Gemma 4 audio modality — raw audio via `AudioRecord` straight to the model. No SpeechRecognizer, no third-party STT, no external service touches the bytes. Gemma 4 itself produces transcription as part of its response (native ASR capability). Audio path stays inside our process end-to-end.
- **Transcription handling:** Gemma 4 returns both the transcription and the follow-up question in a single structured response. Transcription is shown alongside the model's response in the entry view (visually secondary to the model's response — see design-guidelines.md §"Entry transcript"). Transcription is saved as `entry_text` (the substrate of the entry); the saved model turn is stored separately as `follow_up`, and the authored tone is stored as `persona` so prior entries keep their original speaker label even after the default persona changes. Audio bytes are discarded after the model call. Per the v1 single-turn scope choice (see §"Two-tier processing"), each capture is one self-contained exchange — the entry view is two items (your transcription + the model's follow-up), not a scrolling conversation.
- **Audio constraint (per `adrs/ADR-001-stack-and-build-infra.md` §Q4):** 30s hard cap per capture in v1. One Gemma call returns transcription + follow-up from a single final chunk. Audio past 30 seconds is truncated at the audio layer; the deferred `>30s` multi-chunk orchestration lives in backlog row `multi-chunk-foreground` and is not part of the v1 contract.
- **Voice output:** None in v1. Gemma 4 doesn't natively generate audio, and adding a TTS engine (Kokoro etc.) is deferred to v2. Documented as an explicit limitation in the blog post.
- **Fallback runtime if LiteRT-LM is unworkable:** llama.cpp via JNI with GGUF Q4_K_M. Adopting it ships as a v1.5 contingency only — would require a superseding ADR per `AGENTS.md` guardrail 13, not a v1 default.

## Out of scope
- Therapist referrals
- Proactive crisis routing / keyword detection. Exception: if the user explicitly asks for immediate self-harm help, show the local static safety message from `ux-copy.md`; do not diagnose, triage, or call a network service.
- Cloud anything
- iOS, web, desktop
- Custom dictation engine (we use Gemma 4 native audio modality)
- E2B fallback path
- Mental wellness / mental health framing in any UI copy or marketing
- Gamification, streaks, points, "good day vs bad day" scoring, Duolingo-style anything
- Voice output / TTS (Gemma 4 doesn't generate audio; non-Gemma TTS deferred to v2)
- Audio retention with N-day expiry + encryption-at-rest (deferred to v2)
- Video input (Gemma can process video-style prompts as extracted frames plus audio chunks; deferred to v2 for engineering scope and RAM pressure)
- Hotword / always-on listening
- Multi-step agentic tool chains (not validated for reliable local v1 behavior)
- Echoes as a template (recurrence is pattern-engine output, not a dump archetype)
- Weekly recap callouts (deferred to v2)
- Light theme

## Target device
- **Reference:** Galaxy S24 Ultra (12 GB RAM, SD8 Gen 3)
- **Minimum spec for the post:** Android 14+, 8+ GB RAM, 6 GB free storage. 2024+ flagship territory, with the S24 Ultra as the only promised reference device.
- APK distributed via GitHub releases for sideloading. No Play Store for v1.

## Open decisions
- Demo video storyboard (90s pitch + 5-min walkthrough beats)
- Blog post body (hook and tagline locked, full body TBD)
- App icon specifics (concept locked: partial footprint dissolving into mist)
