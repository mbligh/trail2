package net.pocketmap.shared.recording

import kotlinx.serialization.json.*
import net.pocketmap.shared.TrackStatistics

/** Geometry only: callers retain ownership of GPS acquisition and recording files. */
object RecordingMapData {
    data class Point(override val latitude: Double, override val longitude: Double,
        override val time: Long, override val altitude: Double? = null) : TrackStatistics.Sample
    data class Pin(val latitude: Double, val longitude: Double, val text: String)

    /** Preserve Android's existing rule: do not draw across gaps over 90 seconds. */
    fun track(points: List<TrackStatistics.Sample>): String {
        val segments = mutableListOf<MutableList<TrackStatistics.Sample>>()
        points.forEachIndexed { index, point ->
            if (index == 0 || point.time - points[index - 1].time > 90_000) segments.add(mutableListOf())
            segments.last().add(point)
        }
        return collection(segments.filter { it.size >= 2 }.map { segment ->
            feature("LineString", JsonArray(segment.map { coordinate(it.latitude, it.longitude) }), "Recorded trail")
        })
    }

    fun pins(pins: List<Pin>): String = collection(pins.map { pin ->
        feature("Point", coordinate(pin.latitude, pin.longitude), if (pin.text.isBlank()) "Pin" else "Pin\n${pin.text}")
    })

    private fun coordinate(latitude: Double, longitude: Double) = JsonArray(listOf(JsonPrimitive(longitude), JsonPrimitive(latitude)))
    private fun feature(kind: String, coordinates: JsonArray, name: String) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") { put("name", name) }
        putJsonObject("geometry") { put("type", kind); put("coordinates", coordinates) }
    }
    private fun collection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection"); put("features", JsonArray(features))
    }.toString()
}
