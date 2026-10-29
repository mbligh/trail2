package net.pocketmap

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercise the shared Compose header while MapLibre remains a native view. */
class ComposeHeaderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun basemapSelectionAndLayerMenuPersistInExistingPreferences() {
        val prefs = rule.activity.getSharedPreferences("map", android.content.Context.MODE_PRIVATE)
        val originallyEnabled = prefs.getBoolean("overlay_contours", true)

        rule.onNodeWithTag("basemap-control").performClick()
        rule.onNodeWithTag("basemap-hybrid").performClick()
        rule.runOnIdle { assertEquals("hybrid", prefs.getString("basemap", "")) }

        rule.onNodeWithTag("trail-menu").performClick()
        rule.onNodeWithTag("menu-layers").performClick()
        rule.onNodeWithTag("map-layer-contours").performClick()
        rule.runOnIdle { assertEquals(!originallyEnabled, prefs.getBoolean("overlay_contours", originallyEnabled)) }

        // Toggling an overlay leaves its submenu open so users can edit several layers.
        rule.onNodeWithTag("map-layer-contours").assertExists()
        rule.onNodeWithTag("map-layer-contours").performClick()
        rule.runOnIdle { assertEquals(originallyEnabled, prefs.getBoolean("overlay_contours", !originallyEnabled)) }
    }

    @Test fun trailVisibilityMenuLabelUpdatesAfterAction() {
        rule.onNodeWithTag("trail-menu").performClick()
        rule.onNodeWithTag("menu-TOGGLE_TRAILS").assertExists().performClick()
        rule.onNodeWithTag("trail-menu").performClick()
        rule.onNodeWithText("Show trails").assertExists()
    }
}
