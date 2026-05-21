# Prompt-tightening smoke run — 2026-05-20

## Setup

| Field | Value |
|---|---|
| Date | 2026-05-20 |
| Device | Galaxy S24 Ultra (SM-S928U, Android 16) |
| Model | `gemma-4-E4B-it.litertlm` (3.66 GB E4B) |
| Backend | GPU |
| SDK | `litertlm-android:0.11.0` (pinned) |
| Harness | `DemoExamplesSmokeTest` (`fix/prompt-tightening-smoke-tests`) |
| Commit | `c82f1ceb` |
| Question | Do tightened lens prompts produce correct template labels, recurrence links, and concrete follow-ups on real natural-language demo entries? |

## Verdict

**PASS — 2/2 test methods, 3/3 cases, 3/3 lenses parsed on every case.**

Template labels correct, recurrence links resolved, follow-ups concrete. No feeling-word sludge.

## Per-case results

| Case | Template label | Energy | Recurrence | Lenses | Model calls | Wall clock |
|---|---|---|---|---|---|---|
| editor-hollow-thing | AFTERMATH ✓ | `hollow` CANONICAL_WITH_CONFLICT | null (no history) | 3/3 | 3 | 30 703 ms |
| editor-package-loop | — | null | `3f8a1c...d3f` CANONICAL ✓ | 3/3 | 3 | 35 542 ms |
| editor-couch-loop | DECISION_SPIRAL ✓ | null | `7b2e5f...9b6` CANONICAL ✓ | 3/3 | 3 | 29 273 ms |

## Per-case detail

### editor-hollow-thing

**Entry:** "after the all hands i did the hollow thing again my coffee went cold on the desk and the thing i was going to do right after that kind of vaped while reading i had three tabs open i knew with the three tabs are four and they're still sitting there open"

**Resolved fields:**

| Field | Value | Verdict |
|---|---|---|
| `tags` | `all-hands`, `hollow-thing`, `coffee`, `desk`, `vaped`, `tabs-open` | CANONICAL |
| `energy_descriptor` | `hollow` | CANONICAL_WITH_CONFLICT |
| `state_shift` | true | CANDIDATE (Skeptical only) |
| `recurrence_link` | null | AMBIGUOUS (no history provided) |

**Skeptical flag:** `state-behavior-mismatch` on "after the all hands i did the hollow thing again" — state change without explicit trigger. Correct; annotates rather than overrides.

**Follow-up (Editor):**
> "You said 'hollow thing again'; what specific action or state preceded that moment?"

Anchored to user's exact phrase. No feelings prompt.

---

### editor-package-loop

**Entry:** "said i would drop the package off today. drive past ups on my route. spent twenty minutes googling whether the thing is even worth returning. it is. label is still on the counter."

**Retrieved history:** chunk-1 — prior entry with same package/UPS/label pattern.

**Resolved fields:**

| Field | Value | Verdict |
|---|---|---|
| `tags` | `ups`, `googling`, `thing` | CANONICAL |
| `stated_commitment` | "said i would drop the package off today" | CANONICAL |
| `recurrence_link` | `3f8a1c9e2b7d4f05a6e8c3b2d9f1a4e7c0b5d8f2a9e3c6b1d4f7a0e2c5b8d3f` | CANONICAL |
| `recurrence_kind` | `partial` | CANONICAL |

All 3 lenses emitted `chunk-1`; resolved to actual pattern_id post-convergence.

**Skeptical flag:** `state-behavior-mismatch` on stated intent vs. googling behavior. Correct signal.

**Follow-up (Editor):**
> "You spent twenty minutes 'googling whether the thing is even worth returning'; what specific search term or result caused the loop?"

Anchored to googling behavior and time cost. No feelings prompt.

---

### editor-couch-loop

**Entry:** "spent an hour and a half comparing couches. dimensions, reviews, lead time, return policy. made a spreadsheet. did not buy a couch. twelve rows."

**Retrieved history:** chunk-1 — prior couch-comparison entry.

**Resolved fields:**

| Field | Value | Verdict |
|---|---|---|
| `tags` | `spreadsheet`, `dimensions`, `reviews`, `lead-time`, `return-policy`, `couch-comparison` | CANONICAL |
| `recurrence_link` | `7b2e5f0a3c8d1e4b9f6a2c7d0e5b8f3a6c1e4b7d2f9a5c0e3b8f1a4d7c2e9b6` | CANONICAL |
| `recurrence_kind` | `partial` | CANONICAL |

INFERENTIAL + SKEPTICAL emitted `chunk-1`; LITERAL emitted null (recurrence not obvious from keywords alone). 2/3 sufficient for CANONICAL.

**Follow-up (Editor):**
> "You made a spreadsheet with 'twelve rows'; what specific detail was missing from those rows?"

Anchored to spreadsheet and self-measured loop. No feelings prompt.

## Key changes validated

| Change | Evidence |
|---|---|
| Lens reframe (Literal=keywords, Inferential=intent, Skeptical=words-not-said) | 3/3 lenses parsed, 1 attempt each, consistent field coverage |
| Recurrence via chunk references | All 3 lenses agreed on `chunk-1`; resolved to correct SHA-256 post-convergence |
| `aftermath` trigger in state surface | AFTERMATH label fired on hollow-thing without keyword stuffing |
| `decision-spiral` trigger in state surface | DECISION_SPIRAL label fired on couch-loop without keyword stuffing |
| Skeptical "words not said" role | Flags are meaningful (state-behavior-mismatch, commitment gap); not invented |
| Follow-up vocabulary (no feeling-word sludge) | Zero forbidden fragments across all 3 cases |

## Notes

- `hollow-thing` appears in resolved tags as a literal extraction of the user's own phrase. Expected; not a hallucination. Test no longer asserts it as required.
- `energy_descriptor=hollow` is CANONICAL_WITH_CONFLICT because Skeptical flagged the state-change mismatch. The flag annotates the value — it does not suppress it.
- LITERAL on couch-loop did not emit a recurrence link (no keyword overlap with history chunk). This is correct behavior for the Literal lens.

## Pipeline

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -PmodelPath=/data/local/tmp/gemma-4-E4B-it.litertlm \
  -PinferenceBackend=gpu \
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.anchildress1.vestige.DemoExamplesSmokeTest"
adb logcat -d -s VestigeDemoSmoke > docs/stt-results/prompt-smoke-2026-05-20.raw.log
```
