package net.pocketmap

import android.Manifest
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real window bounds, including navigation mode, rotation and display-cutout overlays. */
class ScreenInsetsTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun headerMapAndCornerControlsStayInsideSystemSafeArea() {
        lateinit var safe: Rect
        var width = 0
        var height = 0
        rule.waitUntil(timeoutMillis = 10_000) {
            var delivered = false
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                if (bars != null && bars.top + bars.bottom + bars.left + bars.right > 0) {
                    val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
                    val compose = content.getChildAt(0)
                    val decorOrigin = IntArray(2); decor.getLocationOnScreen(decorOrigin)
                    val composeOrigin = IntArray(2); compose.getLocationOnScreen(composeOrigin)
                    safe = Rect(decorOrigin[0] + bars.left - composeOrigin[0],
                        decorOrigin[1] + bars.top - composeOrigin[1],
                        decorOrigin[0] + decor.width - bars.right - composeOrigin[0],
                        decorOrigin[1] + decor.height - bars.bottom - composeOrigin[1])
                    width = decor.width; height = decor.height
                    delivered = true
                }
            }
            delivered
        }
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = "/data/local/tmp/trail-insets/insets-${width}x${height}-${safe.top}-${safe.bottom}.png"
        // UiAutomation executes a program directly, not a shell command chain.
        for (command in listOf("mkdir -p /data/local/tmp/trail-insets", "screencap -p $screenshot")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        val screenshotSize = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("stat -c %s $screenshot"))
            .bufferedReader().use { it.readText().trim().toLongOrNull() }
        assertTrue("Missing inset regression screenshot", screenshotSize != null && screenshotSize > 0)
        val tags = listOf("basemap-control", "map-credits", "trail-menu", "pin-control",
            "recenter-control", "report-control", "record-control", "map-surface")
        for (tag in tags) {
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag overlaps left system UI: $bounds, safe=$safe", bounds.left >= safe.left - 1f)
            assertTrue("$tag overlaps clock/cutout: $bounds, safe=$safe", bounds.top >= safe.top - 1f)
            assertTrue("$tag overlaps right system UI: $bounds, safe=$safe", bounds.right <= safe.right + 1f)
            assertTrue("$tag overlaps navigation bar: $bounds, safe=$safe", bounds.bottom <= safe.bottom + 1f)
        }
        // Insets must be applied exactly once, without adding an extra empty bar.
        val header = rule.onNodeWithTag("basemap-control").fetchSemanticsNode().boundsInRoot
        val map = rule.onNodeWithTag("map-surface").fetchSemanticsNode().boundsInRoot
        assertEquals("Header has doubled/missing top inset", safe.top.toFloat(), header.top, 1f)
        assertEquals("Map has doubled/missing bottom inset", safe.bottom.toFloat(), map.bottom, 1f)

    }
}
