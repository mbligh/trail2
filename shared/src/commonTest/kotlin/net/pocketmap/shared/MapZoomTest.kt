package net.pocketmap.shared

import kotlin.test.*
import net.pocketmap.shared.map.MapZoom

class MapZoomTest {
    @Test fun displayedZoomPreservesLegacyScaleAndRendererLimits() {
        for (step in 30..190) {
            val displayed = step / 10.0
            assertEquals((displayed - 1.0).coerceIn(2.0, 18.0), MapZoom.renderer(displayed))
            assertEquals(displayed, MapZoom.displayed(MapZoom.renderer(displayed)), 0.000001)
        }
        assertEquals(2.0, MapZoom.renderer(-100.0))
        assertEquals(18.0, MapZoom.renderer(100.0))
    }
}
