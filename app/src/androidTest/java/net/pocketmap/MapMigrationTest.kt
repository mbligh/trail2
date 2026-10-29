package net.pocketmap

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.maplibre.android.geometry.LatLng
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.IOException

/** Exercises the real native renderer and local tile route, with all network blocked. */
@RunWith(AndroidJUnit4::class)
class MapMigrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun grantLocationForUiTests() {
        instrumentation.runOnMainSync { org.maplibre.android.MapLibre.getInstance(context) }
        for (permission in listOf("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.$permission")).use { it.readBytes() }
        }
    }
    private fun renderer(activity: MainActivity): TrailMap = MainActivity::class.java.getDeclaredField("map").apply { isAccessible = true }.get(activity) as TrailMap
    private fun native(map: TrailMap): MapLibreMap? = TrailMap::class.java.getDeclaredField("native").apply { isAccessible = true }.get(map) as MapLibreMap?
    private fun offline(map: TrailMap) {
        val repo = TrailMap::class.java.getDeclaredField("tiles").apply { isAccessible = true }.get(map) as TileRepository
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor(repo).addInterceptor { throw IOException("Airplane-mode test: network blocked") }.build())
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
            await(scenario, "Bright vector roads render from bundled tiles") { map ->
                val native = native(map)
                native?.style?.isFullyLoaded == true && native.queryRenderedFeatures(android.graphics.RectF(0f,0f,map.width.toFloat(),map.height.toFloat())).any { it.hasProperty("class") }
            }
            val document = TrailKml.Document("Test trails", listOf(TrailKml.Feature("Offline test trail", "LineString", listOf(TrailKml.Coordinate(40.688,-74.782),TrailKml.Coordinate(40.692,-74.778)), emptyList(), TrailKml.Style())))
            val points = listOf(RecordedTrack.Point(40.688,-74.782,1000,null,4f),RecordedTrack.Point(40.692,-74.778,31_000,null,3f))
            scenario.onActivity { renderer(it).apply { showTrails(document,true); showRecording(points,emptyList()) } }
            await(scenario, "trails and recording") { map -> native(map)?.queryRenderedFeatures(android.graphics.RectF(0f,0f,map.width.toFloat(),map.height.toFloat()), "trail-lines", "trail-recording")?.size?.let { it >= 2 } == true }
            val config = MapConfiguration(context)
            for (choice in listOf("hybrid", "bright")) {
                scenario.onActivity { renderer(it).setBasemap(config.basemaps.first { b -> b.name == choice }) }
                await(scenario, "$choice restores trails and recording") { map -> native(map)?.style?.isFullyLoaded == true && native(map)?.queryRenderedFeatures(android.graphics.RectF(0f,0f,map.width.toFloat(),map.height.toFloat()), "trail-lines", "trail-recording")?.size?.let { it >= 2 } == true }
            }
            assertEquals(emptyList<String>(), errors)
            await(scenario, "Bright streets restored for screenshot") { map ->
                native(map)?.queryRenderedFeatures(android.graphics.RectF(0f,0f,map.width.toFloat(),map.height.toFloat()))?.any { it.hasProperty("class") } == true
            }
            Thread.sleep(500)
            instrumentation.waitForIdleSync()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/maplibre-offline.png")).use { it.readBytes() }
        }
    }

    @Test fun accurateGpsSurvivesNetworkReadingsAndRecorderSelectsAccurateRecentFix() {
        fun fix(provider: String, accuracy: Float, nanos: Long, lat: Double) = Location(provider).apply { latitude = lat; longitude = -74.78; this.accuracy = accuracy; elapsedRealtimeNanos = nanos; time = System.currentTimeMillis() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
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
                // A real down event must open the editor at one second, *before* up.
                compose.onNodeWithTag("pin-control").performTouchInput { down(center) }
                Thread.sleep(1_200)
                scenario.onActivity {
                    assertTrue("Hold must open before release", dialog(it)?.isShowing == true)
                    assertTrue(RecordedPins.read(track).isEmpty())
                }
                compose.onNodeWithTag("pin-control").performTouchInput { up() }
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
                compose.onNodeWithTag("pin-control").performTouchInput { down(center); up() }
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
