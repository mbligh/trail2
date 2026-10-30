package net.pocketmap.composeprobe

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.File

internal object RenderAssertions {
    fun assertContours(filename: String) =
        assertMapPixels(filename, "visible magenta contour lines", 40) { color ->
            val red = (color shr 16) and 255
            val green = (color shr 8) and 255
            val blue = color and 255
            red > 180 && blue > 130 && red - green > 80 && blue - green > 60
        }

    fun awaitResources(description: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!ready() && System.currentTimeMillis() < deadline) Thread.sleep(250)
        assertTrue("Renderer did not load $description", ready())
    }

    fun assertMapVariety(filename: String) =
        assertMapPixels(filename, "rendered map colors", 16, distinct = true) { true }

    /** Check the central viewport, excluding all Compose text/system chrome. */
    private fun assertMapPixels(
        filename: String,
        description: String,
        minimum: Int,
        distinct: Boolean = false,
        matches: (Int) -> Boolean
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        var count = 0
        for (attempt in 1..30) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                try {
                    val colors = HashSet<Int>()
                    count = 0
                    for (y in bitmap.height / 4 until bitmap.height * 3 / 4 step 2) {
                        for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 2) {
                            val color = bitmap.getPixel(x, y) and 0x00ffffff
                            if (matches(color)) {
                                count++
                                if (distinct) colors.add(color)
                            }
                        }
                    }
                    if (distinct) count = colors.size
                    val output = File(instrumentation.targetContext.getExternalFilesDir(null), filename)
                    output.outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    // Gradle can uninstall the app after connected tests. Preserve
                    // assertion frames in shell-owned storage before that happens.
                    val command = "mkdir -p /data/local/tmp/compose-probe-smoke && cp '" +
                        output.absolutePath + "' '/data/local/tmp/compose-probe-smoke/$filename'"
                    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
                    val diagnostic = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .bufferedReader().use { it.readText() }
                    assertTrue("Cannot preserve $filename: $diagnostic", diagnostic.isBlank())
                } finally { bitmap.recycle() }
                if (count >= minimum) return
            }
            Thread.sleep(500)
        }
        assertTrue("Map lacks $description ($count, expected >= $minimum); see $filename", count >= minimum)
    }
}
