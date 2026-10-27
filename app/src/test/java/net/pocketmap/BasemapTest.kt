package net.pocketmap

import org.junit.Assert.*
import org.junit.Test

class BasemapTest {
    @Test fun configuredMapsAndIndependentLayerDefaults() {
        val config = """{"sources":{},"basemaps":[{"name":"streets","style":"maps/custom/style.json"},{"name":"hybrid","label":"Satellite and roads","style":"styles/hybrid.json"}],"overlays":[{"id":"parcels","name":"Parcels","defaultVisible":true,"sources":{"parcel-data":{"type":"geojson","data":"asset://maps/parcels.geojson"}},"layers":[]}]}"""
        val parsed = MapConfiguration { if (it == "basemaps.json") config else "{}" }
        assertEquals(listOf("streets", "hybrid"), parsed.basemaps.map { it.name })
        assertEquals("Satellite and roads", parsed.basemaps[1].label)
        assertEquals("maps/custom/style.json", parsed.basemaps[0].style)
        assertTrue(parsed.overlays.single().defaultVisible)
        assertTrue(parsed.sources.has("parcel-data"))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateMapNamesAreRejected() {
        MapConfiguration { if (it == "basemaps.json") """{"basemaps":[{"name":"same"},{"name":"same"}]}""" else "{}" }
    }
    @Test fun standardMbtilesRowsAreFlipped() {
        assertEquals(0L, MbTilesCache.xyzToTmsY(0,0))
        assertEquals(20451L, MbTilesCache.xyzToTmsY(15,12316))
    }
}
