package net.pocketmap.composeprobe

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import net.pocketmap.composeprobe.RenderAssertions.assertMapVariety
import net.pocketmap.composeprobe.RenderAssertions.awaitResources
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.Position

@RunWith(AndroidJUnit4::class)
class OverlayParityTest {
    @Test fun appLayersUpdateAndRestoreAfterStyleChangesWithOriginalPinArtwork() {
        ActivityScenario.launch(ProbeActivity::class.java).use { scenario ->
            scenario.onActivity { it.fixtureEnabled = true }
            fun names(layer: String): List<String> {
                lateinit var state: MapState
                scenario.onActivity { state = it.mapState }
                return runBlocking { withContext(Dispatchers.Main) {
                    state.queryRenderedFeatures(DpRect(0.dp, 0.dp, 1000.dp, 2000.dp), setOf(layer))
                        .mapNotNull { it.properties?.get("name")?.jsonPrimitive?.content }
                } }
            }
            fun assertLayers() = awaitResources("trails, recording and original pin image") {
                "Styled trail" in names("trail-lines") &&
                    names("trail-recording").isNotEmpty() &&
                    "Pin annotation survives style reload" in names("trail-recording-pins")
            }
            awaitResources("map viewport") { var ready = false; scenario.onActivity { ready = it.mapReady }; ready }
            assertLayers()
            assertMapVariety("compose-app-overlays.png")
            for (style in listOf("earth", "bright")) {
                scenario.onActivity { it.showStyle(style) }
                assertLayers()
            }
            scenario.onActivity { it.trailsVisible = false }
            awaitResources("hide trails without hiding recording/pins") {
                names("trail-lines").isEmpty() && names("trail-recording").isNotEmpty() && names("trail-recording-pins").isNotEmpty()
            }
            scenario.onActivity { it.trailsVisible = true; it.recordingRevision = 1 }
            awaitResources("updated track data") { "Recorded trail 1" in names("trail-recording") }
            scenario.recreate()
            assertLayers()
            // A map click must resolve the pin annotation using the actual symbol layer.
            scenario.onActivity {
                it.mapState.screenLocationFromPosition(Position(latitude = 40.692, longitude = -74.771))
                    ?.let(it.mapState::click)
            }
            awaitResources("pin annotation tap") { var hit = false; scenario.onActivity { hit = it.pinText == "Pin annotation survives style reload" }; hit }
            scenario.onActivity { it.heading = 45f }
            assertMapVariety("compose-app-heading.png")
            // Query the polygon's hole; it must not become a solid fill after migration.
            lateinit var state: MapState
            scenario.onActivity { state = it.mapState }
            val insideHole = runBlocking { withContext(Dispatchers.Main) {
                val center = state.screenLocationFromPosition(Position(latitude = 40.692, longitude = -74.771))!!
                state.queryRenderedFeatures(center, setOf("trail-polygon-fill"))
            } }
            assertTrue("Polygon hole was filled", insideHole.isEmpty())
            assertTrue("Polygon never rendered", names("trail-polygon-fill").isNotEmpty())
        }
    }
}
