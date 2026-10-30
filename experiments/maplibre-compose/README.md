# MapLibre Compose renderer experiment

This standalone Android project tests MapLibre Compose 0.20.0 and its OpenGL
runtime with a separate application ID, `net.pocketmap.composeprobe`. It does not
upgrade or overwrite Trail2. Build with Gradle 9.4.1:

```
gradle -p experiments/maplibre-compose :app:assembleDebug
```

Production Trail2 2.0.5 now shares the Kotlin 2.4.20 / Compose 1.12.1 / AGP 9.2 /
Gradle 9.4.1 toolchain, compile SDK 37, target SDK 36 and minimum SDK 26. Its map
still uses MapLibre Android SDK 13.7.1. The probe remains outside the root Gradle
project so renderer changes stay isolated until production integration.

## Data and offline routing

`preBuild` uses production `tools/prepare_maps` to bundle real Tewksbury vector
streets, Natural Earth, the Bright/hybrid styles, fonts and sprites. The resource
provider reads seed MBTiles, production-format mutable raster caches and PMTiles
range files. It compiles the actual production `MbTilesCache` and `RangeCacheStorage`
implementations, including their file identities, TMS convention and eviction.
All files live in the probe's private Android sandbox; it never reads another app.

Available styles include Bright, Natural Earth, synthetic PMTiles contours, real
NJ contours, NJ 2020 satellite, hybrid and a white test background. Only synthetic
contours use invented elevation lines. Live contours and imagery use their actual
configured public sources, with INTERNET permission for uncached data. Offline-only
mode forbids HTTP at the provider as well as disabling emulator networking.

## Checks

The deterministic offline suite covers bundled resources, actual rendered map and
contour pixels, cache-format read/write compatibility, style changes, camera state
across recreation, one-finger drag/follow/recenter, zoom, and trail/recording/pin
fixtures. Pins use the unchanged production drawable. Overlay checks cover track
updates, independent trail visibility, annotation taps and rendered polygon holes.
Fixture tracks/pins are synthetic, not user recordings.

Separate instrumentation invocations warm actual NJ contour and satellite/hybrid
viewports online, stop the app process, disable networking and replay from disk.
Both replays require zero attempted HTTP requests and zero uncached ranges/tiles.
Warm-up includes a bounded one-tile viewport border to cover edge/LOD scheduling;
it does not download statewide contour or satellite coverage.

`ComposeProbeSmoke` contains assertion screenshots, instrumentation output and
renderer logs. Screenshots are preserved in shell-owned emulator storage during
tests, so Gradle uninstall cannot remove the evidence before artifact collection.
Compilation alone does not establish rendering, and Android success is not an iOS
compatibility claim. iOS targets and platform adapters are still to be added.
