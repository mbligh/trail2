# MapLibre Compose offline-resource experiment

This is **not** a Trail2 release build and is deliberately absent from the root `settings.gradle.kts`. It tests MapLibre Compose 0.20.0, its OpenGL runtime, and a `MapResourceProvider` which answers style and raster tile requests without an HTTP network fallback.

The production Trail2 app remains on Kotlin 2.1.20 / Compose 1.8.2 and MapLibre Android SDK 13.7.1. This isolated experiment uses Kotlin 2.4.20, AGP 9.2, Android compileSdk 37 and Gradle 9.4.1 to avoid changing that working pipeline.

Build from the repository root with Gradle 9.4.1: `gradle -p experiments/maplibre-compose :app:assembleDebug`. The APK installs with a distinct application ID `net.pocketmap.composeprobe`, so it cannot upgrade or overwrite Trail2.

## What this does and does not prove

- Exercises a **real** MapLibre Compose `MapResourceProvider` and offline style/tile routing against a synthetic raster tile, on Android when launched.
- A successful build proves API compatibility with the experiment's toolchain, **not** the render runtime, offline-cache correctness, or iOS compatibility.
- Separately, Trail2's production `TileRepository.loadOfflineResource` provides bytes from real bundled MBTiles and persistent caches. The native emulator test verifies that bridge.
- Next connect the MapLibre Compose provider to that adapter and test actual Tewksbury vector tiles, sprites/glyphs, source/layer ordering, PMTiles ranges, and style switches, then add emulator rendering tests. Do not replace the production map until those work offline.
