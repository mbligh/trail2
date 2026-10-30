package net.pocketmap.composeprobe

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * Android-only experimental adapter over the *same generated* offline map assets as Trail2.
 * Unlike the shipping TileRepository it never contacts a server or mutates a tile cache.
 * The renderer stays in a separate app and does not touch Trail2's private files.
 */
internal class OfflineProbeResources(private val context: Context) : Closeable {
    companion object {
        const val HOST = "https://trail.tiles.invalid/"
    }

    val styleLoads = AtomicInteger()
    val vectorTiles = AtomicInteger()
    val rasterTiles = AtomicInteger()
    val glyphLoads = AtomicInteger()
    val spriteLoads = AtomicInteger()
    val sampleContourTiles = AtomicInteger()
    val remoteContourTiles = AtomicInteger()

    private val sources = JSONObject(readAssetText("resolved-sources.json"))
    private val manifests = JSONObject(readAssetText("offline-manifest.json"))
    private val databases = mutableMapOf<String, SQLiteDatabase>()
    private var sampleFile: RandomAccessFile? = null
    private val samplePmtiles by lazy { PmtilesArchive(::localPmtilesRange) }
    private val remoteRange by lazy {
        PmtilesRangeSource(
            java.io.File(context.filesDir, "probe-pmtiles-ranges"),
            "https://mapping.mbligh.org/contours.pmtiles",
            "nj-contours-v1"
        )
    }
    private val remotePmtiles by lazy { PmtilesArchive(remoteRange::read) }
    private val contourOverlay by lazy {
        val raw = JSONObject(readAssetText("basemaps.json")).getJSONArray("overlays")
        (0 until raw.length()).map { raw.getJSONObject(it) }
            .first { it.getString("id") == "contours" }
    }
    private val archiveFor = mapOf(
        "openmaptiles" to "maps/tewksbury-streets.mbtiles",
        "ne2_shaded" to "maps/tewksbury-earth.mbtiles"
    )
    private val route = Regex("^tile/(openmaptiles|ne2_shaded|sample-contours|nj-contours)/(\\d+)/(\\d+)/(\\d+)$")
    private val assetPaths: Set<String> = manifests.getJSONObject("resources").keys().asSequence().toSet()

    private fun readAssetText(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
    private fun readAssetBytes(path: String) = context.assets.open(path).use { it.readBytes() }

    fun accepts(url: String): Boolean = url.startsWith(HOST)

    /**
     * Called on a MapLibre resource thread. Keep SQLite access serialized and return
     * null for missing tiles (MapResourceLoad.NoContent); never attempt HTTP fallback.
     */
    @Synchronized fun load(url: String): ByteArray? {
        if (!accepts(url)) return null
        val path = Uri.decode(url.removePrefix(HOST).substringBefore('?'))
        if (path == "style-bright.json") {
            styleLoads.incrementAndGet()
            return style("bright")
        }
        if (path in setOf("style-earth.json", "style-sample-contours.json", "style-nj-contours.json")) {
            styleLoads.incrementAndGet()
            return style(path.removePrefix("style-").removeSuffix(".json"))
        }
        route.matchEntire(path)?.let { match ->
            val source = match.groupValues[1]
            val z = match.groupValues[2].toIntOrNull() ?: return null
            val x = match.groupValues[3].toLongOrNull() ?: return null
            val y = match.groupValues[4].toLongOrNull() ?: return null
            if (z !in 0..22 || x < 0 || y < 0 || x >= (1L shl z) || y >= (1L shl z)) return null
            if (source == "sample-contours" || source == "nj-contours") {
                val bytes = runCatching {
                    if (source == "sample-contours") samplePmtiles.get(z, x.toInt(), y.toInt())
                    else remotePmtiles.get(z, x.toInt(), y.toInt())
                }.getOrNull()
                if (bytes != null) {
                    if (source == "sample-contours") sampleContourTiles.incrementAndGet()
                    else remoteContourTiles.incrementAndGet()
                }
                return bytes
            }
            val db = database(source)
            val tms = (1L shl z) - 1 - y
            val data = db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), tms.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
            if (data != null) {
                if (source == "openmaptiles") vectorTiles.incrementAndGet() else rasterTiles.incrementAndGet()
            }
            return data?.let(::gunzip)
        }
        if ((path.startsWith("fonts/") && path.endsWith(".pbf")) ||
            (path.startsWith("sprites/bright") && (path.endsWith(".png") || path.endsWith(".json")))) {
            // Generated manifest is a whitelist: no traversal or arbitrary asset access.
            if (path !in assetPaths) return null
            if (path.startsWith("fonts/")) glyphLoads.incrementAndGet() else spriteLoads.incrementAndGet()
            return readAssetBytes(path)
        }
        return null
    }

    private fun localPmtilesRange(offset: Long, length: Int): ByteArray? {
        val archive = "maps/contour-probe.pmtiles"
        if (archive !in assetPaths) return null
        val file = sampleFile ?: run {
            val dest = java.io.File(context.filesDir, "sample-contours.pmtiles")
            if (!dest.exists()) {
                val tmp = java.io.File(dest.path + ".tmp")
                try {
                    context.assets.open(archive).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(tmp.renameTo(dest)) { "Cannot stage PMTiles fixture" }
                } finally { tmp.delete() }
            }
            RandomAccessFile(dest, "r").also { sampleFile = it }
        }
        if (offset < 0 || length < 0 || offset > file.length() - length) return null
        return ByteArray(length).also { file.seek(offset); file.readFully(it) }
    }

    private fun database(source: String): SQLiteDatabase =
        databases.getOrPut(source) {
            val archive = archiveFor.getValue(source)
            require(archive in assetPaths) { "Missing generated archive: $archive" }
            val file = context.getDatabasePath("probe-" + source + ".mbtiles")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val tmp = java.io.File(file.path + ".tmp")
                try {
                    context.assets.open(archive).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(tmp.renameTo(file)) { "Unable to stage $archive" }
                } finally { tmp.delete() }
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }

    /** Keeps the exact Bright layer stack and bundled sprite/font resources. */
    private fun style(name: String): ByteArray {
        if (name == "earth") return JSONObject().apply {
            put("version", 8)
            put("sources", JSONObject().put("ne2_shaded", offlineSource("ne2_shaded")))
            put("layers", JSONArray().put(JSONObject()
                .put("id", "earth")
                .put("source", "ne2_shaded")
                .put("type", "raster")))
        }.toString().encodeToByteArray()

        val result = JSONObject(readAssetText("styles/bright.json"))
        val styleSources = result.getJSONObject("sources")
        val ids = styleSources.keys().asSequence().toList()
        ids.forEach { id ->
            // A fully offline style must have a local source for *every* source.
            require(id in archiveFor) { "Bright references unbundled source $id" }
            styleSources.put(id, offlineSource(id))
        }
        if (name == "sample-contours" || name == "nj-contours") {
            val synthetic = name == "sample-contours"
            styleSources.put("nj-contours", JSONObject()
                .put("type", "vector")
                .put("minzoom", if (synthetic) 12 else 9)
                .put("maxzoom", 16)
                .put("tiles", JSONArray().put(HOST + "tile/" +
                    (if (synthetic) "sample-contours" else "nj-contours") + "/{z}/{x}/{y}")))
            val layers = result.getJSONArray("layers")
            val overlayLayers = contourOverlay.getJSONArray("layers")
            for (i in 0 until overlayLayers.length()) {
                layers.put(JSONObject(overlayLayers.getJSONObject(i).toString()))
            }
        }
        result.put("glyphs", HOST + "fonts/{fontstack}/{range}.pbf")
        if (result.has("sprite")) result.put("sprite", HOST + "sprites/bright")
        return result.toString().encodeToByteArray()
    }

    private fun offlineSource(id: String): JSONObject {
        val source = JSONObject(sources.getJSONObject(id).toString())
        source.remove("url")
        source.remove("archive")
        source.remove("cacheVersion")
        source.remove("cacheIdentity")
        source.remove("vector_layers")
        source.put("tiles", JSONArray().put(HOST + "tile/$id/{z}/{x}/{y}"))
        // For each archive, the maximum source zoom comes from its actual metadata.
        val db = database(id)
        db.rawQuery("SELECT name,value FROM metadata WHERE name IN ('minzoom','maxzoom')", null).use { cursor ->
            while (cursor.moveToNext()) source.put(cursor.getString(0), cursor.getInt(1))
        }
        return source
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else bytes

    @Synchronized override fun close() {
        databases.values.forEach { it.close() }
        databases.clear()
        sampleFile?.close()
        sampleFile = null
    }
}
