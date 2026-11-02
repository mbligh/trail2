package net.pocketmap

import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LegendTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    )
    @Test fun sharedLegendKeepsOriginalColorsAndDismissesWithDoneAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui = RealComposeUi(scenario)
            ui.click("trail-menu"); ui.click("menu-LEGEND")
            listOf("Blocked", "Unknown", "TTA trail", "Public trail").forEach(ui::text)
            val bounds = (0..3).map { ui.screenBounds("legend-swatch-$it") }
            // Frozen native legend reference; test actual rendered swatches.
            val colors = listOf(Color.rgb(219,68,54), Color.rgb(248,151,27), Color.rgb(244,235,55), Color.rgb(0,157,87))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val deadline = SystemClock.elapsedRealtime()+10_000
            var matches = false
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                matches = bounds.zip(colors).all { (rect, expected) ->
                    val actual = screenshot.getPixel(rect.center.x.roundToInt(),rect.center.y.roundToInt())
                    abs(Color.red(actual)-Color.red(expected)) <= 3 && abs(Color.green(actual)-Color.green(expected)) <= 3 && abs(Color.blue(actual)-Color.blue(expected)) <= 3
                }
                screenshot.recycle()
                if (matches) break
                Thread.sleep(100)
            }
            assertTrue("Shared legend colors differ from native reference", matches)
            for (command in listOf("mkdir -p /data/local/tmp/trail-insets", "screencap -p /data/local/tmp/trail-insets/shared-legend.png")) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
            ui.click("legend-done"); ui.absent("trail-legend")
            ui.node("record-control")
            ui.click("trail-menu"); ui.click("menu-LEGEND"); ui.text("Public trail")
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
            ui.absent("trail-legend"); ui.node("record-control")
        }
    }
}
