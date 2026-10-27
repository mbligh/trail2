package net.pocketmap

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import android.widget.Toast
import java.io.File
import java.time.Instant

class RecordingService : Service(), LocationListener {
    companion object {
        @Volatile var running = false
        @Volatile var latestGpsFix: Location? = null
        const val STOP = "net.pocketmap.STOP_RECORDING"
        private const val TIMEOUT = "net.pocketmap.RECORDING_TIMEOUT"
        private const val MAX_RECORDING_MILLIS = 4 * 60 * 60 * 1000L
    }
    private lateinit var manager: LocationManager
    private var track: File? = null
    private var lastSampleWindow = Long.MIN_VALUE
    private val recentFixes = ArrayDeque<Location>()
    private val handler = Handler(Looper.getMainLooper())
    private var deadlineElapsedRealtime = Long.MAX_VALUE
    private val timeout = Runnable { stopIfExpired() }
    private val timeoutIntent by lazy {
        PendingIntent.getService(this, 2, Intent(this, RecordingService::class.java).setAction(TIMEOUT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("recording", "Trail recording", NotificationManager.IMPORTANCE_LOW))
    }
    @Suppress("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        // A stale alarm must never start a recording or stop a newer session.
        if (intent?.action == TIMEOUT) {
            if (!running) stopSelf() else stopIfExpired()
            return START_NOT_STICKY
        }
        if (stopIfExpired()) return START_NOT_STICKY
        if (running) return START_NOT_STICKY
        latestGpsFix = null
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Enable GPS and precise location to record", Toast.LENGTH_LONG).show()
            stopSelf(); return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "recording").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trail is recording").setContentText("Trail point saved every 30s • stops after 4h").setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, notification)
            val directory = File(filesDir, "recordings").apply { mkdirs() }
            track = File(directory, "Trail-${Instant.now().toString().replace(':', '-')}.csv").apply { createNewFile() }
            getSharedPreferences("map", MODE_PRIVATE).edit().putString("recorded_track", track!!.name).apply()
            running = true
            // elapsedRealtime includes screen-off/deep-sleep time and ignores clock changes.
            deadlineElapsedRealtime = SystemClock.elapsedRealtime() + MAX_RECORDING_MILLIS
            handler.postDelayed(timeout, MAX_RECORDING_MILLIS)
            // Wake for cleanup even if GPS has no signal and the app is in the background.
            // Android may defer this inexact alarm; the GPS callback enforces the cutoff too.
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedRealtime, timeoutIntent)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, this)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not start recording — check location permission", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onLocationChanged(location: Location) {
        if (!running || stopIfExpired()) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - location.elapsedRealtimeNanos > 45_000_000_000L) return

        val fix = Location(location)
        latestGpsFix = Location(fix)
        recentFixes.addLast(fix)
        val oldestAllowed = fix.elapsedRealtimeNanos - 10_000_000_000L
        while (recentFixes.isNotEmpty() && recentFixes.first().elapsedRealtimeNanos < oldestAllowed) {
            recentFixes.removeFirst()
        }

        // Preserve the existing behavior of saving the first fresh fix immediately.
        // Thereafter, every ~30 seconds choose the newest fix whose reported accuracy
        // is still reasonably close to the best fix seen in the previous 10 seconds.
        if (lastSampleWindow != Long.MIN_VALUE &&
            fix.elapsedRealtimeNanos - lastSampleWindow < 30_000_000_000L) return

        val sample = chooseSample(recentFixes) ?: return
        try {
            track?.appendText("${sample.latitude},${sample.longitude},${sample.time},${if (sample.hasAltitude()) sample.altitude else ""},${if (sample.hasAccuracy()) sample.accuracy else ""}\n")
            lastSampleWindow = fix.elapsedRealtimeNanos
        } catch (_: Exception) {
            Toast.makeText(this, "Recording stopped: could not save GPS point", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun stopIfExpired(): Boolean {
        if (!running || SystemClock.elapsedRealtime() < deadlineElapsedRealtime) return false
        running = false
        latestGpsFix = null
        Toast.makeText(this, "Recording stopped after 4 hours. Your track is saved.", Toast.LENGTH_LONG).show()
        stopSelf()
        return true
    }

    private fun chooseSample(fixes: Collection<Location>): Location? {
        if (fixes.isEmpty()) return null
        val withAccuracy = fixes.filter { it.hasAccuracy() }
        if (withAccuracy.isEmpty()) return fixes.maxByOrNull { it.elapsedRealtimeNanos }

        val bestAccuracy = withAccuracy.minOf { it.accuracy }
        val acceptableAccuracy = maxOf(bestAccuracy * 2f, bestAccuracy + 5f)
        return withAccuracy
            .filter { it.accuracy <= acceptableAccuracy }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }
    override fun onProviderDisabled(provider: String) { }
    override fun onProviderEnabled(provider: String) { }
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onDestroy() {
        running = false
        latestGpsFix = null
        recentFixes.clear()
        handler.removeCallbacks(timeout)
        getSystemService(AlarmManager::class.java).cancel(timeoutIntent)
        manager.removeUpdates(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
