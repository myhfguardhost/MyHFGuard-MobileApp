plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    kotlin("kapt")
}

// AI chat uses the SAME existing MyHFGuard Node/Express Render service.
// No separate FastAPI Render service is required.
val aiApiBaseUrl = providers.gradleProperty("AI_API_BASE_URL")
    .orElse("https://myhfguard.onrender.com")
    .get()
    .trimEnd('/')

android {
    namespace = "com.vitalink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vitalink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "2.1-ai-nurse-v2-tts-guard"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"https://sqmiosfervzwrxjastdg.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InNxbWlvc2ZlcnZ6d3J4amFzdGRnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjQ2Nzk1NDMsImV4cCI6MjA4MDI1NTU0M30.2Y8hae6GXjBBd1w5Qg51PwKPdSFOaxU7lF7gOsvmijE\"")
        buildConfigField("String", "API_BASE_URL", "\"https://myhfguard.onrender.com\"")
        // Optional test override. Production uses the existing MyHFGuard Node server by default.
        buildConfigField("String", "AI_API_BASE_URL", "\"$aiApiBaseUrl\"")
        buildConfigField("String", "REGISTER_CONFIRM_REDIRECT_URL", "\"myhfguard://login\"")
        buildConfigField("String", "PATIENT_LOGIN_DOMAIN", "\"patients.myhfguard.local\"")
    }

    buildTypes {
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    if (providers.gradleProperty("verificationMedia").orNull == "true") {
        sourceSets.getByName("main").res.setSrcDirs(listOf("../.verification-media/res"))
        sourceSets.getByName("main").assets.setSrcDirs(listOf("../.verification-media/assets"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    debugImplementation(libs.androidx.ui.tooling)
    // 1.1.0-rc03 requires compileSdk 36 + AGP > 8.6.0 — incompatible with this project.
    // 1.1.0-alpha11 is the last release that compiles against SDK 35 / AGP 8.6.0
    // and still includes the OEM permission-check fix needed for vivo devices.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
}
