package net.pocketmap

import org.json.JSONArray
import org.json.JSONObject

/** Frozen Android map-data implementation from before sharing recording geometry. */
object LegacyRecordingMapData {
    fun track(track: List<RecordedTrack.Point>): String {
        val segments = mutableListOf<MutableList<RecordedTrack.Point>>()
        track.forEachIndexed { index, point ->
            if (index == 0 || point.time - track[index-1].time > 90_000) segments.add(mutableListOf())
            segments.last().add(point)
        }
        return collection(segments.filter { it.size >= 2 }.map { points -> feature("LineString", JSONArray(points.map { listOf(it.longitude, it.latitude) }), JSONObject().put("name", "Recorded trail")) }).toString()
    }
    private fun feature(kind: String, coordinates: JSONArray, properties: JSONObject) = JSONObject().put("type", "Feature").put("properties", properties).put("geometry", JSONObject().put("type", kind).put("coordinates", coordinates))

    fun pins(pins: List<RecordedPins.Pin>): String = collection(pins.map { pin -> feature("Point", JSONArray().put(pin.longitude).put(pin.latitude), JSONObject().put("name", if (pin.text.isBlank()) "Pin" else "Pin\n${pin.text}")) }).toString()
    private fun collection(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features))
}
