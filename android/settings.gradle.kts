pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Versions live here (not in a root plugins {} block) so that building :core alone never
    // has to resolve the Android Gradle Plugin.
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("com.google.gms.google-services") version "4.4.2"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SaveOurSoul"

// Pure-Kotlin protocol/relay logic: builds and tests on any JVM, no Android SDK needed.
include(":core")

// The Android app needs an Android SDK (ANDROID_HOME or local.properties sdk.dir).
// Skipping it when no SDK is present lets `./gradlew :core:test` run on CI boxes without one.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.warn("No Android SDK found: building :core only. Set ANDROID_HOME to build :app.")
}
