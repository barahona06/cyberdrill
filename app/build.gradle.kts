plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.cyberdrill.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cyberdrill.app"
        // minSdk 26: required by the ML Kit Prompt API behind Firebase AI Logic on-device inference.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Google's ML Kit GenAI artifacts (transitive via firebase-ai-ondevice)
        // are published with Kotlin 2.3 metadata; the compiler must not reject
        // them when building with Kotlin 2.0.20.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Firebase AI Logic (hybrid inference) ---
    // Versions per the official "Get started with hybrid inference" guide (Nov 2026).
    // firebase-ai-ondevice is NOT part of the Firebase BoM: it is pinned by hand.
    implementation("com.google.firebase:firebase-ai:17.17.0")
    implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta05")
    // App Check is mandatory for AI Logic. Debug builds install the debug
    // provider (register the debug token printed to Logcat in the Firebase
    // console); release builds install Play Integrity. See CyberDrillApplication.
    implementation("com.google.firebase:firebase-appcheck-debug:19.4.1")
    implementation("com.google.firebase:firebase-appcheck-playintegrity:19.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
