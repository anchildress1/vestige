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
- Android: minSdk 31 / targetSdk 35 / compileSdk 35, JVM toolchain 25 (Java source/target compat 17)

## Prerequisites

| Tool | Required for | Install |
|---|---|---|
| JDK 25 LTS (Temurin) | Gradle runtime + Kotlin toolchain | `brew install --cask temurin` |
| Android SDK + `adb` | build + install on device | Android Studio, or `brew install --cask android-commandlinetools` |
| System Gradle | one-time wrapper bootstrap | `brew install gradle` |
| `lefthook` | git hooks | `brew install lefthook` |
| `gitleaks` | secret scan | `brew install gitleaks` |
| `actionlint` | workflow lint | `brew install actionlint` |
| `ktlint` | format + lint Kotlin | `brew install ktlint` |
| `detekt` | static analysis Kotlin | `brew install detekt` |
| `gh` | repo ops | `brew install gh` |

`ANDROID_HOME` must be set and `$ANDROID_HOME/platform-tools` must be on `PATH` so `adb` resolves.

## Build

```bash
make install          # bootstrap gradle wrapper, install lefthook hooks
make doctor           # verify local toolchain and environment variables
make build            # assemble debug APK
make test             # unit tests + Kover XML coverage + 80% verification
make lint             # ktlint + detekt + Android lint
make verify           # lint + test + build + staged secret scan
make ci               # full local check (lint + test + build)
make clean
```

## Run on a device

Reference device: Galaxy S24 Ultra. Anything modern Android 12+ works.

### One-time phone setup

1. **Settings → About phone** → tap **Build number** 7 times to unlock developer options.
2. **Settings → Developer options** → enable **USB debugging**. Optional: enable **Wireless debugging** if you'd rather not cable up.
3. (Optional) **Stay awake** while charging — speeds iteration.

### Connect

USB:

```bash
adb devices
# expect: <serial>    device
# if "unauthorized", accept the prompt on the phone (check "Always allow")
```

Wireless (Android 11+):

```bash
# On phone: Developer options → Wireless debugging → Pair device with pairing code
adb pair <ip:port>      # use the pair port + 6-digit code shown on phone
adb connect <ip:port>   # then use the connect port shown on phone
adb devices             # verify
```

### Install + launch

```bash
./gradlew :app:installDebug
adb shell monkey -p dev.anchildress1.vestige -c android.intent.category.LAUNCHER 1
```

`installDebug` builds and installs in one step. The `monkey` invocation just opens the launcher activity without you having to tap the icon.

Manual APK install:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Tail logs

```bash
adb logcat -s "VestigeApplication:*" "AndroidRuntime:E" "*:F"
```

Filters to Vestige-tagged logs plus crashes only.

### Reinstall clean

```bash
adb uninstall dev.anchildress1.vestige
./gradlew :app:installDebug
```

### Troubleshooting

| Symptom | Fix |
|---|---|
| `adb: command not found` | `export PATH="$ANDROID_HOME/platform-tools:$PATH"` in your shell profile |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK didn't include `arm64-v8a`. Verify with `unzip -l app/build/outputs/apk/debug/app-debug.apk \| grep arm64-v8a` |
| `INSTALL_FAILED_USER_RESTRICTED` | Disable **Verify apps over USB** in Developer options |
| App crashes on launch | `adb logcat AndroidRuntime:E *:S` for stack trace |
| Themed monochrome icon on Android 13+ | Expected — placeholder icon; final design lands Phase 6 |

## Privacy

Zero outbound network calls during normal operation. Model download is the only network event. See PRD §Privacy for the verification protocol.

## License

[Polyform Shield 1.0.0 + Supplemental Terms](LICENSE). Source-available, non-commercial. See LICENSE for the full grant and exceptions.
