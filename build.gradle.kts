plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.objectbox) apply false
    alias(libs.plugins.kover)
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
