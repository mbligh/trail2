package net.pocketmap.composeprobe

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.pocketmap.MbTilesCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RasterCacheInteropTest {
    @Test fun productionCacheIdentityAndTmsRowsSurviveComposeReadsAndWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val definition = JSONObject(context.assets.open("resolved-sources.json").bufferedReader().use { it.readText() })
            .getJSONObject("nj-orthos")
        val directory = File(context.cacheDir, "raster-interop-${System.nanoTime()}")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff206030.toInt()) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val z = 12; val x = 1197; val y = 1540
        try {
            val identity = definition.optString("cacheIdentity").ifBlank {
                definition.getJSONArray("tiles").toString() + definition.optString("archive")
            }
            val key = "nj-orthos" + identity + definition.optString("cacheVersion")
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            val productionFile = File(directory, "map-cache/$hash.mbtiles")
            MbTilesCache(productionFile).use { it.put(z, x, y, bytes) }
            RasterProbeSource(directory, "nj-orthos", definition, allowNetwork = { false }).use { source ->
                assertEquals(productionFile, source.cacheFile)
                assertArrayEquals(bytes, source.load(z, x, y))
                assertEquals(0, source.networkRequests.get())
                assertNull(source.load(z, x + 1, y))
                assertEquals(1, source.blockedRequests.get())
            }
            RasterProbeSource(directory, "nj-orthos", definition, allowNetwork = { true }, fetch = { url ->
                assertTrue("ArcGIS tile URLs must use z/y/x", url.endsWith("/tile/$z/$y/${x + 1}"))
                bytes
            }).use { source ->
                assertArrayEquals(bytes, source.load(z, x + 1, y))
                assertEquals(1, source.networkRequests.get())
            }
            // Open with the production implementation independently, after the
            // Compose adapter has closed: the write is standard persistent TMS MBTiles.
            MbTilesCache(productionFile).use { assertArrayEquals(bytes, it.get(z, x + 1, y)) }
            val changed = JSONObject(definition.toString()).put("cacheVersion", "test-new-version")
            RasterProbeSource(directory, "nj-orthos", changed, allowNetwork = { false }).use {
                assertNotEquals(productionFile, it.cacheFile)
                assertNull(it.load(z, x, y))
            }
            RasterProbeSource(directory, "nj-orthos", definition, allowNetwork = { true },
                fetch = { "<html>upstream unavailable</html>".encodeToByteArray() }).use {
                assertNull(it.load(z, x + 2, y))
                assertNull(it.load(z, x + 2, y))
                assertEquals("Invalid imagery must not be cached", 2, it.networkRequests.get())
            }
        } finally { directory.deleteRecursively() }
    }
}
