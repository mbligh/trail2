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
            ui.click("trail-menu"); ui.click("menu-layers"); ui.click("map-layer-contours")
            assertEquals(!originallyEnabled, prefs.getBoolean("overlay_contours", originallyEnabled))
            ui.node("map-layer-contours"); ui.click("map-layer-contours")
            assertEquals(originallyEnabled, prefs.getBoolean("overlay_contours", !originallyEnabled))
        }
    }

    @Test fun trailVisibilityMenuLabelUpdatesAfterAction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui = RealComposeUi(scenario)
            ui.click("trail-menu"); ui.click("menu-TOGGLE_TRAILS")
            ui.click("trail-menu"); ui.text("Show trails")
        }
    }
}
