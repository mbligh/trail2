package net.pocketmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Routes tile requests through immutable seeds + persistent MBTiles + online fallback. */
class TileRepository(private val context: Context) : Interceptor, Closeable {
    private data class Store(val definition: JSONObject, val seed: SQLiteDatabase?, val cache: MbTilesCache)
    private val stores = mutableMapOf<String, Store>()
    private val pmtiles = mutableMapOf<String, String>()
    private val ranges = RangeCache(File(context.filesDir, "map-cache/pmtiles"))
    private val resources = JSONObject(context.assets.open("bundled-resources.json").bufferedReader().use { it.readText() })
    private val manifest = JSONObject(context.assets.open("offline-manifest.json").bufferedReader().use { it.readText() }).getJSONObject("resources")
    private val client = OkHttpClient.Builder().addInterceptor(this).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var closed = false
    init { HttpRequestUtil.setOkHttpClient(client) }

    @Synchronized private fun assetFile(path: String): File {
        require(!path.startsWith('/') && path.split('/').none { it == ".." })
        val digest = manifest.optString(path).ifBlank { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown" }
        val file = File(context.filesDir, "bundled/${hash(digest)}/$path")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            try {
                context.assets.open(path).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(file)) { "Cannot install offline map" }
            } finally { temporary.delete() }
        }
        return file
    }

    @Synchronized fun styleSource(id: String, definition: JSONObject): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9._-]+")) && !id.startsWith("trail-")) { "Invalid/reserved source ID: $id" }
        val source = JSONObject(definition.toString())
        val url = source.optString("url")
        if (url.startsWith("pmtiles://https://")) pmtiles[url.removePrefix("pmtiles://")] = source.optString("cacheVersion", "1")
        if (url.startsWith("pmtiles://asset://")) source.put("url", "pmtiles://" + android.net.Uri.fromFile(assetFile(url.removePrefix("pmtiles://asset://"))).toString())
        if (source.optString("type") in listOf("raster", "vector", "raster-dem") && (source.has("tiles") || source.optString("archive").endsWith(".mbtiles"))) {
            val archive = source.optString("archive")
            // Also namespace MapLibre's own ambient cache, not just our MBTiles files.
            // Different styles may legitimately reuse a source ID for different data.
            val route = hash(id + source.toString() + manifest.optString(archive))
            if (!stores.containsKey(route)) {
                val seed = if (archive.isNotBlank()) runCatching {
                    val file = assetFile(archive)
                    if (file.length() == 0L) null else SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                }.onFailure { Log.w("TrailTiles", "Cannot open seed $archive", it) }.getOrNull() else null
                if (!source.has("tiles")) source.put("tiles", JSONArray())
                seed?.rawQuery("SELECT name,value FROM metadata WHERE name IN ('minzoom','maxzoom','bounds')", null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(0); val value = cursor.getString(1)
                        if (!source.has(key)) {
                            if (key == "bounds") source.put(key, JSONArray(value.split(',').map(String::toDouble)))
                            else source.put(key, value.toInt())
                        }
                    }
                }
                val identity = source.optString("cacheIdentity").ifBlank { source.getJSONArray("tiles").toString() + archive }
                val key = identity + source.optString("cacheVersion")
                val cacheFile = File(context.filesDir, "map-cache/${hash(id + key)}.mbtiles")
                // Preserve the three original sources' existing offline runtime caches.
                val legacyName = when (id) { "nj-orthos" -> "njsatellite"; "osm-raster" -> "openstreetmap"; "topo-raster" -> "opentopomap"; else -> null }
                val legacy = legacyName?.let { File(context.filesDir, "maps/$it-cache.mbtiles") }
                val migrated = File(context.filesDir, "map-cache/legacy-$id-migrated")
                if (!migrated.exists() && legacy?.exists() == true) {
                    cacheFile.parentFile?.mkdirs()
                    if (!cacheFile.exists()) legacy.copyTo(cacheFile)
                    migrated.writeText(cacheFile.name)
                }
                stores[route] = Store(JSONObject(source.toString()), seed, MbTilesCache(cacheFile))
            }
            val installed = stores.getValue(route).definition
            for (key in listOf("minzoom", "maxzoom", "bounds")) {
                if (!source.has(key) && installed.has(key)) source.put(key, installed.get(key))
            }
            source.remove("url")
            source.put("tiles", JSONArray().put("https://trail.tiles.invalid/$id/$route/{z}/{x}/{y}"))
        }
        source.remove("archive"); source.remove("cacheVersion"); source.remove("cacheIdentity"); source.remove("vector_layers")
        return source
    }

    /**
     * Offline resource bridge for a future MapLibre Compose MapResourceProvider.
     *
     * The provider should accept only URLs returned by [acceptsOfflineResource],
     * then return the bytes (or NoContent for null). Deliberately does not use
     * OkHttp's remote fallback: a missing tile must not silently use the network.
     * Invoke from a background dispatcher; SQLite and asset reads are blocking.
     *
     * Style sources must first be installed through [styleSource], which gives
     * each tile archive its cache-isolated synthetic URL.
     */
    fun acceptsOfflineResource(url: String): Boolean =
        url.startsWith("https://trail.tiles.invalid/") ||
            resources.optString(url).isNotBlank()

    fun loadOfflineResource(url: String): ByteArray? {
        if (closed || !acceptsOfflineResource(url)) return null
        resources.optString(url).takeIf { it.isNotBlank() }?.let { asset ->
            return context.assets.open(asset).use { it.readBytes() }
        }
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host != "trail.tiles.invalid") return null
        val parts = parsed.pathSegments
        if (parts.size != 5 || parts[0].isNotEmpty()) return null
        val store = synchronized(this) { stores[parts[1]] } ?: return null
        val z = parts[2].toIntOrNull() ?: return null
        val x = parts[3].toIntOrNull() ?: return null
        val y = parts[4].toIntOrNull() ?: return null
        if (z !in 0..30 || x < 0 || y < 0 ||
            x.toLong() >= (1L shl z) || y.toLong() >= (1L shl z)) return null
        val seed = synchronized(this) {
            if (closed) null else store.seed?.let { MbTilesCache.read(it, z, x, y) }
        }
        return (seed ?: store.cache.get(z, x, y))?.let(::uncompressed)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (closed) throw IOException("Map closed")
        val url = request.url
        val resource = resources.optString(url.toString())
        if (resource.isNotBlank()) {
            val data = context.assets.open(resource).use { it.readBytes() }
            return response(chain, data, if (resource.endsWith(".json")) "application/json" else if (resource.endsWith(".png")) "image/png" else "application/x-protobuf")
        }
        if (url.host != "trail.tiles.invalid") {
            val namespace = synchronized(this) { pmtiles[url.toString()] }
            val range = request.header("Range")
            if (namespace == null || range == null) return chain.proceed(request)
            ranges.get(chain, namespace)?.let { return it }
            val result = chain.proceed(request)
            if (result.code != 206) return result
            val contentRange = result.header("Content-Range") ?: return result
            val mime = result.header("Content-Type") ?: "application/octet-stream"
            val data = result.body?.bytes() ?: throw IOException("Empty archive range")
            result.close()
            runCatching { ranges.put(url.toString(), range, namespace, contentRange, mime, data) }
                .onFailure { Log.w("TrailTiles", "Cannot cache archive range", it) }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .header("Content-Range",contentRange).header("Accept-Ranges","bytes").body(data.toResponseBody(mime.toMediaType())).build()
        }
        val parts = url.pathSegments
        if (parts.size != 5) throw IOException("Invalid tile path")
        val store = synchronized(this) { stores[parts[1]] } ?: throw IOException("Unknown tile source")
        val z = parts[2].toInt(); val x = parts[3].toInt(); val y = parts[4].toInt()
        val mime = if (store.definition.optString("type") == "vector") "application/x-protobuf" else "image/png"
        val seed = synchronized(this) { if (closed) null else store.seed?.let { MbTilesCache.read(it, z, x, y) } }
        (seed ?: store.cache.get(z, x, y))?.let { return response(chain, uncompressed(it), mime) }
        val templates = store.definition.getJSONArray("tiles")
        if (templates.length() == 0) return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Tile outside bundled coverage").body(ByteArray(0).toResponseBody()).build()
        val remote = templates.getString((x + y) % templates.length()).replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val result = chain.proceed(request.newBuilder().url(remote).header("User-Agent", "Trail2/2.0 (https://github.com/mbligh/trail2)").build())
        if (!result.isSuccessful) return result
        val data = result.body?.bytes() ?: throw IOException("Empty tile")
        val contentType = result.header("Content-Type") ?: mime
        result.close()
        // A full disk must not prevent display of a successfully fetched tile.
        runCatching { store.cache.put(z, x, y, if (store.definition.optString("type") == "vector") compressed(data) else data) }.onFailure { Log.w("TrailTiles", "Cannot cache tile", it) }
        return response(chain, data, contentType)
    }
    private fun uncompressed(data: ByteArray): ByteArray = if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    } else data
    private fun compressed(data: ByteArray): ByteArray {
        if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) return data
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }
    private fun response(chain: Interceptor.Chain, data: ByteArray, mime: String) = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(data.toResponseBody(mime.toMediaType())).build()
    @Synchronized override fun close() {
        closed = true
        client.dispatcher.cancelAll()
        stores.values.forEach { it.seed?.close(); it.cache.close() }
        stores.clear()
        HttpRequestUtil.setOkHttpClient(null)
    }
    companion object { fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) } }
}
