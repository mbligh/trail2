package net.pocketmap

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory

/** Exercise the shipping share action and read its actual provider URI. */
class RecordingExportSharingTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS)

    @Test fun gpxAndKmlShareValidFilesWithPinNotesAndReadGrants() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(File(context.filesDir, "recordings").apply { mkdirs() }, "Shared-export-${System.nanoTime()}.csv")
        file.writeText("40.689,-74.777,123,80.0,3.0\n40.691,-74.774,30123,81.0,3.0\n40.693,-74.768,150123,,20.0\n40.695,-74.765,180123,90.0,20.0\n")
        RecordedPins.append(file, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 40.695; longitude = -74.765; time = 180_123; altitude = 90.0; accuracy = 3f
        }, "Gate <&> 🐴\nNote\u0001")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val captured = AtomicReference<Intent>()
                val monitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.action != Intent.ACTION_CHOOSER) return null
                        captured.set(Intent(intent))
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                instrumentation.addMonitor(monitor)
                try {
                    for (gpx in listOf(true, false)) {
                        captured.set(null)
                        scenario.onActivity { activity ->
                            MainActivity::class.java.getDeclaredMethod("shareRecording", File::class.java, Boolean::class.javaPrimitiveType!!)
                                .apply { isAccessible = true }.invoke(activity, file, gpx)
                        }
                        val chooser = captured.get()
                        assertNotNull("Share action did not open chooser", chooser)
                        @Suppress("DEPRECATION")
                        val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                        assertEquals(Intent.ACTION_SEND, send.action)
                        @Suppress("DEPRECATION")
                        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                        assertEquals("content", uri.scheme)
                        assertEquals(context.packageName + ".files", uri.authority)
                        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
                        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                        val mime = if (gpx) "application/gpx+xml" else "application/vnd.google-earth.kml+xml"
                        assertEquals(mime, send.type)
                        assertEquals(mime, context.contentResolver.getType(uri))
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                            .newDocumentBuilder().parse(bytes.inputStream())
                        val ns = if (gpx) "http://www.topografix.com/GPX/1/1" else "http://www.opengis.net/kml/2.2"
                        assertEquals(ns, document.documentElement.namespaceURI)
                        assertEquals(2, document.getElementsByTagNameNS(ns, if (gpx) "trkseg" else "LineString").length)
                        if (gpx) {
                            assertEquals(4, document.getElementsByTagNameNS(ns, "trkpt").length)
                            val point = document.getElementsByTagNameNS(ns, "trkpt").item(0) as Element
                            assertEquals("1970-01-01T00:00:00.123Z", point.getElementsByTagNameNS(ns, "time").item(0).textContent)
                            val pin = document.getElementsByTagNameNS(ns, "wpt").item(0) as Element
                            assertEquals("Gate <&> 🐴\nNote", pin.getElementsByTagNameNS(ns, "desc").item(0).textContent)
                            assertEquals(1, document.getElementsByTagNameNS("https://mbligh.org/trail", "ascentMeters").length)
                        } else {
                            assertEquals(1, document.getElementsByTagNameNS(ns, "Point").length)
                            assertEquals(5, document.getElementsByTagNameNS(ns, "Data").length)
                            assertTrue(bytes.toString(Charsets.UTF_8).contains("<styleUrl>#recorded-pin</styleUrl>"))
                        }
                    }
                } finally { instrumentation.removeMonitor(monitor) }
            }
        } finally {
            file.delete()
            File(file.parentFile, file.nameWithoutExtension + ".pins").delete()
            for (extension in listOf("gpx", "kml")) File(File(context.filesDir, "exports"), file.nameWithoutExtension + "." + extension).delete()
        }
    }
}
