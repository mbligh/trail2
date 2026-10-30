import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.pocketmap.composeprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "net.pocketmap.composeprobe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.foundation:foundation-layout:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("org.maplibre.compose:maplibre-compose:0.20.0")
    runtimeOnly("org.maplibre.compose:maplibre-compose-runtime-opengl-android:0.20.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Generate exactly the same offline resources as the production app,
// but inside this isolated project's own build folder.
val rootRepo = rootProject.projectDir.resolve("../..").canonicalFile
val generatedMaps = layout.buildDirectory.dir("generated/offlineMapAssets")
val prepareOfflineProbeMaps by tasks.registering(Exec::class) {
    inputs.files(rootRepo.resolve("offline-maps.json"), rootRepo.resolve("basemaps.json"),
        rootRepo.resolve("tools/prepare_maps"))
    outputs.dir(generatedMaps)
    workingDir = rootRepo
    commandLine("python3", rootRepo.resolve("tools/prepare_maps").absolutePath,
        "--output", generatedMaps.get().asFile.absolutePath)
}
val generateSamplePmtiles by tasks.registering(Exec::class) {
    dependsOn(prepareOfflineProbeMaps)
    inputs.file(rootRepo.resolve("experiments/maplibre-compose/tools/make_contour_fixture.py"))
    outputs.file(generatedMaps.map { it.file("maps/contour-probe.pmtiles") })
    workingDir = rootRepo
    commandLine("python3",
        rootRepo.resolve("experiments/maplibre-compose/tools/make_contour_fixture.py").absolutePath,
        "--output", generatedMaps.get().file("maps/contour-probe.pmtiles").asFile.absolutePath)
}
android.sourceSets.getByName("main").assets.srcDir(generatedMaps.get().asFile)
tasks.named("preBuild").configure { dependsOn(generateSamplePmtiles) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(generateSamplePmtiles) }
android.androidResources.noCompress += listOf("mbtiles", "pmtiles")

// Compile the production cache implementation verbatim without pulling the
// shipping app or its older MapLibre/toolchain into the isolated experiment.
val productionCacheSources by tasks.registering(Sync::class) {
    from(rootRepo.resolve("app/src/main/java")) { include("net/pocketmap/MbTilesCache.kt", "net/pocketmap/RangeCacheStorage.kt") }
    into(layout.buildDirectory.dir("generated/productionCacheSources"))
}
// AGP 9 rejects TaskProvider/Provider values in the legacy SourceSet API.
android.sourceSets.getByName("main").java.srcDir(
    layout.buildDirectory.dir("generated/productionCacheSources").get().asFile)
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
    .configureEach { dependsOn(productionCacheSources) }
