plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "net.pocketmap"
    compileSdk = 36
    defaultConfig { applicationId = "mbligh.trail"; minSdk = 26; targetSdk = 36; versionCode = System.getenv("TRAIL_VERSION_CODE")?.toInt() ?: 13; versionName = "1.32" }
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
dependencies { implementation(files("libs/osmdroid-android-6.1.20.aar")); testImplementation("junit:junit:4.13.2") }

// Bundle the repository-root configuration on every build; do not maintain a second copy.
val bundleBasemaps by tasks.registering(Sync::class) {
    from(rootProject.file("basemaps.json"))
    into(layout.buildDirectory.dir("generated/basemapAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/basemapAssets"))
tasks.named("preBuild").configure { dependsOn(bundleBasemaps) }
