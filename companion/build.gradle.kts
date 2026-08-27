plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val storeApplicationId = System.getenv("ANDROID_COMPANION_APPLICATION_ID")
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: "com.example.universalremote.companion"

android {
    namespace = "com.example.universalremote.companion"
    compileSdk = 36
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = storeApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "0.9.11"
    }
}

val releaseStore = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

if (!releaseStore.isNullOrBlank() && !releaseAlias.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
    android.signingConfigs.create("ciRelease") {
        storeFile = rootProject.file(releaseStore)
        storePassword = releaseStorePassword
        keyAlias = releaseAlias
        keyPassword = releaseKeyPassword
    }
    android.buildTypes.getByName("release").signingConfig = android.signingConfigs.getByName("ciRelease")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}