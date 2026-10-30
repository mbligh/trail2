package net.pocketmap.composeprobe

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import net.pocketmap.composeprobe.RenderAssertions.assertContours
import net.pocketmap.composeprobe.RenderAssertions.assertMapVariety
import net.pocketmap.composeprobe.RenderAssertions.awaitResources

@RunWith(AndroidJUnit4::class)
class OfflineRenderTest {
    @Test fun pmtilesContoursAndPersistentRangesWorkOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pmtiles = context.assets.open("maps/contour-probe.pmtiles").use { it.readBytes() }
        assertTrue(pmtiles.copyOfRange(0, 7).decodeToString() == "PMTiles")
        val z = 12
        val lon = -74.771
        val lat = 40.692
        val n = 1 shl z
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val latR = Math.toRadians(lat)
        val y = ((1.0 - kotlin.math.asinh(kotlin.math.tan(latR)) / Math.PI) / 2 * n).toInt()
        val cache = File(context.cacheDir, "pmtiles-range-test-" + System.nanoTime())
        try {
            val uri = "https://mapping.mbligh.org/contours.pmtiles"
            val source = PmtilesRangeSource(cache, uri, "test-v1") { offset, length ->
                pmtiles.copyOfRange(offset.toInt(), (offset + length).toInt())
            }
            val archive = PmtilesArchive(source::read)
            assertTrue(archive.tileType == 1)
            assertTrue(archive.minZoom == 12)
            assertTrue(archive.maxZoom == 16)
            val tile = archive.get(z, x, y)
            assertNotNull("Unable to decode synthetic PMTiles directory and MVT", tile)
            assertTrue(tile!!.size > 40)
            assertTrue("Hilbert tile IDs must be unique",
                PmtilesArchive.tileId(1, 0, 0) != PmtilesArchive.tileId(1, 1, 0))
            assertTrue(archive.get(3, 0, 0) == null)

            // A new reader with the same cache namespace must use only disk
            // ranges; the mock remote fetcher is now unavailable.
            val offline = PmtilesRangeSource(cache, uri, "test-v1") { _, _ -> null }
            val replay = PmtilesArchive(offline::read)
            assertTrue(tile.contentEquals(replay.get(z, x, y)))
            val invalidated = PmtilesRangeSource(cache, uri, "test-v2") { _, _ -> null }
            assertTrue(invalidated.read(0, 127) == null)
        } finally { cache.deleteRecursively() }

        OfflineProbeResources(context).use { reader ->
            val sampleUrl = OfflineProbeResources.HOST + "tile/sample-contours/$z/$x/$y"
            assertTrue(reader.load(sampleUrl)?.isNotEmpty() == true)
            assertTrue(reader.sampleContourTiles.get() > 0)
            val style = org.json.JSONObject(
                reader.load(OfflineProbeResources.HOST + "style-sample-contours.json")!!
                    .decodeToString())
            assertTrue(style.getJSONObject("sources").has("nj-contours"))
            val layers = style.getJSONArray("layers")
            assertTrue((0 until layers.length()).any {
                layers.getJSONObject(it).getString("id") == "nj-contour-lines-100"
            })
        }
    }

    @Test fun sampleContoursRenderThroughMapLibreWhileOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resources = (context.applicationContext as ProbeApplication).offlineResources
        val initialContours = resources.sampleContourTiles.get()
        val initialStyles = resources.styleLoads.get()
        val intent = android.content.Intent(context, ProbeActivity::class.java)
            .putExtra("style", "sample-contours")
        ActivityScenario.launch<ProbeActivity>(intent).use { scenario ->
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline &&
                resources.sampleContourTiles.get() < initialContours + 2) Thread.sleep(500)
            assertTrue("MapLibre did not load PMTiles sample contour tiles",
                resources.sampleContourTiles.get() >= initialContours + 2)
            assertTrue("MapLibre did not load the contour style",
                resources.styleLoads.get() > initialStyles)
            // Requests alone do not prove that the source-layer/filter/paint
            // combination actually drew any contours on the map.
            assertContours("compose-contours.png")
        }
    }

    @Test fun realBundledTilesAndResourcesRenderWithoutInternet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ProbeActivity::class.java).use { scenario ->
            lateinit var resources: OfflineProbeResources
            scenario.onActivity { resources = it.offlineResources }
            val vector = resources.load(OfflineProbeResources.HOST + "tile/openmaptiles/0/0/0")
            val raster = resources.load(OfflineProbeResources.HOST + "tile/ne2_shaded/0/0/0")
            assertNotNull("Missing real bundled vector MBTiles root", vector)
            assertNotNull("Missing real bundled Natural Earth MBTiles root", raster)
            assertTrue("Expected nontrivial vector tile", vector!!.size > 50)
            assertTrue("Expected raster image", raster!!.size > 100)
            assertTrue("No network for tile fallback", !resources.accepts("https://tiles.openfreemap.org/planet"))
            assertTrue("External URL must be a miss", resources.load("https://tiles.openfreemap.org/planet") == null)

            // Renderer callback must actually request the Bright style and vector
            // tiles, rather than only exercising our reader directly.
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline &&
                (resources.styleLoads.get() < 1 || resources.vectorTiles.get() < 2)) {
                Thread.sleep(500)
            }
            assertTrue("Compose renderer did not request offline Bright style",
                resources.styleLoads.get() > 0)
            assertTrue("Compose renderer did not load live offline vector tiles",
                resources.vectorTiles.get() >= 2)

            awaitResources("bundled Bright fonts and sprites") {
                resources.glyphLoads.get() > 0 && resources.spriteLoads.get() > 0
            }
            assertMapVariety("compose-offline.png")
        }
    }

    @Test fun offlineStylesSwitchAndSurviveActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resources = (instrumentation.targetContext.applicationContext as ProbeApplication).offlineResources
        ActivityScenario.launch(ProbeActivity::class.java).use { scenario ->
            awaitResources("initial Bright style") { resources.styleLoads.get() > 0 }
            assertMapVariety("compose-switch-bright.png")
            val rasters = resources.rasterTiles.get()
            scenario.onActivity { it.showStyle("earth") }
            awaitResources("Natural Earth raster tiles") { resources.rasterTiles.get() > rasters }
            assertMapVariety("compose-switch-earth.png")

            val contours = resources.sampleContourTiles.get()
            scenario.onActivity { it.showStyle("sample-contours") }
            awaitResources("PMTiles after style switch") { resources.sampleContourTiles.get() > contours }
            assertContours("compose-switch-contours.png")
            scenario.recreate()
            scenario.onActivity {
                assertTrue("Recreation reset the selected style", it.selected == "sample-contours")
            }
            assertContours("compose-recreated-contours.png")

            val styles = resources.styleLoads.get()
            scenario.onActivity { it.showStyle("bright") }
            awaitResources("Bright after switching back") { resources.styleLoads.get() > styles }
            assertMapVariety("compose-switch-back.png")
        }
    }

}
