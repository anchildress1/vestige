plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.objectbox) apply false
    alias(libs.plugins.kover)
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
