package net.pocketmap.shared.config

import kotlinx.serialization.json.*
import kotlin.test.*

class SharedMapConfigurationTest {
    private val fixture = """{"basemaps":[{"name":"raster","source":"base"},{"name":"vector","style":"style.json"}],"overlays":[{"id":"notes","name":"Notes","defaultVisible":true,"sources":{"notes":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}},"layers":[{"id":"note-labels","type":"symbol","source":"notes"}]}]}"""
    private fun config(text: String = fixture) = SharedMapConfiguration { path -> when (path) {
        "basemaps.json" -> text
        "resolved-sources.json" -> """{"base":{"type":"raster","tiles":["https://tiles.invalid/{z}/{x}/{y}"]}}"""
        else -> """{"version":8,"glyphs":"asset://fonts/{fontstack}/{range}","sources":{"base":{"type":"raster","tiles":["old"]}},"layers":[]}"""
    } }
    @Test fun onlyEnabledOverlaysReachPlatformRouterAndInputsRemainImmutable() {
        val c = config()
        assertEquals("raster", c.basemaps.first().label)
        assertTrue(c.overlays.single().defaultVisible)
        val before = c.sources.toString()
        val routed = mutableListOf<String>()
        fun style(index: Int, enabled: Set<String>) = Json.parseToJsonElement(c.style(c.basemaps[index], enabled) { id, definition ->
            routed.add(id); definition
        }).jsonObject
        val disabled = style(0, emptySet())
        assertEquals(listOf("base"), routed)
        assertEquals(1, disabled.getValue("layers").jsonArray.size)
        assertTrue(disabled.getValue("glyphs").jsonPrimitive.content.startsWith("https:"))
        routed.clear()
        val enabled = style(1, setOf("notes"))
        assertEquals(listOf("base", "notes"), routed)
        assertEquals("asset://fonts/{fontstack}/{range}", enabled.getValue("glyphs").jsonPrimitive.content)
        assertEquals("https://tiles.invalid/{z}/{x}/{y}", enabled.getValue("sources").jsonObject.getValue("base").jsonObject.getValue("tiles").jsonArray[0].jsonPrimitive.content)
        assertEquals(before, c.sources.toString())
        routed.clear()
        assertEquals(disabled, style(0, emptySet()))
        assertEquals(listOf("base"), routed)
    }
    @Test fun duplicateNamesOverlayIdsAndSourcesAreRejected() {
        assertFailsWith<IllegalArgumentException> { config("""{"basemaps":[{"name":"x"},{"name":"x"}]}""") }
        assertFailsWith<IllegalArgumentException> { config("""{"basemaps":[]}""") }
        assertFailsWith<IllegalArgumentException> { config(fixture.replace("\"sources\":{\"notes\"", "\"sources\":{\"base\"")) }
        val overlay = """{"id":"same","name":"Same","layers":[]}"""
        assertFailsWith<IllegalArgumentException> { config("""{"basemaps":[{"name":"x"}],"overlays":[$overlay,$overlay]}""") }
    }
}
