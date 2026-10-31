package net.pocketmap

import org.junit.Assert.assertEquals
import org.junit.Test

/** Compare all geometry/style fields with the independent pre-migration DOM reader. */
class TrailKmlParityTest {
    @Test fun sharedReaderMatchesNativeReaderAcrossStylesAndGeometry() {
        val styles = """
          <Style id="icon-1763-normal"><LineStyle><color>aa123456</color><width>3.5</width></LineStyle><IconStyle><color>ffabcdef</color><scale>1.3</scale></IconStyle><PolyStyle><color>7f030201</color><outline>0</outline></PolyStyle></Style>
          <StyleMap id="mapped"><Pair><key>normal</key><styleUrl>#icon-1763-normal</styleUrl></Pair></StyleMap>
        """
        val geometries = listOf(
            "<LineString><coordinates>-74.7,40.7,10 -74.8,40.8,20</coordinates></LineString>",
            "<Point><coordinates>-74.7,40.7</coordinates></Point>",
            "<Polygon><outerBoundaryIs><LinearRing><coordinates>-75,40 -74,40 -74,41 -75,40</coordinates></LinearRing></outerBoundaryIs><innerBoundaryIs><LinearRing><coordinates>-74.9,40.1 -74.8,40.1 -74.8,40.2 -74.9,40.1</coordinates></LinearRing></innerBoundaryIs></Polygon>"
        )
        for (inline in listOf("", "<Style><LineStyle><color>ff0000ff</color><width>99</width></LineStyle><PolyStyle><fill>0</fill></PolyStyle></Style>")) {
            val xml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Paths &amp; places</name>$styles
              <Folder><name>Ignore me</name><Placemark><Point><coordinates>0,0</coordinates></Point></Placemark></Folder>
              <Folder><name>Trails</name><Placemark><name><![CDATA[River & gate 🐴]]></name><styleUrl>#mapped</styleUrl>$inline<MultiGeometry>${geometries.joinToString("")}</MultiGeometry></Placemark>
              <Folder><name>Trails</name><Placemark><Point><coordinates>-74.5,40.5</coordinates></Point></Placemark></Folder></Folder>
              </Document></kml>""".toByteArray()
            assertEquals(LegacyTrailKml.parse(xml).toString(), TrailKml.parse(xml).toString())
        }
    }
}
