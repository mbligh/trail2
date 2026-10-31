package net.pocketmap.shared

import kotlin.test.*
import net.pocketmap.shared.recording.RecordingExportXml as Export
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.core.KtXmlReader

class RecordingExportXmlTest {
    private val stats = TrackStatistics.Summary(1234.5, 180, 60, 30.0, 20.0)
    private class Element(val name: String) { var text = "" }
    @OptIn(ExperimentalXmlUtilApi::class)
    private fun elements(xml: String): List<Element> {
        val reader = KtXmlReader(xml, expandEntities = true, relaxed = false)
        val stack = mutableListOf<Element>()
        val result = mutableListOf<Element>()
        try {
            while (reader.hasNext()) when (reader.next()) {
                EventType.START_ELEMENT -> Element(reader.localName).let { stack.add(it); result.add(it) }
                EventType.END_ELEMENT -> stack.removeAt(stack.lastIndex)
                EventType.TEXT, EventType.ENTITY_REF, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE -> if (stack.isNotEmpty()) stack.last().text += reader.text
                else -> Unit
            }
            assertTrue(stack.isEmpty())
            return result
        } finally { reader.close() }
    }

    @Test fun preservesMillisecondTimesAndMachineReadableSummary() {
        val points = listOf(-1L, 0L, 10L, 100L, 123L, 999L).map { Export.Fix(40.7, -74.8, it, 100.5, 3.0) }
        val gpx = elements(Export.gpx("Track", points, stats, "Summary"))
        assertEquals(listOf("1969-12-31T23:59:59.999Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00.010Z",
            "1970-01-01T00:00:00.100Z", "1970-01-01T00:00:00.123Z", "1970-01-01T00:00:00.999Z"), gpx.filter { it.name == "time" }.map { it.text })
        assertEquals("1234.5", gpx.single { it.name == "distanceMeters" }.text)
        assertEquals("180", gpx.single { it.name == "elapsedSeconds" }.text)
        val kml = Export.kml("Track", points, stats, "Summary")
        elements(kml)
        for (value in listOf("distanceMeters" to "1234.5", "elapsedSeconds" to "180", "movingSeconds" to "60", "ascentMeters" to "30.0", "descentMeters" to "20.0")) {
            assertTrue(kml.contains("<Data name=\"${value.first}\"><value>${value.second}</value></Data>"))
        }
    }

    @Test fun groupsAccuracyRunsAndPreservesGpsGap() {
        val accuracies = listOf(3.0, 4.0, 4.0, 5.0, 15.0, 15.1, null, 3.0, 3.0)
        val points = accuracies.mapIndexed { i, accuracy ->
            Export.Fix(40.0 + .001 * i, -74.0, if (i <= 6) i * 30_000L else 300_001L + (i - 7) * 30_000,
                if (i == 0) null else 100.0, accuracy)
        }
        val kml = elements(Export.kml("Track", points, stats, "Summary"))
        assertEquals(listOf("#good", "#moderate", "#poor", "#unknown", "#good"), kml.filter { it.name == "styleUrl" }.map { it.text })
        assertEquals(listOf(3, 3, 2, 2, 2), kml.filter { it.name == "coordinates" }.map { it.text.trim().lines().size })
        assertTrue(kml.first { it.name == "coordinates" }.text.contains("-74.0,40.0,0.0"))
        val gpx = elements(Export.gpx("Track", points, stats, "Summary"))
        assertEquals(2, gpx.count { it.name == "trkseg" })
        assertEquals(9, gpx.count { it.name == "trkpt" })
        assertEquals(8, gpx.count { it.name == "ele" })
    }

    @Test fun escapesNamesAndNotesAndRetainsBluePinStyleAndWaypointOrder() {
        val name = "Walk 🐴 <&> \u0001\ud800"
        val note = "Gate <&> \"broken\"\n🐴\t\u0001\udc00"
        val pins = listOf(Export.Pin(40.7, -74.8, 123L, null, note), Export.Pin(40.7, -74.8, 1000L, 80.0, ""))
        val gpx = Export.addToGpx(Export.gpx(name, emptyList(), stats, "Stats <&>"), pins)
        val kml = Export.addToKml(Export.kml(name, emptyList(), stats, "Stats <&>"), pins)
        val g = elements(gpx); val k = elements(kml)
        assertEquals("Walk 🐴 <&> ", g.last { it.name == "name" }.text)
        assertEquals("Walk 🐴 <&> ", k.first { it.name == "name" }.text)
        assertEquals("Gate <&> \"broken\"\n🐴\t", g.first { it.name == "desc" }.text)
        assertEquals("Stats <&>", g.last { it.name == "desc" }.text)
        assertEquals("Gate <&> \"broken\"\n🐴\t", k.last { it.name == "description" }.text)
        assertTrue(gpx.indexOf("<wpt ") < gpx.indexOf("<trk>"))
        assertEquals(2, g.count { it.name == "wpt" })
        assertEquals(2, k.count { it.name == "Point" })
        assertTrue(kml.contains("https://maps.google.com/mapfiles/kml/pushpin/blue-pushpin.png"))
        assertEquals(listOf("#recorded-pin", "#recorded-pin"), k.filter { it.name == "styleUrl" }.map { it.text })
        assertTrue(k.any { it.name == "when" && it.text == "1970-01-01T00:00:00.123Z" })
    }
}
