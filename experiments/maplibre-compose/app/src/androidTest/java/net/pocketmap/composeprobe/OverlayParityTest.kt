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
            // Query hit-testing can return the containing polygon for its hole.
            // Verify the actual fill pixels over a controlled white background.
            val resources = (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as ProbeApplication).offlineResources
            val before = resources.styleLoads.get()
            scenario.onActivity { it.showStyle("blank") }
            awaitResources("controlled polygon background") { resources.styleLoads.get() > before }
            fun screen(position: Position): Pair<Int, Int> {
                var result = 0 to 0
                scenario.onActivity {
                    val offset = it.mapState.screenLocationFromPosition(position)!!
                    val origin = IntArray(2)
                    it.findViewById<android.view.View>(android.R.id.content).getLocationOnScreen(origin)
                    val density = it.resources.displayMetrics.density
                    result = (origin[0] + offset.x.value * density).toInt() to
                        (origin[1] + offset.y.value * density).toInt()
                }
                return result
            }
            val hole = screen(Position(latitude = 40.691, longitude = -74.772))
            val fill = screen(Position(latitude = 40.687, longitude = -74.779))
            awaitResources("white polygon hole and green surrounding fill") {
                val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot() ?: return@awaitResources false
                try {
                    val inside = bitmap.getPixel(hole.first, hole.second)
                    val outside = bitmap.getPixel(fill.first, fill.second)
                    android.graphics.Color.red(inside) > 240 && android.graphics.Color.green(inside) > 240 &&
                        android.graphics.Color.blue(inside) > 240 &&
                        android.graphics.Color.green(outside) - android.graphics.Color.red(outside) > 20 &&
                        android.graphics.Color.green(outside) - android.graphics.Color.blue(outside) > 20
                } finally { bitmap.recycle() }
            }
            assertMapVariety("compose-polygon-hole.png")
            assertTrue("Polygon never rendered", names("trail-polygon-fill").isNotEmpty())
        }
    }
}
