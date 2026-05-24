# Changelog

## 1.0.0 (2026-05-24)


### Features

* **core-inference:** foreground capture call + parser (Story 2.2) ([#6](https://github.com/anchildress1/vestige/issues/6)) ([503cc23](https://github.com/anchildress1/vestige/commit/503cc231793a3dd3b1e1d0a15127b2e68e871ce7))
* **core-inference:** persona switching during session (Story 2.3) ([#7](https://github.com/anchildress1/vestige/issues/7)) ([f36b1f7](https://github.com/anchildress1/vestige/commit/f36b1f772cea54119f113d1c4a9fa88d360095ca))
* final-polish UI redesign + model-readiness correctness ([#46](https://github.com/anchildress1/vestige/issues/46)) ([8c18af6](https://github.com/anchildress1/vestige/commit/8c18af66d643d66bfd695a176e03c47ecba3d50e))
* **inference:** single-pass 3-lens extraction with convergence resolver ([#55](https://github.com/anchildress1/vestige/issues/55)) ([9bd2c23](https://github.com/anchildress1/vestige/commit/9bd2c23c66bc1c181d5353a1f171c0bb1abadb7d))
* **patterns:** Story 4.8 — Polished Pattern List + Pattern Detail ([#34](https://github.com/anchildress1/vestige/issues/34)) ([8bd03ff](https://github.com/anchildress1/vestige/commit/8bd03ff611082a2a2fbfebbd5597a62ed3aa8ce9))
* **phase-2:** agent-emitted template labels (Story 2.10) ([#13](https://github.com/anchildress1/vestige/issues/13)) ([8f9503b](https://github.com/anchildress1/vestige/commit/8f9503bf5a31ad2561108b5ec73db70b557236ae))
* **phase-2:** background extraction lifecycle service (Story 2.6.5) + ADR-006/007 ([#11](https://github.com/anchildress1/vestige/issues/11)) ([e723a44](https://github.com/anchildress1/vestige/commit/e723a44f8e47aa8ddda4d86c568fbb023a7c7ffe))
* **phase-2:** background extraction worker (Story 2.6) ([#10](https://github.com/anchildress1/vestige/issues/10)) ([e81eb0e](https://github.com/anchildress1/vestige/commit/e81eb0e028afb87445d3e73269692bfdcf90201e))
* **phase-2:** capture session + transcript (Story 2.1) + dependabot ([#3](https://github.com/anchildress1/vestige/issues/3)) ([ac26924](https://github.com/anchildress1/vestige/commit/ac269244a3353f4ea92de72ab6ba2b24418e696f))
* **phase-2:** close Phase 2 — ADR-009 + Stories 2.12 / 2.13 + save-flow + coverage gate ([#18](https://github.com/anchildress1/vestige/issues/18)) ([9404e2e](https://github.com/anchildress1/vestige/commit/9404e2ef5993bd0d77974165850a8a610b542e88))
* **phase-2:** convergence resolver (Story 2.8) ([#12](https://github.com/anchildress1/vestige/issues/12)) ([0f9d841](https://github.com/anchildress1/vestige/commit/0f9d841b9b27b7d1b11ba650d068aa22c9e227b6))
* **phase-2:** multi-lens prompt assembly (Story 2.5) ([#9](https://github.com/anchildress1/vestige/issues/9)) ([cc59e14](https://github.com/anchildress1/vestige/commit/cc59e14fb3d3deaa6cab9496e3fe66a006c72a0a))
* **phase-2:** Stories 2.15/2.16/2.18/2.6.6/2.19 — MTP, streaming, retrieval history, concurrent inference (Path C) ([#44](https://github.com/anchildress1/vestige/issues/44)) ([40fc261](https://github.com/anchildress1/vestige/commit/40fc261d306084e64653567acc79d693564aa16a))
* **phase-2:** Story 2.18 — retrieval history into foreground follow-up (option C) ([#43](https://github.com/anchildress1/vestige/issues/43)) ([3f55645](https://github.com/anchildress1/vestige/commit/3f55645ed52b40ee15c9fa6c48979d2135a38c02))
* **phase-2:** STT-B fallback (Story 2.4) + ADR-004 + ADR-005 ([#8](https://github.com/anchildress1/vestige/issues/8)) ([c631d41](https://github.com/anchildress1/vestige/commit/c631d41a200c42d3e5ef9a399c14a6d20d5f6f5e))
* **phase-2:** STT-C/STT-D harnesses (Stories 2.7 + 2.9) ([#14](https://github.com/anchildress1/vestige/issues/14)) ([97a9898](https://github.com/anchildress1/vestige/commit/97a9898f105db77a232e90292e03cffb0e2f4a94))
* **phase-3:** pattern engine — detector, lifecycle, orchestrator, atomic cooldown reservation ([#20](https://github.com/anchildress1/vestige/issues/20)) ([03e1e74](https://github.com/anchildress1/vestige/commit/03e1e746c773b831513a646075cf34ed23e446d1))
* **phase-3:** patterns UI — list + detail (Stories 3.9 + 3.10) ([#24](https://github.com/anchildress1/vestige/issues/24)) ([4eeca6c](https://github.com/anchildress1/vestige/commit/4eeca6c80d8a92f4c873236286cb82a674d08302))
* **phase-3:** RetrievalRepo keyword + tag + recency baseline (Story 3.1) ([#19](https://github.com/anchildress1/vestige/issues/19)) ([0747765](https://github.com/anchildress1/vestige/commit/0747765be2826e1e282544ff3c4d0a959174b53f))
* **phase-3:** Story 3.11 — embed distilled fields, not raw transcript ([#37](https://github.com/anchildress1/vestige/issues/37)) ([43ebc01](https://github.com/anchildress1/vestige/commit/43ebc0154b689797fa24521126d9e8f8b9d7fc1b))
* **phase-3:** Story 3.2 — EmbeddingGemma loader + Embedder API ([#21](https://github.com/anchildress1/vestige/issues/21)) ([c0f99f5](https://github.com/anchildress1/vestige/commit/c0f99f5b79a834176490460514ebcff1e7865fe7))
* **phase-3:** STT-E gate + vector schema (Stories 3.3 + 3.4) ([#22](https://github.com/anchildress1/vestige/issues/22)) ([efa7128](https://github.com/anchildress1/vestige/commit/efa71282a896afeba70dcb78cc1dde0201aae363))
* **phase-4:** design language pivot to Scoreboard + Story 4.1.5 rebuild ([#26](https://github.com/anchildress1/vestige/issues/26)) ([e83cfea](https://github.com/anchildress1/vestige/commit/e83cfea932593fb60ed1bba58307074783201e47))
* **phase-4:** P0 completion — model UX, settings, patterns, diagram atlas ([#36](https://github.com/anchildress1/vestige/issues/36)) ([a1ecec2](https://github.com/anchildress1/vestige/commit/a1ecec2f5d66118242ffbb2b37fbb6470980411a))
* **phase-4:** Story 4.1 — design language pass ([#25](https://github.com/anchildress1/vestige/issues/25)) ([d557de0](https://github.com/anchildress1/vestige/commit/d557de0eef6cf99df7c8d67dc24556b5e2830703))
* **phase-4:** Story 4.5 — Capture surface (voice + typed, error chrome, discard, audio cue) ([#30](https://github.com/anchildress1/vestige/issues/30)) ([9f1b0ea](https://github.com/anchildress1/vestige/commit/9f1b0ea1357c8622ca8b9404c5ca96a959d8cd66))
* **phase-4:** Story 4.6 — History list + Capture footer polish ([#32](https://github.com/anchildress1/vestige/issues/32)) ([c6b2bda](https://github.com/anchildress1/vestige/commit/c6b2bda072d1273f6c1ffbe62775842183e4e8e8))
* **phase-4:** Story 4.6 PR A — EntryEntity.durationMs plumbing ([#31](https://github.com/anchildress1/vestige/issues/31)) ([6673a2e](https://github.com/anchildress1/vestige/commit/6673a2e9257493d355ca2b7be2da46e414156772))
* recurrence viability pipeline + canonical→consensus rename ([#60](https://github.com/anchildress1/vestige/issues/60)) ([dc4d643](https://github.com/anchildress1/vestige/commit/dc4d643e91f97a709ede65664d7e43d35da454af))
* **storage:** make ObjectBox the entry source of truth ([#50](https://github.com/anchildress1/vestige/issues/50)) ([b6246b9](https://github.com/anchildress1/vestige/commit/b6246b9478f4dfbc77b1dc28ec901138b0518023))
* temporal pattern callouts, lens receipts, full data export ([#47](https://github.com/anchildress1/vestige/issues/47)) ([115887a](https://github.com/anchildress1/vestige/commit/115887a94440f6a54b557dad7f8dc01def6f43ae))


### Bug Fixes

* **inference:** prompt tightening + chunk-ref recurrence for smoke test ([#53](https://github.com/anchildress1/vestige/issues/53)) ([e96b889](https://github.com/anchildress1/vestige/commit/e96b889d86cf4d59b883cbc769a2f20223526d3f))
* partial-export data contract + Codex pattern hardening ([#51](https://github.com/anchildress1/vestige/issues/51)) ([47d335d](https://github.com/anchildress1/vestige/commit/47d335d63ba41a73e32dbdfc13df9984f1a10b66))
* **patterns:** per-pattern cooldown + Phase 3 detection cadence (ADR-016) ([#48](https://github.com/anchildress1/vestige/issues/48)) ([bdec2f2](https://github.com/anchildress1/vestige/commit/bdec2f2b9120fe1d00821e344be9e2be26691a6b))
* README anchor links + extraction tuning (template_label, goblin, vocab) ([#63](https://github.com/anchildress1/vestige/issues/63)) ([ed345cc](https://github.com/anchildress1/vestige/commit/ed345cc958b0af65dda0dddfaa25bfc152041a18))
* **release:** reset release-please bootstrap to 1.0.0 ([#40](https://github.com/anchildress1/vestige/issues/40)) ([66b929b](https://github.com/anchildress1/vestige/commit/66b929b8989bf4edb4649493b4e0fcabf22ceefb))


### Reverts

* roll back "prompt tightening + chunk-ref recurrence ([#53](https://github.com/anchildress1/vestige/issues/53))" ([792e80e](https://github.com/anchildress1/vestige/commit/792e80e388375c4aa9049c57a8e9c0d528e4a1a5))
