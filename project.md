# Trail 2 — requirements and implementation

Updated 2026-10-29 for recording pin text and trip summaries (2.0.4).

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

Native Kotlin Activity/UI, Android 8+/API 26 minimum, compile/target 36, JDK 17,
AGP 8.9.1, Kotlin 2.1.20, Gradle 8.13. MapLibre adds native libraries; validate 16 KiB
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
