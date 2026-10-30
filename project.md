# Trail 2 — requirements and implementation

Updated 2026-10-30 through stages 5g–5i and 6a; production version 2.0.6. Phone regression testing is the next gate.

## Product and identity

Small native Android application for following trails, recording walks/rides, and
sharing trail problems with an accurate location and optional photograph. Favor
existing Android UI/camera/share flows and on-device data. No account, backend,
analytics, Google Play Services or API key. Offline tile/track storage is local
SQLite/MBTiles and private files; an external database would defeat offline use.

Repository `mbligh/trail2`, default branch `main`. On-device label **Trail 2**,
application ID **mbligh.trail**, Kotlin namespace `net.pocketmap`. This is independent
of `mbligh/trail`/`net.pocketmap`; both can be installed. Preserve Trail 2's application
ID, development signing key, private recordings/pins/reports and preferences.

## Interface compatibility

Replace osmdroid with MapLibre Native while keeping controls and behavior as close
as possible. Underlying tile appearance may change. Retain:

- White status header, left basemap icon, right info/zoom and hamburger menu.
- Existing 37 dp corner controls: recording bottom right, report flag bottom left,
  recording pin top left, Recenter top right; existing margins/icons/colors.
- Pinch zoom, single-finger dragging that pauses following, Recenter that restores
  following and existing permission/location-settings behavior. Map remains north-up,
  with rotation/tilt disabled. Preserve geographic center and apparent scale across
  updates/sessions. Translate saved 256px-world zooms to MapLibre's 512px world while
  retaining the displayed 3–19 zoom convention.
- Blue position dot; blue phone-facing triangle when heading is reliable and under
  five seconds old. Existing sensor smoothing/true-north/display-rotation logic stays.
- Refresh/zoom/hide/show trails, legend, saved recording exports, blue track/pins,
  fixed-location report editor, camera capture/cancel/retake and Android sharing.
- Add configurable overlay toggles inside Menu → Map layers. Save their state and
  restore app-owned trails/track/pins after every style/layer change.

## Maps, layers, offline coverage

Use MapLibre Native Android 13.7.1. Follow the mapping repository's MapLibre demo:
OpenFreeMap **Bright**, POI/business labels removed; NJ 2020 satellite hybrid retains
vector lines/labels with white text halos. Keep standalone satellite, OSM raster and
OpenTopoMap choices. Bright is default; migrate the previous OSM street default to
Bright once, retaining other explicit choices.

`basemaps.json` contains configured sources, basemap choices and overlay groups.
Raster basemaps can reference one source; vector/layered basemaps reference standard
style JSON. Overlays contain standard sources/layers, optional default visibility,
and appear independently in the existing menu. Initial contours use the demo's
magenta 100/50/20/10-foot zoom bands. Local MBTiles, local/remote PMTiles, bundled or
remote GeoJSON, fonts/sprites/styles can be supplied without Kotlin changes.

Upload reusable resources to `maps/` and edit configuration. Local PMTiles URLs use
`pmtiles://asset://maps/name.pmtiles`; bundled GeoJSON/styles/sprites/fonts can use
`asset://maps/...`. MBTiles `archive` paths point to bundled resources. Both raster
and vector MBTiles are supported, including offline-only archives with source zoom
metadata. Supply all dependencies for an offline vector style. See maps/README.md.

`offline-maps.json` controls the automatic bundled coverage. `tools/prepare_maps`
queries the official NJ government boundary for Tewksbury Township; Shapely selects
**every tile intersecting the boundary, including partially intersecting/touching
tiles**, at each source zoom. It packages 70 OpenFreeMap vector tiles at zooms 0–14,
seven Natural Earth overview tiles at zooms 0–6, and the Bright style's sprites and
Latin/extended Latin/punctuation glyphs for its English NJ labels. Source-native
maximum zoom is 14; MapLibre overzooms without limiting the user's existing zoom.
The street map must work on a clean install without a connection anywhere within
Tewksbury. Satellite imagery is not preloaded by default; it uses uploaded archives
or the runtime tile cache. KML trails retain their existing startup refresh/cache
behavior, so first-time trail retrieval still needs a connection.

Generation uses a staging directory and validates completeness/integrity before
promotion. `offline-manifest.json` records source/boundary URLs, coverage, tile counts,
and SHA-256 hashes. Generated assets and download caches are excluded from Git.
Gradle invokes generation and bundles root configuration automatically. Source data
is cached in Actions; changing offline configuration refreshes it. Never bulk-download
public OSM raster tiles; use a permitted source/archive.

`TileRepository` supplies seed MBTiles and persistent runtime MBTiles to MapLibre
through its HTTP client hook, with online fallback. `MbTilesCache` retains the standard
TMS row convention and FIFO 500 MiB high/400 MiB low watermarks per source. Existing
raster cache files migrate, and app version updates do not delete offline data. Seeds
remain immutable and outside eviction. `RangeCache` caches PMTiles header/directory
and tile byte ranges with a separate shared 500/400 MiB cap; viewed remote contour
regions can be revisited offline. Bundling a complete PMTiles archive guarantees
coverage without prior viewing. `cacheVersion` invalidates changed source content.

## GPS behavior — must survive the renderer migration

The user previously saw dramatically poorer GPS accuracy with the screen locked;
that was fixed before this migration. **Preserve that fix and existing inaccurate-fix
filtering.** MapLibre must not replace LocationManager or own recording acquisition.

RecordingService retains the existing GPS acquisition and filtering:

- Require precise permission and enabled GPS; start explicitly while the app is visible.
- Use a location foreground service/notification; request GPS only every five seconds,
  independently of activity/map lifecycle, including while the phone is locked.
- Reject fixes older than 45 seconds. Keep a 10-second recent GPS window. First fresh
  sample is saved immediately; subsequent sampling windows are at least 30 seconds
  apart. Select the newest fix whose accuracy is within max(best×2, best+5 m).
- Publish the latest raw GPS fix separately from persisted samples.
- Append completed CSV points immediately; gaps over 90 seconds split map/GPX/KML
  segments. Process termination/reboot stops recording; never auto-resume or invent fixes.
- Stop automatically four hours after recording starts, including time waiting for
  GPS and time with the screen locked. Use elapsed realtime, a service-owned timer,
  and an idle-capable wake alarm; GPS callbacks also enforce the deadline before
  accepting fixes. Android may defer the inexact wake alarm when deeply idle, but
  no GPS points are accepted after the deadline. No exact-alarm permission is needed.
  Reopening the map or repeated start commands do not reset the deadline. Cancel the
  timer/alarm when stopped; a new recording gets a fresh four hours. Keep all saved
  points and pins. Service lifecycle tests cover timeout and restart on API 28/35.

Foreground activity location selection is unchanged:

- Request GPS and network while visible, stop the activity's requests on pause.
- Reject cached/live fixes outside the 15-second window. Prefer accurate recent GPS
  (including the recorder's stream) using the same best×2/best+5 m tolerance; network
  is fallback. A newer coarse network fix must not displace available good GPS.
- Pins prefer the recorder's GPS, require a fix no older than 15 seconds, and warn
  with an explicit Save anyway choice for >25 m, unknown accuracy, or network fallback.
- Blue live connector is display-only, never persisted; hide it when stopped/stale,
  backward in time, across >90-second gaps, or displaying a different recording.

A native emulator regression covers GPS selection across pause/resume and the
recorder's accuracy-window selection. Physical GNSS reception/locked-screen accuracy
still needs a real outdoor phone test; simulated fixes cannot establish hardware quality.

## Trails, recording, reports

Fetch public KML from https://www.google.com/maps/d/kml?mid=1zy-nQvF6-6P7JSs3paMU8byWHoE&forcekml=1.
Only the folder named **Trails** is rendered. Preserve style maps, line colors/widths,
polygons/holes, names on tap and the bundled My Maps camper, blue pin, blank marker,
and medical artwork (including tint/scale/anchors). Cache the last validated document;
retain it on refresh failure. Parser/network safety limits remain unchanged. No local
KML/KMZ import is added. Fresh install trail refresh needs a connection.

Tap the top-left blue pin to save an unannotated pin immediately. Hold it for one
second to open a multiline text dialog **while the finger is still down**, with
standard long-press haptic feedback; release must not add a second pin. Moving out,
cancelling, pausing or detaching the button cancels a pending hold. Preserve native
click/long-click accessibility actions. Cancel leaves no pin; Save permits blank text.
Capture the recent GPS fix and recording file when the dialog opens, not after typing.
Keep the existing accuracy warning and explicit Save anyway for poor/unknown/fallback
locations. Preserve the draft, location and target track across activity recreation.
Tap a saved pin to read its text; persist it offline and export it as KML description
and GPX waypoint desc, with XML escaping. Legacy pins need no migration: add an optional
sixth Base64 UTF-8 field to the existing .pins sidecar, retaining the five original fields
and track CSV format. Corrupt optional text must not discard valid pin coordinates.

RecordedTrack CSV storage remains unchanged. Shared trip summaries are
calculated from saved fixes: distance is summed between successive valid points with
gaps up to 90 seconds, elapsed time spans first-to-last saved fix (not service
start/stop), and moving time estimates intervals with at least 0.5 m/s motion.
Ascent/descent sums separate climbs using an 8 m hysteresis, with isolated stationary
altitude spikes suppressed; values are GPS estimates rather than DEM-corrected.
Long GPS outages and missing altitudes cannot create fictitious distance/ascent.
GPX track descriptions and namespaced extensions and KML document descriptions and
ExtendedData publish the same distance (metres), elapsed/moving (seconds), and
ascent/descent (metres). Display conversions use miles/feet for US locale and
kilometres/metres elsewhere. Stop-recording dialog shows all five values. Blue saved path
and pins remain visible after stop/reopen. Recording control changes circle to stop
square, and Stop offers Share KML. Menu retains both saved KML and GPX exports.
KML preserves the existing accuracy-colored segments; GPX retains coordinates/time/
elevation and pins. The recording notification retains its Stop action.

Reports capture a fixed recent location, six-decimal locale-independent coordinates,
accuracy, and the existing Google satellite link. Editable description, optional
full-resolution camera photo and draft restoration remain. Standard share chooser
suggests mbligh@mbligh.org and subject Trail location report; nothing sends directly.
Existing scoped URI providers and permission grants remain unchanged.

## Architecture and distribution

Native Kotlin Activity/UI, Android 8+/API 26 minimum, compile 37 / target 36, JDK 17,
AGP 9.2.0, Kotlin 2.4.20, Compose Multiplatform 1.12.1, Gradle 9.4.1. MapLibre adds native libraries; validate 16 KiB
alignment in the APK/bundle. No WebView or browser UI.

- MainActivity: existing controls, report/share flow, foreground location selection.
- TrailMap: native rendering/lifecycle, styles, runtime GeoJSON, heading/dot/connector.
- MapConfiguration: JSON map and layer definitions; no provider-specific UI logic.
- TileRepository/MbTilesCache/RangeCache: offline seeds, persistent caches, online fallback.
- TrailRepository/TrailKml, RecordingService/RecordedTrack/RecordedPins, PhoneHeading,
  GpxProvider/ReportPhotoProvider: retain their established responsibilities.

Main app/config/map/builder changes automatically run unit tests/lint, package offline
maps, verify coverage, build signed debug APK and publish at
https://mbligh.github.io/trail2/Trail2.apk. Preserve the development signing key.
Play AABs use existing private upload secrets; internal publishing remains guarded by
ENABLE_TRAIL2_PLAY_UPLOAD=true and separate app setup. Never commit credentials.
Failures must not replace the last successful distribution. Native emulator smoke tests
exercise offline rendering/style changes, cache persistence and GPS selection, saving
screenshots/logs. Update version metadata on app releases and keep this document current.

## Cross-platform migration (stage 1: Android remains the only app target)

- Introduce `shared/` as a Kotlin Multiplatform module with Android and JVM targets, no iOS toolchain or Apple account required. The Android app depends on this module directly.
- `TrackStatistics` in `commonMain` owns the existing GPS-fix distance, movement, ascent/descent and gap logic. `RecordedTrack.Point` implements its platform-neutral `Sample` contract; `RecordedTrack.Summary` and existing GPX/KML/UI API remain unchanged for compatibility.
- `HeadingMath` is now `commonMain` and has no JVM-only math calls. Platform-specific sensors, recording service, storage, map SDK, camera and sharing remain Android-only.
- The Linux JVM test target checks portable calculations, and Android tests still exercise integration and exports. CI runs both.
- Next: migrate UI gradually to Compose Multiplatform, initially embedding the existing Android MapLibre view; independently prototype MapLibre Compose offline resource provisioning **before** changing `TileRepository` or the known-good renderer. Only then add an iOS target and Core Location adapter.
- Preserve locked-screen GPS accuracy, offline Tewksbury maps, Android package/signing/updates, and the existing Play/APK publishing pipelines at every stage. No Android UI or behavior change is intended in this stage.

## Compose migration, stage 2: native map inside shared UI

- Add Compose Multiplatform 1.8.2 and matching Kotlin 2.1.20 compiler. The `TrailScreen` screen structure and its status, trail refresh and recording text are in `shared/commonMain`.
- Android `MainActivity` becomes `ComponentActivity`, installs a root `ComposeView`, and uses observable Compose state for header text. Its existing MapLibre `TrailMap` and native basemap/attribution/menu controls use `AndroidView` slots, so their underlying lifecycle, menus, gestures, track pin long-hold, and map overlays remain unchanged.
- Preserve existing manual system-bar insets and Android-specific background GPS recorder, camera, offline caches, signing and package identity. No iOS targets or tools are required.
- Next: migrate native controls to Compose one at a time after native emulator screenshot and interaction regression checks; test MapLibre Compose custom offline resources separately before replacing the renderer.


## Compose migration, stage 3: shared header controls

- All header controls (basemap chooser, attribution/zoom readout, and the main menu including the Map layers submenu) are now real Compose Multiplatform UI in `shared/commonMain`. The shared composable exposes platform-independent choices, layer state and menu action callbacks; Android binds them to its existing MapLibre and repository functions.
- The Compose dropdowns replace Android `PopupMenu`. Selecting basemaps and layers persists to the same existing preferences. Zoom-to-trails forces the same visible-trails state in the Compose header. The Map credits dialog still uses Android's HTML-enabled `AlertDialog` to preserve clickable licence links.
- The map is still hosted in one `AndroidView`, and all four overlay buttons (including the one-second long-press pin gesture) remain native and unmodified. The existing camera, share, GPS, MapLibre lifecycle and offline caches stay unchanged.
- Android instrumentation now verifies shared Compose basemap selection, layer persistence, submenu re-selection, and trail visibility labels. Next: move map corner controls to shared Compose one at a time, preserving the hold-before-release gesture; then prototype MapLibre Compose offline resource loading separately before switching renderer. No iOS target or toolchain configured yet.

## Compose basemap icon parity

- Keep the original `app/src/main/res/drawable/basemap_menu.xml` folded-map/location-pin artwork after converting the basemap selector to Compose. The Android host supplies the drawable as a 40 dp Compose `Image` inside the shared 48 dp control via a platform-agnostic composable slot.
- Do not replace existing artwork with placeholder Compose Canvas drawings during migration. An eventual iOS host can supply an equivalent vector asset without changing the shared selector or menu behavior.

## Compose migration, stage 4: four map corner controls

- Move the recording, report flag, pin, and recenter buttons into the shared `TrailScreen` Compose map overlay. Their 37 dp circular surfaces, 16 dp edges (12 dp for recenter), 6 dp shadow, icon sizes, map stacking, and record red circle/square are preserved.
- Android supplies unchanged vector resources `ic_report_flag`, `ic_track_pin`, `ic_recenter` through platform-neutral composable icon slots. Shared callbacks invoke the same Android recording, report, pin and location logic. Compose state tracks recording/following and explicitly disables in-progress pin gestures on activity pause.
- Pin press uses a 1,000 ms pointer-input timeout and a separate accessibility long-click action, opens the annotation dialog while pressed and suppresses the following release; quick taps add plain pins, canceled drags add nothing. The prior `PinHoldGesture` helper is retained temporarily for backwards-compatibility tests but no longer installed by the Activity.
- MapLibre remains native in `AndroidView`. Offline tile stores, GPS foreground service, GPX/KML outputs, local files, package ID and signing are unchanged. Next verify the long-press regression on emulator, then independently prototype MapLibre Compose offline resource provisioning before replacing the native renderer. iOS toolchain is not yet configured.

- Stage 4 smoke tests dispatch touch events directly to the app's activity window because a long press opens an Android dialog before ACTION_UP, transferring focus away from the Activity; global input injection then fails with a cross-window UID error. This remains an end-to-end Compose hit-test, not a mocked gesture.

## APK update compatibility and versioning

- The direct-download Pages APK uses the repository's stable `development-signing.jks` debug certificate, **not** Google Play's app-signing certificate. Android refuses an APK from one signing channel over an existing installation from the other. Users must update via the same channel used for installation. Before changing channels, export recordings/pins: uninstalling deletes the app's private data.
- Pages builds set `TRAIL_VERSION_CODE` from the current commit's Unix timestamp divided by 60, yielding a monotonically nondecreasing code (> the earlier fixed `18`) without needing full Git history. The build continues to use the same package ID and development keystore, and a failed build must not replace a published APK.
- Play AABs retain their existing per-run version-code allocation and signing/upload workflow. The direct APK's code is independent of Play's because the Android signing identities are deliberately different.

## MapLibre Compose offline adapter, stage 5a (no renderer switch)

- At stage 5a, MapLibre Compose 0.20 required a newer toolchain than the shipping Kotlin 2.1.20 / Compose 1.8.2 build, so renderer validation ran separately. Stage 6a now aligns the production toolchain after those feasibility checks.
- `TileRepository.acceptsOfflineResource(url)` and `loadOfflineResource(url)` provide an explicitly offline, blocking asset/MBTiles/cache reader for MapLibre Compose's `MapResourceProvider`. The reader recognizes bundled glyph/sprite resources and the per-source `https://trail.tiles.invalid/<source>/<hash>/<z>/<x>/<y>` URL scheme generated by `styleSource`. It does **not** trigger remote fallback or change the existing OkHttp interception path.
- The Android instrumentation suite reads a tile from an actual bundled Tewksbury MBTiles archive, asserts bytes are returned, and verifies unknown/out-of-range/network URLs do not accidentally fetch remotely. Existing renderer and GPS tests remain.
- Next: compile an isolated MapLibre Compose 0.20 test renderer with a separate compatible toolchain, register `MapResourceProvider` against this interface, and validate offline styles, fonts, labels, contours, imagery and style changes on an emulator before considering any production renderer replacement. PMTiles URL range handling remains a separate area to evaluate.

## Isolated MapLibre Compose API experiment

- `experiments/maplibre-compose/` is a standalone Android project pinned to MapLibre Compose 0.20.0, Kotlin 2.4.20, and a separate test application ID. It does not participate in the normal Trail2 APK/AAB/Play releases or alter the root Gradle graph.
- Its `MapResourceProvider` serves a synthetic offline raster style and tile without network requests, to compile/probe the current MapLibre Compose runtime API. A separate Actions workflow builds the experimental APK; successful compilation alone does not validate renderer functionality.
- Real offline Tewksbury MBTiles data remains available via the separately tested production `TileRepository.loadOfflineResource` bridge. After the probe builds, connect the two and test genuine vector styling/fonts/PMTiles and emulator rendering before switching production.

## Stage 5b: real offline MapLibre Compose rendering

- Isolated Compose probe now bundles exactly the production-generated Bright style, sprite/fonts, OpenMapTiles Tewksbury MBTiles vector tiles, and Natural Earth MBTiles raster. Its read-only Android `OfflineProbeResources` serves the actual data through MapLibre Compose's `MapResourceProvider` without network access.
- The separate instrumentation workflow launches an Android emulator offline, checks vector/raster archive bytes and verifies renderer-initiated style/vector resource loads. The probe UI switches between bundled Bright and Natural Earth.
- This does not alter Trail2's production SDK, source sets, Play distribution, GPS recorder, user files, or offline runtime caches. PMTiles support/contours, online fallback/caching and iOS remain future parity checks.

## Stage 5c: PMTiles and contour renderer experiment

The standalone Compose probe now supports PMTiles v3 byte-range decoding and persisted HTTP 206 ranges, including a deterministic synthetic contour fixture and the same real NJ contour source URL configured in Trail2. A generated sample archive verifies PMTiles structure, MVT contour source-layer and render integration offline. The actual NJ dataset remains online-on-first-use and has not been claimed to be downloaded or fully offline; satellite source and production cache interoperability are separate parity steps. Production Trail2 remains unchanged.

## Stage 5d: offline renderer parity checks and recreation

- Strengthen the isolated Compose emulator suite to assert visible magenta pixels from
  synthetic PMTiles contour layers, rather than treating tile requests or screenshot
  existence as proof that contours rendered. Bright must also request bundled glyphs
  and sprites and draw a varied map viewport.
- Exercise Bright → Natural Earth → synthetic contours → Bright in one activity,
  checking raster/contour resource loads and map pixels. Recreate the activity on the
  contour style and retain that selection through Android saved instance state.
- Save screenshots for every renderer check in the probe workflow artifacts, including
  failures. All checks run with emulator Wi-Fi/mobile data disabled.
- Remaining before a production renderer switch: actual NJ remote-range rendering and
  offline replay, satellite/hybrid imagery, production cache interoperability, camera/
  gesture and user-overlay parity, then toolchain integration. iOS remains unconfigured.

## Stage 5e: actual NJ contour ranges and cold offline replay

- The isolated Compose workflow first runs deterministic offline renderer checks,
  then fetches the configured real NJ contour PMTiles archive for the Oldwick
  viewport and asserts visible magenta contour lines. It stops the app process,
  disables emulator networking, and launches a separate instrumentation invocation
  to verify the same real contour viewport from persisted byte ranges.
- Range counters distinguish disk hits, HTTP attempts/bytes and blocked cache misses.
  Replay disables HTTP in the provider too and requires zero HTTP attempts and zero
  missing ranges. A warm-up receipt and screenshots distinguish real-data checks
  from the separate synthetic fixture. No statewide download is performed.
- Bound HTTP range body reads to the requested size and log range/PMTiles errors
  for diagnosis. This remains an isolated Android prototype; production Trail2 and
  its user data are unchanged. Satellite/hybrid imagery, production cache format
  interoperability, camera/gesture/user-overlay parity and iOS remain to validate.

## Stage 5f: satellite/hybrid maps and raster-cache interoperability

- The isolated Compose app adds the real configured NJ 2020 satellite source and
  the production-generated hybrid style (imagery plus vector roads/trails/labels).
  It compiles the existing production `MbTilesCache.kt` verbatim through a generated
  source task, without changing the production module/toolchain or user files.
- The raster adapter uses the same source identity, cache version, filename hash,
  standard MBTiles schema and TMS row convention as `TileRepository`. Instrumentation
  writes cache data with the production class and reads it via the Compose adapter,
  then verifies the reverse path after close/reopen and cache-version invalidation.
  Bad image responses must not poison the cache. Online reads are size-bounded.
- Separate real-source warm/cold emulator invocations cover satellite and hybrid
  rendering, bundled hybrid label resources, saved style after recreation, and disk
  replay with zero HTTP attempts or uncached tile requests. Only the viewed Oldwick
  area is cached; no bulk satellite download is introduced.
- Production renderer replacement still requires PMTiles cache-format interoperability,
  camera/gesture and app overlay parity, then toolchain integration. Shared Compose
  UI/core already exist; iOS targets and platform adapters have not been added yet.

## Validated migration checkpoint (2026-10-29)

Stage 5e and 5f code is on `main`. Final verification at commit
`09e800c843ca7d99e657e8a218e457bc9e7a3fb3` passed:
https://github.com/mbligh/trail2/actions/runs/40987077419

- Five deterministic Android 35 emulator tests cover offline styles, synthetic
  contour pixels, activity recreation, glyph/sprite resources and production
  raster-cache interoperability.
- Four separate live-source instrumentation invocations cover actual NJ contours
  and actual NJ 2020 satellite/hybrid maps, each warmed online then replayed in a
  fresh process with networking disabled. Both replay tests require zero HTTP
  attempts and zero missing cached ranges/tiles.
- Assertion screenshots are copied into shell-owned emulator storage during tests
  so Gradle uninstall cannot delete them before artifact collection.
- Production APK/AAB renderer and release version have not changed in these stages.
  No new phone test is needed for the isolated prototype. Next implementation work:
  production PMTiles range-cache interoperability, camera/gesture and app-overlay
  parity, then integrate the newer renderer/toolchain. iOS targets/adapters follow
  after Android parity; no iOS compatibility claim has been made.

## Stage 5g: production PMTiles range-cache compatibility

- Extract `RangeCacheStorage` from the shipping OkHttp interceptor without changing
  its 24-character SHA-256 filename, `URL|Range|namespace` key, binary UTF headers/
  byte-count/payload layout, or FIFO 500/400 MiB eviction policy. The interceptor
  delegates to that storage; existing cached regions remain readable.
- Compile the same storage class into the isolated Compose project. Its PMTiles
  reader now stores the real HTTP Content-Range and MIME alongside bytes, uses the
  production cache directory/key/namespace, and replays native-written ranges.
  This is format compatibility inside the separate app sandbox, not cross-app file access.
- JVM tests read the old binary layout and independently inspect newly written
  files; emulator tests cover both read/write directions and reject mismatched
  server ranges. The real NJ online/cold-offline checks continue to run.

## Stage 5h: Compose camera and follow behavior

- Retain a single Compose `MapState` across basemap changes: its declared baseStyle
  updates directly, while the library's saved-state support retains center/zoom
  through activity recreation. Remove the experiment's map-per-style recreation.
- Match shipping limits (native zoom 2–18 / displayed zoom 3–19), disable rotate/pitch
  gestures and keep pitch zero. Observe pointer count without consuming input so
  one-finger panning pauses following while pinch zoom remains available.
- Keep latest position and following state through recreation, and test recenter,
  paused-follow fix updates, real Activity-dispatched drag input, and zoom input on
  the offline emulator. GPS acquisition remains outside the renderer.

## Stage 5i: app-owned overlays in the Compose renderer

- The isolated map now exercises declarative trail lines, polygons with holes,
  blue recording lines, and the unchanged production pin drawable/annotation tap.
  Sources update without replacing the basemap and reattach after style reloads.
- Verify independent trail visibility, continued recording/pin visibility, changed
  track data, recreation, pin hit-testing and polygon holes on the actual renderer.
  A Compose Canvas also projects the blue position dot/heading through the rendered
  viewport, independently of location acquisition and native map drawing.
- Fixture data is synthetic and opt-in for instrumentation; it is not a real user
  recording. Existing GPS acquisition, files and exports remain untouched.

## Stage 6a: align the production and prototype toolchains

- Upgrade the main build to the separately exercised Kotlin 2.4.20 / Compose 1.12.1 /
  AGP 9.2.0 / Gradle 9.4.1 stack, compile SDK 37 and keep target SDK 36/minimum 26.
  The shared module uses the current Android KMP library plugin. Shared JVM tests
  remain runnable on Linux; the Android app still uses its external Kotlin plugin.
- Update all APK, Play bundle and native-smoke SDK setup together. Preserve the
  package ID, stable signing keys, offline assets, current native renderer, GPS
  service and automatic publishing. Version 2.0.5 distinguishes this release.
- This step intentionally validates the compiler/build upgrade before changing
  the main renderer. The isolated camera and overlay regression work continues.

### Renderer warm-up coverage

The real contour and satellite warm-ups also cache a one-tile border around tiles requested by
the viewport (limited to at most 64 initial viewport requests). A cold renderer can
request an adjacent tile during edge/LOD scheduling that the prior run did not ask
for. Offline replay still requires zero missing byte ranges and zero HTTP attempts;
this does not relax the assertion or download statewide coverage.

## Phone regression gate for 2.0.5 (2026-10-30)

The production build now uses the newer shared UI/compiler stack and compatible
range-cache storage. APK tests/lint/publishing, release tests/lint/Play upload, and
ten production Android 35 emulator tests passed:

- APK: https://github.com/mbligh/trail2/actions/runs/41039554720
- Play internal upload: https://github.com/mbligh/trail2/actions/runs/41039554917
- Native rendering, cache, GPS and UI smoke: https://github.com/mbligh/trail2/actions/runs/41039248885

Before replacing the production renderer, update the existing Play installation
to 2.0.5 and check the actual Pixel's gestures/window lifecycle, offline map startup,
and locked-screen recording. Exercise the original basemap icon/menus, switching
Bright/satellite/hybrid, a plain pin and a one-second annotated pin, a short walk
with the screen locked for a minute or two, then stop summary and GPX/KML sharing.
The emulator checks do not establish real GNSS reception or physical touch behavior.

The production renderer is still MapLibre Android SDK 13.7.1. Shared Compose UI and
core algorithms are already in place; the newer Compose renderer remains isolated.
After the phone baseline passes, integrate that renderer and its resource provider
with the production host, retaining recording service, package/signing, files and
exports. Then add iOS targets/platform adapters; none are configured yet.

The final isolated renderer verification at
`fc579acb847ad669e375ce9b35fb66a3ead52be7` passed all eight deterministic offline
checks plus four actual-source warm/cold checks (twelve total):
https://github.com/mbligh/trail2/actions/runs/41040823614

That includes the production PMTiles/raster cache formats, camera/follow/recenter,
zoom after recreation, styled app-layer fixtures, pin artwork/taps, polygon-hole
pixels, and real contour/satellite/hybrid replay with zero HTTP attempts or cache
misses. Assertion screenshots and logs are stored in the workflow artifacts.
The remaining gate is the 2.0.5 physical-phone baseline described above, before
production renderer integration. All work so far is committed directly on `main`.

## 2.0.6: keep the screen clear of system bars and camera cutouts

Physical Pixel testing of 2.0.5 exposed header overlap with the clock/camera and
bottom controls under three-button navigation. Replace the manual ComposeView
inset listener/View padding with one Compose `WindowInsets.safeDrawing` wrapper
around the shared screen. Enable edge-to-edge explicitly, paint the inset area
white and keep dark system icons. This handles navigation bars, status bars and
cutouts on all sides without double padding. Recording/GPS and map data are unchanged.

An instrumentation regression compares every header/corner control and the map
surface with the actual window's system-bar/cutout bounds, including exact top/
bottom layout checks. CI runs it with three-button navigation, gesture navigation
plus a tall cutout, and landscape rotation, and preserves screenshots for review.
Automatic APK and Play publishing continue; version 2.0.6 is the phone-test patch.

Validated the 2.0.6 fix at `0e858e626ab235de84da8e2b827b63beba01d157`:

- Thirteen production emulator checks passed: the full eleven-test suite in
  three-button mode, plus focused portrait-gesture/cutout and landscape checks:
  https://github.com/mbligh/trail2/actions/runs/41044466991
- Screenshots for all three configurations were retained and visually reviewed;
  header and corner controls stay clear of system UI, without double padding.
- APK and Play publishing passed:
  https://github.com/mbligh/trail2/actions/runs/41044467033
  https://github.com/mbligh/trail2/actions/runs/41044467113
- The twelve isolated renderer checks also passed after fixing screenshot
  persistence (UiAutomation runs individual programs, not shell command chains):
  https://github.com/mbligh/trail2/actions/runs/41044467190

Next: update the physical Pixel through Play to 2.0.6 and verify that both edges are
clear, then complete the remaining phone regression checks before renderer integration.
