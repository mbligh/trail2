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
                val oldSources = mutableMapOf<String, String>()
                val newSources = mutableMapOf<String, String>()
                // Production TileRepository copies each definition before rewriting it.
                val oldStyle = legacy.style(map, enabled) { id, source ->
                    oldSources[id] = source.toString()
                    JSONObject(source.toString()).put("testRoute", id)
                }
                val newStyle = shared.styleJson(map, enabled) { id, source ->
                    newSources[id] = source.toString()
                    JSONObject(source.toString()).put("testRoute", id)
                }
                assertJsonEquals(oldStyle, newStyle, "${map.name}:$enabled")
                // TileRepository hashes source.toString(): check exact cache identities too.
                assertEquals("${map.name}:$enabled source cache identities", oldSources, newSources)
                // Building another selection must not mutate stored definitions or earlier results.
                assertJsonEquals(legacy.sources, shared.sources)
            }
        }
    }
}
