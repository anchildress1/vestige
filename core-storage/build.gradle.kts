import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.objectbox)
    alias(libs.plugins.kover)
}

android {
    namespace = "dev.anchildress1.vestige.storage"
    compileSdk = 36

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
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // ObjectBox test helpers (in-memory store factory) are consumed by both core-storage tests
    // and :app tests. testFixtures keeps a single source of truth instead of stamping copies
    // into each module's src/test/ tree.
    testFixtures {
        enable = true
    }
}

dependencies {
    api(project(":core-model"))

    // ObjectBox types (BoxStore, etc.) leak into AppContainer's public surface; promote to `api`
    // so `:app` can wire and observe lifecycle without a separate dependency declaration.
    api(libs.objectbox.android)
    implementation(libs.objectbox.kotlin)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":core-storage")))

    testImplementation(libs.junit) // Robolectric runner is JUnit 4
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)
    // Android's android.jar ships org.json at runtime; the mockable test jar stubs it. Pin the
    // upstream artifact on the test classpath only so EntryStore hits a real parser.
    testImplementation(libs.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
