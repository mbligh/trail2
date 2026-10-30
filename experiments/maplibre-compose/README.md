# MapLibre Compose offline-resource experiment

This is **not** a Trail2 release build and is deliberately absent from the root `settings.gradle.kts`. It tests MapLibre Compose 0.20.0, its OpenGL runtime, and a `MapResourceProvider` which answers style and raster tile requests without an HTTP network fallback.

The production Trail2 app remains on Kotlin 2.1.20 / Compose 1.8.2 and MapLibre Android SDK 13.7.1. This isolated experiment uses Kotlin 2.4.20, AGP 9.2, Android compileSdk 37 and Gradle 9.4.1 to avoid changing that working pipeline.

Build from the repository root with Gradle 9.4.1: `gradle -p experiments/maplibre-compose :app:assembleDebug`. The APK installs with a distinct application ID `net.pocketmap.composeprobe`, so it cannot upgrade or overwrite Trail2.

## Scope and validation

This experiment validates real Tewksbury vector/raster assets through Compose's
resource provider on Android. It runs independently of production Trail2's mutable
cache and release toolchain. Compilation alone proves API compatibility; emulator
checks below establish the specific offline rendering behaviors covered so far.
They do not establish iOS compatibility or production cache interoperability.

## Real Tewksbury offline prototype

The experiment's `preBuild` now invokes the same `tools/prepare_maps` source as production and bundles **real Tewksbury MBTiles** for OpenFreeMap vector streets and Natural Earth raster, along with the actual Bright style, fonts and sprites. `OfflineProbeResources` reads SQLite's TMS tile rows, inflates gzipped vector tiles, and serves real data through `MapResourceProvider`. No `INTERNET` permission is requested.

The test screen opens near Oldwick at zoom 12.5 using Bright; tapping the top banner switches to the bundled Natural Earth style. Both styles are offline-only. An Android instrumentation test verifies real z0 tile data, confirms offline-only routing and waits for map-engine style/tile requests. CI starts an Android 35 emulator with Wi-Fi and mobile data disabled, runs the test, and attempts to collect a screenshot.

This deliberately uses an isolated read-only adapter over the same generated assets, not Trail2's production mutable tile cache. It does **not** yet demonstrate PMTiles range interception, contours, satellite raster, shared persistent cache updates, or iOS rendering. The production app remains unchanged.

The offline Android UI smoke test additionally captures a screenshot and checks that the central map viewport contains varied rendered pixel colors, guarding against a renderer that requests resources but displays a blank surface. The captured `compose-offline.png` is uploaded with the smoke artifacts.


## Stage 5c: PMTiles v3 and NJ contour compatibility

- The isolated app now has a small PMTiles v3 reader (Hilbert IDs, gzip/no-compression directories, leaf directories, gzip/no-compression tile data) and an exact HTTP 206 byte-range reader with an on-disk 500/400 MiB cache. Network reads are only for the explicit NJ contour archive; already cached ranges remain available with no connection.
- `tools/make_contour_fixture.py` generates a **synthetic** magenta contour vector tile archive around Oldwick, using the genuine PMTiles v3 layout and the same `contours` source layer and `elevation_ft` property expected by Trail2. It is not real NJ elevation data.
- The probe exposes Bright, Natural Earth, synthetic PMTiles contours, and live NJ contours from `https://mapping.mbligh.org/contours.pmtiles`. The user can cycle via the top banner. The live option requires internet for uncached ranges; successful render of the synthetic archive is not proof of live NJ availability.
- The separate probe requests Android INTERNET permission only to support remote-range experiments. This does not change production Trail2. The regular emulator smoke remains offline; separate checks cover PMTiles archive decoding, the cache across reopen, and the synthetic contour renderer.

The test suite now checks a deterministic PMTiles v3 contour fixture, Hilbert addressing, actual `elevation_ft` layer properties, persistent range replay after the remote fetcher disappears, cache namespace invalidation, and MapLibre's offline contour tile requests. The fixture contains invented lines, not real NJ elevation values.

## Offline style and lifecycle parity

The emulator suite checks actual magenta contour pixels, bundled Bright glyph and
sprite loads, and a varied central map viewport. It switches Bright → Natural
Earth → synthetic contours → Bright in one activity, recreates the activity on
contours, and verifies that the saved style survives. Screenshots for each check
are included in `ComposeProbeSmoke`, including the last failed assertion frame.
Synthetic contour pixels establish fixture rendering only. Separate live-data checks
below cover real NJ range replay; satellite/hybrid and production cache integration remain.

## Actual NJ contours: online warm-up, cold offline replay

CI runs `OfflineRenderTest` with networking disabled. It then enables networking
and invokes only `LiveContourRenderTest#warmRealContoursOnline` against the real
configured NJ PMTiles URL with an empty probe range cache. The test requires
rendered magenta lines and actual downloaded ranges. After force-stopping the app,
CI disables networking and invokes `replayRealContoursOffline` in a fresh process
without reinstalling or deleting private data. That test requires visible real
contours from disk, cache hits, zero HTTP attempts and zero uncached range requests.

The probe's offline-only intent also blocks remote fetches at the provider, so a
network-toggle failure cannot silently make replay pass through HTTP. Both phases
save screenshots and instrumentation output in `ComposeProbeSmoke`. This validates
only the viewed Oldwick region, not statewide coverage. Live data availability is
an explicit CI dependency; no synthetic fallback hides a real-source failure.
