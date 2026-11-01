package net.pocketmap.shared.config

import kotlinx.serialization.json.*

data class ConfiguredBasemap(val name: String, val label: String, val style: String?, val source: String?, val attribution: String, val attributionUrl: String?)
data class ConfiguredOverlay(val id: String, val name: String, val defaultVisible: Boolean, val definition: JsonObject)

/** Platform I/O stays outside the shared configuration and style composition rules. */
class SharedMapConfiguration(private val readAsset: (String) -> String) {
    private val config = Json.parseToJsonElement(readAsset("basemaps.json")).jsonObject
    val basemaps = config.getValue("basemaps").jsonArray.map { raw ->
        val item = raw.jsonObject
        val name = item.getValue("name").jsonPrimitive.content
        ConfiguredBasemap(name, item.text("label") ?: name, item.text("style")?.takeIf(String::isNotBlank),
            item.text("source")?.takeIf(String::isNotBlank), item.text("attribution") ?: "",
            item.text("attributionUrl")?.takeIf(String::isNotBlank))
    }.also { require(it.isNotEmpty() && it.map(ConfiguredBasemap::name).distinct().size == it.size) }
    val overlays = config["overlays"]?.jsonArray?.map { raw ->
        val item = raw.jsonObject
        ConfiguredOverlay(item.getValue("id").jsonPrimitive.content, item.getValue("name").jsonPrimitive.content,
            item["defaultVisible"]?.jsonPrimitive?.booleanOrNull ?: false, item)
    } ?: emptyList()
    val sources: JsonObject

    init {
        val merged = Json.parseToJsonElement(readAsset("resolved-sources.json")).jsonObject.toMutableMap()
        overlays.forEach { overlay -> overlay.definition["sources"]?.jsonObject?.forEach { (id, source) ->
            require(id !in merged) { "Duplicate map source: $id" }
            merged[id] = source
        } }
        require(overlays.map { it.id }.distinct().size == overlays.size)
        sources = JsonObject(merged)
    }

    fun style(basemap: ConfiguredBasemap, enabled: Set<String>, route: (String, String) -> String): String {
        val result = if (basemap.style != null) Json.parseToJsonElement(readAsset(basemap.style)).jsonObject.toMutableMap()
        else buildJsonObject {
            put("version", 8)
            val id = requireNotNull(basemap.source)
            put("sources", buildJsonObject { put(id, sources.getValue(id)) })
            put("layers", buildJsonArray { add(buildJsonObject { put("id", "basemap"); put("type", "raster"); put("source", id) }) })
        }.toMutableMap()
        val styleSources = result.getValue("sources").jsonObject.toMutableMap()
        styleSources.keys.toList().forEach { id -> sources[id]?.let { styleSources[id] = it } }
        val layers = result.getValue("layers").jsonArray.toMutableList()
        overlays.filter { it.id in enabled }.forEach { overlay ->
            overlay.definition["sources"]?.jsonObject?.keys?.forEach { id -> styleSources[id] = sources.getValue(id) }
            layers.addAll(overlay.definition.getValue("layers").jsonArray)
        }
        if ("glyphs" !in result) result["glyphs"] = JsonPrimitive("https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf")
        styleSources.keys.toList().forEach { id ->
            styleSources[id] = Json.parseToJsonElement(route(id, styleSources.getValue(id).toString())).jsonObject
        }
        result["sources"] = JsonObject(styleSources)
        result["layers"] = JsonArray(layers)
        return JsonObject(result).toString()
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
}
