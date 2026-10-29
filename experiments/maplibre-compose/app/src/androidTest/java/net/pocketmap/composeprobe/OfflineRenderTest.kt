package net.pocketmap.composeprobe

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineRenderTest {
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

            instrumentation.waitForIdleSync()
            // Native tile requests alone don't prove the GPU presented a map.
            // Sample only the middle of the screenshot (outside the Compose
            // control banner). A blank/black/white map has very few colors;
            // a rendered Bright map contains roads, water, land and labels.
            var distinctColors = 0
            for (attempt in 1..12) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                if (bitmap == null) {
                    Thread.sleep(500)
                    continue
                }
                try {
                    val colors = HashSet<Int>()
                    val x0 = bitmap.width / 4
                    val x1 = bitmap.width * 3 / 4
                    val y0 = bitmap.height / 4
                    val y1 = bitmap.height * 3 / 4
                    val stepX = ((x1 - x0) / 50).coerceAtLeast(1)
                    val stepY = ((y1 - y0) / 50).coerceAtLeast(1)
                    for (y in y0 until y1 step stepY) {
                        for (x in x0 until x1 step stepX) {
                            colors.add(bitmap.getPixel(x, y) and 0x00ffffff)
                        }
                    }
                    distinctColors = colors.size
                    val output = File(
                        instrumentation.targetContext.getExternalFilesDir(null), "compose-offline.png")
                    output.outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
                if (distinctColors >= 16) break
                Thread.sleep(1_000)
            }
            assertTrue("MapLibre Compose surface is blank ($distinctColors distinct colors)",
                distinctColors >= 16)
        }
    }
}
