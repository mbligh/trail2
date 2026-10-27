package net.pocketmap

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingServiceTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<RecordingService>
    private var destroyed = false
    private val service get() = controller.get()
    private val fourHours = Duration.ofHours(4)
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private fun track(): File = File(File(app.filesDir, "recordings"), app.getSharedPreferences("map", 0).getString("recorded_track", null)!!)
    private fun fix() = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 40.69; longitude = -74.78; accuracy = 4f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        time = System.currentTimeMillis()
    }

    @Before fun start() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        RecordingService.running = false
        controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        assertTrue(RecordingService.running)
    }

    @After fun destroy() { if (!destroyed) controller.destroy() }

    @Test fun gpsStopsAtFourHoursFromStartAndKeepsSavedPoints() {
        val file = track()
        // Time spent waiting for the first GPS fix counts toward the limit too.
        ShadowSystemClock.advanceBy(fourHours.minusMillis(1))
        service.onLocationChanged(fix())
        assertEquals(1, RecordedTrack.read(file).size)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        service.onLocationChanged(fix())
        assertFalse(RecordingService.running)
        assertNull(RecordingService.latestGpsFix)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, RecordedTrack.read(file).size)
    }

    @Test fun timerStopsWithoutGpsAndCleanupCancelsWakeAlarm() {
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertTrue(alarm.allowWhileIdle)
        assertEquals(SystemClock.elapsedRealtime() + fourHours.toMillis(), alarm.triggerAtTime)
        shadowOf(Looper.getMainLooper()).idleFor(fourHours)
        assertFalse(RecordingService.running)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(RecordedTrack.read(track()).isEmpty())
        // The real framework destroys the service after stopSelf().
        controller.destroy()
        destroyed = true
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun elapsedClockAlarmStopsWithoutUiOrGpsEvenIfHandlerHasNotRun() {
        val timeoutIntent = shadowOf(alarms.scheduledAlarms.single().operation).savedIntent
        val uptime = SystemClock.uptimeMillis()
        ShadowSystemClock.simulateDeepSleep(fourHours)
        assertEquals(uptime, SystemClock.uptimeMillis())
        // Deliver the wake alarm while the UI and main-looper timer are idle.
        service.onStartCommand(timeoutIntent, 0, 2)
        assertFalse(RecordingService.running)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun repeatedStartAndEarlyAlarmDoNotExtendOrStopRecording() {
        val file = track()
        val alarm = alarms.scheduledAlarms.single()
        val timeoutIntent = shadowOf(alarm.operation).savedIntent
        ShadowSystemClock.advanceBy(Duration.ofHours(3))
        service.onStartCommand(Intent(app, RecordingService::class.java), 0, 2)
        service.onStartCommand(timeoutIntent, 0, 3)
        assertTrue(RecordingService.running)
        assertEquals(file, track())
        assertEquals(alarm.triggerAtTime, alarms.scheduledAlarms.single().triggerAtTime)
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        service.onLocationChanged(fix())
        assertFalse(RecordingService.running)
    }

    @Test fun stoppingCancelsOldTimeoutAndNextSessionGetsFourHours() {
        service.onLocationChanged(fix())
        val saved = track()
        val oldAlarm = shadowOf(alarms.scheduledAlarms.single().operation).savedIntent
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        controller.destroy()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 2)
        assertEquals(SystemClock.elapsedRealtime() + fourHours.toMillis(), alarms.scheduledAlarms.single().triggerAtTime)
        service.onStartCommand(oldAlarm, 0, 3)
        assertTrue(RecordingService.running)
        assertEquals(1, RecordedTrack.read(saved).size)
        assertNotEquals(saved, track())
    }
}
