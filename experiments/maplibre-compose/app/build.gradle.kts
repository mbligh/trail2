plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.pocketmap.composeprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "net.pocketmap.composeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("org.maplibre.compose:maplibre-compose:0.20.0")
    runtimeOnly("org.maplibre.compose:maplibre-compose-runtime-opengl-android:0.20.0")
}
