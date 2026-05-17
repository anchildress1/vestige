.PHONY: setup install bootstrap-wrapper doctor build assemble reinstall _reinstall_base push-model seed-entries logcat test lint format ktlint-format ktlint-check detekt android-lint secret-scan commitlint verify-no-telemetry verify ci clean

GRADLE := ./gradlew
KTLINT := $(or $(shell command -v ktlint 2>/dev/null), $(HOME)/.local/bin/ktlint)
DETEKT := $(or $(shell command -v detekt 2>/dev/null), $(HOME)/.local/bin/detekt)
DETEKT_INPUTS := app/src,core-model/src,core-inference/src,core-storage/src

# One-time dev environment bootstrap — installs git hooks via lefthook.
setup:
	@if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then $(MAKE) bootstrap-wrapper; fi
	@command -v lefthook >/dev/null 2>&1 || { echo "❌ lefthook not found. Install: https://github.com/evilmartians/lefthook"; exit 1; }
	lefthook install

# Build + install APK only. Preserves app data, model, and seeded fixtures.
# Use this for code-only iterations where a full data reset is not needed.
install:
	@command -v adb >/dev/null 2>&1 || { echo "❌ adb not found. Install Android platform-tools."; exit 1; }
	@adb get-state >/dev/null 2>&1 || { echo "❌ no device connected. Run 'adb devices' to check."; exit 1; }
	$(GRADLE) :app:assembleDebug
	adb install -r -d app/build/outputs/apk/debug/app-debug.apk

doctor:
	./scripts/doctor.sh

# Generate the gradle-wrapper.jar via a system Gradle install. One-shot bootstrap.
# Wrapper version is sourced from gradle/wrapper/gradle-wrapper.properties so the regenerated
# jar matches whatever distribution that file pins — drift is impossible by construction.
bootstrap-wrapper:
	@command -v gradle >/dev/null 2>&1 || { echo "❌ system gradle not found. Install: brew install gradle"; exit 1; }
	@version=$$(grep -E '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties | sed -E 's|.*gradle-([0-9.]+)-bin\.zip|\1|') ; \
		test -n "$$version" || { echo "❌ could not parse Gradle version from gradle/wrapper/gradle-wrapper.properties"; exit 1; } ; \
		echo "Generating wrapper for Gradle $$version" ; \
		gradle wrapper --gradle-version "$$version" --distribution-type bin

build:
	$(GRADLE) :app:assembleDebug

# ── Reinstall workflow ────────────────────────────────────────────────────────
# `make reinstall` is the single device-iteration target. It uninstalls, installs the debug
# APK, runs any environment-specific setup, and tails logcat. Behavior is driven by ENV:
#
#   ENV=dev (default)   → adds dev-only setup steps (model push, future fixtures, etc.).
#   ENV=prod            → no special setup; walks the production onboarding flow.
#
# Add new dev-only steps by appending them to `DEV_SETUP_STEPS_dev`. The prod variant stays
# empty by design so a flipped ENV always reflects the real first-run experience.
ENV ?= dev
VESTIGE_PACKAGE := dev.anchildress1.vestige

# App tags + inference/GPU runtime tags. AndroidRuntime ensures crash stacktraces are never swallowed.
LOGCAT_TAGS := Vestige|CaptureVM|PatternDetailVM|PatternsListVM|OnboardingPrefs|HistoryViewModel|MarkdownEntryStore|DebugSeedReceiver|litertlm|LiteRt|tflite|TfLite|GpuDelegate|Adreno|Mali|AndroidRuntime|System\.err

# Filenames must match `core-model/src/main/resources/model/manifest.properties` exactly so
# the artifact store accepts the pushed files without re-downloading. Local source paths
# default to ~/Downloads/<filename>; override the VESTIGE_*_FILE vars for non-default homes.
VESTIGE_MAIN_MODEL_FILENAME := gemma-4-E4B-it.litertlm
VESTIGE_EMBEDDING_MODEL_FILENAME := embeddinggemma-300M_seq512_mixed-precision.tflite
VESTIGE_EMBEDDING_TOKENIZER_FILENAME := sentencepiece.model
VESTIGE_MAIN_MODEL_FILE ?= $(HOME)/Downloads/$(VESTIGE_MAIN_MODEL_FILENAME)
VESTIGE_EMBEDDING_MODEL_FILE ?= $(HOME)/Downloads/$(VESTIGE_EMBEDDING_MODEL_FILENAME)
VESTIGE_EMBEDDING_TOKENIZER_FILE ?= $(HOME)/Downloads/$(VESTIGE_EMBEDDING_TOKENIZER_FILENAME)

DEV_SETUP_STEPS_dev := push-model seed-entries
DEV_SETUP_STEPS_prod :=
DEV_SETUP_STEPS := $(DEV_SETUP_STEPS_$(ENV))

reinstall: _reinstall_base $(DEV_SETUP_STEPS) logcat

_reinstall_base:
	@if [ "$(ENV)" != "dev" ] && [ "$(ENV)" != "prod" ]; then \
		echo "❌ Unknown ENV='$(ENV)'. Set ENV=dev (default) or ENV=prod."; exit 1; \
	fi
	@echo "→ ENV=$(ENV); reinstall steps: _reinstall_base $(DEV_SETUP_STEPS) logcat"
	$(GRADLE) :app:assembleDebug
	adb uninstall $(VESTIGE_PACKAGE); adb install -r -d app/build/outputs/apk/debug/app-debug.apk

# Stream the on-device artifacts into the freshly-installed app's data dir via `run-as`.
# `adb uninstall` wipes filesDir on every iteration; this skips the 3.66 GB onboarding
# download path (main model required; embedding pair optional — Story 3.4 contingent).
# Debug-only by construction — `run-as` requires a debuggable APK.
push-model:
	@command -v adb >/dev/null 2>&1 || { echo "❌ adb not found. Install Android platform-tools."; exit 1; }
	@adb get-state >/dev/null 2>&1 || { echo "❌ no device connected. Run 'adb devices' to check."; exit 1; }
	@adb shell "pm list packages $(VESTIGE_PACKAGE)" | grep -q "$(VESTIGE_PACKAGE)" || { \
		echo "❌ $(VESTIGE_PACKAGE) is not installed. Run 'make reinstall' first."; \
		exit 1; \
	}
	@./scripts/push-vestige-artifact.sh "$(VESTIGE_PACKAGE)" "$(VESTIGE_MAIN_MODEL_FILE)" "$(VESTIGE_MAIN_MODEL_FILENAME)" required
	@./scripts/push-vestige-artifact.sh "$(VESTIGE_PACKAGE)" "$(VESTIGE_EMBEDDING_MODEL_FILE)" "$(VESTIGE_EMBEDDING_MODEL_FILENAME)" optional
	@./scripts/push-vestige-artifact.sh "$(VESTIGE_PACKAGE)" "$(VESTIGE_EMBEDDING_TOKENIZER_FILE)" "$(VESTIGE_EMBEDDING_TOKENIZER_FILENAME)" optional
# Seed fixture entries + patterns via DebugSeedReceiver (debug builds only).
# Uses explicit component targeting (-n); works even with android:exported="false".
seed-entries:
	@command -v adb >/dev/null 2>&1 || { echo "❌ adb not found. Install Android platform-tools."; exit 1; }
	@adb get-state >/dev/null 2>&1 || { echo "❌ no device connected. Run 'adb devices' to check."; exit 1; }
	@adb shell "pm list packages $(VESTIGE_PACKAGE)" | grep -q "$(VESTIGE_PACKAGE)" || { \
		echo "❌ $(VESTIGE_PACKAGE) is not installed. Run 'make reinstall' first."; \
		exit 1; \
	}
	@echo "→ seeding debug fixtures…"
	@adb shell am broadcast -n "$(VESTIGE_PACKAGE)/$(VESTIGE_PACKAGE).debug.DebugSeedReceiver" \
		|| { echo "❌ broadcast failed — is this a debug build?"; exit 1; }
	@echo "✓ seed complete"
# ───────────────────────────────────────────────────────────────────────────────

logcat:
	@command -v adb >/dev/null 2>&1 || { echo "❌ adb not found. Install Android platform-tools."; exit 1; }
	@adb get-state >/dev/null 2>&1 || { echo "❌ no device connected. Run 'adb devices' to check."; exit 1; }
	@adb shell monkey -p "$(VESTIGE_PACKAGE)" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
	@pid=""; for i in 1 2 3 4 5 6 7 8 9 10; do \
		pid=$$(adb shell pidof dev.anchildress1.vestige | tr -d '\r'); \
		[ -n "$$pid" ] && break; sleep 1; \
	done; \
	if [ -z "$$pid" ]; then \
		echo "⚠ could not resolve app PID — filtering by tags only (Ctrl-C to stop)"; \
		adb logcat -v color -T 1 $(EXTRA) | grep -E "$(LOGCAT_TAGS)"; \
	else \
		echo "📱 tailing pid=$$pid (Ctrl-C to stop)"; \
		adb logcat -v color -T 1 --pid="$$pid" $(EXTRA) | grep -E "$(LOGCAT_TAGS)"; \
	fi

assemble:
	$(GRADLE) :app:assembleRelease

test:
	$(GRADLE) :core-model:test :core-inference:testDebugUnitTest :core-storage:testDebugUnitTest :app:testDebugUnitTest koverXmlReport koverVerify

lint: ktlint-check detekt android-lint

format: ktlint-format

# Format only files passed via FILES= (used by lefthook pre-commit). Empty FILES is a no-op.
ktlint-format:
	@command -v $(KTLINT) >/dev/null 2>&1 || { echo "❌ ktlint not found. Install: brew install ktlint"; exit 1; }
	@if [ -n "$(FILES)" ]; then $(KTLINT) -F $(FILES); fi

ktlint-check:
	@command -v $(KTLINT) >/dev/null 2>&1 || { echo "❌ ktlint not found. Install: brew install ktlint"; exit 1; }
	$(KTLINT)

detekt:
	@command -v $(DETEKT) >/dev/null 2>&1 || { echo "❌ detekt not found. Install: brew install detekt"; exit 1; }
	$(DETEKT) --build-upon-default-config --config detekt.yml --input $(DETEKT_INPUTS)

android-lint:
	$(GRADLE) :app:lintDebug

secret-scan:
	@if command -v gitleaks >/dev/null 2>&1; then \
		gitleaks git --staged --redact; \
	else \
		echo "❌ gitleaks not found. Install: https://github.com/gitleaks/gitleaks#installing"; \
		exit 1; \
	fi

commitlint:
	./scripts/check-commit-msg.sh $(COMMIT_MSG_FILE)

verify-no-telemetry:
	$(GRADLE) verifyNoTelemetry

verify: lint test build secret-scan verify-no-telemetry

ci: lint test build verify-no-telemetry

clean:
	$(GRADLE) clean
	rm -rf app/build build .gradle
