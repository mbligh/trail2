package net.pocketmap

import net.pocketmap.shared.trails.SharedTrailGeoJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrailGeoJsonParityTest {
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
            assertTrue("Geometry or styles differ: $actual\n$expected", expected.similar(actual))
        }
        assertTrue(JSONObject(LegacyTrailGeoJson.encode(null)).similar(
            JSONObject(SharedTrailGeoJson.encode(net.pocketmap.shared.trails.SharedTrailKml.Document("Empty", emptyList())))))
    }
}
