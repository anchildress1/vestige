import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
}

// Release signing per ADR-001 §Q5. Reads `keystore.properties` from the repo root if present.
// When the file is absent (agent-loop machines, fresh clones) the release variant falls back to
// debug signing — a build-time WARNING is emitted so the operator knows the APK isn't a
// submission-ready artifact. The Phase 6 submission build requires the real keystore.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
} else {
    logger.warn(
        "[vestige] keystore.properties is missing — release builds will sign with the DEBUG " +
            "keystore. Copy keystore.properties.example and set the four fields before " +
            "Phase 6 submission. (ADR-001 §Q5)",
    )
    null
}

// Derived so a single release-please bump on versionName auto-bumps versionCode too.
// Format: MAJOR * 10000 + MINOR * 100 + PATCH. Caps at 99 minor / 99 patch — fine for v1.x.
val appVersionName = "1.0.0" // x-release-please-version
val appVersionCode = appVersionName.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "dev.anchildress1.vestige"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anchildress1.vestige"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Forward `-PmodelPath=<path>` / `-PaudioPath=<path>` / `-PlatencyBudgetMs=<ms>` into
        // instrumentation runner args (consumed by SttAAudioPlumbingTest, PersonaToneSmokeTest,
        // LiteRtLmTextSmokeTest, PerCapturePersonaSmokeTest).
        listOf("modelPath", "audioPath", "latencyBudgetMs").forEach { key ->
            project.findProperty(key)?.toString()?.let { value ->
                testInstrumentationRunnerArguments[key] = value
            }
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        // Toolchain (JDK the compiler runs on) tracks latest LTS; java source/target compat stays
        // at 17 because that's the highest the Android docs guarantee for API 34+ desugaring.
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-storage"))
    implementation(project(":core-inference"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)
    debugImplementation(libs.bundles.compose.debug)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Privacy gate per ADR-001 §Q7 + AGENTS.md guardrail 13. Walks the release runtime classpath
// and fails the build if any artifact coordinate matches a known telemetry SDK fingerprint.
// The runtime counterpart is `NetworkGate` in :core-model.
tasks.register("verifyNoTelemetry") {
    group = "verification"
    description = "Fail the build if known telemetry/analytics SDKs land in this module's release classpath."

    val patterns = listOf(
        "firebase-analytics", "firebase-crashlytics", "firebase-perf", "firebase-config",
        "crashlytics", "mlkit-analytics",
        "google-analytics", "play-services-analytics", "play-services-measurement",
        "io.sentry:", "com.sentry:",
        "segment.analytics", "com.segment",
        "mixpanel-android", "mixpanel-java",
        "amplitude-android", "amplitude-analytics",
        "datadog-android", "ddog",
        "newrelic-android",
        "appcenter-analytics",
        "kochava",
    )

    val classpath = configurations.named("releaseRuntimeClasspath")

    doLast {
        // artifactView with lenient = true avoids the AGP 9 variant-ambiguity errors that hit
        // resolvedConfiguration.resolvedArtifacts on android-application configurations.
        val artifactView = classpath.get().incoming.artifactView { lenient(true) }
        val coordinates = artifactView.artifacts.artifacts.mapNotNull { artifact ->
            val moduleId = artifact.id.componentIdentifier
                as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
            moduleId?.let { "${it.group}:${it.module}".lowercase() }
        }
        val violations = coordinates.flatMap { coordinate ->
            patterns.filter { pattern -> pattern in coordinate }.map { pattern -> "$coordinate matches '$pattern'" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Telemetry / analytics dependency detected — AGENTS.md guardrail 13 violation:\n" +
                    violations.joinToString("\n") + "\n\nRemove the dependency or supersede the guardrail.",
            )
        }
    }
}
