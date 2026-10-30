package net.pocketmap.composeprobe

import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.pocketmap.composeprobe.RenderAssertions.awaitResources
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

@RunWith(AndroidJUnit4::class)
class CameraParityTest {
    @Test fun cameraSurvivesStyleChangesRecreationAndOneFingerDragPausesFollowing() {
        ActivityScenario.launch(ProbeActivity::class.java).use { scenario ->
            awaitResources("camera viewport") { var ready = false; scenario.onActivity { ready = it.mapReady }; ready }
            val desired = CameraPosition(center = Position(latitude = 40.697, longitude = -74.778), zoom = 13.5)
            scenario.onActivity { it.mapState.setCameraPosition(desired) }
            fun assertCamera(expected: CameraPosition) {
                awaitResources("expected camera") {
                    var ready = false
                    scenario.onActivity {
                        val current = it.mapState.cameraPosition
                        ready = kotlin.math.abs(current.center.latitude - expected.center.latitude) < 0.000001 &&
                            kotlin.math.abs(current.center.longitude - expected.center.longitude) < 0.000001 &&
                            kotlin.math.abs(current.zoom - expected.zoom) < 0.001
                    }
                    ready
                }
            }
            assertCamera(desired)
            scenario.onActivity { it.showStyle("earth") }
            assertCamera(desired)
            scenario.onActivity { it.showStyle("bright") }
            scenario.recreate()
            assertCamera(desired)
            awaitResources("camera viewport after recreation") { var ready = false; scenario.onActivity { ready = it.mapReady }; ready }

            // Dispatch real one-finger input through the Activity/Compose hit-test.
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float) {
                scenario.onActivity {
                    val root = it.window.decorView
                    val properties = MotionEvent.PointerProperties().apply {
                        id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                    val coordinates = MotionEvent.PointerCoords().apply {
                        this.x = x; y = root.height * 0.5f; pressure = 1f; size = 1f
                    }
                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                        arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_TOUCHSCREEN, 0).also { event ->
                        it.dispatchTouchEvent(event); event.recycle()
                    }
                }
            }
            var startX = 0f
            scenario.onActivity { startX = it.window.decorView.width * 0.4f }
            touch(MotionEvent.ACTION_DOWN, startX)
            for (step in 1..12) { Thread.sleep(20); touch(MotionEvent.ACTION_MOVE, startX + step * 12) }
            touch(MotionEvent.ACTION_UP, startX + 144)
            awaitResources("drag to pause following") { var paused = false; scenario.onActivity { paused = !it.following }; paused }
            lateinit var dragged: CameraPosition
            scenario.onActivity { it.mapState.stopCameraMovement() }
            awaitResources("drag camera to settle") { var still = false; scenario.onActivity { still = !it.mapState.isCameraMoving }; still }
            scenario.onActivity { dragged = it.mapState.cameraPosition }
            assertTrue("Drag did not move the map", kotlin.math.abs(dragged.center.longitude - desired.center.longitude) > 0.00001)
            val fix = Position(latitude = 40.693, longitude = -74.769)
            scenario.onActivity { it.updateFix(fix) }
            assertCamera(dragged)
            scenario.recreate()
            scenario.onActivity { assertFalse("Following state was lost", it.following); it.recenter() }
            awaitResources("recenter to latest fix") {
                var ready = false
                scenario.onActivity { ready = it.following && kotlin.math.abs(it.mapState.cameraPosition.center.latitude - fix.latitude) < 0.000001 }
                ready
            }
            awaitResources("rendered viewport before zoom gesture") { var ready = false; scenario.onActivity { ready = it.mapReady }; ready }
            scenario.onActivity {
                assertEquals(0.0, it.mapState.cameraPosition.bearing, 0.0001)
                assertEquals(0.0, it.mapState.cameraPosition.pitch, 0.0001)
                it.mapState.scaleBy(2.0, DpOffset(160.dp, 220.dp))
            }
            awaitResources("pinch-style zoom input") { var zoomed = false; scenario.onActivity { zoomed = it.mapState.cameraPosition.zoom > desired.zoom + 0.5 }; zoomed }
            scenario.onActivity { assertEquals(0.0, it.mapState.cameraPosition.bearing, 0.0001); assertEquals(0.0, it.mapState.cameraPosition.pitch, 0.0001) }
        }
    }
}
