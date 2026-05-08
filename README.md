# Vestige

On-device voice journaling for ADHD adults. Anti-sycophant, behavioral, private.

> Built for the Gemma 4 Challenge. See [`PRD.md`](PRD.md) for the spec, [`AGENTS.md`](AGENTS.md) for AI agent rules.

## Status

Scaffold. Phase 0 validation pending. The full README pass lands later.

## Stack

- Kotlin `2.3.21` + Jetpack Compose (BOM `2026.04.01`)
- Gradle KTS + version catalog (`gradle/libs.versions.toml`)
- Gemma 4 E4B via LiteRT-LM, on-device only
- ObjectBox `5.4.2` (structured tags) + markdown (entry source-of-truth)
- Android: minSdk 31 / targetSdk 35 / compileSdk 35, JVM 17

## Build

```bash
make install          # bootstrap gradle wrapper + lefthook
make build            # assemble debug APK
make test             # unit tests + Kover XML coverage
make lint             # ktlint + detekt
make ci               # full local check (lint + test + build)
```

System deps: `ktlint`, `gitleaks`, `lefthook`, `actionlint`, system Gradle (one-time wrapper bootstrap), JDK 17, `npx`.

## Privacy

Zero outbound network calls during normal operation. Model download is the only network event. See PRD §Privacy for the verification protocol.

## License

[Polyform Shield 1.0.0 + Supplemental Terms](LICENSE). Source-available, non-commercial. See LICENSE for the full grant and exceptions.
