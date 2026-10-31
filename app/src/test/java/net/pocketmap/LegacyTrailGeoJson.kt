package net.pocketmap

import org.json.JSONArray
import org.json.JSONObject

/** Exact pre-migration map-data encoder, retained only as a test oracle. */
object LegacyTrailGeoJson {
    fun encode(trails: TrailKml.Document?, trailsVisible: Boolean = true): String {
        val features = if (!trailsVisible) emptyList() else trails?.features?.map { f ->
            val coordinates = JSONArray(f.points.map { listOf(it.longitude, it.latitude) })
            val geometry = when (f.kind) { "Point" -> coordinates.getJSONArray(0); "Polygon" -> JSONArray().put(closed(coordinates)).apply { f.holes.forEach { put(closed(JSONArray(it.map { p -> listOf(p.longitude, p.latitude) }))) } }; else -> coordinates }
            val p = JSONObject().put("name", f.name).put("lineColor", color(f.style.lineColor)).put("lineOpacity", if (f.kind == "Polygon" && !f.style.outline) 0 else (f.style.lineColor ushr 24) / 255.0)
                .put("fillColor", color(f.style.fillColor)).put("fillOpacity", if (f.style.fill) (f.style.fillColor ushr 24) / 255.0 else 0.0).put("width", f.style.width.toInt().coerceAtLeast(2))
                .put("icon", iconKey(f)).put("scale", f.style.iconScale).put("anchor", if (f.style.iconId == "1763") "center" else "bottom")
            feature(f.kind, geometry, p)
        } ?: emptyList()
        return collection(features).toString()
    }

    private fun iconKey(feature: TrailKml.Feature) = "trail-icon-${feature.style.iconId}-${feature.style.iconColor}"
    private fun closed(ring: JSONArray): JSONArray {
        if (ring.length() > 0 && ring.getJSONArray(0).toString() != ring.getJSONArray(ring.length()-1).toString()) ring.put(ring.getJSONArray(0))
        return ring
    }
    private fun color(value: Int) = String.format(java.util.Locale.US, "#%06x", value and 0xffffff)
    private fun feature(kind: String, coordinates: Any, properties: JSONObject) = JSONObject().put("type", "Feature").put("properties", properties).put("geometry", JSONObject().put("type", kind).put("coordinates", coordinates))
    private fun collection(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features))
}
