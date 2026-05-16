# ADR-003 — Pattern Detection Algorithm & Persistence

**Status:** Accepted
**Date:** 2026-05-08
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Depends on:** `concept-locked.md` §"Analysis", `concept-locked.md` §"Pattern persistence", `PRD.md` §P0 Patterns, `adrs/ADR-001-stack-and-build-infra.md` §Q1 (`:core-storage` ownership), `adrs/ADR-002-multi-lens-extraction-pattern.md` (`recurrence_link` predicate, observation-generation prompt)
**Validated by:** PRD §"Pattern surfacing" acceptance criterion (≥10 entries → ≥1 cross-entry pattern surfaces, sourced, no interpretive overlay)

---

## Context

`concept-locked.md`, `PRD.md`, and AGENTS.md all describe what pattern detection produces. None of them describe how it works. The CLI agent will pick something in Phase 3 whether we record it or not, and pattern detection is the most visible "the app is smart" beat in the demo. Ad-hoc Phase-3 code will get rewritten three times before submission.

What is already locked elsewhere (do not change):

| Decision | Source |
|---|---|
| Detection runs every 10 entries (hardcoded for v1) | `PRD.md` §P0 Patterns |
| Pattern threshold = ≥3 supporting entries | `PRD.md` §P0 Capture loop |
| Pattern actions = `dismiss` / `snooze` / `mark-resolved` (P0) | `PRD.md` §P0 Patterns |
| Snooze duration = 7 days, fixed | `ux-copy.md` §"Locked v1 behavior" |
| Pattern-callout cooldown = 3 entries (callout only, not detection) | `concept-locked.md` §Analysis |
| Pattern claims must be sourced (counts, dates, snippets, tags, field evidence) | AGENTS.md guardrail 12 |
| No feelings / motivation interpretation | `concept-locked.md` §"Pattern persistence" |
| `pattern_id` is deterministic (pattern engine emits stable IDs) | `adrs/ADR-002` resolver predicate for `recurrence_link` |
| Pattern names are model-generated; no user rename in v1 | `ux-copy.md` §"Locked UX Decisions" |

What this ADR closes:

1. The set of pattern **primitives** the engine recognizes in v1 (what counts as a pattern at all).
2. **`pattern_id` generation** — content-addressable hash of which inputs, in what order.
3. The **matching predicate** — when a new entry "supports" an existing pattern.
4. The **persisted shape** — ObjectBox `Pattern` entity fields, lifecycle states, transitions.
5. The **cooldown** semantics — global vs. per-pattern; how callouts interact with snooze/resolve.
6. **Re-eval and dismissal interactions** — what happens when supporting entries change after the user took an action.

---

## Decision (summary)

Ship five pattern primitives in v1, all sourced and content-addressable. Pattern detection is a deterministic Kotlin pass over entries from the last 90 days, run after every 10th entry. Patterns persist in ObjectBox keyed by content hash; the entity carries lifecycle state (`active` / `dismissed` / `snoozed` / `resolved`) plus the supporting-entry references needed for the source list. Cooldown is global, not per-pattern. Mark-resolved is sticky — new evidence does not re-open a resolved pattern.

---

## Pattern primitives (v1)

Five kinds. Each emits one `Pattern` row when its threshold trips. Each is independent — the same entry can support multiple patterns.

| `pattern_kind` | Signature | Threshold | Example callout (Witness tone) |
|---|---|---|---|
| `template_recurrence` | `template_label` | ≥3 entries with the same label in the last 90 days | "Fourth Aftermath in twelve. Worth noting." |
| `tag_pair_co_occurrence` | `(template_label, sorted tag pair)` | ≥3 entries containing **both** tags within entries of that label | "Tuesday meetings have a body count. Standup + crashed across four entries." |
| `time_of_day_cluster` | `("goblin", null)` (sole bucket in v1) | ≥3 entries between 00:00–05:00 local time in the last 30 days | "Three goblin hours this week. Same admin loop." |
| `commitment_recurrence` | `("commitment", topic_or_person)` | ≥3 entries with `stated_commitment.topic_or_person` matching | "You've logged commitments about her in five entries. Last on May 2." |
| `vocab_frequency` | `("vocab", token)` | A single normalized token appears in ≥N entries with ≥2 distinct contexts (N=4 in v1) | "'Tired' is doing six different jobs this month." |

**Why these five.** They map directly to the demo beats already promised in `concept-locked.md` §Analysis and the `ux-copy.md` Pattern Detail / Roast examples. Anything beyond these is `../backlog.md` territory.

**Why tag pairs and not tag triples or arbitrary subsets.** Pair enumeration is `O(t²)` per entry where `t` is tag count; triples are `O(t³)` and the resulting patterns rarely earn their visual real estate in a 5-min demo. Triples are a `../backlog.md` candidate.

**Why a 90-day rolling window for `template_recurrence` and `tag_pair_co_occurrence` but 30-day for `time_of_day_cluster`.** Behavioral clusters drift. Witness saying "fourth Aftermath" feels honest when it's "fourth in twelve recent entries"; "fourth Aftermath in your entire history of 200 entries" reads as nagging. Goblin-hours specifically is meant to catch *current* sleep patterns, hence the tighter window.

**Why no machine-learning-shaped detection in v1.** Counting is sourced and explainable. A learned pattern model would need training data we don't have, and AGENTS.md guardrail 12 would invalidate any pattern the engine couldn't justify with concrete evidence.

---

## `pattern_id` generation

Content-addressable. The hash inputs are exactly the signature columns from the table above, serialized as a stable JSON object, then SHA-256.

```kotlin
// pseudocode
val signature = mapOf(
  "kind" to pattern.kind.name,
  "key" to pattern.signatureKey, // sorted, normalized
)
val pattern_id = sha256(Json.encodeToString(signature)) // hex string
```

**Stability rules:**
- Tags inside the signature are normalized (lowercase, kebab-case, sorted) **before** hashing. Adding a new supporting entry never changes the hash, because the hash inputs are the *defining* signature, not the *evidence*.
- `pattern_kind` is part of the hash, so a `template_recurrence` for `aftermath` and a `vocab_frequency` for `aftermath` cannot collide.
- Vocabulary tokens are stemmed and lowercased before hashing (`tireds`, `tired`, `Tired` → `tired`).

**Why content-addressable instead of an autoincrement primary key.**
- ADR-002's `recurrence_link` resolver predicate requires deterministic IDs across re-eval and across the cold-start sweep. A random UUID makes "this entry matches the same pattern as before" untestable.
- Content addressing means re-running the pattern detection over the same data produces the same IDs. Convergence resolver (ADR-002) and pattern detection share this invariant.
- Markdown source-of-truth (`architecture-brief.md` §"Markdown Entry Shape") stores `recurrence_link` as `pattern_id`. If we restored from markdown, the ObjectBox cache would rebuild with the same IDs.

---

## Matching predicate (when an entry "supports" a pattern)

Run on every new entry, not only on detection-trigger entries. Cheap; bounds extraction `recurrence_link` per ADR-002.

| `pattern_kind` | Predicate |
|---|---|
| `template_recurrence` | `entry.template_label == pattern.signature.label` |
| `tag_pair_co_occurrence` | `entry.template_label == pattern.signature.label` AND `pattern.signature.tag_pair ⊆ entry.tags` |
| `time_of_day_cluster` | `entry.timestamp.localHour ∈ [0,5)` |
| `commitment_recurrence` | `entry.stated_commitment != null` AND `entry.stated_commitment.topic_or_person == pattern.signature.topic_or_person` |
| `vocab_frequency` | `entry.tags ∋ pattern.signature.token` OR `entry.entry_text` contains the token (stemmed match) |

**Subset semantics for tag pairs.** The pair is required as a subset, not an exact match. An entry tagged `{tuesday-meeting, standup, flattened}` supports the pattern signature `(aftermath, [standup, flattened])` even though `tuesday-meeting` is also present. This is what makes the pattern read as "post-standup crash across four entries" instead of breaking when one entry has an extra tag.

**Vocabulary-frequency context check.** The "≥2 distinct contexts" requirement of `vocab_frequency` is computed from the supporting set, not the predicate. The predicate just identifies candidate entries; the threshold step (next section) enforces the diversity requirement so a single long sentence using "tired" four times does not become a pattern.

---

## Detection algorithm (run every 10 entries)

```
1. Load all entries from the last 90 days, indexed by template_label and by id.
   For time_of_day_cluster, also load the last 30 days separately.
2. For each pattern_kind, enumerate candidate signatures over the loaded entries.
3. For each candidate signature, count supporting entries (the matching predicate above).
4. Apply per-kind threshold (mostly ≥3).
5. Compute pattern_id from the signature.
6. Upsert into the Pattern store:
     - If pattern_id is new → INSERT with state=active, generate model-emitted title/observation
     - If pattern_id exists with state=active → UPDATE supporting_entry_ids, last_seen_timestamp, latest_callout_text
     - If pattern_id exists with state=snoozed AND now ≥ snoozed_until → flip state to active, UPDATE
     - If pattern_id exists with state=snoozed AND now < snoozed_until → UPDATE supporting set silently, do not re-surface
     - If pattern_id exists with state=dismissed OR resolved → UPDATE supporting set silently, do not re-surface
7. For each pattern that flipped to active in this run, emit a Patterns-list row (P0 demo beat).
8. For the entry that triggered detection, ask the pattern engine for one matching active pattern (if cooldown allows) → that becomes the pattern-enhanced callout appended to the per-entry observation.
```

**Step 6's "UPDATE silently" is the key product call.** A dismissed pattern still grows its supporting set in the background, so if the user un-dismisses it later (v1.5 affordance) the count is honest. In v1, dismiss is one-way; the count just keeps quietly.

**Pattern title generation (step 6 INSERT).** When a new active pattern lands, generate a short title via one short model call using the persona-injected observation-generation prompt (ADR-002 §3). Title is ≤24 chars (`Tuesday Meetings`, `Goblin Hours`, `Invoice Email`). Persisted on insert; never regenerated in v1.

**Detection cost.** Worst case at 200 entries with average 8 tags each:
- `template_recurrence`: 6 candidate signatures (the closed enum). 200 entries scanned. Trivial.
- `tag_pair_co_occurrence`: ≤6 × C(8,2) = 168 candidates per entry, ~33,600 candidate-entry pairs total. Bounded.
- `time_of_day_cluster`: one signature, scan-once.
- `commitment_recurrence`: one signature per distinct topic_or_person seen. ~10–30.
- `vocab_frequency`: token frequency table over `entry.tags ∪ entry_text` stems.

All five together run in ~tens of milliseconds at v1 scale on the reference S24 Ultra. No incremental aggregates needed; full recompute every 10 entries is fine. Pre-aggregating is a `../backlog.md` candidate when entries cross 1000.

---

## ObjectBox `Pattern` entity (persistence shape)

```kotlin
@Entity
data class Pattern(
  @Id var id: Long = 0,                             // ObjectBox PK, internal
  @Index @Unique var patternId: String = "",        // SHA-256 hex, the content-addressable id
  var kind: String = "",                            // pattern_kind enum name
  var signatureJson: String = "",                   // exact bytes that were hashed (debug + dedup audit)
  var title: String = "",                           // model-generated, ≤24 chars
  var templateLabel: String? = null,                // denormalized for filtering; null for vocab/goblin
  var firstSeenTimestamp: Long = 0,                 // when threshold was first crossed
  var lastSeenTimestamp: Long = 0,                  // most recent supporting entry timestamp
  var supportingEntryIds: ToMany<Entry> = ...,      // ObjectBox relation
  var state: String = "active",                     // active | dismissed | snoozed | resolved
  var snoozedUntil: Long? = null,                   // unix-ms; null when state != snoozed
  var stateChangedTimestamp: Long = 0,              // when user last took action; 0 = never
  var latestCalloutText: String = "",               // appended to per-entry observation when this pattern fires
)
```

**Why `signatureJson` alongside `patternId`.** Debugging hash collisions and verifying pattern-engine determinism without re-running the detector. Cheap storage, paid back the first time a re-eval doesn't match.

**Why `templateLabel` is denormalized.** UI filtering by template (`P1: History filter by tag, template, or date range` per `PRD.md`) is a frequent query. Denormalizing avoids parsing `signatureJson` on every list query.

**Why no `kind`-specific subtypes.** Single-table inheritance. Five kinds is small enough that a flat shape stays readable. ObjectBox migrations get cheaper too.

---

## Lifecycle & state transitions

```
NEW (computed)   →  active            when threshold ≥3 first crossed
active           →  dismissed         user tapped Dismiss
active           →  snoozed           user tapped Snooze 7 days; sets snoozed_until = now + 7d
active           →  resolved          user tapped Mark resolved
active           →  below_threshold   Re-eval drops support count <3 (internal only, not user-facing — see §"Re-eval interaction")
below_threshold  →  active            later entry restores support count ≥3 (idempotent re-emerge)
snoozed          →  active            snoozed_until passed AND a detection run sees the pattern still meeting threshold
snoozed          →  active            user tapped Un-snooze (Patterns view exposes this)
snoozed          →  dismissed         user tapped Dismiss while snoozed (skip the un-snooze step)
dismissed        →  (terminal)        no transition out in v1
resolved         →  (terminal)        no transition out in v1; new evidence accumulates silently per "UPDATE silently" rule
```

**Why `dismissed` and `resolved` are terminal in v1.** Adding "un-dismiss" or "auto-reopen on stronger evidence" doubles the state-machine surface and is invisible in the demo. The user has agency to act on a pattern; if they kill it, they killed it. Reopening is a `../backlog.md` candidate (similar to `reeval-auto-promote`).

**Auto-promotion of snoozed → active.** Detection step 6 handles this. A user who snoozed Tuesday Meetings 7 days ago will see it return on the next detection run after the snooze expires *if the pattern still has fresh supporting entries*. If the pattern has decayed (no new supporting entries in the last 30 days), it stays snoozed silently — re-surfacing a stale pattern is more annoying than helpful.

**Mark-resolved is sticky for the demo.** Concept-locked says the app respects user agency; this is the cleanest expression of that. Pattern Detail still shows the sourced evidence (P1 history filter applies), but the Patterns list does not include it.

---

## Cooldown (callout-side only, global)

Pattern detection runs every 10 entries and updates the Pattern store unconditionally. The **callout** — the appended line on a per-entry observation, e.g., "Witness also noticed: this is the fourth Aftermath in twelve" — has a 3-entry global cooldown.

**Mechanics:**
- Persist `lastCalloutEntryId: Long?` and `lastCalloutTimestamp: Long?` as singleton settings (one row per app, not per pattern).
- After a callout fires on entry `E`, suppress callouts on the next 3 entries even if active patterns match.
- Suppressing a callout does not suppress the per-entry observation itself. Per-entry observations always run (ADR-002 §3).

**Why global, not per-pattern.** Concept-locked says "Cooldown of 3 entries on the pattern-callout part only" (singular). Per-pattern cooldown lets two patterns fire callouts on the same entry, which is exactly the noise we are trying to suppress. Global is one tunable knob, not five.

**Pattern selection on a callout-eligible entry.** When multiple active patterns match, pick the one with the highest `supporting_entry_count`. Ties broken by `lastSeenTimestamp` (most recent first). This makes the callout track the strongest pattern, not a random one.

---

## Re-eval interaction (P1)

Per ADR-002, Re-eval re-runs the 3-lens pipeline on a stored entry and may change `tags` or `template_label`. That can change which patterns this entry supports.

**Contract:**
- After Re-eval commits new field values, recompute the entry's pattern matches against the existing Pattern store.
- For patterns the entry no longer supports, remove from `supportingEntryIds` and decrement implied count. If supporting count drops below 3, **the pattern stays in the store but transitions to a hidden state** (new value: `state="below_threshold"` — internal only, not user-facing). It does not get deleted; if a later entry restores the count, it un-hides.
- For new patterns the entry now supports, run a normal upsert (step 6 of detection).

**Why a hidden state instead of delete.** Pattern_id is content-addressable. If we delete a pattern at count 2 and a future entry brings it back to 3, the new INSERT collides with no row. Keeping the row hidden makes the upsert idempotent.

---

## Options Considered

### Option A: Counting-based content-addressable patterns (CHOSEN)

| Dimension | Assessment |
|---|---|
| Complexity | Low — five primitives, one detector pass, deterministic IDs |
| Cost | Trivial at v1 scale (≤500 entries) |
| Demo legibility | High — every callout is sourced, every claim is countable |
| Spec-drift risk | Low — every locked decision in `concept-locked.md`/`PRD.md`/AGENTS.md is preserved |
| Team familiarity | Plain Kotlin; no new deps |

**Pros:** Sourced by construction. Idempotent across re-eval and cold-start sweep. Easy to test (`ADR-002` Q4-style fixture suite extends naturally).
**Cons:** Bounded primitives. Doesn't catch patterns the five primitives can't express. (Mitigation: that's `../backlog.md`.)

### Option B: Embedding-cluster-based pattern detection

Compute embedding clusters over entries (using the STT-E-contingent EmbeddingGemma vector layer), surface clusters as patterns.

| Dimension | Assessment |
|---|---|
| Complexity | High — clustering algorithm choice, threshold tuning, cluster naming |
| Cost | Vector index recompute per detection run |
| Demo legibility | Lower — "this cluster" is harder to source than "this tag pair appeared 4 times" |
| Spec-drift risk | High — STT-E-conditional means patterns disappear if EmbeddingGemma is cut |
| Team familiarity | New territory mid-build |

**Pros:** Catches semantic-similarity patterns even without exact tag overlap.
**Cons:** Pattern detection becomes STT-E-conditional, which fights the locked spec (patterns are P0; embeddings are STT-E-conditional). AGENTS.md guardrail 12 is harder to satisfy from a cluster than from a count.

### Option C: Single-call LLM pattern surface (model-as-detector)

After every 10 entries, send the full corpus to Gemma 4 and ask "what patterns do you see?"

| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | Latency + battery — full-corpus prompt every 10 entries |
| Demo legibility | Inconsistent — model output varies run-to-run |
| Spec-drift risk | High — non-deterministic IDs break ADR-002's `recurrence_link` predicate |
| Team familiarity | OK |

**Pros:** Could find patterns the five primitives miss.
**Cons:** Non-deterministic. Sources are model-narrated, not engine-counted (AGENTS.md guardrail 12 violated). Re-eval doesn't compose.

---

## Trade-off Analysis

The dominant trade-off is **explainability vs. expressive coverage**. Option A is narrow but every claim is bulletproof. Option B/C might catch something Option A misses, but at the cost of either (1) STT-E conditionality or (2) AGENTS.md guardrail 12 violations.

For a 17-day build aimed at the demo and the privacy/sourced-claims story, narrow-but-bulletproof wins on both axes. The five primitives directly cover every callout example already written in `concept-locked.md`, `design-guidelines.md`, and `ux-copy.md` — there is no demo beat we lose by going narrow.

The other significant trade-off is **mark-resolved sticky vs. auto-reopen**. Sticky respects user agency and the "anti-pushy brand" rule. Auto-reopen is more "smart-app" but exactly the kind of feature the user dismissed for a reason. Sticky is the on-brand answer.

---

## Consequences

**Easier:**
- ADR-002's `recurrence_link` resolver predicate has a stable, testable definition.
- Pattern detection has a unit-testable surface (synthetic entry sets → expected pattern IDs).
- The pattern engine output (sourced counts) drops cleanly into the persona-flavored observation generation prompt from ADR-002 §3.
- Re-eval composes — recomputing pattern matches on a single entry is cheap.

**Harder:**
- The five primitives are the ceiling for v1 patterns. If a demo scenario surfaces a needed sixth, this ADR gets superseded, not patched.
- Pattern title generation requires one short model call per new active pattern. Battery cost is small (titles cap at ≤24 chars) but counts against the per-entry budget. ADR-002's foreground/observation prompts already account for this.
- ObjectBox migration from the v1 schema if a future version adds incremental aggregates or per-pattern cooldown.

**Revisit when:**
- Entry counts cross ~1000 and full-recompute every 10 entries starts costing noticeable battery → introduce incremental aggregates.
- A v1.5 demo scenario actually needs tag triples or learned-cluster patterns → write a successor ADR; don't patch this one.
- User feedback shows mark-resolved is too sticky → add `auto-reopen-on-fresh-evidence` semantics (already noted in `../backlog.md`?).

---

## Action Items

**Ordering note.** Per PRD §"Build philosophy: build first, test at failure zones," there is no upfront validation phase — phases run inline with stop-and-test points. Pattern detection's stake is STT-C and STT-D: if the 3-lens extraction does not produce reliable `tags` and `template_label`, pattern detection has nothing to count, and the spec rebuilds.

1. [ ] Phase 1 — add `Pattern` ObjectBox entity per the schema above. Includes `state="below_threshold"` enum value.
2. [ ] Phase 1 — add `lastCalloutEntryId` / `lastCalloutTimestamp` to a singleton settings row.
3. [ ] Phase 3 — implement the five primitives' detection passes in `:core-storage` (or `:core-inference` if the title-generation model call needs proximity to `InferenceCoordinator`; pick during scaffold).
4. [ ] Phase 3 — implement the matching predicate as a per-entry hook (`onEntryCommitted`) so `recurrence_link` resolves cheaply.
5. [ ] Phase 3 — pattern title generation via the observation-generation prompt (ADR-002 §3); cache title on insert, never regenerate in v1.
6. [ ] Phase 3 — global cooldown enforcement on pattern-callout append.
7. [ ] Phase 3 — Re-eval recompute path: on Re-eval commit, recompute matches and update `supportingEntryIds`; flip patterns to/from `below_threshold` as needed.
8. [ ] Phase 3 — unit-test fixture suite mirroring ADR-002 Q4: synthetic entry sets covering each primitive's threshold edge, the cooldown reset, dismiss/snooze/resolved transitions, and the snoozed-until expiry path.
9. [ ] Phase 4 — Patterns list / Pattern Detail UI per `design-guidelines.md` and `ux-copy.md`; un-snooze affordance lands here.
10. [ ] Update `architecture-brief.md` §"AppContainer Ownership" — `PatternStore` ownership note now references this ADR for state-machine behavior.

---

### Addendum (2026-05-11) — "distinct contexts" operational definition

Story 3.5's implementation initially read "≥2 distinct contexts" as ≥2 distinct
`template_label` values across the supporting set. That narrowed legitimate corpus shapes
out of the pattern engine — entries with `templateLabel = null` (the common case before
extraction has stabilized) were rejected wholesale, and a stretch of four `aftermath`-only
entries with the same vocab token collapsed to a context-count of 1.

The original ADR text "the diversity requirement so a single long sentence using 'tired'
four times does not become a pattern" describes the within-entry case. `vocabTokensFor`
returns a `Set` per entry, so each entry contributes once per token by construction — the
≥4 distinct-entry threshold already enforces the diversity guard.

**Operational definition (v1):** "≥N entries with ≥2 distinct contexts" reduces to ≥N
distinct entries containing the stemmed token. No additional template-label diversity
requirement. The `VOCAB_MIN_CONTEXTS` constant is removed; `VOCAB_THRESHOLD = 4` is the only
gate.

### Addendum (2026-05-11) — `latestCalloutText` is frozen on silent-update branches

Step 6's UPDATE branches:
- `active` → refreshes `supportingEntryIds`, `lastSeenTimestamp`, **and** `latestCalloutText`.
- `snoozed` / `dismissed` / `resolved` → refreshes `supportingEntryIds` and `lastSeenTimestamp`
  silently. `latestCalloutText` is **not** rewritten.

This preserves the user-facing string from the moment the row left ACTIVE. A v1.5
un-dismiss or un-snooze surface will show what the user last saw, not arbitrary later
evidence.

### Addendum (2026-05-11) — kebab-case normalization in signature hash

The "lowercase, kebab-case, sorted" wording under §"`pattern_id` generation" is normative.
Implementation routes all label / tag / topic inputs through a single `TagNormalize.kebab`
helper that lowercases, replaces whitespace + underscores with hyphens, collapses repeats,
and trims edge hyphens. The same helper is called in the signature builder, the detector's
grouping keys, and the matcher's compare path so the hash inputs cannot drift between
subsystems.

### Addendum (2026-05-11) — callout cooldown is counted per committed entry, globally

§"Cooldown (callout-side only, global)" reads "After a callout fires on entry E, suppress
callouts on the next 3 entries even when active patterns match." Operational reading: the
window is wall-clock-by-entry — every entry committed during the window decrements the
counter, regardless of whether that entry would have matched a pattern.

Implementation: `PatternDetectionOrchestrator.onEntryCommitted` consumes one slot at the
top of every call when the cooldown is active, then short-circuits. Only when the cooldown
is permitted does callout selection run. A blank `latestCalloutText` on a matched pattern
logs a warning and returns null without firing — it does not start a fresh window.

Trade-off rationale: a streak of unrelated entries between two matches can expire the
window invisibly, which means two semantically-adjacent callouts can land back-to-back if
the user logged unrelated entries between them. To the user that reads as appropriate
spacing (other entries did go by); to the engine it's the simpler invariant — one rule,
no carve-out, deterministic wall-clock pacing. The "anti-pushy brand" rule is satisfied
either way because the perceived nag-rate is what the user types between, not what the
counter says internally.

### Addendum (2026-05-13) — user actions reduced to two; resolution becomes model-detected

`§Lifecycle & state transitions` and `§Mark-resolved is sticky for the demo` are superseded
for v1 forward by `docs/spec-pattern-action-buttons.md` and `docs/ux-copy.md` §"Pattern List".

What changes:

- User-facing actions on a pattern collapse from three (`Dismiss` / `Snooze 7 days` /
  `Mark resolved`) to **two** (`Skip` / `Drop`). The `Mark resolved` affordance is
  removed entirely — a pattern closing should be earned by behavioral evidence, not
  self-reported. That mirrors the core product framing: Vestige observes, it does not
  validate.
- `PatternState` enum renames: `RESOLVED` → `CLOSED`, `DISMISSED` → `DROPPED`. The new
  `CLOSED` state is **model-detected only** — no user transition reaches it in v1. The
  staleness check that lands a pattern in `CLOSED` is deferred to v1.5 (`backlog.md`
  §`pattern-auto-close`). v1 ships with the `CLOSED` enum value reserved but unreachable
  so the v1.5 transition is a backfill plus check, not an ObjectBox migration.
- New field `PatternEntity.snoozedUntil: Long?` (epoch ms; null when not snoozed). Cold-
  start sweep transitions `SNOOZED → ACTIVE` when `snoozedUntil` has elapsed; no
  WorkManager job (per spec §P0.4).
- Snackbar copy: `Dropped.` and `Skipped.` retain `Undo`. Model-detected `Closed`
  is silent — no snackbar, visible on next list load.
- Section headers: `ACTIVE` / `SKIPPED · ON HOLD` / `CLOSED · DONE` / `DROPPED` per
  `ux-copy.md` §"Pattern List / Section headers". The Mist-era `RESOLVED · FADED` and
  `DISMISSED` labels retire with `Mark resolved`.

What does not change:

- Detection algorithm (five primitives, 90-day window, ObjectBox persistence).
- Callout cooldown semantics (Addendum 2026-05-11 still applies).
- Snoozed-on-stale-evidence rule (the snooze wake-up still requires the pattern to still
  meet threshold; spec §P0.4 honors this implicitly via the detection re-emerge path).
- Re-eval interaction with `below_threshold`.

Story 4.8 carries the implementation. Story 3.8's `markResolved` API path and the
`PatternAction.MARKED_RESOLVED` enum value retire with that story; both stay live in v1
until Story 4.8 lands so existing shipped code keeps compiling.

### Addendum (2026-05-13b) — Restart action; non-active states are reversible; snooze window preserved on undo

Refinement on top of the prior 2026-05-13 Addendum. The two-action user surface
(`Skip` / `Drop`) is extended by a third user-driven control, `Restart`, on any
non-active visible state. See `docs/spec-pattern-action-buttons.md` §P0.3 for the
acceptance criteria.

What changes:

- User-facing actions become `Skip` + `Drop` (on `ACTIVE`) and `Restart` (on
  `SNOOZED` / `DISMISSED` / `RESOLVED`). `MARKED_RESOLVED` / `Done` stays system-only
  (`pattern-auto-close`, v1.5) — the prior addendum's "no user transition reaches
  CLOSED in v1" rule still holds for that specific path.
- `DISMISSED` and `RESOLVED` are no longer terminal in the original "no transition
  out in v1" sense from `§Lifecycle & state transitions`. The persistence shape is
  unchanged — only the set of legal next states expands. The ObjectBox schema does
  not move.
- `Restart` undo restores the **exact pre-restart snapshot**, including the original
  `snoozedUntil` when the prior state was `SNOOZED`. Previously the undo path reset
  `snoozedUntil` to `null`, which would have left the row in `SNOOZED` with no expiry
  — a silent foot-gun (the cold-start sweep would never wake it). `PatternRepo.restart`
  now takes a `previousSnoozedUntil: Long?` and `forceTo(...)` preserves it; a
  `require(snoozedUntilMs != null)` precondition rejects a malformed snooze restore.
- Snackbar copy adds `Pattern is back.` (with Undo) for the Restart path. `Skipped.`
  and `Dropped.` retain their copy from the prior addendum.

What does not change:

- Detection algorithm, callout cooldown, snoozed-on-stale-evidence rule, re-eval with
  `below_threshold` — all carried forward from the prior addenda.
- Story 3.8's `markResolved` API path and the `PatternAction.MARKED_RESOLVED` enum
  value still retire with Story 4.8, per the prior addendum; Restart adds to the user
  surface, it does not change the retirement plan for the resolved-write path.

### Addendum (2026-05-15) — Phase-3 pre-work + verdict surfacing (queued for post-Phase-4)

Companion to the ADR-002 (2026-05-15) personality + observation-depth addendum. The same
audit surfaced two structural gaps on the pattern-detection side. Both are queued for
post-Phase-4 — Scoreboard rebuild lands first. Neither alters the five primitives, the
content-addressable `pattern_id` rule, the matching predicate, or any persisted shape
recorded above.

**`time_of_day_cluster` ships in Phase 2's tail, ahead of the Phase-3 pattern-engine work.**

The user-visible cliff between Phase 2 (per-entry observations only) and Phase 3 (cross-
entry pattern callouts) is the single biggest gap between what the spec promises and what
the user sees. `time_of_day_cluster` is the cheapest of the five primitives to implement
in isolation — one signature (`("goblin", null)`), scan-once over the last 30 days, no tag
enumeration, no commitment-topic enumeration, no vocab-token frequency table.

Implementation lands in `:core-storage` per §"Detection algorithm (run every 10 entries)"
step 6 with the rest of the five-primitive detector stubbed (`emptyList()` returns from each
of the other four enumerators). `PatternDetectionOrchestrator` already carries the cooldown
machinery + tests; wiring `time_of_day_cluster` through it is the integration step. Result:
the Goblin Hours pattern callout becomes the first cross-entry observation users ever see —
turns "shallow per-recording response" into "I see a pattern after my third 3am entry"
without waiting for Phase 3's full pattern-engine landing.

The §"Detection cost" calculus is unchanged (one signature, scan-once is bounded by entry
count, not by the other primitives' enumerator costs). The §"Pattern title generation"
short-model-call path runs only when this single primitive crosses threshold, so the Phase-2
title-generation cost is bounded to one pattern-title model call ever, not per detection
run.

When Phase 3 lands, the other four enumerators wire in alongside; `time_of_day_cluster`'s
implementation does not need a rewrite. The §"ObjectBox `Pattern` entity" schema and the
state machine are the contract this pre-work commits to.

**Verdict chips in entry detail — the convergence resolver becomes a visible product
surface.**

§"`pattern_id` generation" + ADR-002 §"Convergence Resolver Contract / Resolution rules"
write per-field verdicts (`canonical` / `candidate` / `ambiguous` / `canonical_with_conflict`)
that today live in storage and never reach the user except indirectly through the
deterministic-vs-model observation-generation routing. The Reading section per
`concept-locked.md` §Re-eval is described as showing per-lens output collapsed; surface the
**verdict** on each canonical field as a small chip in the same view (`canonical` /
`candidate` / `ambiguous` / `canonical_with_conflict`).

The data exists in ObjectBox today (Story 2.8 ticked, `ResolvedField.verdict` is persisted
per Story 2.12). The work is a Phase-4 read path, not a Phase-3 write path. This is the
demo beat that earns the multi-lens architecture *visually* — judges see "the model
disagreed with itself here" without needing the technical-walkthrough explanation. ADR-002's
sixth-addendum rubric verified `canonical_with_conflict` reaches A4 and B2 on the STT-D
corpus deterministically across three runs; those entries become the demo storyboard
material when this surfacing lands.

Surfacing belongs to Phase 4 (after the Scoreboard rebuild stabilizes per ADR-011) and the
chip styling is owned by `design-guidelines.md`. Recording the data ↔ surface contract
here so the Phase-4 work has the spec it needs.

**Pattern-callout cooldown stays as written; deterministic-observation cooldown
generalizes from it.**

§"Cooldown (callout-side only, global)" is unchanged. ADR-002 (2026-05-15) introduces a
parallel `DeterministicObservationCooldown` keyed by `ObservationEvidence` enum value with
the same 3-entry window and same global semantics as defined here. The two cooldowns
coexist — one gates pattern-callout *appends* (this ADR), the other gates deterministic
observation *kinds* (ADR-002). The §"Why global, not per-pattern" rationale generalizes to
the observation cooldown: per-kind cooldown lets unrelated evidence kinds fire freely while
suppressing the kind that already fired, which is exactly the noise the user-facing
deterministic-observation case needs suppressed. Identical knob shape, different keying
domain.

**What does not change.**

- The five pattern primitives, their thresholds, the matching predicate, the
  content-addressable `pattern_id` rule, the `Pattern` ObjectBox entity, the lifecycle
  state machine (with the 2026-05-13 + 2026-05-13b user-action revisions), the global
  pattern-callout cooldown, the Re-eval `below_threshold` interaction.
- The "no machine-learning-shaped detection in v1" rationale from §"Pattern primitives".
  Surfacing verdicts as chips is reading existing engine output, not adding learned
  detection.
- The `pattern_id` content-addressable rule. Pre-shipping `time_of_day_cluster` ahead of
  the other four primitives does not affect its hash inputs (the signature is
  `("goblin", null)`, defined in §"Pattern primitives" v1 row).

Implementation queued for post-Phase-4. Story file gets entries per item; tracking lives
in stories, work-decision rationale lives here.
