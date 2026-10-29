package net.pocketmap

import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecordedPinsTest {
    private val text = "Gate, broken & <blocked> \"east\"\nFollow café trail 🌲"
    private fun track() = File(RuntimeEnvironment.getApplication().filesDir, "Walk.csv").apply { writeText("40.69,-74.78,1000,,4\n") }
    private fun location() = Location("gps").apply { latitude = 40.69; longitude = -74.78; time = 1000; altitude = 120.5; accuracy = 4f }
    private fun xml(text: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
    private fun pinDescription(doc: org.w3c.dom.Document) = (doc.getElementsByTagName("Placemark").item(0) as org.w3c.dom.Element).getElementsByTagName("description").item(0).textContent

    @Test fun readsLegacyPinsAlongsideMultilineUnicodeNotes() {
        val track = track()
        File(track.parentFile, "Walk.pins").writeText("40.69,-74.78,1000,120.5,4.0\n")
        RecordedPins.append(track, location(), text)
        RecordedPins.append(track, location())
        val pins = RecordedPins.read(track)
        assertEquals(3, pins.size)
        assertEquals("", pins.first().text)
        assertEquals(text, pins[1].text)
        assertEquals(120.5, pins[1].altitude)
        assertEquals(4f, pins[1].accuracy)
        assertEquals("", pins.last().text)
        assertEquals(3, File(track.parentFile, "Walk.pins").readLines().size)
    }

    @Test fun kmlAndGpxPreserveTextAndEscapeXml() {
        val pin = RecordedPins.Pin(40.69, -74.78, 1000, 120.5, 4f, text)
        val kml = xml(RecordedPins.addToKml(RecordedTrack.kml("Walk", emptyList()), listOf(pin)))
        val gpx = xml(RecordedPins.addToGpx(RecordedTrack.gpx("Walk", emptyList()), listOf(pin)))
        assertEquals(text, pinDescription(kml))
        assertEquals(text, (gpx.getElementsByTagName("wpt").item(0) as org.w3c.dom.Element).getElementsByTagName("desc").item(0).textContent)
        assertEquals("40.69", gpx.getElementsByTagName("wpt").item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("-74.78,40.69,120.5", kml.getElementsByTagName("coordinates").item(0).textContent)
        val invalid = pin.copy(text = "Gate\u0001blocked")
        assertEquals("Gateblocked", pinDescription(xml(RecordedPins.addToKml(RecordedTrack.kml("Walk", emptyList()), listOf(invalid)))))
    }

    @Test fun damagedOptionalTextDoesNotLosePinCoordinates() {
        val track = track()
        File(track.parentFile, "Walk.pins").writeText("40.69,-74.78,1000,,,not valid base64!\nbroken\n")
        val pin = RecordedPins.read(track).single()
        assertEquals(40.69, pin.latitude, 0.000001)
        assertEquals("", pin.text)
    }
}
