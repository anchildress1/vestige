## ADR-010 — EmbeddingGemma runtime swap from LiteRT-LM to LiteRT (supersedes ADR-001 §Embeddings)

**Status:** Accepted
**Date:** 2026-05-11
**Deciders:** Ashley (sole owner). AI implementors read this as authoritative.
**Supersedes:** `adrs/ADR-001-stack-and-build-infra.md` §"Locked Stack" Embeddings row, and the same ADR's §Q6 description of the embedding-artifact contract.
**Touched stories:** `docs/stories/phase-3-memory-patterns.md` Story 3.2 done-when bullet 1 ("loads via LiteRT-LM in `:core-inference`").

---

### Context

ADR-001 §"Locked Stack" pinned EmbeddingGemma 300M as "via LiteRT-LM — contingent on STT-E." Story 3.2 inherited that wording and asked for the artifact to load via the `com.google.ai.edge.litertlm:litertlm-android` SDK already in use for Gemma 4 E4B-audio.

The Hugging Face artifact for `litert-community/embeddinggemma-300m` (browsed 2026-05-11) ships **only `.tflite`** flatbuffers — eight sequence-length variants (seq256 / seq512 / seq1024 / seq2048) crossed with three target hardware fans (generic, Tensor G5, MediaTek MT6991/MT6993, Snapdragon SM8550/SM8650/SM8750/SM8850) plus a `sentencepiece.model` tokenizer. **No `.litertlm` flatbuffer exists in that repo.**

LiteRT-LM is the language-model serving runtime — it expects `.litertlm` flatbuffers that embed both weights and the conversation/streaming machinery. An encoder-only embedding model has no chat machinery. Loading the EmbeddingGemma `.tflite` through `litertlm-android` would either fail to parse or expose the artifact through an API surface that does not match what an embedding consumer needs (single forward pass, return a vector — no `Conversation`, no `Engine.createConversation()`).

The lower-level **LiteRT** runtime (`com.google.ai.edge.litert:litert`) is the right primitive for a `.tflite` encoder model. ADR-001 §"Locked Stack" already cites this artifact and rejects it as a *direct* dependency on the basis that adding it would collide with `libLiteRt.so` packaged inside `litertlm-android` at `:app:mergeDebugNativeLibs`. That rejection was correct *for the LLM runtime* but did not contemplate a second-runtime embedding case.

### What this ADR closes

1. **Which runtime loads EmbeddingGemma.** Switch from `litertlm-android` to direct `com.google.ai.edge.litert:litert` (LiteRT, no `-lm` suffix).
2. **Native-lib collision resolution.** Document the strategy the implementation branch must follow so the second runtime does not duplicate `libLiteRt.so`.
3. **Artifact selection.** Which of the eight `.tflite` variants ships with v1 if STT-E passes.
4. **Story 3.2 implications.** The story description must be edited to point at LiteRT, not LiteRT-LM. Done-when bullets that referenced `Embedder.embed(text)` returning a `FloatArray` of ~768d remain valid — only the loader changes.

---

### Decision

**EmbeddingGemma 300M loads through the LiteRT (TFLite) runtime directly.** The pre-1.0 `com.google.ai.edge.litert:litert` artifact (version per `runtime-research.md` follow-up) is added to `:core-inference` as a separate direct dependency. The two runtimes ship side-by-side in the APK.

Artifact pick for v1 (if STT-E passes): **`embeddinggemma-300M_seq512_mixed-precision.tflite` (generic, 179 MB).** Reasoning:
- Vestige's entry corpus skews short (capture sessions are minutes, not hours) — `seq512` covers the median entry comfortably and leaves headroom on outliers.
- The hardware-fan variants (Tensor G5 / MediaTek / Snapdragon) optimize for specific NPUs that LiteRT may or may not target depending on backend selection at runtime. The generic variant ships on every device; backend choice happens in the runtime config, not in the artifact selection.
- The 248 MB Tensor G5 `seq2048` variant is the largest alternative — its 60 MB tail does not pay for itself when most entries fit in 512 tokens.

Tokenizer: bundle `sentencepiece.model` alongside the artifact. Same `ModelArtifactStore` contract — separate file, separate SHA-256 entry in `manifest.properties`, separate retry budget, same allowed-hosts allowlist.

---

### Native-lib collision resolution

ADR-001's stated risk was the `libLiteRt.so` symbol collision at `:app:mergeDebugNativeLibs`. Two strategies, in order of preference:

1. **Per-runtime ABI selection in `packagingOptions`.** Configure `pickFirst "**/libLiteRt.so"` (or the equivalent jniLibs pick) so only one `.so` lands in the APK. LiteRT-LM is the larger runtime and bundles the same underlying LiteRT — `pickFirst` resolving to LiteRT-LM's copy should satisfy both loaders, since the LiteRT runtime is what LiteRT-LM uses internally.
2. **Manual `excludes` on the LiteRT artifact's bundled `.so`.** If `pickFirst` produces a runtime failure (LiteRT initializing against a LiteRT-LM-bundled `.so` that has different symbol versioning), exclude `libLiteRt.so` from the LiteRT artifact and rely on the LiteRT-LM-bundled version. Treat that as the v1 ship contract; revisit if version drift breaks it.

The implementation branch verifies via Story 3.2's smoke test (cosine similarity > 0.6 between "I crashed at 3pm" and "I felt overwhelmed at 3pm"). If both strategies fail, the existential stop-and-test fires and embeddings defer to v1.5.

---

### Consequences

**Easier:**
- Story 3.2 can be written without the format-mismatch obstacle.
- STT-E (Story 3.3) becomes a runnable test rather than a paper exercise.
- `:core-inference` keeps the LiteRT-LM engine for chat / per-entry observations and adds a thin `Embedder` wrapper for the encoder path.

**Harder:**
- `:app`'s AAB / APK now bundles two runtimes (LiteRT-LM + LiteRT) and one extra ~180 MB artifact. APK size review per ADR-001 §"Locked Stack" Distribution row becomes a Phase 5 (`docs/stories/phase-5-demo-optimization.md`) audit item.
- Backend selection (CPU / GPU / NPU) is per-runtime — `BackendChoice` in `LiteRtLmEngine` does not auto-propagate to the `Embedder`. The implementation branch defines a separate `EmbedderBackend` configuration and surfaces it in `AppContainer`.

**Revisit when:**
- Google publishes an `EmbeddingGemma`-class `.litertlm` artifact under `litert-community/`. At that point this ADR is supersedable: switch back to single-runtime serving and drop the second SDK.
- The `pickFirst` / `excludes` resolution drifts on a LiteRT or LiteRT-LM bump — the `.so` collision is the load-bearing assumption.

---


---

### Addendum (2026-05-11) — load EmbeddingGemma via `localagents-rag`, not raw LiteRT

The decision above named `com.google.ai.edge.litert:litert` as a direct dependency, with an implicit assumption that the consumer would also pull in a SentencePiece tokenizer. Inspection of the EmbeddingGemma artifact (`huggingface.co/litert-community/embeddinggemma-300m`) confirmed the tokenizer is a separate `sentencepiece.model` protobuf — not embedded in the `.tflite` flatbuffer, not exposed through any signature.

There is no first-party Google SentencePiece library for Android. The `tensorflow-lite-support` tokenizers are deprecated; `mediapipe:tasks-text:TextEmbedder` requires TFLite Model Metadata that EmbeddingGemma does not ship; `ai.djl.sentencepiece` ships only x86/desktop natives.

`com.google.ai.edge.localagents:localagents-rag:0.3.0` is the **active Google-supported path**. The AAR bundles `libgemma_embedding_model_jni.so` (~23 MB native lib that statically links both the LiteRT TFLite runtime and `sentencepiece::SentencePieceProcessor`) and exposes `GemmaEmbeddingModel(modelPath, tokenizerPath, useGpu)` returning a `ListenableFuture<ImmutableList<Float>>` per call via the `Embedder<String>` interface.

**Net effect for v1:**
- ADR-010's premise (use a runtime distinct from LiteRT-LM for the encoder) holds — the bundled JNI library is self-contained LiteRT.
- The collision risk this ADR worried about (`libLiteRt.so` in two AARs) does not apply: `libgemma_embedding_model_jni.so` is a single combined `.so`, statically linked, with no shared symbol with `libLiteRt.so` from `litertlm-android`.
- `EmbedderBackend` (CPU/GPU) is configured via the SDK's `useGpu` constructor flag, not via a per-runtime `BackendChoice` enum.
- The SDK pulls in `okhttp` and `guava` transitively. OkHttp is unused on Vestige's code path (NetworkGate already owns the only HTTP primitive); the dependency sits on the classpath but the seal-by-default `NetworkGate` posture from ADR-001 §Q7 is unaffected.

**Revisit when:**
- Google publishes a standalone Android-native SentencePiece library that pairs cleanly with raw LiteRT — at that point the direct-LiteRT path from this ADR's main body becomes viable again without the AAR size cost.
- `localagents-rag` adds Gemini-cloud surfaces that NetworkGate cannot audit by classpath grep. If that happens, fall back to a fork or excludes-only build.
