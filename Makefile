.PHONY: install bootstrap-wrapper build assemble test lint format ktlint-format ktlint-check detekt secret-scan commitlint ci clean

GRADLE := ./gradlew
KTLINT := $(or $(shell command -v ktlint 2>/dev/null), $(HOME)/.local/bin/ktlint)
DETEKT := $(or $(shell command -v detekt 2>/dev/null), $(HOME)/.local/bin/detekt)

install:
	@if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then $(MAKE) bootstrap-wrapper; fi
	@command -v lefthook >/dev/null 2>&1 || { echo "❌ lefthook not found. Install: https://github.com/evilmartians/lefthook"; exit 1; }
	lefthook install

# Generate the gradle-wrapper.jar via a system Gradle install. One-shot bootstrap.
bootstrap-wrapper:
	@command -v gradle >/dev/null 2>&1 || { echo "❌ system gradle not found. Install: brew install gradle"; exit 1; }
	gradle wrapper --gradle-version 9.1.0 --distribution-type bin

build:
	$(GRADLE) :app:assembleDebug

assemble:
	$(GRADLE) :app:assembleRelease

test:
	$(GRADLE) :app:testDebugUnitTest :app:koverXmlReport :app:koverVerify

lint: ktlint-check detekt

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
	$(DETEKT) --build-upon-default-config --config detekt.yml --input app/src

secret-scan:
	@if command -v gitleaks >/dev/null 2>&1; then \
		gitleaks git --staged --redact; \
	else \
		echo "❌ gitleaks not found. Install: https://github.com/gitleaks/gitleaks#installing"; \
		exit 1; \
	fi

commitlint:
	./scripts/check-commit-msg.sh $(COMMIT_MSG_FILE)

ci: lint test build

clean:
	$(GRADLE) clean
	rm -rf app/build build .gradle
