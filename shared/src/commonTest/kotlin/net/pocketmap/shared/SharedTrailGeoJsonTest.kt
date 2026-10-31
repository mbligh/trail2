package net.pocketmap.shared

import kotlin.test.*
import kotlinx.serialization.json.*
import net.pocketmap.shared.trails.SharedTrailGeoJson
import net.pocketmap.shared.trails.SharedTrailKml

class SharedTrailGeoJsonTest {
    @Test fun closesPolygonAndHoleAndPreservesStyleAndEscapedName() {
        val xml = """<kml><Folder><name>Trails</name><Placemark><name>Gate &amp; "horse" 🐴</name>
            <Style><LineStyle><color>80332211</color><width>3.8</width></LineStyle>
            <PolyStyle><fill>0</fill><outline>0</outline></PolyStyle></Style>
            <Polygon><outerBoundaryIs><LinearRing><coordinates>-75,40 -74,40 -74,41</coordinates></LinearRing></outerBoundaryIs>
            <innerBoundaryIs><LinearRing><coordinates>-74.9,40.1 -74.8,40.1 -74.8,40.2</coordinates></LinearRing></innerBoundaryIs></Polygon>
            </Placemark></Folder></kml>"""
        val output = Json.parseToJsonElement(SharedTrailGeoJson.encode(SharedTrailKml.parse(xml.encodeToByteArray()))).jsonObject
        val feature = output.getValue("features").jsonArray.single().jsonObject
        val props = feature.getValue("properties").jsonObject
        assertEquals("Gate & \"horse\" 🐴", props.getValue("name").jsonPrimitive.content)
        assertEquals("#112233", props.getValue("lineColor").jsonPrimitive.content)
        assertEquals(0.0, props.getValue("lineOpacity").jsonPrimitive.double)
        assertEquals(0.0, props.getValue("fillOpacity").jsonPrimitive.double)
        assertEquals(3, props.getValue("width").jsonPrimitive.int)
        val rings = feature.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(2, rings.size)
        for (ring in rings) {
            assertEquals(4, ring.jsonArray.size)
            assertEquals(ring.jsonArray.first(), ring.jsonArray.last())
        }
        assertEquals(JsonArray(listOf(JsonPrimitive(-75.0), JsonPrimitive(40.0))), rings.first().jsonArray.first())
    }
}
