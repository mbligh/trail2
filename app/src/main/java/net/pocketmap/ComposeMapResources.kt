package net.pocketmap

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Per-map resource owner; preserves the shipping MBTiles and PMTiles cache identities. */
internal class ComposeMapResources(private val context: Context) : Closeable {
    internal val tiles = TileRepository(context)
    private data class Archive(val url: String, val reader: PmtilesArchive, val file: RandomAccessFile?)
    private val archives = mutableMapOf<String, Archive>()
    @Volatile private var closed = false

    val provider = MapResourceProvider(
        accepts = { true },
        load = { request ->
            val data = load(request.url)
            if (data == null) MapResourceLoad.NoContent() else MapResourceLoad.Bytes(data)
        }
    )

    /** Native's pmtiles protocol is replaced with a provider-owned TileJSON/tile route. */
    @Synchronized fun style(json: JSONObject): String {
        val sources = json.getJSONObject("sources")
        sources.keys().asSequence().toList().forEach { id ->
            val source = sources.getJSONObject(id)
            val raw = source.optString("url")
            if (!raw.startsWith("pmtiles://")) return@forEach
            val url = raw.removePrefix("pmtiles://")
            val key = TileRepository.hash(raw)
            if (!archives.containsKey(key)) {
                val file = if (url.startsWith("file://")) RandomAccessFile(File(Uri.parse(url).path!!), "r") else null
                val reader = if (file != null) PmtilesArchive { offset, length ->
                    synchronized(file) {
                        if (offset < 0 || length < 0 || offset > file.length() - length) null
                        else ByteArray(length).also { file.seek(offset); file.readFully(it) }
                    }
                } else {
                    // styleSource registered the source's cacheVersion before stripping it.
                    PmtilesArchive { offset, length -> tiles.loadArchiveRange(url, offset, length) }
                }
                archives[key] = Archive(url, reader, file)
            }
            source.put("url", "https://trail.pmtiles.invalid/$key/source.json")
        }
        return json.toString()
    }

    private fun archiveResource(url: String): ByteArray? {
        if (closed) return null
        val path = Uri.parse(url).pathSegments
        if (path.size < 2) return null
        // Never hold the style-registration lock across a remote range request.
        // Switching layers must remain responsive while contours are downloading.
        val archive = synchronized(this) { if (closed) null else archives[path[0]] } ?: return null
        val reader = archive.reader
        if (path.size == 2 && path[1] == "source.json") {
            return JSONObject().put("tilejson", "3.0.0").put("minzoom", reader.minZoom)
                .put("maxzoom", reader.maxZoom).put("tiles", JSONArray().put(
                    "https://trail.pmtiles.invalid/${path[0]}/{z}/{x}/{y}"))
                .toString().encodeToByteArray()
        }
        if (path.size != 4) return null
        val z = path[1].toIntOrNull() ?: return null
        val x = path[2].toIntOrNull() ?: return null
        val y = path[3].toIntOrNull() ?: return null
        return reader.get(z, x, y)
    }

    private fun load(url: String): ByteArray? {
        if (closed) return null
        return try {
            if (url.startsWith("https://trail.pmtiles.invalid/")) archiveResource(url)
            else tiles.loadResource(url)
        } catch (error: Exception) {
            android.util.Log.w("TrailTiles", "Map resource unavailable: $url", error)
            null
        }
    }

    @Synchronized override fun close() {
        closed = true
        archives.values.forEach { archive -> archive.file?.let { file -> synchronized(file) { file.close() } } }
        archives.clear()
        tiles.close()
    }
}
