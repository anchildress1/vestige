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
                    // root package alongside MainActivity.
                    classes(
                        "dev.anchildress1.vestige.MainActivity",
                        "dev.anchildress1.vestige.MainActivity*",
                        "dev.anchildress1.vestige.MainActivityKt",
                        "dev.anchildress1.vestige.MainActivityKt*",
                        "dev.anchildress1.vestige.*MainActivityKt*",
                        "dev.anchildress1.vestige.VestigeApplication",
                        "dev.anchildress1.vestige.VestigeApplication*",
                        "dev.anchildress1.vestige.ui.theme.*",
                        // Compose screen files. JVM Compose UI tests under Robolectric cover the
                        // user-visible behaviour (see *ScreenTest.kt), but branch coverage on
                        // @Composable functions caps at ~50% because the Compose compiler injects
                        // `Composer` + `$changed` Int args that produce uncoverable
                        // `if ($changed != 0 || !composer.skipping)` branches.
                        // Reference: kotlinx-kover #756 — "Wrong branch coverage for composables".
                        "dev.anchildress1.vestige.ui.patterns.PatternsListScreenKt",
                        "dev.anchildress1.vestige.ui.patterns.PatternsListScreenKt*",
                        "dev.anchildress1.vestige.ui.patterns.*PatternsListScreenKt*",
                        "dev.anchildress1.vestige.ui.patterns.PatternDetailScreenKt",
                        "dev.anchildress1.vestige.ui.patterns.PatternDetailScreenKt*",
                        "dev.anchildress1.vestige.ui.patterns.*PatternDetailScreenKt*",
                        "dev.anchildress1.vestige.ui.patterns.PatternsHostKt",
                        "dev.anchildress1.vestige.ui.patterns.PatternsHostKt*",
                        "dev.anchildress1.vestige.ui.patterns.*PatternsHostKt*",
                        "dev.anchildress1.vestige.ui.patterns.EntryDetailPlaceholderScreenKt",
                        "dev.anchildress1.vestige.ui.patterns.EntryDetailPlaceholderScreenKt*",
                        "dev.anchildress1.vestige.ui.patterns.*EntryDetailPlaceholderScreenKt*",
                        "dev.anchildress1.vestige.ui.patterns.TraceBarKt",
                        "dev.anchildress1.vestige.ui.patterns.TraceBarKt*",
                        "dev.anchildress1.vestige.ui.patterns.*TraceBarKt*",
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
            listOf(
                "**/ui/theme/**",
                "**/VestigeApplication.kt",
                "**/MainActivity.kt",
                "**/LiteRtLmEngine.kt",
                "**/AudioCapture.kt",
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
