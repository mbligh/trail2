package net.pocketmap.composeprobe

import android.graphics.BitmapFactory
import net.pocketmap.MbTilesCache
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Uses the actual production MBTiles implementation and its existing cache-file identity. */
internal class RasterProbeSource(
    directory: File,
    id: String,
    private val definition: JSONObject,
    private val allowNetwork: () -> Boolean,
    private val fetch: (String) -> ByteArray? = ::fetchRaster
) : Closeable {
    companion object {
        private fun fetchRaster(url: String): ByteArray? {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 7_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Trail2-ComposeProbe (https://github.com/mbligh/trail2)")
                if (connection.responseCode != 200) throw IOException("Raster HTTP ${connection.responseCode}")
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 8 * 1024 * 1024) throw IOException("Oversized raster tile")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally { connection.disconnect() }
        }
    }

    // Identical to TileRepository.styleSource's runtime-cache identity; never use
    // production app directories in this separate test app's Android sandbox.
    private val identity = definition.optString("cacheIdentity").ifBlank {
        definition.getJSONArray("tiles").toString() + definition.optString("archive")
    }
    private val key = id + identity + definition.optString("cacheVersion")
    private val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    val cacheFile = File(directory, "map-cache/$hash.mbtiles")
    private val cache = MbTilesCache(cacheFile)
    val cacheHits = AtomicInteger()
    val networkRequests = AtomicInteger()
    val blockedRequests = AtomicInteger()

    @Synchronized fun load(z: Int, x: Int, y: Int): ByteArray? {
        if (z !in 0..definition.optInt("maxzoom", 20) || x < 0 || y < 0 ||
            x.toLong() >= (1L shl z) || y.toLong() >= (1L shl z)) return null
        cache.get(z, x, y)?.let { cacheHits.incrementAndGet(); return it }
        if (!allowNetwork()) { blockedRequests.incrementAndGet(); return null }
        val templates = definition.getJSONArray("tiles")
        if (templates.length() == 0) return null
        val url = templates.getString((x + y) % templates.length())
            .replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        require(url.startsWith("https://"))
        networkRequests.incrementAndGet()
        val bytes = try { fetch(url) } catch (error: IOException) {
            android.util.Log.w("ComposeProbeRaster", "Cannot fetch $url", error)
            null
        } ?: return null
        // Reject HTML error pages or corrupt images before poisoning offline data.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        runCatching { cache.put(z, x, y, bytes) }.onFailure {
            android.util.Log.w("ComposeProbeRaster", "Cannot cache $url", it)
        }
        return bytes
    }

    fun styleSource(): JSONObject = JSONObject(definition.toString()).apply {
        remove("url"); remove("archive"); remove("cacheVersion"); remove("cacheIdentity")
        put("tiles", org.json.JSONArray().put(OfflineProbeResources.HOST + "tile/nj-orthos/{z}/{x}/{y}"))
    }

    override fun close() = cache.close()
}
