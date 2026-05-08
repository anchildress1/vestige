plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.objectbox)
    alias(libs.plugins.kover)
}

// Derived so a single release-please bump on versionName auto-bumps versionCode too.
// Format: MAJOR * 10000 + MINOR * 100 + PATCH. Caps at 99 minor / 99 patch — fine for v1.x.
val appVersionName = "1.0.0" // x-release-please-version
val appVersionCode = appVersionName.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "dev.anchildress1.vestige"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.anchildress1.vestige"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)
    debugImplementation(libs.bundles.compose.debug)

    implementation(libs.objectbox.android)
    implementation(libs.objectbox.kotlin)

    // Inference — litertlm-android is the high-level entry point for Gemma 4 .litertlm models.
    // It bundles the underlying LiteRT runtime (libLiteRt.so), so do not also depend on
    // com.google.ai.edge.litert:litert directly — both ship the same native lib path and
    // collide at :app:mergeDebugNativeLibs.
    implementation(libs.litert.lm)

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

// Kover verify rule deferred until real production code lands. Re-enable with
// minBound(80) (per AGENTS.md) once :app has anything beyond Compose / theme stubs.
// See v1.5-backlog.md.
