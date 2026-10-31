package net.pocketmap

import net.pocketmap.shared.trails.SharedTrailGeoJson
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TrailGeoJsonParityTest {
    private fun assertJsonEquals(expected: Any?, actual: Any?, path: String = "root") {
        when (expected) {
            is JSONObject -> {
                assertTrue("$path must be an object", actual is JSONObject)
                actual as JSONObject
                val keys = expected.keys().asSequence().toSet()
                assertEquals(path, keys, actual.keys().asSequence().toSet())
                for (key in keys) assertJsonEquals(expected.get(key), actual.get(key), "$path.$key")
            }
            is JSONArray -> {
                assertTrue("$path must be an array", actual is JSONArray)
                actual as JSONArray
                assertEquals(path, expected.length(), actual.length())
                for (index in 0 until expected.length()) assertJsonEquals(expected.get(index), actual.get(index), "$path[$index]")
            }
            is Number -> {
                assertTrue("$path must be numeric", actual is Number)
                assertEquals(path, expected.toDouble(), (actual as Number).toDouble(), 0.0)
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    @Test fun sharedConverterMatchesPreviousProductionGeometryAndStyleProperties() {
        val outer = listOf(TrailKml.Coordinate(40.0, -75.0), TrailKml.Coordinate(40.0, -74.0), TrailKml.Coordinate(41.0, -74.0))
        val hole = listOf(TrailKml.Coordinate(40.1, -74.9), TrailKml.Coordinate(40.1, -74.8), TrailKml.Coordinate(40.2, -74.8))
        for (closed in listOf(false, true)) for (fill in listOf(false, true)) for (outline in listOf(false, true)) {
            val ring = if (closed) outer + outer.first() else outer
            val styles = listOf("1763", "1899", "1205", "503", "", "unknown").map { icon ->
                TrailKml.Style(0x80123456.toInt(), 3.8f, icon, 0xffaabbcc.toInt(), 1.3f, 0x33010203, fill, outline)
            }
            val features = styles.flatMap { style -> listOf(
                TrailKml.Feature("Line \\\" & 🐴", "LineString", outer.take(2), emptyList(), style),
                TrailKml.Feature("Point", "Point", outer.take(1), emptyList(), style),
                TrailKml.Feature("Area", "Polygon", ring, listOf(hole), style)
            ) }
            val document = TrailKml.Document("Fixture", features)
            val actual = JSONObject(SharedTrailGeoJson.encode(TrailKml.shared(document)))
            val expected = JSONObject(LegacyTrailGeoJson.encode(document))
            assertJsonEquals(expected, actual)
        }
        assertJsonEquals(JSONObject(LegacyTrailGeoJson.encode(null)),
            JSONObject(SharedTrailGeoJson.encode(net.pocketmap.shared.trails.SharedTrailKml.Document("Empty", emptyList()))))
    }
}
