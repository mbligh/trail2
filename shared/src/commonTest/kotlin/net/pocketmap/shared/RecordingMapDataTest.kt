package net.pocketmap.shared

import kotlin.test.*
import kotlinx.serialization.json.*
import net.pocketmap.shared.recording.RecordingMapData

class RecordingMapDataTest {
    private fun features(json: String) = Json.parseToJsonElement(json).jsonObject.getValue("features").jsonArray
    private fun point(time: Long, longitude: Double = -74.0) = RecordingMapData.Point(40.0, longitude, time)

    @Test fun emptyAndSingleFixDoNotInventALine() {
        assertTrue(features(RecordingMapData.track(emptyList())).isEmpty())
        assertTrue(features(RecordingMapData.track(listOf(point(1000)))).isEmpty())
        assertTrue(features(RecordingMapData.pins(emptyList())).isEmpty())
    }

    @Test fun retainsNinetySecondEdgesButBreaksLongerGapsWithoutJoiningSingletons() {
        val points = listOf(point(0, -75.0), point(90_000, -74.9), point(180_001, -74.8),
            point(180_002, -74.7), point(270_003, -74.6))
        val lines = features(RecordingMapData.track(points))
        assertEquals(2, lines.size)
        val coordinates = lines.map { it.jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray }
        assertEquals(listOf(-75.0, -74.9), coordinates[0].map { it.jsonArray[0].jsonPrimitive.double })
        assertEquals(listOf(-74.8, -74.7), coordinates[1].map { it.jsonArray[0].jsonPrimitive.double })
        assertEquals(40.0, coordinates[0][0].jsonArray[1].jsonPrimitive.double)
        for (line in lines) assertEquals("Recorded trail", line.jsonObject.getValue("properties").jsonObject.getValue("name").jsonPrimitive.content)
        // Preserve the old Android map order for duplicate/backward timestamps.
        val sameRun = features(RecordingMapData.track(listOf(point(1000), point(1000), point(999))))
        assertEquals(3, sameRun.single().jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.size)
    }

    @Test fun pinLabelsPreserveNotesAndEscapedCharacters() {
        val notes = listOf("", " \n\t", "Gate, \"broken\"\n🐴 <&>")
        val pins = features(RecordingMapData.pins(notes.map { RecordingMapData.Pin(40.7, -74.8, it) }))
        assertEquals(listOf("Pin", "Pin", "Pin\nGate, \"broken\"\n🐴 <&>"), pins.map {
            it.jsonObject.getValue("properties").jsonObject.getValue("name").jsonPrimitive.content
        })
    }
}
