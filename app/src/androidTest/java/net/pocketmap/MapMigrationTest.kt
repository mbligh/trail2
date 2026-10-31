package net.pocketmap

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.IOException

/** Exercises the shipping Compose renderer and local tile route, with all network blocked. */
@RunWith(AndroidJUnit4::class)
class MapMigrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun grantLocationForUiTests() {
        for (permission in listOf("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.$permission")).use { it.readBytes() }
        }
    }
    private fun renderer(activity: MainActivity): TrailMap = MainActivity::class.java.getDeclaredField("map").apply { isAccessible = true }.get(activity) as TrailMap
    private fun offline(map: TrailMap) { map.tiles.allowNetwork = false }
    private fun isolateGps(activity: MainActivity) {
        // Prevent the emulator's live 5 m fix from competing with this test's injected fixes.
        val manager = MainActivity::class.java.getDeclaredField("manager").apply { isAccessible = true }.get(activity) as LocationManager
        manager.removeUpdates(activity)
        (MainActivity::class.java.getDeclaredField("recentLocations").apply { isAccessible = true }.get(activity) as ArrayDeque<*>).clear()
    }
    private fun following(activity: MainActivity, value: Boolean) {
        MainActivity::class.java.getDeclaredField("following").apply { isAccessible = true }.setBoolean(activity, value)
    }
    private fun awaitRendered(scenario: ActivityScenario<MainActivity>, description: String,
        layerIds: Set<String>? = null, predicate: (List<org.maplibre.spatialk.geojson.Feature<org.maplibre.spatialk.geojson.Geometry, kotlinx.serialization.json.JsonObject?>>) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < end) {
            var map: TrailMap? = null
            scenario.onActivity { map = renderer(it) }
            val features = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val state = map?.mapState
                    if (state?.viewport == null) null else state.queryRenderedFeatures(
                        androidx.compose.ui.unit.DpRect(0.dp, 0.dp,
                            (map!!.width / context.resources.displayMetrics.density).dp,
                            (map!!.height / context.resources.displayMetrics.density).dp), layerIds)
                }
            }
            if (features != null && predicate(features)) return
            Thread.sleep(100)
        }
        fail("Timed out: $description")
    }
    private fun await(scenario: ActivityScenario<MainActivity>, description: String, predicate: (TrailMap) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            scenario.onActivity { ready = predicate(renderer(it)) }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out: $description")
    }

    @Test fun offlineRendererPreservesAppLayersAcrossStyleChanges() {
        context.getSharedPreferences("map", Context.MODE_PRIVATE).edit().clear().putString("basemap", "bright").putFloat("lat", 40.69f).putFloat("lon", -74.78f).putFloat("zoom", 15f).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val errors = mutableListOf<String>()
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredField("following").apply { isAccessible = true }.setBoolean(activity,false)
                renderer(activity).apply { onError = { errors.add(it) }; offline(this); setCenter(LatLng(40.69,-74.78)) }
                activity.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 40.69; longitude = -74.78; accuracy = 4f
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); time = System.currentTimeMillis()
                })
            }
            awaitRendered(scenario, "Bright vector roads render from bundled tiles") { features ->
                features.any { it.properties?.containsKey("class") == true }
            }
            val document = TrailKml.Document("Test trails", listOf(TrailKml.Feature("Offline test trail", "LineString", listOf(TrailKml.Coordinate(40.688,-74.782),TrailKml.Coordinate(40.692,-74.778)), emptyList(), TrailKml.Style())))
            val icons = listOf("1763", "1899", "1205", "503").mapIndexed { index, icon ->
                TrailKml.Feature("Original icon $icon", "Point", listOf(
                    TrailKml.Coordinate(40.69, -74.784 + index * 0.002)), emptyList(),
                    TrailKml.Style(iconId = icon, iconColor = 0xff009d57.toInt()))
            }
            // Exercise the actual portable importer before renderer/style/tap checks.
            val kml = buildString {
                append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Test trails</name>""")
                for (icon in icons) {
                    val id = icon.style.iconId
                    append("""<Style id="icon-$id-normal"><IconStyle><color>ff579d00</color></IconStyle></Style>""")
                    append("""<StyleMap id="mapped-$id"><Pair><key>normal</key><styleUrl>#icon-$id-normal</styleUrl></Pair></StyleMap>""")
                }
                append("<Folder><name>Trails</name>")
                for (feature in document.features + icons) {
                    append("<Placemark><name>${feature.name}</name>")
                    if (feature.style.iconId.isNotEmpty()) append("<styleUrl>#mapped-${feature.style.iconId}</styleUrl>")
                    append("<${feature.kind}><coordinates>")
                    append(feature.points.joinToString(" ") { "${it.longitude},${it.latitude}" })
                    append("</coordinates></${feature.kind}></Placemark>")
                }
                append("</Folder></Document></kml>")
            }
            val completeDocument = TrailKml.parse(kml.toByteArray())
            assertEquals(document.copy(features = document.features + icons), completeDocument)
            val pin = RecordedPins.Pin(40.691, -74.78, 1000, null, 4f, "Broken gate")
            val points = listOf(RecordedTrack.Point(40.688,-74.782,1000,null,4f),RecordedTrack.Point(40.692,-74.778,31_000,null,3f))
            scenario.onActivity { renderer(it).apply { showTrails(completeDocument,true); showRecording(points,listOf(pin)) } }
            awaitRendered(scenario, "trails and recording", setOf("trail-lines", "trail-recording")) { it.size >= 2 }
            fun assertArtwork() {
                awaitRendered(scenario, "original icons and annotated pin") { features ->
                    val names = features.mapNotNull { it.properties?.get("name")?.toString()?.trim('"') }
                    icons.all { it.name in names } && names.any { it.contains("Broken gate") }
                }
            }
            assertArtwork()
            val config = MapConfiguration(context)
            for (choice in listOf("hybrid", "bright")) {
                var previousStyle = 0
                scenario.onActivity { renderer(it).apply {
                    previousStyle = styleLoads
                    setBasemap(config.basemaps.first { b -> b.name == choice })
                } }
                await(scenario, "$choice actually finishes loading its new style") { it.styleLoads > previousStyle }
                awaitRendered(scenario, "$choice restores trails and recording", setOf("trail-lines", "trail-recording")) { it.size >= 2 }
                assertArtwork()
            }
            scenario.onActivity { renderer(it).apply {
                val state = mapState!!
                state.click(state.screenLocationFromPosition(org.maplibre.spatialk.geojson.Position(pin.longitude, pin.latitude))!!)
            } }
            await(scenario, "annotated pin tap label") { map ->
                val popup = TrailMap::class.java.getDeclaredField("popup").apply { isAccessible = true }.get(map) as android.widget.PopupWindow?
                popup?.isShowing == true && (popup.contentView as android.widget.TextView).text.contains("Broken gate")
            }
            scenario.onActivity { renderer(it).apply { onPause(); setTrailsVisible(false) } }
            awaitRendered(scenario, "hide trails leaves recording visible", setOf("trail-lines", "trail-recording")) { features ->
                features.size == 1 && features.single().properties?.get("name")?.toString() == "\"Recorded trail\""
            }
            scenario.onActivity { renderer(it).setTrailsVisible(true) }
            assertArtwork()

            assertEquals(emptyList<String>(), errors)
            awaitRendered(scenario, "Bright streets restored for screenshot") { features ->
                features.any { it.properties?.containsKey("class") == true }
            }
            Thread.sleep(500)
            instrumentation.waitForIdleSync()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/maplibre-offline.png")).use { it.readBytes() }
        }
    }

    @Test fun composeCameraSurvivesRecreationAndRealDragPausesFollowing() {
        context.getSharedPreferences("map", Context.MODE_PRIVATE).edit()
            .putFloat("lat", 40.69f).putFloat("lon", -74.78f).putFloat("zoom", 15f).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "Compose viewport") { it.mapState?.viewport != null }
            scenario.onActivity { activity ->
                isolateGps(activity); following(activity, false)
                renderer(activity).apply { setCenter(LatLng(40.697, -74.778)); setZoom(14.5) }
            }
            fun cameraMatches() = await(scenario, "saved center and display zoom") {
                kotlin.math.abs(it.mapCenter.latitude - 40.697) < 0.000001 &&
                    kotlin.math.abs(it.mapCenter.longitude + 74.778) < 0.000001 &&
                    kotlin.math.abs(it.zoomLevelDouble - 14.5) < 0.001
            }
            cameraMatches()
            val config = MapConfiguration(context)
            scenario.onActivity { renderer(it).setBasemap(config.basemaps.first { b -> b.name == "hybrid" }) }
            cameraMatches()
            scenario.recreate()
            await(scenario, "viewport after recreation") { it.mapState?.viewport != null }
            scenario.onActivity {
                isolateGps(it)
                assertFalse("Following must survive recreation", MainActivity::class.java.getDeclaredField("following").apply { isAccessible = true }.getBoolean(it))
            }
            cameraMatches()
            scenario.onActivity { following(it, true) }
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, step: Int) {
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val properties = android.view.MotionEvent.PointerProperties().apply {
                        id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
                    }
                    val coordinates = android.view.MotionEvent.PointerCoords().apply {
                        x = root.width * 0.4f + step * 12; y = root.height * 0.5f
                        pressure = 1f; size = 1f
                    }
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                        arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, 0)
            for (step in 1..12) { Thread.sleep(20); touch(android.view.MotionEvent.ACTION_MOVE, step) }
            touch(android.view.MotionEvent.ACTION_UP, 12)
            await(scenario, "one-finger drag moves map and pauses following") {
                kotlin.math.abs(it.mapCenter.longitude + 74.778) > 0.00001
            }
            scenario.onActivity { activity ->
                assertFalse(MainActivity::class.java.getDeclaredField("following").apply { isAccessible = true }.getBoolean(activity))
                val state = renderer(activity).mapState!!
                assertEquals(0.0, state.cameraPosition.bearing, 0.0001)
                assertEquals(0.0, state.cameraPosition.pitch, 0.0001)
                state.scaleBy(2.0, androidx.compose.ui.unit.DpOffset(160.dp, 220.dp))
            }
            await(scenario, "zoom input retains display convention") { it.zoomLevelDouble > 15.0 }
        }
    }

    @Test fun accurateGpsSurvivesNetworkReadingsAndRecorderSelectsAccurateRecentFix() {
        fun fix(provider: String, accuracy: Float, nanos: Long, lat: Double) = Location(provider).apply { latitude = lat; longitude = -74.78; this.accuracy = accuracy; elapsedRealtimeNanos = nanos; time = System.currentTimeMillis() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                isolateGps(activity)
                val now = SystemClock.elapsedRealtimeNanos()
                activity.onLocationChanged(fix(LocationManager.GPS_PROVIDER,4f,now-2_000_000_000,40.69))
                activity.onLocationChanged(fix(LocationManager.NETWORK_PROVIDER,1200f,now,41.5))
                activity.onLocationChanged(fix(LocationManager.GPS_PROVIDER,300f,now-1_000_000_000,41.6))
                val current = MainActivity::class.java.getDeclaredField("current").apply { isAccessible = true }.get(activity) as Location
                assertEquals(4f,current.accuracy)
                assertEquals(40.69,current.latitude,0.000001)
            }
            // Simulate screen-off/background and resume; recorder GPS still wins over network.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            RecordingService.running = true
            RecordingService.latestGpsFix = fix(LocationManager.GPS_PROVIDER,3f,SystemClock.elapsedRealtimeNanos(),40.691)
            try {
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    activity.onLocationChanged(fix(LocationManager.NETWORK_PROVIDER,2000f,SystemClock.elapsedRealtimeNanos(),41.7))
                    val current = MainActivity::class.java.getDeclaredField("current").apply { isAccessible = true }.get(activity) as Location
                    assertEquals(3f,current.accuracy)
                    assertEquals(40.691,current.latitude,0.000001)
                }
            } finally { RecordingService.running = false; RecordingService.latestGpsFix = null }
        }
        val choose = RecordingService::class.java.getDeclaredMethod("chooseSample",Collection::class.java).apply { isAccessible = true }
        val now = SystemClock.elapsedRealtimeNanos()
        val selected = choose.invoke(RecordingService(),listOf(fix("gps",4f,now-5_000_000_000,40.69),fix("gps",6f,now-1_000_000_000,40.691),fix("gps",300f,now,41.7))) as Location
        assertEquals(6f,selected.accuracy)
        assertEquals(40.691,selected.latitude,0.000001)
    }

    @Test fun composePinHoldOpensBeforeReleaseAndDoesNotDuplicatePinOnTap() {
        val directory = java.io.File(context.filesDir, "recordings").apply { mkdirs() }
        val track = java.io.File(directory, "compose-pin-${System.nanoTime()}.csv")
        track.writeText("40.69,-74.78,1000,,4\n")
        val prefs = context.getSharedPreferences("map", Context.MODE_PRIVATE)
        val oldTrack = prefs.getString("recorded_track", null)
        prefs.edit().putString("recorded_track", track.name).commit()
        fun gps() = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 40.69; longitude = -74.78; accuracy = 4f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); time = System.currentTimeMillis()
        }
        fun dialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("pinDialog")
            .apply { isAccessible = true }.get(activity) as android.app.AlertDialog?
        fun editor(activity: MainActivity) = MainActivity::class.java.getDeclaredField("pinEditor")
            .apply { isAccessible = true }.get(activity) as android.widget.EditText
        RecordingService.running = true
        RecordingService.latestGpsFix = gps()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Dispatch real-time pointer events directly to the Activity decor view.
                // This verifies the 1,000 ms hold without test-clock timing tricks.
                val density = context.resources.displayMetrics.density
                var x = 0f
                var y = 0f
                var downTime = 0L
                fun touch(action: Int) {
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        // Trail status may disappear or reappear after Activity recreation.
                        // Recompute the Compose button's position before each new press.
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            val mapOrigin = IntArray(2)
                            val decorOrigin = IntArray(2)
                            renderer(activity).getLocationOnScreen(mapOrigin)
                            activity.window.decorView.getLocationOnScreen(decorOrigin)
                            x = mapOrigin[0] - decorOrigin[0] + (16f + 37f / 2f) * density
                            y = mapOrigin[1] - decorOrigin[1] + (16f + 37f / 2f) * density
                        }
                        downTime = SystemClock.uptimeMillis()
                    }
                    val event = android.view.MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), action, x, y, 0
                    )
                    try {
                        // Target the app's own window, not the globally focused window.
                        // During hold, an AlertDialog becomes focused while the finger is
                        // down; global injection can then target another UID/window.
                        scenario.onActivity { activity -> activity.window.decorView.dispatchTouchEvent(event) }
                    } finally { event.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN)
                Thread.sleep(1_200)
                scenario.onActivity {
                    assertTrue("Hold must open before release", dialog(it)?.isShowing == true)
                    assertTrue(RecordedPins.read(track).isEmpty())
                }
                touch(android.view.MotionEvent.ACTION_UP)
                scenario.onActivity {
                    assertTrue("Release must not save another pin", RecordedPins.read(track).isEmpty())
                    editor(it).setText("Broken gate")
                }
                scenario.recreate()
                scenario.onActivity {
                    assertEquals("Broken gate", editor(it).text.toString())
                    dialog(it)!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                }
                instrumentation.waitForIdleSync()
                assertEquals("Broken gate", RecordedPins.read(track).single().text)
                // A following short touch must still create a plain pin.
                RecordingService.latestGpsFix = gps() // Do not reuse a stale fix after recreation.
                touch(android.view.MotionEvent.ACTION_DOWN)
                touch(android.view.MotionEvent.ACTION_UP)
                instrumentation.waitForIdleSync()
                assertEquals(2, RecordedPins.read(track).size)
                assertEquals("", RecordedPins.read(track).last().text)
            }
        } finally {
            RecordingService.running = false; RecordingService.latestGpsFix = null
            prefs.edit().putString("recorded_track", oldTrack).commit()
            java.io.File(directory, track.nameWithoutExtension + ".pins").delete()
            track.delete()
        }
    }

    @Test fun composeOfflineBridgeReadsExistingMbtilesWithoutNetwork() {
        // Use the exact bundled MBTiles seed instead of synthetic mock tiles.
        val copied = java.io.File(context.cacheDir, "bridge-seed-${System.nanoTime()}.mbtiles")
        try {
            context.assets.open("maps/tewksbury-streets.mbtiles").use { source ->
                copied.outputStream().use { target -> source.copyTo(target) }
            }
            val database = android.database.sqlite.SQLiteDatabase.openDatabase(
                copied.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            val coords = database.rawQuery(
                "SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles LIMIT 1", null).use { rows ->
                assertTrue("Bundled Tewksbury archive must contain tiles", rows.moveToFirst())
                val z = rows.getInt(0)
                val x = rows.getInt(1)
                val tms = rows.getLong(2)
                Triple(z, x, ((1L shl z) - 1L - tms).toInt())
            }
            database.close()
            TileRepository(context).use { repository ->
                val source = repository.styleSource(
                    "offline-bridge-test",
                    JSONObject("""{"type":"vector","archive":"maps/tewksbury-streets.mbtiles"}""")
                )
                val template = source.getJSONArray("tiles").getString(0)
                val url = template.replace("{z}", coords.first.toString())
                    .replace("{x}", coords.second.toString())
                    .replace("{y}", coords.third.toString())
                assertTrue(repository.acceptsOfflineResource(url))
                val bytes = repository.loadOfflineResource(url)
                assertNotNull("Provider should read bundled bytes without network", bytes)
                assertTrue(bytes!!.isNotEmpty())
                // An unknown route is a cache miss, not an implicit network request.
                assertNull(repository.loadOfflineResource("https://trail.tiles.invalid/absent/0/0/0"))
                assertFalse(repository.acceptsOfflineResource("https://tile.openstreetmap.org/0/0/0.png"))
                assertNull(repository.loadOfflineResource("https://tile.openstreetmap.org/0/0/0.png"))
                assertNull(repository.loadOfflineResource("https://trail.tiles.invalid/invalid/99/0/0"))
            }
        } finally { copied.delete() }
    }

    @Test fun composeProviderServesLocalPmtilesThroughItsRegisteredTileJsonRoute() {
        // One valid v3 directory entry and a two-byte vector tile. No external data.
        val file = java.io.File(context.cacheDir, "provider-${System.nanoTime()}.pmtiles")
        val header = java.nio.ByteBuffer.allocate(127).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray()); header.put(3.toByte())
        header.putLong(8, 127); header.putLong(16, 5)
        header.putLong(56, 132); header.putLong(64, 2)
        header.put(97, 1.toByte()); header.put(98, 1.toByte()); header.put(99, 1.toByte())
        val tile = byteArrayOf(26, 0)
        file.writeBytes(header.array() + byteArrayOf(1, 0, 1, 2, 1) + tile)
        try {
            ComposeMapResources(context).use { resources ->
                resources.tiles.allowNetwork = false
                val style = JSONObject().put("version", 8).put("layers", org.json.JSONArray())
                    .put("sources", JSONObject().put("local", JSONObject().put("type", "vector")
                        .put("url", "pmtiles://" + android.net.Uri.fromFile(file))))
                val rewritten = JSONObject(resources.style(style))
                val url = rewritten.getJSONObject("sources").getJSONObject("local").getString("url")
                assertTrue(url.startsWith("https://trail.pmtiles.invalid/"))
                val load = ComposeMapResources::class.java.getDeclaredMethod("load", String::class.java)
                    .apply { isAccessible = true }
                val metadata = JSONObject((load.invoke(resources, url) as ByteArray).decodeToString())
                assertEquals(0, metadata.getInt("minzoom")); assertEquals(0, metadata.getInt("maxzoom"))
                val template = metadata.getJSONArray("tiles").getString(0)
                fun request(z: Int) = template.replace("{z}", z.toString()).replace("{x}", "0").replace("{y}", "0")
                assertArrayEquals(tile, load.invoke(resources, request(0)) as ByteArray)
                assertNull(load.invoke(resources, request(1)))
                assertNull(load.invoke(resources, "https://trail.pmtiles.invalid/unknown/0/0/0"))
            }
        } finally { file.delete() }
    }

    @Test fun offlineOnlyArchiveKeepsZoomLimitsAcrossSwitches() {
        TileRepository(context).use { repo ->
            val definition = JSONObject("""{"type":"vector","archive":"maps/tewksbury-streets.mbtiles"}""")
            val first = repo.styleSource("offline-only",definition)
            val again = repo.styleSource("offline-only",definition)
            assertEquals(14,first.getInt("maxzoom"))
            assertEquals(14,again.getInt("maxzoom"))
            val local = repo.styleSource("local-pmtiles",JSONObject("""{"type":"vector","url":"pmtiles://asset://maps/tewksbury-streets.mbtiles"}"""))
            assertTrue(local.getString("url").startsWith("pmtiles://file:///"))
        }
    }

    @Test fun reusedSourceIdsAndUpdatedSeedsCannotMixNativeCacheEntries() {
        TileRepository(context).use { repo ->
            val first = repo.styleSource("shared",JSONObject("""{"type":"vector","cacheVersion":"v1","tiles":["https://example.org/a/{z}/{x}/{y}"]}"""))
            val second = repo.styleSource("shared",JSONObject("""{"type":"vector","cacheVersion":"v1","tiles":["https://example.org/b/{z}/{x}/{y}"]}"""))
            assertNotEquals(first.getJSONArray("tiles").getString(0),second.getJSONArray("tiles").getString(0))
            val client = OkHttpClient.Builder().addInterceptor(repo).addInterceptor { chain ->
                val data = if (chain.request().url.pathSegments.first() == "a") byteArrayOf(1) else byteArrayOf(2)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(data.toResponseBody("application/x-protobuf".toMediaType())).build()
            }.build()
            fun get(source: JSONObject) = client.newCall(Request.Builder().url(source.getJSONArray("tiles").getString(0).replace("{z}","14").replace("{x}","200").replace("{y}","300")).build()).execute().use { it.body!!.bytes() }
            assertArrayEquals(byteArrayOf(1),get(first))
            assertArrayEquals(byteArrayOf(2),get(second))
        }
    }

    @Test fun remoteArchiveRangesRemainAvailableOfflineAfterReopen() {
        val definition = JSONObject("""{"type":"vector","url":"pmtiles://https://example.org/map.pmtiles","cacheVersion":"${SystemClock.elapsedRealtimeNanos()}"}""")
        val request = Request.Builder().url("https://example.org/map.pmtiles").header("Range","bytes=0-2").build()
        val data = byteArrayOf(1,2,3)
        TileRepository(context).use { repo ->
            repo.styleSource("range-test",definition)
            val client = OkHttpClient.Builder().addInterceptor(repo).addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content").header("Content-Range","bytes 0-2/100").body(data.toResponseBody("application/octet-stream".toMediaType())).build() }.build()
            assertArrayEquals(data,client.newCall(request).execute().use { it.body!!.bytes() })
        }
        TileRepository(context).use { repo ->
            repo.styleSource("range-test",definition)
            val client = OkHttpClient.Builder().addInterceptor(repo).addInterceptor { throw IOException("offline") }.build()
            client.newCall(request).execute().use { response ->
                assertEquals(206,response.code)
                assertEquals("bytes 0-2/100",response.header("Content-Range"))
                assertArrayEquals(data,response.body!!.bytes())
            }
        }
    }

    @Test fun runtimeTilesRemainAvailableOfflineAfterRepositoryReopens() {
        val id = "cache-test"
        val definition = JSONObject("""{"type":"vector","maxzoom":14,"tiles":["https://example.org/{z}/{x}/{y}"],"cacheVersion":"${SystemClock.elapsedRealtimeNanos()}"}""")
        val data = byteArrayOf(26,0)
        TileRepository(context).use { repo ->
            val source = repo.styleSource(id,definition)
            val request = Request.Builder().url(source.getJSONArray("tiles").getString(0).replace("{z}","14").replace("{x}","100").replace("{y}","200")).build()
            val client = OkHttpClient.Builder().addInterceptor(repo).addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(data.toResponseBody("application/x-protobuf".toMediaType())).build() }.build()
            assertArrayEquals(data,client.newCall(request).execute().use { it.body!!.bytes() })
        }
        TileRepository(context).use { repo ->
            val source = repo.styleSource(id,definition)
            val request = Request.Builder().url(source.getJSONArray("tiles").getString(0).replace("{z}","14").replace("{x}","100").replace("{y}","200")).build()
            val client = OkHttpClient.Builder().addInterceptor(repo).addInterceptor { throw IOException("offline") }.build()
            assertArrayEquals(data,client.newCall(request).execute().use { it.body!!.bytes() })
        }
    }
}
