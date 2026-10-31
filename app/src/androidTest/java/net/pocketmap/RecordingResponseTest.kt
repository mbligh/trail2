package net.pocketmap

import android.content.Intent
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RecordingResponseTest {
    @get:Rule val permissions = GrantPermissionRule.grant(android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS)

    @Test fun buttonAcknowledgesServiceStartAndStopWithPollingDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("map", 0)
        val previous = prefs.getString("recorded_track", null)
        var created: String? = null
        context.stopService(Intent(context, RecordingService::class.java))
        instrumentation.waitForIdleSync()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui = RealComposeUi(scenario)
                scenario.onActivity { activity ->
                    val handler = MainActivity::class.java.getDeclaredField("recordingHandler").apply { isAccessible = true }.get(activity) as Handler
                    handler.removeCallbacksAndMessages(null)
                }
                ui.description("Start recording")
                // Both taps run in one main-thread turn, before service acknowledgement.
                ui.node("record-control") { node ->
                    val click = node.config.getOrNull(SemanticsActions.OnClick)!!.action!!
                    assertTrue(click()); assertTrue(click())
                }
                ui.await("service confirms recording") { RecordingService.running }
                created = prefs.getString("recorded_track", null)
                ui.description("Stop recording")
                assertNotNull(created)
                assertTrue(File(File(context.filesDir, "recordings"), created!!).isFile)
                ui.node("record-control") { node ->
                    val click = node.config.getOrNull(SemanticsActions.OnClick)!!.action!!
                    assertTrue(click()); assertTrue(click())
                }
                ui.await("service confirms stop") { !RecordingService.running }
                ui.description("Start recording")
                fun savedMessage(view: View): Boolean {
                    if (view is TextView && view.text.toString().startsWith("Your track is saved on this phone.")) return true
                    return view is ViewGroup && (0 until view.childCount).any { savedMessage(view.getChildAt(it)) }
                }
                ui.await("saved summary appears after confirmed stop") {
                    WindowInspector.getGlobalWindowViews().any(::savedMessage)
                }
            }
        } finally {
            context.stopService(Intent(context, RecordingService::class.java))
            instrumentation.waitForIdleSync()
            prefs.edit().apply { if (previous == null) remove("recorded_track") else putString("recorded_track", previous) }.commit()
            created?.let { name ->
                val file = File(File(context.filesDir, "recordings"), name)
                file.delete(); File(file.parentFile, file.nameWithoutExtension + ".pins").delete()
            }
        }
    }
}
