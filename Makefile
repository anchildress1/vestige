.PHONY: install bootstrap-wrapper doctor build assemble reinstall reinstall-tail logcat test lint format ktlint-format ktlint-check detekt android-lint secret-scan commitlint verify-no-telemetry verify ci clean

GRADLE := ./gradlew
KTLINT := $(or $(shell command -v ktlint 2>/dev/null), $(HOME)/.local/bin/ktlint)
DETEKT := $(or $(shell command -v detekt 2>/dev/null), $(HOME)/.local/bin/detekt)
DETEKT_INPUTS := app/src,core-model/src,core-inference/src,core-storage/src

install:
	@if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then $(MAKE) bootstrap-wrapper; fi
	@command -v lefthook >/dev/null 2>&1 || { echo "❌ lefthook not found. Install: https://github.com/evilmartians/lefthook"; exit 1; }
	lefthook install

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

reinstall:
	adb uninstall dev.anchildress1.vestige; $(GRADLE) :app:installDebug

# Reinstall then tail device logs for the app PID. `--pid=$(adb shell pidof ...)` filters to the
# app only; the inline retry handles the brief window between install and process start. Ctrl-C
# to stop. Add EXTRA="..." to append filters, e.g. `make reinstall-tail EXTRA='*:W'`.
reinstall-tail: reinstall logcat

logcat:
	@command -v adb >/dev/null 2>&1 || { echo "❌ adb not found. Install Android platform-tools."; exit 1; }
	@adb get-state >/dev/null 2>&1 || { echo "❌ no device connected. Run 'adb devices' to check."; exit 1; }
	@adb shell am start -n dev.anchildress1.vestige/.MainActivity >/dev/null 2>&1 || true
	@pid=""; for i in 1 2 3 4 5; do \
		pid=$$(adb shell pidof dev.anchildress1.vestige | tr -d '\r'); \
		[ -n "$$pid" ] && break; sleep 1; \
	done; \
	if [ -z "$$pid" ]; then \
		echo "⚠ could not resolve app PID — falling back to package-name filter"; \
		adb logcat -v color -T 1 $(EXTRA); \
	else \
		echo "📱 tailing pid=$$pid (Ctrl-C to stop)"; \
		adb logcat -v color -T 1 --pid="$$pid" $(EXTRA); \
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
