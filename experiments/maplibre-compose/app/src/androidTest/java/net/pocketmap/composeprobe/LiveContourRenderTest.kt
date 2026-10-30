package net.pocketmap.composeprobe

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.pocketmap.composeprobe.RenderAssertions.assertContours
import net.pocketmap.composeprobe.RenderAssertions.awaitResources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run the methods separately: warm online, stop the process, disable networking, replay. */
@RunWith(AndroidJUnit4::class)
class LiveContourRenderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources get() = (context.applicationContext as ProbeApplication).offlineResources
    private val receipt get() = File(context.filesDir, "real-contour-warm.json")

    @Test fun warmRealContoursOnline() {
        receipt.delete()
        // Fresh process and an empty cache prove this really fetched the NJ dataset.
        File(context.filesDir, "probe-pmtiles-ranges").deleteRecursively()
        val intent = Intent(context, ProbeActivity::class.java).putExtra("style", "nj-contours")
        ActivityScenario.launch<ProbeActivity>(intent).use {
            awaitResources("actual NJ contour tiles") { resources.remoteContourTiles.get() >= 2 }
            assertContours("compose-real-contours-online.png")
            // Let viewport-adjacent requests finish before the cold/offline invocation.
            var previous = -1
            var stable = 0
            val deadline = System.currentTimeMillis() + 30_000
            while (stable < 6 && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                val count = resources.remoteContourTiles.get()
                stable = if (count == previous) stable + 1 else 0
                previous = count
            }
            val ranges = resources.remoteRange
            assertTrue("No real HTTP ranges fetched", ranges.networkRequests.get() > 0)
            assertTrue("No real NJ PMTiles bytes fetched", ranges.networkBytes.get() > 127)
            assertEquals(0, ranges.blockedRequests.get())
            receipt.writeText(JSONObject()
                .put("networkRequests", ranges.networkRequests.get())
                .put("networkBytes", ranges.networkBytes.get())
                .put("renderedTiles", resources.remoteContourTiles.get())
                .toString())
        }
    }

    @Test fun replayRealContoursOffline() {
        assertTrue("Online warm-up did not complete", receipt.exists())
        assertTrue(JSONObject(receipt.readText()).getLong("networkBytes") > 127)
        assertEquals("Replay must start in a fresh process", 0, resources.remoteContourTiles.get())
        val intent = Intent(context, ProbeActivity::class.java)
            .putExtra("style", "nj-contours")
            .putExtra("offline-only", true)
        ActivityScenario.launch<ProbeActivity>(intent).use {
            awaitResources("real NJ contours from disk after process restart") {
                resources.remoteContourTiles.get() >= 2
            }
            assertContours("compose-real-contours-offline.png")
            val ranges = resources.remoteRange
            assertTrue("No persisted range replay", ranges.cacheHits.get() > 0)
            assertEquals("Offline replay attempted HTTP", 0, ranges.networkRequests.get())
            assertEquals("Offline viewport needed uncached ranges", 0, ranges.blockedRequests.get())
        }
    }
}
