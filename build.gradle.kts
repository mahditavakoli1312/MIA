// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Resolves build-time keys from local.properties / env / secrets.enc /
// secrets.public.properties, and registers the ./gradlew secrets* tasks.
apply(from = rootProject.file("gradle/secrets.gradle.kts"))
