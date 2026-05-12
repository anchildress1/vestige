import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kover)
}

android {
    namespace = "dev.anchildress1.vestige.inference"
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
}

dependencies {
    api(project(":core-model"))

    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)

    // Inference — litertlm-android is the high-level entry point for Gemma 4 .litertlm models.
    // It bundles the underlying LiteRT runtime (libLiteRt.so), so do not also depend on
    // com.google.ai.edge.litert:litert directly — both ship the same native lib path and
    // collide at :app:mergeDebugNativeLibs.
    implementation(libs.litert.lm)

    // Embedding — bundled libgemma_embedding_model_jni.so statically links LiteRT TFLite +
    // SentencePiece, so it doesn't collide with litert-lm's libLiteRt.so.
    implementation(libs.localagents.rag)
    // Runtime dep — only protobuf provider GemmaEmbeddingModel uses is behind the SDK's
    // optional tasks-genai dep, which we don't otherwise need.
    implementation(libs.protobuf.javalite)
    // Bridge the SDK's ListenableFuture return to suspend.
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // Android's android.jar ships org.json at runtime; the mockable test jar stubs it. Pin the
    // upstream artifact on the test classpath only so LensResponseParser hits a real parser.
    testImplementation(libs.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
