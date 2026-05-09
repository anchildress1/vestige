# Android Runtime Research — Gemma 4 E4B

## Recommendation: LiteRT-LM (direct SDK)

Google's official edge-LLM runtime. A pre-built E4B model exists on Hugging Face: `litert-community/gemma-4-E4B-it-litert-lm`. When the Android scaffold exists, pin the LiteRT-LM Android dependency in `gradle/libs.versions.toml`. CPU/GPU acceleration is handled by LiteRT-LM via XNNPack and ML Drift.

We are **not** using MediaPipe LLM Inference as a wrapper. Earlier drafts of this doc paired the two; the locked stack is LiteRT-LM SDK only. `AGENTS.md` guardrail 13 forbids a second inference runtime.

### Why this wins for a 17-day build
- No native compilation needed — write Kotlin app code
- Pre-converted model artifact saves the conversion step entirely
- Official guides + AICore Developer Preview as a bonus path
- Hardware acceleration is automatic

## Surprise from the research
**E2B and E4B are effective-parameter edge models, not the 26B MoE family.** Google's docs describe E2B/E4B as small models for ultra-mobile, edge, and browser deployment. Their "E" label means effective parameters; the larger on-disk footprint comes from per-layer embeddings. The model-choice argument should be: E4B is the strongest small Gemma 4 model built for local multimodal edge use, not "MoE on a phone."

## Performance (from research)
- LiteRT-LM E4B artifact is **3.66 GB** on disk according to the Hugging Face model card.
- Published LiteRT-LM benchmark data currently targets newer reference devices than the S24 Ultra. Treat all S24 Ultra latency as a stop-and-test measurement, not a promise.
- Gemma 4 E4B Q4-class inference has an approximate 5 GB static-weight memory requirement before runtime/context overhead in Google's overview; LiteRT-LM memory mapping can reduce working memory on supported backends.
- Recommended device RAM for the Vestige demo: 12 GB. Minimum post language: Android 14+, 8 GB RAM, 6 GB free storage; external devices are "best effort."

## Alternatives considered

### llama.cpp + GGUF via JNI/Kotlin
- **Pros:** runs on more devices, mature, full control, established Kotlin libraries (kotlinllamacpp, llama-jni)
- **Cons:** native compilation, no NPU, more boilerplate, slower on devices that have NPUs
- **Verdict:** **Not a v1 fallback during normal build.** If LiteRT-LM fails existentially at the relevant stop-and-test or early Phase 1, stop and write a superseding ADR; do not silently swap runtimes mid-build. Otherwise defer to v1.5. Adoption requires a superseding ADR per `AGENTS.md` guardrail 13.

### MLC LLM
- **Verdict:** Skip. It's a finished Play Store chat app, not an embeddable library. Wrong shape.

### AICore Developer Preview
- **Pros:** System-shared model, smallest app footprint
- **Cons:** Pixel-only / limited device set, beta API
- **Verdict:** **Research-only / post-v1 exploration.** Do not implement in v1 unless a later ADR explicitly supersedes the LiteRT-LM-only locked stack. Recorded here for historical context, not as a buildable path.

## Around the model
- **Embeddings:** EmbeddingGemma 300M via LiteRT (~200 MB quantized, sub-15 ms inference). Pre-built `litert-community/embeddinggemma-300m`. Same runtime as the main model. Contingent on STT-E (visible advantage over tag-only retrieval on prepared sample data).
- **Storage:** ObjectBox (native Android, native vector engine — less wiring than SQLite + sqlite-vec for MVP)
- **Speech-to-text:** Gemma 4 native audio modality. Model-level audio prep target from Google's Gemma docs: mono 16 kHz float32 samples normalized to `[-1, 1]`, max 30 seconds per clip. Android implementation path is the **STT-A stop-and-test** in Phase 1 because LiteRT-LM documents `Content.AudioBytes` / `Content.AudioFile` without spelling out byte packing. **No `SpeechRecognizer`, no third-party STT.** `concept-locked.md` §Stack owns the product rule; `adrs/ADR-001-stack-and-build-infra.md` §Q4 owns the Android encoding contract.
- **Source-of-truth journal text:** Markdown files on local storage; ObjectBox indexes them

## Risks
- **Device RAM** — E4B is reference-device territory. If an external test device cannot run E4B acceptably, document it as a known limitation; do not silently add an E2B fallback to v1.
- **Battery / thermal** — sustained inference warms the device. Design for short bursts (probe → answer → summarize), not continuous generation.
- **Model file size** — 3.66 GB download is real. First-run UX needs a Wi-Fi-required gate and a clear progress UI.
- **AICore preview availability** — if we use it as a path, it's beta and can break.

## Sources
- Android Developers Blog: Gemma 4 — https://android-developers.googleblog.com/2026/04/gemma-4-new-standard-for-local-agentic-intelligence.html
- Gemma 4 model overview — https://ai.google.dev/gemma/docs/core
- Gemma audio understanding — https://ai.google.dev/gemma/docs/capabilities/audio
- LiteRT-LM Android guide — https://ai.google.dev/edge/litert-lm/android
- LiteRT-LM repo — https://github.com/google-ai-edge/LiteRT-LM
- HF model card — https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
- Keras Gemma4AudioConverter — https://keras.io/keras_hub/api/models/gemma4/gemma4_audio_converter/
- MindStudio E2B/E4B edge guide — https://www.mindstudio.ai/blog/gemma-4-edge-deployment-e2b-e4b-models
- llama.cpp Android docs — https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- Android RAM tier guide — https://dev.to/engineeredai/run-a-local-llm-on-android-what-ram-tier-you-need-and-which-models-actually-work-2nkp
