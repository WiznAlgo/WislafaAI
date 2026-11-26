plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.winatra.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.winatra.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // LLM native library hanya untuk HP 64-bit (memperkecil APK)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // LLM on-device (Gemma). Catatan: MediaPipe LLM Inference sekarang maintenance-only,
    // penggantinya LiteRT-LM. 0.10.27 masih berfungsi untuk file model .task / .litertlm.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // OCR on-device (model Latin ikut di APK, tanpa internet / Play Services)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
