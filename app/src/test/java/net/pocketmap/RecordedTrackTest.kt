package net.pocketmap
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
class RecordedTrackTest {
    @Test fun exportsValidGpxWithTimeElevationAndEscapedName() {
        val text = RecordedTrack.gpx("Walk & <woods>", listOf(RecordedTrack.Point(40.7, -74.7, 0L, 100.5)))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        assertEquals("Walk & <woods>", doc.getElementsByTagName("name").item(0).textContent)
        assertEquals("1970-01-01T00:00:00Z", doc.getElementsByTagName("time").item(0).textContent)
        assertEquals("100.5", doc.getElementsByTagName("ele").item(0).textContent)
        assertEquals("40.7", doc.getElementsByTagName("trkpt").item(0).attributes.getNamedItem("lat").nodeValue)
    }
    @Test fun breaksLineAcrossLongGpsOutage() {
        val text = RecordedTrack.gpx("Walk", listOf(RecordedTrack.Point(40.0, -74.0, 0L, null), RecordedTrack.Point(41.0, -74.0, 120_000L, null)))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        assertEquals(2, doc.getElementsByTagName("trkseg").length)
    }
    @Test fun recoversSavedPointsAndIgnoresIncompleteWrite() {
        val file = java.io.File.createTempFile("track", ".csv")
        try { file.writeText("40.7,-74.7,1000,12.0,4.5\n40.8,-74.8,31000,,12.0\nbroken"); val points = RecordedTrack.read(file); assertEquals(2, points.size); assertEquals(4.5f, points[0].accuracy); assertEquals(12.0f, points[1].accuracy) } finally { file.delete() }
    }
    @Test fun kmlIsValidAndColorsAccuracySegments() {
        val text = RecordedTrack.kml("Walk & <woods>", listOf(
            RecordedTrack.Point(40.0, -74.0, 0L, 100.0, 3f),
            RecordedTrack.Point(40.1, -74.1, 30_000L, 101.0, 4f),
            RecordedTrack.Point(40.2, -74.2, 60_000L, 102.0, 10f),
            RecordedTrack.Point(40.3, -74.3, 90_000L, null, 20f)
        ))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        assertEquals("Walk & <woods>", doc.getElementsByTagName("name").item(0).textContent)
        assertTrue(text.contains("<styleUrl>#good</styleUrl>"))
        assertTrue(text.contains("<styleUrl>#moderate</styleUrl>"))
        assertTrue(text.contains("<styleUrl>#poor</styleUrl>"))
    }

    @Test fun cumulativeAscentAndDescentAddMultipleHills() {
        // A 100-ft up/down followed by a 200-ft up/down (heights stored in metres).
        val heights = listOf(0.0, 15.24, 30.48, 15.24, 0.0, 30.48, 60.96, 30.48, 0.0)
        val points = heights.mapIndexed { i, h ->
            RecordedTrack.Point(40.0 + i * 0.0002, -74.0, i * 30_000L, h, 4f)
        }
        val stats = RecordedTrack.summarize(points)
        assertEquals(91.44, stats.ascentMeters, 0.001)
        assertEquals(91.44, stats.descentMeters, 0.001)
        assertEquals(240, stats.elapsedSeconds)
        assertTrue(stats.distanceMeters > 0)
        assertTrue(stats.movingSeconds > 0)
        assertTrue(RecordedTrack.displaySummary(stats, true).contains("Ascent: 300 ft"))
    }

    @Test fun ignoresSmallAltitudeJitterAndStationarySpike() {
        val heights = listOf(100.0, 103.0, 99.0, 102.0, 100.0, 145.0, 100.0, 101.0)
        val points = heights.mapIndexed { i, h -> RecordedTrack.Point(40.0, -74.0, i * 30_000L, h) }
        val stats = RecordedTrack.summarize(points)
        assertEquals(0.0, stats.ascentMeters, 0.01)
        assertEquals(0.0, stats.descentMeters, 0.01)
        assertEquals(0.0, stats.distanceMeters, 0.01)
        assertEquals(0, stats.movingSeconds)
    }

    @Test fun ignoresGpsGapsForDistanceAndElevationButNotElapsedTime() {
        val points = listOf(
            RecordedTrack.Point(40.0, -74.0, 0, 0.0),
            RecordedTrack.Point(40.001, -74.0, 30_000, 15.0),
            RecordedTrack.Point(41.0, -74.0, 180_000, 300.0),
            RecordedTrack.Point(41.001, -74.0, 210_000, 310.0)
        )
        val stats = RecordedTrack.summarize(points)
        assertEquals(25.0, stats.ascentMeters, 0.001)
        assertEquals(0.0, stats.descentMeters, 0.001)
        assertEquals(210, stats.elapsedSeconds)
        assertTrue(stats.distanceMeters < 300.0)
    }

    @Test fun sharesSameMachineReadableSummaryInGpxAndKml() {
        val points = listOf(
            RecordedTrack.Point(40.0, -74.0, 0, 100.0, 4f),
            RecordedTrack.Point(40.001, -74.0, 30_000, 115.0, 4f),
            RecordedTrack.Point(40.002, -74.0, 60_000, 100.0, 4f)
        )
        val gpx = RecordedTrack.gpx("Hike", points)
        val kml = RecordedTrack.kml("Hike", points)
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val gpxDoc = parser.parse(gpx.byteInputStream())
        val kmlDoc = parser.parse(kml.byteInputStream())
        for (field in listOf("distanceMeters", "elapsedSeconds", "movingSeconds", "ascentMeters", "descentMeters")) {
            val gpxValue = gpxDoc.getElementsByTagName("trail:$field").item(0).textContent
            val kmlItems = kmlDoc.getElementsByTagName("Data")
            val kmlValue = (0 until kmlItems.length).map { kmlItems.item(it) }
                .first { it.attributes.getNamedItem("name").nodeValue == field }
                .textContent
            assertEquals(gpxValue, kmlValue)
        }
        assertTrue(gpxDoc.getElementsByTagName("desc").item(0).textContent.contains("Ascent"))
        assertTrue(kmlDoc.getElementsByTagName("description").item(0).textContent.contains("Ascent"))
    }
}
