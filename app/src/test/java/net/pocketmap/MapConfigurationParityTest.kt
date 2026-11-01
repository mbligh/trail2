package net.pocketmap

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MapConfigurationParityTest {
    @Test fun allShippingBasemapsAndOverlayCombinationsMatchFrozenBuilder() {
        val root = listOf(File("."), File("..")).first { File(it, "basemaps.json").isFile }
        val generated = File(root, "app/build/generated/mapAssets")
        assertTrue("Offline assets must be built before the parity test", File(generated, "resolved-sources.json").isFile)
        val read: (String) -> String = { path ->
            (if (path == "basemaps.json") File(root, path) else File(generated, path)).readText()
        }
        val legacy = LegacyMapConfiguration(read)
        val shared = MapConfiguration(read)
        assertEquals(legacy.basemaps, shared.basemaps)
        assertEquals(legacy.overlays.map { Triple(it.id, it.name, it.defaultVisible) }, shared.overlays.map { Triple(it.id, it.name, it.defaultVisible) })
        assertJsonEquals(legacy.sources, shared.sources)
        for (mask in 0 until (1 shl shared.overlays.size)) {
            val enabled = shared.overlays.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.map { it.id }.toSet()
            for (map in shared.basemaps) {
                // Preserve source identities/definitions at the native tile-routing boundary.
                val route: (String, JSONObject) -> JSONObject = { id, source -> source.put("testRoute", id) }
                assertJsonEquals(legacy.style(map, enabled, route), shared.styleJson(map, enabled, route), "${map.name}:$enabled")
                // Building another selection must not mutate stored definitions or earlier results.
                assertJsonEquals(legacy.sources, shared.sources)
            }
        }
    }
}
