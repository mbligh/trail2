package net.pocketmap

import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercise real shared Compose controls alongside the renderer's main-thread event loop. */
class ComposeHeaderTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    @Test fun headerHasCenteredLabelsReadingsAndFullHeightDividers() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui = RealComposeUi(scenario)
            for ((index, label) in listOf("Dist", "Speed", "Time", "GPS", "Zoom").withIndex()) {
                ui.text(label)
                val slot = ui.bounds("header-metric-$index")
                val title = ui.bounds("header-label-$index")
                val value = ui.bounds("header-value-$index")
                assertEquals("Label not centered: $label", slot.center.x, title.center.x, 1f)
                assertEquals("Value not centered: $label", slot.center.x, value.center.x, 1f)
                assertEquals("Label should be above value: $label", true, title.bottom <= value.top)
                if (index > 0) {
                    val divider = ui.bounds("header-divider-$index")
                    assertEquals("Divider should extend above both lines", true, divider.top <= title.top)
                    assertEquals("Divider should extend below both lines", true, divider.bottom >= value.bottom)
                }
            }
        }
    }

    @Test fun basemapSelectionAndLayerMenuPersistInExistingPreferences() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui = RealComposeUi(scenario)
            lateinit var prefs: android.content.SharedPreferences
            var originallyEnabled = false
            scenario.onActivity { activity ->
                prefs = activity.getSharedPreferences("map", android.content.Context.MODE_PRIVATE)
                val fallback = MapConfiguration(activity).overlays.first { it.id == "contours" }.defaultVisible
                originallyEnabled = prefs.getBoolean("overlay_contours", fallback)
            }
            ui.node("basemap-artwork")
            ui.click("basemap-control"); ui.click("basemap-hybrid")
            assertEquals("hybrid", prefs.getString("basemap", ""))
            ui.click("basemap-control"); ui.click("map-layer-contours")
            assertEquals(!originallyEnabled, prefs.getBoolean("overlay_contours", originallyEnabled))
            ui.node("map-layer-contours"); ui.click("map-layer-contours")
            assertEquals(originallyEnabled, prefs.getBoolean("overlay_contours", !originallyEnabled))
        }
    }

    @Test fun removedBasemapFallsBackWithoutDiscardingCameraOrLayers() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("map", android.content.Context.MODE_PRIVATE)
        val original = prefs.all
        try {
            prefs.edit().putString("basemap", "opentopomap").putBoolean("maplibre_migrated", true)
                .putFloat("lat", 40.691f).putFloat("lon", -74.773f).putFloat("zoom", 15f)
                .putBoolean("overlay_contours", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui = RealComposeUi(scenario)
                ui.description("Choose basemap: bright")
                assertEquals("bright", prefs.getString("basemap", ""))
                scenario.onActivity { activity ->
                    assertEquals(listOf("bright", "hybrid", "njsatellite", "openstreetmap"), MapConfiguration(activity).basemaps.map { it.name })
                }
                assertEquals(40.691f, prefs.getFloat("lat", 0f), 0f)
                assertEquals(-74.773f, prefs.getFloat("lon", 0f), 0f)
                assertEquals(15f, prefs.getFloat("zoom", 0f), 0f)
                assertEquals(true, prefs.getBoolean("overlay_contours", false))
            }
        } finally {
            val edit = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            } }
            edit.commit()
        }
    }

    @Test fun trailVisibilityMenuLabelUpdatesAfterAction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui = RealComposeUi(scenario)
            lateinit var prefs: android.content.SharedPreferences
            scenario.onActivity {
                prefs = it.getSharedPreferences("map", android.content.Context.MODE_PRIVATE)
            }
            val original = prefs.getBoolean("tta_trails_visible", true)
            ui.click("basemap-control")
            ui.text("TTA Trails")
            ui.click("tta-trails-toggle")
            assertEquals(!original, prefs.getBoolean("tta_trails_visible", original))
            ui.click("tta-trails-toggle")
            assertEquals(original, prefs.getBoolean("tta_trails_visible", !original))
        }
    }
}
