package net.pocketmap

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TrailKmlTest {
    private fun parse(body: String): TrailKml.Document {
        val bytes = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Test trails</name><Folder><name>Trails</name>$body</Folder></Document></kml>""".toByteArray()
        val result = TrailKml.parse(bytes)
        assertEquals(LegacyTrailKml.parse(bytes).toString(), result.toString())
        return result
    }
    @Test fun resolvesNormalStyleAndLongitudeLatitudeOrder() {
        val doc = parse("""<name>Test trails</name>
          <Style id="green"><LineStyle><color>ff579d00</color><width>3.5</width></LineStyle></Style>
          <StyleMap id="mapped"><Pair><key>normal</key><styleUrl>#green</styleUrl></Pair></StyleMap>
          <Placemark><name>Walk</name><styleUrl>#mapped</styleUrl><LineString><coordinates>-74.7,40.7,0 -74.8,40.8,0</coordinates></LineString></Placemark>""")
        assertEquals("Test trails", doc.name)
        val trail = doc.features.single()
        assertEquals("Walk", trail.name)
        assertEquals(0xff009d57.toInt(), trail.style.lineColor)
        assertEquals(3.5f, trail.style.width)
        assertEquals(TrailKml.Coordinate(40.7, -74.7), trail.points.first())
    }
    @Test fun supportsMultiGeometryAndPolygonHoles() {
        val doc = parse("""<Placemark><name>Places</name><MultiGeometry>
          <Point><coordinates>-74.7,40.7</coordinates></Point>
          <Polygon><outerBoundaryIs><LinearRing><coordinates>-75,40 -74,40 -74,41 -75,40</coordinates></LinearRing></outerBoundaryIs>
          <innerBoundaryIs><LinearRing><coordinates>-74.9,40.1 -74.8,40.1 -74.8,40.2 -74.9,40.1</coordinates></LinearRing></innerBoundaryIs></Polygon>
          </MultiGeometry></Placemark>""")
        assertEquals(2, doc.features.size)
        assertEquals(1, doc.features.first { it.kind == "Polygon" }.holes.size)
    }
    @Test fun rejectsHtmlNetworkLinksAndInvalidCoordinates() {
        for (bad in listOf("<html>Sign in</html>", "<!DOCTYPE kml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><kml>&x;</kml>")) {
            assertThrows(Exception::class.java) { TrailKml.parse(bad.toByteArray()) }
        }
        assertThrows(Exception::class.java) { parse("<NetworkLink><Link><href>https://example.com</href></Link></NetworkLink>") }
        assertThrows(Exception::class.java) { parse("<Placemark><Point><coordinates>-74,100</coordinates></Point></Placemark>") }
    }
    @Test fun ignoresOtherLayersAndKeepsCamperStyle() {
        val doc = TrailKml.parse("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
            <Style id="icon-1763-normal"><IconStyle><color>ffd18802</color><scale>1</scale></IconStyle></Style>
            <Folder><name>Properties</name><Placemark><Point><coordinates>0,0</coordinates></Point></Placemark></Folder>
            <Folder><name>Trails</name><Placemark><styleUrl>#icon-1763-normal</styleUrl><Point><coordinates>-74,40</coordinates></Point></Placemark></Folder>
            </Document></kml>""".toByteArray())
        assertEquals(1, doc.features.size)
        assertEquals("1763", doc.features.single().style.iconId)
        assertEquals(0xff0288d1.toInt(), doc.features.single().style.iconColor)
    }
    @Test fun readsLiveMapExportWhenProvided() {
        val fixture = System.getenv("TRAIL_KML_SMOKE") ?: return
        val bytes = File(fixture).readBytes()
        val doc = TrailKml.parse(bytes)
        assertEquals(LegacyTrailKml.parse(bytes).toString(), doc.toString())
        assertEquals("TTA Trails", doc.name)
        assertEquals(108, doc.features.count { it.kind == "LineString" })
        assertEquals(10, doc.features.count { it.kind == "Point" })
        assertEquals(0, doc.features.count { it.kind == "Polygon" })
    }
}
