package net.pocketmap

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compare the real shared UI against independent, unchanged native XML artwork. */
class ControlArtworkTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @Test fun sharedControlPathsMatchOriginalNativeArtwork() {
        data class Reference(val tag: String, val resource: Int, val dp: Int, val tint: Int? = null)
        val references = listOf(
            Reference("basemap-artwork", R.drawable.basemap_menu, 40),
            Reference("report-artwork", R.drawable.ic_report_flag, 28),
            Reference("pin-artwork", R.drawable.ic_track_pin, 24),
            Reference("recenter-artwork", R.drawable.ic_recenter, 28, Color.rgb(21, 101, 192))
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ui = RealComposeUi(scenario)
            ui.click("recenter-control")
            val expected = references.map { reference ->
                var bounds = Rect.Zero
                ui.node(reference.tag) { bounds = it.boundsInWindow }
                val width = bounds.width.roundToInt(); val height = bounds.height.roundToInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                scenario.onActivity { activity ->
                    assertEquals(reference.dp * activity.resources.displayMetrics.density, bounds.width, 1f)
                    assertEquals(reference.dp * activity.resources.displayMetrics.density, bounds.height, 1f)
                    val drawable = activity.getDrawable(reference.resource)!!.mutate()
                    reference.tint?.let { drawable.setTint(it) }
                    val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                    drawable.setBounds(0, 0, width, height); drawable.draw(canvas)
                }
                Triple(reference.tag, bounds, bitmap)
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var failures = listOf<String>()
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                failures = expected.mapNotNull { (tag, bounds, bitmap) ->
                    var total = 0L; var changed = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val actual = screenshot.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y)
                        val original = bitmap.getPixel(x, y)
                        val delta = listOf(abs(Color.red(actual) - Color.red(original)),
                            abs(Color.green(actual) - Color.green(original)), abs(Color.blue(actual) - Color.blue(original)))
                        total += delta.sum()
                        if (delta.max() > 32) changed++
                    }
                    val count = bitmap.width * bitmap.height
                    val error = total.toDouble() / (count * 3)
                    if (error > 8 || changed.toDouble() / count > 0.12) "$tag: mean=$error, changed=$changed/$count" else null
                }
                screenshot.recycle()
                if (failures.isEmpty()) break
                Thread.sleep(100)
            }
            for (command in listOf("mkdir -p /data/local/tmp/trail-insets",
                "screencap -p /data/local/tmp/trail-insets/shared-control-artwork.png")) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
            expected.forEach { it.third.recycle() }
            assertTrue("Shared artwork differs from native reference: $failures", failures.isEmpty())
        }
    }
}
