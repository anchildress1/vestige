# Concept — Locked

**Name:** Vestige

**Category:** On-device brain tracker (cognition tracker), not a journaling app and not a mental wellness app.

**Hook:** "I wrote a brain tracker that won't blow smoke up your ass."

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

Templates are no longer user-picked. Capture screen has no template grid. The user just records or types. The agent labels each entry post-extraction based on which surfaces dominate. Goblin Hours additionally has a **deterministic floor**: a midnight–5am capture is labelled Goblin Hours whenever the agent didn't commit to a more specific archetype (see below).

Six labels:
- **Crashed** — energy crash (State surface: crash / depletion state words → `tags`)
- **Deep Space** — hyperfocus debrief (Behavioral surface: focus subject + extended duration + things-ignored mentions)
- **Busy Stalling** — task paralysis (Behavioral surface: stuck task + resistance markers)
- **Nonstop Spiral** — rumination loop (State surface: decision-looping + iteration markers)
- **Goblin Hours** — late-night capture. The agent can read it directly, but it also has a **deterministic floor**: when the agent commits to nothing more specific than `audit`, a midnight–5am local capture is labelled Goblin Hours from the timestamp alone (the model is never handed the clock, and an entry's text can name a different hour than when it was actually captured). A specific archetype read is never overridden.
- **Brain Dump** — catch-all when no archetype dominates

Echoes is not a template — recurrence is pattern-engine output across entries.

## Multi-lens extraction architecture

Each entry runs through a **3-lens × 5-surface** extraction pipeline. Three lenses produce three full extractions; convergence between them is what gets saved.

**Five surfaces** (orthogonal extraction modules — what gets extracted):
1. **Behavioral** — activities, sequence, time-of-day, environmental context → contributes to `tags`
2. **State** — attention/energy markers → contributes to `tags` (state words)
3. **Vocabulary** — the entry's overall felt tone → contributes to `vocabulary` (one word)
4. **Commitment** — things the user said they'd do → contributes to `stated_commitment`
5. **Recurrence** — match against history → contributes to `recurrence_link`

**Three lenses** (orthogonal framings — how each surface is extracted):
1. **Literal** — strict, only what's explicit
2. **Inferential** — charitable, explicit + reasonable contextual inference
3. **Skeptical** — adversarial, flag contradictions, missing pieces, what doesn't add up

**Mix-and-match prompt architecture:** surface modules and lens modules stored separately. Each vector pass = one lens + all five surface instructions composed into a single prompt. 3 model calls per entry (one per lens), each returning the full schema.

**Convergence rules:**
- ≥2 of 3 lenses agree on a field → **consensus**, saved as authoritative
- Only Inferential populates a field → **candidate**, lower confidence, not used by pattern engine until promoted
- Lenses disagree → **ambiguous**, saved null with a note
- Skeptical flags conflict even when others agree → **consensus with conflict marker**

**Two-tier processing:**
- *Foreground:* fast pass returns transcription + one persona follow-up per `adrs/ADR-018-inline-foreground-follow-up.md`. Prior-entry recall stays out of foreground; pattern callouts are how Vestige references older entries.
- *Background:* 3-lens multi-pass runs after the chunk is acknowledged. Canonical extraction populates over the next 30–90 seconds.

## Schema (minimal v1)

Eleven content fields total. Extracted fields are convergence-driven; `entry_observations` is generated after convergence from the stored transcript plus resolved fields. No archetype-specific quantification in v1.

- `entry_text` — substrate (transcription or typed)
- `follow_up` — foreground persona follow-up for voice captures; `null` for typed entries
- `persona` — recorded selected persona for row provenance
- `timestamp` — auto
- `template_label` — agent-emitted + convergence-voted across all six archetypes; **Goblin Hours additionally has a deterministic floor** — a midnight–5am capture takes over from a non-committal Brain Dump when the clock qualifies (the agent can still read Goblin Hours directly)
- `tags` — free-form, model-extracted (people, topics, activities, places)
- `vocabulary` — nullable; one lowercase word for the entry's overall felt tone (Inferential lens wins)
- `recurrence_link` — nullable; pattern_id if entry matches a known pattern
- `stated_commitment` — nullable; tag-only tracking (text + entry_id + topic/person). Pattern engine surfaces "logged commitments about [topic] in N entries, last on [date]." No formal resolution logic in v1.
- `entry_observations` — 1–2 persisted observations from this entry alone, each with evidence text or a field reference. Generated after convergence; never freeform speculation.
- `confidence` — per-field convergence result (consensus / candidate / ambiguous / consensus_with_conflict)

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

**Pattern-enhanced callout.** Pattern analysis runs **periodically — every 3 completed entries** ([ADR-014](adrs/ADR-014-foreground-background-split-and-periodic-pattern-analysis.md), supersedes the original "≥10 entries" threshold trigger); when a pattern with ≥3 supporting entries is detected, the per-entry observation is *appended* with a pattern callout. "Witness also noticed: this is the fourth Crashed entry in twelve — all post-meeting." **Per-pattern** callout cooldown of 3 ([ADR-016](adrs/ADR-016-pattern-callout-cooldown-per-pattern.md), supersedes the original global cooldown); per-entry observations continue normally during cooldown.

**Roast me button (P1)** — on-demand deep analysis across history, available in patterns view after the normal pattern list works. User-initiated, no hard threshold: button may be visible from entry one, but generation may return the insufficient-data fallback copy from `ux-copy.md` when there is not enough history to make a sourced roast. Output must always be sourced (counts, dates, quotes); never freeform speculation.

## Pattern persistence
- Surfaced patterns persist as their own list, skippable / droppable / model-closeable (v1.5)
- Own tab in the app
- Pattern interpretation allowed (counts, co-occurrences, vocabulary). Feelings/motivation interpretation forbidden. "Fourth Crashed in twelve, all post-meeting" — yes. "You might be feeling overwhelmed" — never.

## Memory architecture
- ObjectBox = source of truth for entries, tags, patterns, and vectors **(P0)**
- Markdown files = generated export output (one per entry, readable, debuggable)
- **Vector index + EmbeddingGemma 300M ship (STT-E passed 2026-05-12).** EmbeddingGemma 300M via LiteRT (pre-built `litert-community/embeddinggemma-300m`), loaded through `localagents-rag` (ADR-010). Vectors are computed per entry and STT-E-validated. **Caveat:** the vector *surfaces* — ranked hybrid retrieval and the Vocab Drift clustering — are **not yet live** in the build (see README §"Known Limitations"; `backlog.md` → `embedding-retrieval-surface` / `vocab-cluster-threshold`).
- Hybrid retrieval P0 baseline: keyword + Gemma-extracted tags + recency. The vector (semantic similarity) layer is implemented on top but not yet wired into a live surface.
- **Why both layers (when shipped):** tags are a modeling layer that drifts with vocabulary; embeddings are a measurement layer that stays stable. Vector layer protects pattern detection from user vocabulary drift over months.

## Stack
- **LLM:** Gemma 4 E4B (effective-parameter small model built for edge/mobile use; not the 26B MoE variant)
- **Runtime:** LiteRT-LM SDK (`litertlm-android`). Single inference runtime per `AGENTS.md` guardrail 13.
- **Model artifact:** `litert-community/gemma-4-E4B-it-litert-lm` from Hugging Face (pre-converted)
- **Platform:** Android, Kotlin + Jetpack Compose
- **Voice input:** Native Gemma 4 audio modality — raw audio via `AudioRecord` straight to the model. No SpeechRecognizer, no third-party STT, no external service touches the bytes. Gemma 4 itself produces transcription as part of its response (native ASR capability). Audio path stays inside our process end-to-end.
- **Transcription handling:** Gemma 4 returns transcription + one follow-up in a structured foreground response. Transcription is saved as `entry_text` (the substrate of the entry); the follow-up is saved as entry metadata from the same call. Audio bytes are discarded after the model call. The entry view is transcript plus extraction receipts, not a chat exchange.
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
