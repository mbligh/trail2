# Trail 2 (MapLibre development)

This is an independent development copy of [Trail](https://github.com/mbligh/trail). It currently retains the osmdroid implementation while the mapping-engine migration is planned. The Android application ID is `mbligh.trail` (separate from the original `net.pocketmap`), so both applications can be installed simultaneously. The on-device label is **Trail 2**. The source namespace remains `net.pocketmap` temporarily; it does not control the Play package ID.

## Independent distribution setup

- The mirrored repository includes its own APK Pages and Play bundle workflows. GitHub Pages needs to be enabled for this repository (Settings → Pages → Source: GitHub Actions); it is not copied by `git push --mirror`.
- The APK workflow publishes `Trail2.apk` at `https://mbligh.github.io/trail2/` once Pages is configured.
- The Play workflow builds a signed bundle only if `TRAIL_UPLOAD_KEYSTORE_BASE64` and `TRAIL_UPLOAD_PASSWORD` secrets exist in **this** repository. Add `TRAIL_PLAY_SERVICE_ACCOUNT_JSON` for Play uploads.
- **Play publishing is disabled by default**. Create the distinct `mbligh.trail` app in Play Console first, confirm the app signing/upload key and grant the service account access to it, then set the repository Actions variable `ENABLE_TRAIL2_PLAY_UPLOAD=true` to enable internal testing uploads.
- Repository secrets and Pages settings are not transferred by cloning or mirroring. Use a dedicated upload key for this separate Play app if desired. Never commit upload signing secrets.
- The bundled `development-signing.jks` signs local debug APKs; it is not a substitute for the Play upload key.

## Original project documentation

The original README follows for implementation details; historical install URLs and package IDs there describe the **old** app, not Trail 2.

---

# Trail

Personal native Android map: OpenStreetMap street tiles, blue location dot, foreground GPS following, pinch zoom, and recenter. Dragging pauses following. No account, API key, or Google Play Services required.

## Install
Download the latest APK from https://mbligh.github.io/trail/Trail.apk (or visit https://mbligh.github.io/trail/).

Open Trail.apk on your phone, allow installation from the browser/file manager if Android asks, then grant location access **while using the app**. Choose precise location for GPS accuracy. Enable the phone's Location setting. The first GPS fix may take a little time outdoors. Internet is needed for uncached tiles.

## Build
Open this folder in Android Studio, install Android SDK 36, and select Build APK(s). Alternatively use JDK 17 and `./gradlew assembleDebug`. The APK is under app/build/outputs/apk/debug/. The project targets API 36 and supports Android 8 onward, including newer Android releases. It has not been tested on a physical Android 17 device.

Map-following location updates stop when the app is paused; an explicitly started recording continues in its foreground service. Approximate location permission works but is less precise. Map zoom and position persist. Tiles are cached by osmdroid using its standard HTTP expiration handling; there is no bulk/offline download feature. Attribution is visible and links to https://www.openstreetmap.org/copyright.

## Components and privacy
Kotlin, Android LocationManager, osmdroid 6.1.20 (Apache-2.0). osmdroid is archived upstream, so it is pinned for this small initial version; a maintained engine can replace it later. Tile requests go to https://tile.openstreetmap.org and reveal the displayed area and IP to the tile service. Location itself stays on the phone. No analytics or backend.

OpenStreetMap tile policy: https://operations.osmfoundation.org/policies/tiles/.

## Phone checks
- Grant precise location while using the app; check the dot and follow behavior outdoors.
- Walk a short distance; position should follow without changing zoom.
- Drag the map; following should pause. Tap Recenter to restore it.
- Pinch zoom; verify your zoom remains when walking and reopening.
- Deny permission, then grant it via Recenter; test with Location disabled/re-enabled.
- Rotate the phone, switch away/back, and check layout and location recovery.

The included `development-signing.jks` is the personal development signing key (standard Android debug password `android`, alias `androiddebugkey`). Keep it with this project so later debug APKs can replace this installation. This is a development build, not a Play Store release.

Validation: APK compilation and Android lint completed successfully (no lint errors; cosmetic/accessibility and internationalization warnings remain). APK signature verified. Physical-device GPS and gesture testing remains to be done.

The application ID remains `net.pocketmap` so Trail installs as an update to the original Pocket Map app. Source is in `mbligh/tools/trail`.

## TTA Trails overlay (1.2)

At startup, Trail fetches this map's current KML export in a background thread:
https://www.google.com/maps/d/kml?mid=1zy-nQvF6-6P7JSs3paMU8byWHoE&forcekml=1

The map must remain accessible to anyone with the link; no Google sign-in or API key is used. The My Maps export endpoint is not a versioned API and may change. The last validated export is kept in private app storage and displayed immediately on subsequent starts; if the refresh fails, the cached overlay stays visible. An internet connection is still needed for uncached OpenStreetMap tiles.

The **Trails** menu can refresh, zoom to the overlay, or hide/show it. Zooming to trails pauses GPS following; Recenter restores it. Lines and property polygons retain KML colors; place markers use the map engine's standard pins. Tap a feature to see its name. The status line distinguishes refreshed data, cached data, and download failures. Polygon holes and multi-geometry placemarks are supported. Remote icons, HTML balloons, ground overlays, and network-link exports are not loaded.

Run `./gradlew testDebugUnitTest lintDebug assembleDebug` to validate. Parser tests cover style maps, KML color order, coordinates, polygon holes, multi-geometry, and invalid/unsafe XML. For a full-map smoke test, set `TRAIL_KML_SMOKE` to the absolute path of a freshly downloaded export; the optional test expects the initial TTA Trails map's 108 lines, 10 points, and no polygons in the Trails layer.

## Trails layer and My Maps icons (1.3)

Only the KML folder named **Trails** is rendered; other folders are ignored. The initial map contains 108 lines and 10 places in that layer. Styles remain resolved from the whole document. The current My Maps camper, pin, blank marker, and medical artwork is bundled so it works offline; KML marker scale is respected, and blank markers use the KML tint. The camper icon is the actual blue My Maps symbol, centered at its location. New unsupported icon types fall back to a standard pin until artwork is added.

My Maps KML sometimes exports custom symbols as a generic blank image. For the camper and blue pin, the original composed icon URLs were obtained from the My Maps viewer and the images bundled in drawable-nodpi. These Google icon endpoints are not a versioned API; startup refresh only fetches KML and does not depend on fetching icons.

## Recording and GPX sharing (1.4)

Tap the red circular button at the bottom right to start recording; it becomes a red stop square. GPS fixes are saved at least 30 seconds apart (actual delivery depends on Android and GPS availability). A red line shows the current/latest recording. Recording uses a location foreground service and continues when the activity is closed or the screen turns off; the notification offers a Stop action. Start recording while Trail is visible, with precise location and GPS enabled. Allow notifications to see the recording notification normally.

Tap Stop to save and optionally share a GPX. **Trails → Share recorded GPX…** lists saved recordings for later sharing. Android's share chooser supplies a GPX attachment to Gmail or another app; Trail does not send it itself. Each new recording creates a separate file; earlier recordings remain in private app storage. No storage permission or backend is required. GPX includes latitude, longitude, UTC timestamps, and elevation when GPS supplies it. GPS outages longer than 90 seconds create separate GPX segments to avoid drawing across a gap.

Points are appended immediately, so completed points survive process termination. If Android kills the service or the phone restarts, recording stops; reopen Trail and start a new recording. The existing recorded points remain available for sharing. Sampling does not fabricate points when GPS is unavailable. The map's foreground following is separate from recording.

Validation includes GPX XML parsing, escaped track names, timestamps/elevation, gap segmentation, and recovery of complete saved points. Real-device checks still needed: record a walk with the screen off for several minutes, stop from the notification, reopen Trail, and share the GPX to Gmail.

## Location report flag (1.5)

The red flag at bottom left opens an editable message containing the current latitude and longitude to six decimal places, with a comma and space between them. Add a description, then tap Share to choose Gmail or another app. The location is captured when the flag is tapped, so editing does not move the reported point. A fix older than 30 seconds is rejected; wait for a fresh location and try again. The dialog displays GPS accuracy separately from coordinate precision.

Email apps receive `mbligh@mbligh.org` through Android's standard recipient extra and a subject of "Trail location report". Whether a particular receiving app uses that extra is up to that app. Nothing is sent automatically; the receiving app lets you review and send the message.

## Optional report photo (1.6)

Tap the red flag, enter a description, then choose **Take photo** to open the phone's camera app. Confirm the photo to return to the report; **Retake photo** replaces it. Canceling the camera keeps the existing description and any previous photo. The captured coordinates and draft survive activity recreation while using the camera. Tap **Share** to send the full-resolution JPEG alongside the report text through Android's share chooser, with the same suggested email recipient. Sharing without a photo still works. Receiving apps decide how to use the text and recipient extras.

Photos are kept in private app storage and exposed only through individual URI grants. Camera write access is revoked when it returns; share targets receive read access. No camera or storage permission is requested because an existing camera app handles capture. Attachments remain available after sharing so receiving apps can read them; uninstalling Trail removes them.

Real-device validation: try capture, cancel, retake, rotate while in the camera, and share to Gmail; confirm both the coordinates/description and photo arrive in the draft.

## Blue recorded path and satellite report link (1.7)

While recording, the path drawn from saved GPS points is blue and grows as the 30-second samples arrive. It remains visible after stopping or reopening Trail. At least two points are needed for a line; GPS gaps over 90 seconds remain separate segments.

Flag reports now include six-decimal coordinates and a Google Maps URL centered on the same coordinates at zoom 18 with satellite basemap requested. This uses the documented Maps URL format (https://developers.google.com/maps/documentation/urls/get-started). The satellite map opens centered on the location, without a marker. The URL remains part of the editable message, including when sharing a photo.

## Automated APK builds and GitHub Pages

The canonical source is https://github.com/mbligh/trail. The previous `mbligh/tools/trail` directory has been removed; its earlier commit history remains in the tools repository.

Pushes to `main` that change `app/**`, Gradle build files, the wrapper, signing key, workflow, or download page run unit tests and lint, build the signed debug APK, and deploy `Trail.apk` and a download page to GitHub Pages. Pull requests run the same checks without publishing. You can also run **Build and publish APK** manually from Actions. The existing development signing key is retained for installation compatibility.

One-time setup: in **Settings → Pages → Build and deployment → Source**, select **GitHub Actions**, then rerun the workflow. GitHub Pages serves the most recent successful main-branch build; failed builds do not replace the previous APK.
