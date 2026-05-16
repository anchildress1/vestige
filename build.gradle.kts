plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.objectbox) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.sonar)
}

dependencies {
    kover(project(":app"))
    kover(project(":core-model"))
    kover(project(":core-inference"))
    kover(project(":core-storage"))
}

// Compose @Composable screen/host files cap at ~50% branch coverage from the Compose
// compiler's `Composer` + `$changed` synthetics (kotlinx-kover #756 — "Wrong branch
// coverage for composables"). Single source of truth: kover wants FQCN class globs,
// Sonar wants source paths — both derive from this list, so a new screen is one line,
// not six across two formats. Non-Composable logic lives in its own classes/files and
// stays counted. Behaviour is covered by the *ScreenTest.kt Robolectric suites.
val composeScreenExclusions = listOf(
    "ui.history.HistoryHost",
    "ui.history.HistoryRow",
    "ui.history.HistoryScreen",
    "ui.history.EntryDetailHost",
    "ui.history.EntryDetailScreen",
    "ui.patterns.PatternsListScreen",
    "ui.patterns.PatternDetailScreen",
    "ui.patterns.PatternsHost",
    "ui.patterns.EntryDetailPlaceholderScreen",
    "ui.patterns.TraceBar",
    "ui.patterns.TraceBarE",
    "ui.onboarding.OnboardingHost",
    "ui.onboarding.OnboardingStepContent",
    "ui.onboarding.OnboardingScaffold",
    "ui.onboarding.OnboardingScreens",
    "ui.onboarding.PersonaPickScreen",
    "ui.onboarding.WiringScreen",
    "ui.onboarding.ModelDownloadPlaceholderScreen",
    "ui.components.ScoreboardPrimitives",
    "ui.components.VestigeSurface",
    "ui.components.VestigeScaffold",
    "ui.components.AccentModifiers",
    "ui.capture.IdleLayout",
    "ui.capture.LiveLayout",
    "ui.capture.RecButton",
    "ui.capture.LiveLevelBars",
    "ui.capture.ChunkProgressBar",
    "ui.capture.CaptureScreen",
    "ui.capture.TypeEntrySheet",
)

// kover form: the file-class `XKt`, its `XKt*` synthetics, and Compose's
// `*XKt*` (`ComposableSingletons$XKt`) lambda holder — three globs per screen.
val koverComposeClassGlobs: List<String> = composeScreenExclusions.flatMap { rel ->
    val pkg = "dev.anchildress1.vestige." + rel.substringBeforeLast('.')
    val kt = rel.substringAfterLast('.') + "Kt"
    listOf("$pkg.$kt", "$pkg.$kt*", "$pkg.*$kt*")
}

kover {
    reports {
        total {
            filters {
                excludes {
                    // Kover's `*` glob requires ≥1 trailing character — `MainActivity*` matches
                    // `MainActivityKt` but NOT bare `MainActivity`. Enumerate both forms so
                    // top-level classes AND their Compose / coroutine / FileKt synthetics are
                    // excluded together. Leading `*` on `*MainActivityKt*` catches Compose's
                    // `ComposableSingletons$MainActivityKt` lambda holder, which lives in the
                    // root package alongside MainActivity. `CaptureViewModelFactory` is lifecycle
                    // factory glue; tested business derivations live in `CaptureHostModels.kt`.
                    // Compose screen/host files are generated from `composeScreenExclusions`
                    // (single source of truth shared with Sonar) — see the val above the block.
                    classes(
                        *koverComposeClassGlobs.toTypedArray(),
                        "dev.anchildress1.vestige.MainActivity",
                        "dev.anchildress1.vestige.MainActivity*",
                        "dev.anchildress1.vestige.MainActivityKt",
                        "dev.anchildress1.vestige.MainActivityKt*",
                        "dev.anchildress1.vestige.*MainActivityKt*",
                        "dev.anchildress1.vestige.CaptureViewModelFactory",
                        "dev.anchildress1.vestige.CaptureViewModelFactory*",
                        "dev.anchildress1.vestige.VestigeApplication",
                        "dev.anchildress1.vestige.VestigeApplication*",
                        "dev.anchildress1.vestige.ui.theme.*",
                        // Debug-only fixture seeder for on-device manual verification.
                        // FLAG_DEBUGGABLE-gated at the call site; not on any release path.
                        "dev.anchildress1.vestige.debug.*",
                        // Model loading and audio recording require on-device hardware/model;
                        // exercised by androidTest, not JVM unit tests.
                        "dev.anchildress1.vestige.inference.LiteRtLmEngine",
                        "dev.anchildress1.vestige.inference.LiteRtLmEngine*",
                        "dev.anchildress1.vestige.inference.AudioCapture",
                        "dev.anchildress1.vestige.inference.AudioCapture*",
                    )
                }
            }
            verify {
                rule {
                    // INSTRUCTION default — bytecode-level coverage. Kept so the historical
                    // gate continues to fire on raw bytecode regressions.
                    bound {
                        minValue = 85
                    }
                }
                rule {
                    // LINE — matches Sonar's overall-line-coverage metric so the local hook
                    // doesn't ship code that fails the cloud gate. Same 85% floor by intent.
                    bound {
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                        minValue = 85
                    }
                }
                rule {
                    // BRANCH — Sonar's "Coverage on New Code" gate (default 80%) is line-based
                    // but condition coverage drives the same kind of regression. Keep at 80%
                    // so the floor isn't lower than Sonar's new-code rule.
                    bound {
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                        minValue = 80
                    }
                }
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "anchildress1_vestige")
        property("sonar.organization", "anchildress1")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.kotlin.coveragePlugin", "jacoco")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/kover/report.xml")
        property(
            "sonar.junit.reportPaths",
            listOf(
                "app/build/test-results/testDebugUnitTest",
                "core-inference/build/test-results/testDebugUnitTest",
                "core-model/build/test-results/test",
                "core-storage/build/test-results/testDebugUnitTest",
            ).joinToString(","),
        )
        property(
            "sonar.exclusions",
            listOf(
                "**/build/**",
                "**/generated/**",
                "**/*.gradle.kts",
                "**/objectbox-models/**",
            ).joinToString(","),
        )
        property(
            "sonar.coverage.exclusions",
            (
                listOf(
                    "**/ui/theme/**",
                    "**/VestigeApplication.kt",
                    "**/MainActivity.kt",
                    "**/LiteRtLmEngine.kt",
                    "**/AudioCapture.kt",
                    // Debug-only fixture seeder, FLAG_DEBUGGABLE-gated; never on a release path.
                    "**/debug/**",
                ) +
                    // Compose @Composable bodies cap at ~50% branch coverage from `Composer`
                    // + `$changed` instrumentation (kotlinx-kover #756); same source-of-truth
                    // list the kover `excludes { classes(...) }` block derives from.
                    composeScreenExclusions.map { "**/${it.replace('.', '/')}.kt" }
                ).joinToString(","),
        )
        // Both pattern view-models share an action-dispatch + undo skeleton (dismiss /
        // snooze / markResolved / restart). The structural overlap is intentional for the
        // list + detail surface pair; Story 4.8 retires `markResolved` and extracts a shared
        // dispatcher when the `PatternAction` enum + `PatternState` rename land. Excluding
        // the two VMs from CPD avoids gating PR #26 on that cleanup.
        property(
            "sonar.cpd.exclusions",
            listOf(
                "**/ui/patterns/PatternsListViewModel.kt",
                "**/ui/patterns/PatternDetailViewModel.kt",
            ).joinToString(","),
        )
        property("sonar.qualitygate.wait", "true")
        property("sonar.qualitygate.timeout", "300")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// Aggregator that delegates to :app:verifyNoTelemetry. The actual classpath resolution lives
// inside :app's build script because Gradle 9 forbids cross-project configuration resolution.
tasks.register("verifyNoTelemetry") {
    group = "verification"
    description = "Fail the build if known telemetry/analytics SDKs land in :app's release classpath."
    dependsOn(":app:verifyNoTelemetry")
}
