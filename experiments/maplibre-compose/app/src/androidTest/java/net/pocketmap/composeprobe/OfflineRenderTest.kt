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
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                val output = File(instrumentation.targetContext.getExternalFilesDir(null), "compose-offline.png")
                output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
