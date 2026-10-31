package net.pocketmap

import org.junit.Assert.assertEquals
import org.junit.Test

/** Independent pre-migration XML writers must produce the same Android exports. */
class RecordingExportParityTest {
    @Test fun exportedBytesMatchForTimestampsGapsAccuracyAndPins() {
        val cases = listOf(emptyList(), listOf(0L), listOf(-1L, 10, 100, 123, 999, 1000),
            listOf(0L, 30_000, 60_000, 90_000, 120_000, 210_001, 240_001), listOf(1000L, 1000, 999))
        for (times in cases) {
            val accuracy = listOf(3f, 4.9f, 5f, 15f, 15.1f, null, 3f)
            val points = times.mapIndexed { i, time -> RecordedTrack.Point(40.7 + i * .001, -74.8, time, if (i % 2 == 0) 100.5 else null, accuracy[i % accuracy.size]) }
            val old = points.map { LegacyRecordedTrack.Point(it.latitude, it.longitude, it.time, it.altitude, it.accuracy) }
            val name = "Walk & <woods> 🐴"
            val gpx = RecordedTrack.gpx(name, points)
            val kml = RecordedTrack.kml(name, points)
            assertEquals(LegacyRecordedTrack.gpx(name, old), gpx)
            assertEquals(LegacyRecordedTrack.kml(name, old), kml)
            val pins = listOf("", "Gate <&>\n🐴 \"note\"", "bad\u0001text").mapIndexed { i, text ->
                RecordedPins.Pin(40.7, -74.8, listOf(-1L, 123L, 1000L)[i], if (i == 0) null else 120.5, 3f, text)
            }
            val oldPins = pins.map { LegacyRecordedPins.Pin(it.latitude, it.longitude, it.time, it.altitude, it.accuracy, it.text) }
            assertEquals(LegacyRecordedPins.addToGpx(gpx, oldPins), RecordedPins.addToGpx(gpx, pins))
            assertEquals(LegacyRecordedPins.addToKml(kml, oldPins), RecordedPins.addToKml(kml, pins))
        }
    }
}
