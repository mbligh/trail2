plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "net.pocketmap"
    buildFeatures { compose = true }
    compileSdk = 36
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; applicationId = "mbligh.trail"; minSdk = 26; targetSdk = 36; versionCode = System.getenv("TRAIL_VERSION_CODE")?.toInt() ?: 18; versionName = "2.0.4" }
    signingConfigs.getByName("debug") { storeFile = rootProject.file("development-signing.jks"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    signingConfigs.create("upload") {
        System.getenv("TRAIL_UPLOAD_KEYSTORE")?.let { storeFile = file(it) }
        storePassword = System.getenv("TRAIL_UPLOAD_PASSWORD")
        keyAlias = "trail-upload"
        keyPassword = System.getenv("TRAIL_UPLOAD_PASSWORD")
        storeType = "PKCS12"
    }
    buildTypes.getByName("release") {
        signingConfig = signingConfigs.getByName("upload")
        isMinifyEnabled = false
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.foundation:foundation-layout:1.8.2")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.8.2")
}

// Bundle the repository-root configuration on every build; do not maintain a second copy.
val bundleBasemaps by tasks.registering(Sync::class) {
    from(rootProject.file("basemaps.json"))
    into(layout.buildDirectory.dir("generated/basemapAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/basemapAssets"))
val prepareMaps by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("offline-maps.json"), rootProject.file("basemaps.json"), rootProject.file("tools/prepare_maps"))
    inputs.dir(rootProject.file("maps"))
    outputs.dir(layout.buildDirectory.dir("generated/mapAssets"))
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/prepare_maps", "--output", layout.buildDirectory.dir("generated/mapAssets").get().asFile.absolutePath)
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/mapAssets"))
android.androidResources.noCompress += listOf("mbtiles", "pmtiles")
tasks.named("preBuild").configure { dependsOn(bundleBasemaps, prepareMaps) }
