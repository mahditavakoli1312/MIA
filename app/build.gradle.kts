import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Resolution order, highest first: local.properties > env var > the committed,
// encrypted secrets.enc > the committed, public-by-design secrets.public.properties.
// Defined in gradle/secrets.gradle.kts, which the root build applies.
@Suppress("UNCHECKED_CAST")
val secret = rootProject.extra["miaSecret"] as (String) -> String

android {
    namespace = "ir.mahditavakoli.mia"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.mahditavakoli.mia"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_TOKEN", "\"${secret("GITHUB_TOKEN")}\"")
        // Optional build-time default for the Gemini key (on-device voice→intent); the
        // runtime value entered in Settings (stored encrypted) takes precedence. See SecretStore.
        buildConfigField("String", "GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
        // Optional build-time default for the OpenRouter key. It powers both the in-app typed
        // commands (minimax/minimax-m3:free) and the CI issue agent (pushed to each repo as the
        // OPENROUTER_API_KEY Actions secret); the runtime override from Settings wins.
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${secret("OPENROUTER_API_KEY")}\"")
        // Optional second OpenRouter key. Nothing routes to it until the primary one reports a
        // limit (HTTP 429 rate limit / 402 out of credit); see OxTextIntentClassifier.
        buildConfigField(
            "String",
            "OPENROUTER_FALLBACK_API_KEY",
            "\"${secret("OPENROUTER_FALLBACK_API_KEY")}\""
        )
        // Optional build-time default for the MiniMax platform key. It powers the paid
        // `MiniMax-M3` option in the app and is pushed to each repo as the MINIMAX_API_KEY
        // Actions secret; the runtime override from Settings wins. See SecretStore.
        buildConfigField("String", "MINIMAX_API_KEY", "\"${secret("MINIMAX_API_KEY")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)
    // @aar: pull the Android-native artifacts (bundled libsodium + JNA dispatch libs).
    // Versions come from the catalog; the @aar classifier can't be expressed as an alias.
    implementation("com.goterl:lazysodium-android:${libs.versions.lazysodium.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.chucker)
    releaseImplementation(libs.chucker.no.op)
}