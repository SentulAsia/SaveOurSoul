plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.saveoursoul.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.saveoursoul.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // One variant per environment. They install side by side (different application ids), talk to
    // their own backend, and use their own Bluetooth/Nearby namespace so test phones never carry
    // real SOS traffic. Build e.g. `./gradlew assembleProdRelease` or `installDevDebug`.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "SOS Dev")
            buildConfigField("String", "APP_ENV", "\"development\"")
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"https://saveoursoul-api-dev.onrender.com\"")
            buildConfigField("String", "MESH_NAMESPACE", "\"DEV\"")
        }
        create("testing") {
            dimension = "env"
            applicationIdSuffix = ".testing"
            versionNameSuffix = "-testing"
            resValue("string", "app_name", "SOS Testing")
            buildConfigField("String", "APP_ENV", "\"testing\"")
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"https://saveoursoul-api-testing.onrender.com\"")
            buildConfigField("String", "MESH_NAMESPACE", "\"TESTING\"")
        }
        create("prod") {
            dimension = "env"
            resValue("string", "app_name", "SaveOurSoul")
            buildConfigField("String", "APP_ENV", "\"production\"")
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"https://saveoursoul-api.onrender.com\"")
            buildConfigField("String", "MESH_NAMESPACE", "\"PRODUCTION\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Nearby Connections: peer-to-peer over Bluetooth, BLE and Wi-Fi (hotspot / Wi-Fi Direct / LAN).
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
