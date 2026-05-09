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

sonar {
    properties {
        property("sonar.projectKey", "anchildress1_vestige")
        property("sonar.organization", "anchildress1")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.kotlin.coveragePlugin", "jacoco")
        property("sonar.coverage.jacoco.xmlReportPaths", "app/build/reports/kover/report.xml")
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
            ).joinToString(","),
        )
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
