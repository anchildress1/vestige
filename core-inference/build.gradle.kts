plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.anchildress1.vestige.inference"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(25)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core-model"))

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
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
