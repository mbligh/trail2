package net.pocketmap.composeprobe

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.pocketmap.composeprobe.RenderAssertions.assertMapVariety
import net.pocketmap.composeprobe.RenderAssertions.awaitResources
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real NJ 2020 imagery, never a synthetic stand-in. Invoke warm/replay in separate processes. */
@RunWith(AndroidJUnit4::class)
class LiveRasterRenderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources get() = (context.applicationContext as ProbeApplication).offlineResources
    private val receipt get() = File(context.filesDir, "real-raster-warm.txt")

    @Test fun warmSatelliteAndHybridOnline() {
        receipt.delete()
        File(context.filesDir, "map-cache").deleteRecursively()
        val intent = Intent(context, ProbeActivity::class.java).putExtra("style", "satellite")
        ActivityScenario.launch<ProbeActivity>(intent).use { scenario ->
            awaitResources("actual NJ satellite tiles") { resources.rasterTiles.get() >= 2 }
            assertMapVariety("compose-satellite-online.png")
            scenario.onActivity { it.showStyle("hybrid") }
            awaitResources("hybrid roads, labels and imagery") {
                resources.vectorTiles.get() >= 2 && resources.glyphLoads.get() > 0
            }
            assertMapVariety("compose-hybrid-online.png")
            Thread.sleep(3_000)
            assertTrue("No satellite viewport border cached", resources.satellite.prefetchViewportBorder() > 0)
            assertTrue("No real satellite HTTP fetches", resources.satellite.networkRequests.get() > 0)
            assertEquals(0, resources.satellite.blockedRequests.get())
            receipt.writeText("NJ 2020 tiles fetched: ${resources.satellite.networkRequests.get()}")
        }
    }

    @Test fun replaySatelliteAndHybridOffline() {
        assertTrue("Satellite warm-up did not finish", receipt.exists())
        assertEquals("Expected fresh process", 0, resources.rasterTiles.get())
        val intent = Intent(context, ProbeActivity::class.java)
            .putExtra("style", "satellite").putExtra("offline-only", true)
        ActivityScenario.launch<ProbeActivity>(intent).use { scenario ->
            awaitResources("satellite imagery from persisted MBTiles") { resources.rasterTiles.get() >= 2 }
            assertMapVariety("compose-satellite-offline.png")
            scenario.onActivity { it.showStyle("hybrid") }
            awaitResources("bundled hybrid roads and labels") {
                resources.vectorTiles.get() >= 2 && resources.glyphLoads.get() > 0
            }
            assertMapVariety("compose-hybrid-offline.png")
            scenario.recreate()
            scenario.onActivity { assertEquals("hybrid", it.selected) }
            assertMapVariety("compose-hybrid-recreated.png")
            assertTrue(resources.satellite.cacheHits.get() >= 2)
            assertEquals("Replay attempted HTTP", 0, resources.satellite.networkRequests.get())
            assertEquals("Replay needed uncached imagery", 0, resources.satellite.blockedRequests.get())
        }
    }
}
