plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cn.roomscan.app"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "cn.roomscan.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0-preview"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("distribution") {
            System.getenv("ROOMSCAN_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("ROOMSCAN_STORE_PASSWORD")
            keyAlias = System.getenv("ROOMSCAN_KEY_ALIAS") ?: "roomscan"
            keyPassword = System.getenv("ROOMSCAN_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            if (!System.getenv("ROOMSCAN_KEYSTORE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("distribution")
            }
        }
    }
}
dependencies {
    implementation("com.google.ar:core:1.57.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}

