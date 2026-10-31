package net.pocketmap

import net.pocketmap.shared.recording.RecordingMapData
import org.json.JSONObject
import org.junit.Test

class RecordingMapDataParityTest {
    @Test fun commonGeometryMatchesOldAndroidForGapsAndPinNotes() {
        val cases = listOf(emptyList(), listOf(0L), listOf(0L, 30_000, 60_000),
            listOf(0L, 90_000, 180_001, 210_001, 310_001), listOf(1000L, 1000, 999))
        for (times in cases) {
            val points = times.mapIndexed { i, time -> RecordedTrack.Point(40.7 + i * .001, -74.8 + i * .001, time, 100.0, 3f) }
            assertJsonEquals(JSONObject(LegacyRecordingMapData.track(points)), JSONObject(RecordingMapData.track(points)))
        }
        val pins = listOf("", " \n\t", "Gate, \"broken\"\n🐴 <&>").map { RecordedPins.Pin(40.7, -74.8, 1000, 100.0, 3f, it) }
        assertJsonEquals(JSONObject(LegacyRecordingMapData.pins(pins)), JSONObject(RecordingMapData.pins(
            pins.map { RecordingMapData.Pin(it.latitude, it.longitude, it.text) })))
    }
}
