package net.pocketmap.shared

import kotlin.test.*
import net.pocketmap.shared.trails.SharedTrailKml

class SharedTrailKmlTest {
    private fun parse(body: String) = SharedTrailKml.parse("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Test &amp; trails 🐴</name>$body</Document></kml>""".encodeToByteArray())

    @Test fun retainsStyleMapsInlineStylesGeometryHolesAndNestedFolderIdentity() {
        val doc = parse("""
            <Style id="icon-503-green"><LineStyle><color>ff579d00</color><width>13</width></LineStyle>
              <IconStyle><color>ffd18802</color><scale>3</scale></IconStyle>
              <PolyStyle><color>7f030201</color><fill>0</fill><outline>0</outline></PolyStyle></Style>
            <StyleMap id="first"><Pair><key>highlight</key><styleUrl>#wrong</styleUrl></Pair><Pair><key>normal</key><styleUrl>#second</styleUrl></Pair></StyleMap>
            <StyleMap id="second"><Pair><key>normal</key><styleUrl>#icon-503-green</styleUrl></Pair></StyleMap>
            <Folder><name>Properties</name><Placemark><Point><coordinates>0,0</coordinates></Point></Placemark></Folder>
            <Folder><name>Trails</name><Placemark><name><![CDATA[River & gate 🐴]]></name><styleUrl>#first</styleUrl><MultiGeometry>
              <LineString><coordinates>-74.7,40.7,100 -74.8,40.8,90</coordinates></LineString>
              <Point><coordinates>-74.75,40.75</coordinates></Point>
              <Polygon><outerBoundaryIs><LinearRing><coordinates>-75,40 -74,40 -74,41 -75,40</coordinates></LinearRing></outerBoundaryIs>
                <innerBoundaryIs><LinearRing><coordinates>-74.9,40.1 -74.8,40.1 -74.8,40.2 -74.9,40.1</coordinates></LinearRing></innerBoundaryIs></Polygon>
            </MultiGeometry></Placemark>
            <Folder><name>Trails</name><Placemark><Point><coordinates>-74.6,40.6</coordinates></Point></Placemark></Folder>
            <Placemark><styleUrl>#first</styleUrl><Style><LineStyle><color>ff0000ff</color><width>3.5</width></LineStyle></Style>
              <LineString><coordinates>-74.7,40.7 -74.8,40.8</coordinates></LineString></Placemark>
            </Folder>
        """.trimIndent())
        assertEquals("Test & trails 🐴", doc.name)
        assertEquals(5, doc.features.size)
        val mapped = doc.features.filter { it.name == "River & gate 🐴" }
        assertEquals(listOf("LineString", "Point", "Polygon"), mapped.map { it.kind })
        val style = mapped.first().style
        assertEquals(0xff009d57.toInt(), style.lineColor)
        assertEquals(12f, style.width)
        assertEquals("503", style.iconId)
        assertEquals(0xff0288d1.toInt(), style.iconColor)
        assertEquals(2f, style.iconScale)
        assertEquals(0x7f010203, style.fillColor)
        assertFalse(style.fill); assertFalse(style.outline)
        assertEquals(1, mapped.last().holes.size)
        assertEquals(SharedTrailKml.Coordinate(40.7, -74.7), mapped.first().points.first())
        assertEquals(1, doc.features.count { it.kind == "Point" && it.name == "Trail" })
        assertEquals(0xffff0000.toInt(), doc.features.last().style.lineColor)
        assertEquals(3.5f, doc.features.last().style.width)
    }

    @Test fun rejectsMalformedExternalAndOversizedMaps() {
        val invalid = listOf(
            "<html>Sign in</html>", "<kml><Folder></kml>",
            "<!DOCTYPE kml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><kml>&x;</kml>",
            "<kml><Folder><name>Trails</name><NetworkLink/></Folder></kml>",
            "<kml><Folder><name>Trails</name><Placemark><Point><coordinates>-74,100</coordinates></Point></Placemark></Folder></kml>",
            "<kml><Folder><name>Trails</name><Placemark><Point><coordinates>NaN,40</coordinates></Point></Placemark></Folder></kml>",
            "<kml><Folder><name>Trails</name><Placemark><LineString><coordinates>-74,40</coordinates></LineString></Placemark></Folder></kml>",
            "<kml>" + "<Folder>".repeat(130) + "</Folder>".repeat(130) + "</kml>"
        )
        invalid.forEach { xml -> assertFailsWith<Exception> { SharedTrailKml.parse(xml.encodeToByteArray()) } }
        assertFailsWith<IllegalArgumentException> { SharedTrailKml.parse(ByteArray(SharedTrailKml.MAX_BYTES + 1)) }
        val corrupt = "<kml><Folder><name>Trails</name><Placemark><name>".encodeToByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) +
            "</name><Point><coordinates>-74,40</coordinates></Point></Placemark></Folder></kml>".encodeToByteArray()
        assertFailsWith<Exception> { SharedTrailKml.parse(corrupt) }
    }

    @Test fun handlesPrefixedKmlAndBuiltInCharacterReferences() {
        val xml = """<?xml version="1.0"?><k:kml xmlns:k="http://www.opengis.net/kml/2.2"><k:Document><k:name>A &amp; B</k:name><k:Folder><k:name>Trails</k:name><k:Placemark><k:name>Gate &lt;open&gt; &#x1F434;</k:name><k:Point><k:coordinates>-74,40</k:coordinates></k:Point></k:Placemark></k:Folder></k:Document></k:kml>"""
        val doc = SharedTrailKml.parse(xml.encodeToByteArray())
        assertEquals("A & B", doc.name)
        assertEquals("Gate <open> 🐴", doc.features.single().name)
        assertEquals(doc, SharedTrailKml.parse(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + xml.encodeToByteArray()))
    }
}
