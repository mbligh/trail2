# Trail 2

Native Android trail map using **MapLibre Native**. The existing compact controls,
GPS following/heading marker, TTA trails, recording, pins, KML/GPX sharing, location
reports and camera flow are preserved. Android 8+; no account, API key, backend,
analytics or Google Play Services.

Download: **https://mbligh.github.io/trail2/Trail2.apk**

This is the independent `mbligh/trail2` app: label **Trail 2**, package
`mbligh.trail`. It can coexist with the original `net.pocketmap` Trail. The Kotlin
source namespace remains `net.pocketmap`.

## Maps and offline use

- **Bright** is the default vector map, based on the same OpenFreeMap style as
  `mbligh/mapping/public/maplibre-demo`; business/POI labels are suppressed.
- **NJ Satellite + roads/trails/labels** layers the same vector linework/labels over
  NJ 2020 imagery. Plain satellite, OSM raster and OpenTopoMap remain available.
- The APK includes every vector tile intersecting **Tewksbury Township, NJ**, even
  tiles that only partly intersect the official boundary, at source zooms 0–14.
  Higher zooms enlarge those vectors. Fonts and symbols are bundled too: a fresh
  install can display the Tewksbury street map in airplane mode.
- Raster satellite/topographic maps cache viewed tiles, but are not preloaded by
  default. Uploaded tile archives can provide guaranteed offline coverage.
- Menu → **Map layers** offers JSON-configured overlays, initially NJ contours,
  styled like the mapping demo. Choices survive map changes and app restarts.
  Remote contours cache viewed PMTiles ranges; bundle an archive for guaranteed
  offline coverage without first browsing the region online.
- TTA KML trails refresh at startup and retain their last validated local copy,
  as before. First-time trail refresh still requires a connection.

**Add maps without changing Kotlin:** upload archives/resources to [`maps/`](maps/)
and edit [`basemaps.json`](basemaps.json). Configure automatic offline extraction
with [`offline-maps.json`](offline-maps.json). See [`maps/README.md`](maps/README.md)
for formats, standard MapLibre sources/layers, local paths and examples.

Immutable bundled tiles are separate from persistent downloaded tiles. Runtime
MBTiles caches have a 500 MiB cap per source, trimmed to 400 MiB; PMTiles ranges
have a separate shared 500/400 MiB cache. App updates do not clear these caches.
The existing three raster caches are migrated. A tile source's `cacheVersion`
allows explicit invalidation after changing its schema or replacing an archive.

## GPS and recording

Location acquisition uses Android LocationManager, independently of MapLibre.
Foreground display prefers recent accurate GPS over coarse network estimates.
Recording uses the existing **GPS-only location foreground service**, requesting
fixes every five seconds and saving approximately every 30 seconds. Its recent
accuracy filtering is unchanged, including while the screen is locked. Pins prefer
the recorder's recent GPS fix and warn before saving a poor/unknown/network fix.

Starting a recording requires precise location and enabled GPS. Recording continues
while the map is paused or the screen is locked; its notification offers Stop.
Process termination or reboot stops recording, retaining saved points. No automatic
resumption is added. The blue saved path, display-only live connector, recording
pins and existing KML/GPX formats remain. Reports capture a fixed coordinate with
optional camera photo and share through Android's chooser; nothing is sent directly.

## Build and validation

JDK 17, Android SDK 36/build-tools 35.0.0, Python 3 with `shapely==2.1.2`:

```sh
python3 -m pip install shapely==2.1.2
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
python3 tools/test_offline_maps
```

Gradle's `prepareMaps` task builds the offline package before assembling. The initial
build downloads style/sprite/font resources and selected tiles; subsequent builds
reuse `.map-downloads/`. The generated `offline-manifest.json` records exact tile
counts, source URLs, boundary, and content hashes. Generated downloads stay out of
Git. Incomplete downloads fail without replacing a published APK.

`./gradlew connectedDebugAndroidTest` on an emulator/device tests real native
rendering with network blocked, layer restoration after style changes, persistence
of runtime tile caches, and the GPS accuracy selection across activity pause/resume.
The native smoke workflow runs these on Android 15 and saves a screenshot/logs.
Real-device GNSS accuracy still needs an outdoor walk with the phone locked.

## Distribution

Main changes automatically test/lint/build and publish the development-signed APK
and checksum to GitHub Pages. Keep the included development signing key so APKs
update installed Trail 2 copies. Release bundles use the repository's existing
private signing secrets and automatic internal-track upload when
`ENABLE_TRAIL2_PLAY_UPLOAD=true` and the distinct Play listing is configured.
Signing secrets are never stored in Git. Failed builds retain the previous download.

See [`project.md`](project.md) for requirements and architecture.
