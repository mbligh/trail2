package net.pocketmap

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LocationReportSharingTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)

    @Test fun reportKeepsCapturedFixAndSharesEditedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    fun fix(lat: Double, lon: Double) = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = lat; longitude = lon; accuracy = 3f; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    field("current").set(activity, fix(40.6727254, -74.7490004))
                    MainActivity::class.java.getDeclaredMethod("shareCurrentLocation").apply { isAccessible = true }.invoke(activity)
                    val editor = field("reportEditor").get(activity) as EditText
                    val expected = "40.672725, -74.749000\nhttps://www.google.com/maps/@?api=1&map_action=map&center=40.672725%2C-74.749000&zoom=18&basemap=satellite\n\n"
                    assertEquals(expected, editor.text.toString())
                    editor.append("Broken gate 🐴\nPlease repair.")
                    field("current").set(activity, fix(41.0, -75.0))
                    val dialog = field("reportDialog").get(activity) as AlertDialog
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    @Suppress("DEPRECATION")
                    val send = captured.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    assertEquals(Intent.ACTION_SEND, send.action)
                    assertEquals("text/plain", send.type)
                    assertEquals(expected + "Broken gate 🐴\nPlease repair.", send.getStringExtra(Intent.EXTRA_TEXT))
                    assertEquals("Trail location report", send.getStringExtra(Intent.EXTRA_SUBJECT))
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
