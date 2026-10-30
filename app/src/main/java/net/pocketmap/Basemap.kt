package net.pocketmap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LatLng(val latitude: Double, val longitude: Double)

/** Map choices and layer groups are data; the UI does not know any provider IDs. */
data class Basemap(val name: String, val label: String, val style: String?, val source: String?, val attribution: String, val attributionUrl: String?)
data class MapOverlay(val id: String, val name: String, val defaultVisible: Boolean, val definition: JSONObject)

class MapConfiguration(private val readAsset: (String) -> String) {
    constructor(context: Context) : this({ path -> context.assets.open(path).bufferedReader().use { it.readText() } })
    private val config = JSONObject(readAsset("basemaps.json"))
    val sources = JSONObject(readAsset("resolved-sources.json"))
    val basemaps: List<Basemap> = config.getJSONArray("basemaps").let { array ->
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Basemap(it.getString("name"), it.optString("label", it.getString("name")), it.optString("style").takeIf(String::isNotBlank), it.optString("source").takeIf(String::isNotBlank), it.optString("attribution"), it.optString("attributionUrl").takeIf(String::isNotBlank))
        } }.also { require(it.isNotEmpty() && it.map(Basemap::name).distinct().size == it.size) }
    }
    val overlays: List<MapOverlay> = config.optJSONArray("overlays")?.let { array ->
        (0 until array.length()).map { array.getJSONObject(it).let { item ->
            MapOverlay(item.getString("id"), item.getString("name"), item.optBoolean("defaultVisible"), item)
        } }
    } ?: emptyList()

    init {
        overlays.forEach { overlay ->
            overlay.definition.optJSONObject("sources")?.let { extra -> extra.keys().forEach { id ->
                require(!sources.has(id)) { "Duplicate map source: $id" }
                sources.put(id, extra.getJSONObject(id))
            } }
        }
        require(overlays.map { it.id }.distinct().size == overlays.size)
    }

    fun style(basemap: Basemap, enabled: Set<String>, tiles: TileRepository): JSONObject {
        val result = if (basemap.style != null) JSONObject(readAsset(basemap.style))
        else JSONObject().put("version", 8).put("sources", JSONObject().put(basemap.source!!, sources.getJSONObject(basemap.source)))
            .put("layers", JSONArray().put(JSONObject().put("id", "basemap").put("type", "raster").put("source", basemap.source)))
        val styleSources = result.getJSONObject("sources")
        styleSources.keys().asSequence().toList().forEach { id ->
            if (sources.has(id)) styleSources.put(id, sources.getJSONObject(id))
        }
        val layers = result.getJSONArray("layers")
        // Only install enabled overlays, so unchecked remote sources make no requests.
        overlays.filter { it.id in enabled }.forEach { overlay ->
            overlay.definition.optJSONObject("sources")?.keys()?.forEach { id -> styleSources.put(id, sources.getJSONObject(id)) }
            val definitions = overlay.definition.getJSONArray("layers")
            for (i in 0 until definitions.length()) layers.put(JSONObject(definitions.getJSONObject(i).toString()))
        }
        // Raster choices can still display symbol overlays with the bundled Bright fonts.
        if (!result.has("glyphs")) result.put("glyphs", "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf")
        styleSources.keys().asSequence().toList().forEach { id ->
            styleSources.put(id, tiles.styleSource(id, styleSources.getJSONObject(id)))
        }
        return result
    }
}
