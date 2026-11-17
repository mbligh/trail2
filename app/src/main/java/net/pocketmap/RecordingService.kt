package net.pocketmap

import android.Manifest
import android.app.*
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import android.widget.Toast
import java.io.File

class RecordingService : Service(), LocationListener {
    companion object {
        @Volatile var running = false
        @Volatile var recordingStartedElapsedRealtime = 0L
        @Volatile var latestGpsFix: Location? = null
        const val STATE_CHANGED = "net.pocketmap.RECORDING_STATE_CHANGED"
        const val STOP = "net.pocketmap.STOP_RECORDING"
        const val REFRESH_BLUETOOTH = "net.pocketmap.REFRESH_BLUETOOTH"
        const val REFRESH_WIFI = "net.pocketmap.REFRESH_WIFI"
        private const val TIMEOUT = "net.pocketmap.RECORDING_TIMEOUT"
        private const val MAX_RECORDING_MILLIS = 4 * 60 * 60 * 1000L
    }
    private lateinit var manager: LocationManager
    private var track: File? = null
    private var lastSampleWindow = Long.MIN_VALUE
    private var lastSavedFixElapsedRealtimeNanos = Long.MIN_VALUE
    private var carReceiverRegistered = false
    private val carReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            }
            val address = try { device?.address } catch (_: SecurityException) { null }
            onCarConnected(address)
        }
    }
    private var wifiCallback: WifiStopCallback? = null
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
        if (intent?.action == REFRESH_BLUETOOTH) {
            if (running) refreshBluetoothListener() else stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == REFRESH_WIFI) {
            if (running) refreshWifiListener() else stopSelf()
            return START_NOT_STICKY
        }
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
            track = RecordingFileNames.createRecording(directory)
            getSharedPreferences("map", MODE_PRIVATE).edit().putString("recorded_track", track!!.name).apply()
            // elapsedRealtime includes screen-off/deep-sleep time and ignores clock changes.
            recordingStartedElapsedRealtime = SystemClock.elapsedRealtime()
            deadlineElapsedRealtime = recordingStartedElapsedRealtime + MAX_RECORDING_MILLIS
            handler.postDelayed(timeout, MAX_RECORDING_MILLIS)
            // Wake for cleanup even if GPS has no signal and the app is in the background.
            // Android may defer this inexact alarm; the GPS callback enforces the cutoff too.
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedRealtime, timeoutIntent)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, this)
            running = true
            refreshBluetoothListener()
            refreshWifiListener()
            publishState()
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
            appendSample(sample)
            lastSampleWindow = fix.elapsedRealtimeNanos
        } catch (_: Exception) {
            Toast.makeText(this, "Recording stopped: could not save GPS point", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun appendSample(sample: Location) {
        track?.appendText(listOf(
            sample.latitude, sample.longitude, sample.time,
            if (sample.hasAltitude()) sample.altitude else "",
            if (sample.hasAccuracy()) sample.accuracy else ""
        ).joinToString(",") + "\n")
        lastSavedFixElapsedRealtimeNanos = sample.elapsedRealtimeNanos
    }

    // Listen only during a recording with an explicitly selected paired device.
    // Android Auto commonly connects through the same car Bluetooth connection.
    private fun refreshBluetoothListener() {
        val selected = getSharedPreferences("map", MODE_PRIVATE).getString(CarBluetoothStop.PREF_KEY, null)
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (selected.isNullOrBlank() || !permitted) {
            if (carReceiverRegistered) {
                unregisterReceiver(carReceiver)
                carReceiverRegistered = false
            }
            return
        }
        if (!carReceiverRegistered) {
            try {
                // Bluetooth broadcasts can originate from the privileged Bluetooth
                // process, not our app's UID; NOT_EXPORTED would miss those.
                ContextCompat.registerReceiver(this, carReceiver,
                    IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), ContextCompat.RECEIVER_EXPORTED)
                carReceiverRegistered = true
            } catch (_: SecurityException) { /* Permission revoked: continue GPS recording. */ }
        }
    }

    private inner class WifiStopCallback : ConnectivityManager.NetworkCallback {
        private val gate: WifiConnectionGate<Network>

        constructor(gate: WifiConnectionGate<Network>) : super() { this.gate = gate }
        @android.annotation.TargetApi(Build.VERSION_CODES.S)
        constructor(gate: WifiConnectionGate<Network>, flags: Int) : super(flags) { this.gate = gate }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (wifiCallback !== this || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
            // Android 12+ redacts Wi-Fi identity unless FLAG_INCLUDE_LOCATION_INFO
            // was specified on the callback. On Android 8-9 use WifiManager.
            val observed = if (Build.VERSION.SDK_INT >= 29) {
                (capabilities.transportInfo as? WifiInfo)?.ssid
            } else {
                @Suppress("DEPRECATION")
                try { getSystemService(WifiManager::class.java).connectionInfo?.ssid }
                catch (_: SecurityException) { null }
            }
            if (gate.shouldStop(network,
                    getSharedPreferences("map", MODE_PRIVATE).getString(WifiAutoStop.PREF_KEY, null),
                    observed)) onWifiConnected(observed)
        }

        override fun onLost(network: Network) {
            gate.onLost(network)
        }
    }

    private fun refreshWifiListener() {
        val cm = getSystemService(ConnectivityManager::class.java)
        wifiCallback?.let { cm.unregisterNetworkCallback(it) }
        wifiCallback = null
        val selected = getSharedPreferences("map", MODE_PRIVATE).getString(WifiAutoStop.PREF_KEY, null)
        if (selected.isNullOrBlank() ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        try {
            // Snapshot existing Wi-Fi connections *before* registration. Android
            // immediately sends callbacks for existing networks; these must not
            // be interpreted as a new arrival.
            val existing = cm.allNetworks.filter { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            val gate = WifiConnectionGate(existing)
            val callback = if (Build.VERSION.SDK_INT >= 31)
                WifiStopCallback(gate, ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
            else WifiStopCallback(gate)
            wifiCallback = callback
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback, handler)
        } catch (_: SecurityException) {
            wifiCallback = null // No GPS/location permission: do not disturb the recording.
        } catch (_: IllegalArgumentException) {
            wifiCallback = null // Invalid network callback / unavailable connectivity.
        }
    }

    // Separate stop decision from Android callback so it can be tested.
    internal fun onWifiConnected(ssid: String?) {
        if (!running || !WifiAutoStop.matches(
                getSharedPreferences("map", MODE_PRIVATE).getString(WifiAutoStop.PREF_KEY, null), ssid)) return
        stopForConnection("Wi-Fi connected")
    }

    // Separate from the receiver so the stop decision can be regression-tested.
    internal fun onCarConnected(address: String?) {
        if (!running || !CarBluetoothStop.matches(
                getSharedPreferences("map", MODE_PRIVATE).getString(CarBluetoothStop.PREF_KEY, null), address)) return
        stopForConnection("car Bluetooth connected")
    }

    private fun stopForConnection(reason: String) {
        // Keep the last recent GPS fix even when the 30-second sample isn't due.
        val sample = chooseSample(recentFixes)
        if (sample != null && sample.elapsedRealtimeNanos > lastSavedFixElapsedRealtimeNanos &&
            SystemClock.elapsedRealtimeNanos() - sample.elapsedRealtimeNanos in 0..15_000_000_000L) {
            try { appendSample(sample) } catch (_: Exception) { /* Keep already saved points. */ }
        }
        running = false
        latestGpsFix = null
        Toast.makeText(this, "Recording saved: $reason", Toast.LENGTH_LONG).show()
        stopSelf()
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
    private fun publishState() {
        sendBroadcast(Intent(STATE_CHANGED).setPackage(packageName))
    }
    override fun onDestroy() {
        running = false
        recordingStartedElapsedRealtime = 0L
        latestGpsFix = null
        recentFixes.clear()
        if (carReceiverRegistered) {
            unregisterReceiver(carReceiver)
            carReceiverRegistered = false
        }
        wifiCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wifiCallback = null
        handler.removeCallbacks(timeout)
        getSystemService(AlarmManager::class.java).cancel(timeoutIntent)
        manager.removeUpdates(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishState()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
