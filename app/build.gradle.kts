plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val modelsBaseUrl: String = (project.findProperty("modelsBaseUrl") as String?) ?: ""

android {
    namespace = "com.sentinel.eye"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sentinel.eye"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "2.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "MODELS_BASE_URL", "\"$modelsBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
