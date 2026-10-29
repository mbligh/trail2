package net.pocketmap

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import net.pocketmap.shared.ui.TrailScreen
import net.pocketmap.shared.ui.MapChoice
import net.pocketmap.shared.ui.MapLayerChoice
import net.pocketmap.shared.ui.TrailMenuAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import org.maplibre.android.geometry.LatLng
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.io.File

class MainActivity : ComponentActivity(), LocationListener {
    private lateinit var basemaps: List<Basemap>
    private lateinit var selectedBasemap: Basemap
    private val selectedBasemapName = mutableStateOf("bright")
    private val zoomLabel = mutableStateOf("")
    private val layerSelections = mutableStateOf<Set<String>>(emptySet())
    private val trailsVisibleState = mutableStateOf(true)
    private val statusText = mutableStateOf("Waiting for location…")
    private val trailStatusText = mutableStateOf<String?>("Loading trails…")
    private val recordingStatusText = mutableStateOf<String?>(null)
    private lateinit var map: TrailMap
    private lateinit var mapConfig: MapConfiguration
    private val enabledOverlays = mutableSetOf<String>()
    private lateinit var recenter: android.widget.ImageButton
    private lateinit var manager: LocationManager
    private val trailExecutor = Executors.newSingleThreadExecutor()
    private lateinit var trailRepository: TrailRepository
    private var trailDocument: TrailKml.Document? = null
    private var refreshingTrails = false
    private var destroyed = false
    private var trailsVisible = true
    private lateinit var recordButton: android.widget.ImageButton
    private lateinit var pinGesture: PinHoldGesture
    private var pinDraft: Bundle? = null
    private var pinEditor: android.widget.EditText? = null
    private var pinDialog: android.app.AlertDialog? = null
    private var lastRecordedPoint: RecordedTrack.Point? = null
    private val recordingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var displayedTrack = ""
    private var displayedLength = -1L
    private var recordedPointCount = 0
    private val recordingTick = object : Runnable {
        override fun run() { updateRecording(); if (facingHeading != null) redrawMap(); if (active) recordingHandler.postDelayed(this, 1000) }
    }
    private var following = true
    private var active = false
    private var current: Location? = null
    private val recentLocations = ArrayDeque<Location>()
    private val locationWindowNanos = 15_000_000_000L
    private lateinit var phoneHeading: PhoneHeading
    private var facingHeading: Float? = null
    private var headingTime = 0L
    private var reportText: String? = null
    private var reportAccuracy = 0
    private var reportPhoto: String? = null
    private var cameraPhoto: String? = null
    private var reportEditor: android.widget.EditText? = null
    private var reportDialog: android.app.AlertDialog? = null
    private val prefs by lazy { getSharedPreferences("map", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val root = ComposeView(this)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        mapConfig = MapConfiguration(this)
        basemaps = mapConfig.basemaps
        val oldChoice = prefs.getString("basemap", "bright")
        val choice = if (!prefs.getBoolean("maplibre_migrated", false) && oldChoice == "openstreetmap") "bright" else oldChoice
        selectedBasemap = basemaps.find { it.name == choice } ?: basemaps.first()
        selectedBasemapName.value = selectedBasemap.name
        prefs.edit().putBoolean("maplibre_migrated", true).putString("basemap", selectedBasemap.name).apply()
        mapConfig.overlays.filter { prefs.getBoolean("overlay_${it.id}", it.defaultVisible) }.forEach { enabledOverlays.add(it.id) }
        layerSelections.value = enabledOverlays.toSet()
        map = TrailMap(this, mapConfig, selectedBasemap,
            LatLng(prefs.getFloat("lat", 40.692f).toDouble(), prefs.getFloat("lon", -74.771f).toDouble()),
            prefs.getFloat("zoom", 17f).toDouble(), savedInstanceState?.getBundle("maplibre")).apply {
            onDrag = { following = false; refreshStatus() }
            onCameraChanged = { showAttribution() }
            onError = { message -> android.widget.Toast.makeText(this@MainActivity, "Map: $message", android.widget.Toast.LENGTH_LONG).show() }
            setOverlays(enabledOverlays.toSet())
        }
        val mapFrame = android.widget.FrameLayout(this)
        mapFrame.addView(map, android.widget.FrameLayout.LayoutParams(-1, -1))
        recordButton = android.widget.ImageButton(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setOnClickListener { toggleRecording() }
        }
        mapFrame.addView(recordButton, android.widget.FrameLayout.LayoutParams(dp(37), dp(37), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        val flagButton = android.widget.ImageButton(this).apply {
            contentDescription = "Share current location"
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_report_flag)
            setOnClickListener { shareCurrentLocation() }
        }
        mapFrame.addView(flagButton, android.widget.FrameLayout.LayoutParams(dp(37), dp(37), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        val pinButton = android.widget.ImageButton(this).apply {
            contentDescription = "Add pin to recorded track; hold for one second to add text"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = dp(6).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_track_pin)
            setOnClickListener { addRecordingPin() }
        }
        pinGesture = PinHoldGesture(pinButton) { addRecordingPin(withText = true) }
        mapFrame.addView(pinButton, android.widget.FrameLayout.LayoutParams(dp(37), dp(37), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        recenter = android.widget.ImageButton(this).apply {
            contentDescription = "Recenter and follow GPS"
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_recenter)
            setOnClickListener {
                following = true
                if (!hasPermission()) requestLocationPermission()
                else if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                current?.let { map.controller.setCenter(LatLng(it.latitude, it.longitude)) }
                refreshStatus()
            }
        }
        mapFrame.addView(recenter, android.widget.FrameLayout.LayoutParams(dp(37), dp(37), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        // Preserve a single native instance of MapLibre and each control across recompositions.
        root.setContent {
            TrailScreen(
                status = statusText.value,
                trailStatus = trailStatusText.value,
                recordingStatus = recordingStatusText.value,
                basemaps = basemaps.map { MapChoice(it.name, it.label) },
                selectedBasemap = selectedBasemapName.value,
                onBasemapSelected = ::selectBasemap,
                basemapIcon = {
                    Image(
                        painter = painterResource(R.drawable.basemap_menu),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).testTag("basemap-artwork")
                    )
                },
                zoomLabel = zoomLabel.value,
                onMapCredits = ::showMapCredits,
                trailsVisible = trailsVisibleState.value,
                mapLayers = mapConfig.overlays.map {
                    MapLayerChoice(it.id, it.name, it.id in layerSelections.value)
                },
                onLayerToggled = ::toggleMapLayer,
                onMenuAction = ::performMenuAction,
                map = { AndroidView(factory = { mapFrame }, modifier = Modifier.fillMaxSize()) }
            )
        }
        showAttribution()
        setContentView(root)
        @Suppress("DEPRECATION")
        val rotation = { windowManager.defaultDisplay.rotation }
        phoneHeading = PhoneHeading(this, rotation, { current }) { heading ->
            facingHeading = heading
            headingTime = android.os.SystemClock.elapsedRealtime()
            redrawMap()
        }
        trailRepository = TrailRepository(filesDir)
        refreshTrails()
        if (!hasPermission()) requestLocationPermission()
        savedInstanceState?.let {
            reportText = it.getString("reportText")
            reportAccuracy = it.getInt("reportAccuracy")
            reportPhoto = it.getString("reportPhoto")
            cameraPhoto = it.getString("cameraPhoto")
        }
        if (reportText != null && cameraPhoto == null) showReport()
        savedInstanceState?.getBundle("pinDraft")?.let { showPinText(it) }
    }

    private fun showLegend() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val rows = listOf(
            Color.rgb(219, 68, 54) to "Blocked",
            Color.rgb(248, 151, 27) to "Unknown",
            Color.rgb(244, 235, 55) to "TTA trail",
            Color.rgb(0, 157, 87) to "Public trail"
        )
        for ((color, label) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(android.view.View(this).apply {
                setBackgroundColor(color)
            }, LinearLayout.LayoutParams(dp(48), dp(5)))
            row.addView(TextView(this).apply {
                text = label
                textSize = 16f
            }, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(12)
            })
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Legend")
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showAttribution() {
        zoomLabel.value = formatZoom(map.zoomLevelDouble)
    }

    private fun selectBasemap(id: String) {
        val choice = basemaps.find { it.name == id } ?: return
        if (choice.name == selectedBasemap.name) return
        selectedBasemap = choice
        selectedBasemapName.value = choice.name
        map.setBasemap(choice)
        prefs.edit().putString("basemap", choice.name).apply()
        showAttribution()
        redrawMap()
    }

    private fun toggleMapLayer(id: String) {
        val overlay = mapConfig.overlays.find { it.id == id } ?: return
        if (!enabledOverlays.add(overlay.id)) enabledOverlays.remove(overlay.id)
        layerSelections.value = enabledOverlays.toSet()
        prefs.edit().putBoolean("overlay_${overlay.id}", overlay.id in enabledOverlays).apply()
        map.setOverlays(enabledOverlays.toSet())
    }

    private fun performMenuAction(action: TrailMenuAction) {
        when (action) {
            TrailMenuAction.REFRESH_TRAILS -> refreshTrails()
            TrailMenuAction.ZOOM_TO_TRAILS -> trailDocument?.let { document ->
                val points = document.features.flatMap { it.points }.map { LatLng(it.latitude, it.longitude) }
                following = false
                trailsVisible = true
                trailsVisibleState.value = true
                map.setTrailsVisible(true)
                map.zoomTo(points, dp(40))
                refreshStatus()
            }
            TrailMenuAction.TOGGLE_TRAILS -> {
                trailsVisible = !trailsVisible
                trailsVisibleState.value = trailsVisible
                map.setTrailsVisible(trailsVisible)
                redrawMap()
            }
            TrailMenuAction.LEGEND -> showLegend()
            TrailMenuAction.SHARE_KML -> chooseRecordedTrack(false)
            TrailMenuAction.SHARE_GPX -> chooseRecordedTrack(true)
        }
    }

    private fun showMapCredits() {
        val credits = TextView(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            text = android.text.Html.fromHtml(
                selectedBasemap.attribution +
                    (selectedBasemap.attributionUrl?.let { "<br><br><a href=\"$it\">Source and licence details</a>" } ?: "") +
                    if (selectedBasemap.name == "opentopomap")
                        "<br><br><a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap · ODbL</a>" +
                        "<br><a href=\"https://creativecommons.org/licenses/by-sa/3.0/\">Map style · CC BY-SA 3.0</a>"
                    else "",
                android.text.Html.FROM_HTML_MODE_LEGACY)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        android.app.AlertDialog.Builder(this).setTitle("Map credits").setView(credits)
            .setPositiveButton("Done", null).show()
    }

    private fun formatZoom(zoom: Double): String = if (zoom % 1.0 == 0.0) zoom.toInt().toString() else String.format(java.util.Locale.US, "%.1f", zoom)

    private fun shareCurrentLocation() {
        val fix = current?.let { Location(it) }
        if (fix == null || android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos > 30_000_000_000L) {
            android.widget.Toast.makeText(this, "Waiting for a current location — try again in a moment", android.widget.Toast.LENGTH_LONG).show()
            if (!hasPermission()) requestLocationPermission() else startLocation()
            return
        }
        if (reportText == null) {
            reportText = LocationReport.message(fix.latitude, fix.longitude)
            reportAccuracy = fix.accuracy.toInt()
        }
        showReport()
    }

    private fun photoFile(name: String): File = ReportPhotoProvider.photoFile(filesDir, name)
    private fun photoUri(name: String) = android.net.Uri.Builder().scheme("content")
        .authority("$packageName.photos").appendPath("photo").appendPath(name).build()

    private fun showReport() {
        if (reportDialog?.isShowing == true || reportText == null) return
        val message = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP
            setText(reportText)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        reportEditor = message
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Share current location")
            .setMessage("GPS accuracy ±$reportAccuracy m. Add a description if you like." +
                if (reportPhoto != null) "\nPhoto attached." else "")
            .setView(message)
            .setPositiveButton("Share", null)
            .setNeutralButton(if (reportPhoto == null) "Take photo" else "Retake photo", null)
            .setNegativeButton("Cancel") { _, _ -> clearReport() }
            .setOnCancelListener { clearReport() }
            .create()
        reportDialog = dialog
        dialog.setOnDismissListener { reportEditor = null; reportDialog = null }
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            reportText = message.text.toString()
            takeReportPhoto()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            reportText = message.text.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (reportPhoto == null) "text/plain" else "image/jpeg"
                putExtra(Intent.EXTRA_TEXT, reportText!!.trim())
                putExtra(Intent.EXTRA_EMAIL, arrayOf("mbligh@mbligh.org"))
                putExtra(Intent.EXTRA_SUBJECT, "Trail location report")
                reportPhoto?.let {
                    val uri = photoUri(it)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Trail report photo", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            try {
                startActivity(Intent.createChooser(intent, "Share location report"))
                // Keep the attachment available while the receiving app reads it.
                clearReport(deletePhoto = false)
                dialog.dismiss()
            } catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "No app available to share this report", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun takeReportPhoto() {
        var name: String? = null
        try {
            val directory = File(filesDir, "report-photos").apply { mkdirs() }
            name = File.createTempFile("Trail-report-", ".jpg", directory).name
            val uri = photoUri(name)
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("Trail report photo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            cameraPhoto = name
            startActivityForResult(intent, 3)
            reportDialog?.dismiss()
        } catch (_: Exception) {
            name?.let { photoFile(it).delete() }
            cameraPhoto = null
            android.widget.Toast.makeText(this, "Unable to open the camera app", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform camera activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 3) return
        val name = cameraPhoto ?: return
        cameraPhoto = null
        revokeUriPermission(photoUri(name), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val file = photoFile(name)
        if (resultCode == RESULT_OK && file.length() > 0) {
            reportPhoto?.let { photoFile(it).delete() }
            reportPhoto = name
        } else {
            file.delete()
            if (resultCode == RESULT_OK) android.widget.Toast.makeText(this, "The camera did not save a photo. You can try again.", android.widget.Toast.LENGTH_LONG).show()
        }
        showReport()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pinDraft?.let { draft ->
            draft.putString("text", pinEditor?.text?.toString().orEmpty())
            outState.putBundle("pinDraft", draft)
        }
        reportEditor?.let { reportText = it.text.toString() }
        outState.putString("reportText", reportText)
        outState.putInt("reportAccuracy", reportAccuracy)
        outState.putString("reportPhoto", reportPhoto)
        outState.putString("cameraPhoto", cameraPhoto)
        val mapState = Bundle(); map.onSaveInstanceState(mapState); outState.putBundle("maplibre", mapState)
        super.onSaveInstanceState(outState)
    }

    private fun clearReport(deletePhoto: Boolean = true) {
        if (deletePhoto) reportPhoto?.let { photoFile(it).delete() }
        reportText = null
        reportPhoto = null
        reportEditor = null
    }

    private fun toggleRecording() {
        if (RecordingService.running) {
            lastRecordedPoint = null
            redrawMap()
            stopService(Intent(this, RecordingService::class.java))
            val points = latestRecording()?.let { RecordedTrack.read(it) } ?: emptyList()
            val stats = RecordedTrack.summarize(points)
            val imperial = resources.configuration.locales[0].country.equals("US", ignoreCase = true)
            val message = if (points.size < 2) {
                "Your track is saved on this phone. Not enough GPS fixes for trip statistics."
            } else {
                "Your track is saved on this phone.\n\n" + RecordedTrack.displaySummary(stats, imperial)
            }
            android.app.AlertDialog.Builder(this).setTitle("Recording saved")
                .setMessage(message)
                .setPositiveButton("Share KML") { _, _ -> latestRecording()?.let { shareRecording(it, false) } }
                .setNegativeButton("Done", null).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission()
            android.widget.Toast.makeText(this, "Grant precise location, then tap Record again", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return
        }
        startForegroundService(Intent(this, RecordingService::class.java))
    }
    private fun recordings() = File(filesDir, "recordings").listFiles()?.filter { it.extension == "csv" }?.sortedByDescending { it.name } ?: emptyList()
    private fun latestRecording(): File? = prefs.getString("recorded_track", null)?.let { File(File(filesDir, "recordings"), it) }?.takeIf { it.exists() }
    private fun chooseRecordedTrack(gpx: Boolean) {
        val files = recordings()
        if (files.isEmpty()) { android.widget.Toast.makeText(this, "No recordings yet", android.widget.Toast.LENGTH_SHORT).show(); return }
        android.app.AlertDialog.Builder(this).setTitle("Share recorded track")
            .setItems(files.map { it.nameWithoutExtension }.toTypedArray()) { _, index -> shareRecording(files[index], gpx) }.show()
    }
    private fun shareRecording(file: File, gpx: Boolean = false) {
        val points = RecordedTrack.read(file)
        if (points.isEmpty()) { android.widget.Toast.makeText(this, "No GPS points recorded yet", android.widget.Toast.LENGTH_LONG).show(); return }
        try {
            val directory = File(filesDir, "exports").apply { mkdirs() }
            val extension = if (gpx) "gpx" else "kml"
            val export = File(directory, file.nameWithoutExtension + "." + extension)
            val pins = RecordedPins.read(file)
            val contents = if (gpx) {
                RecordedPins.addToGpx(RecordedTrack.gpx(file.nameWithoutExtension, points), pins)
            } else {
                RecordedPins.addToKml(RecordedTrack.kml(file.nameWithoutExtension, points), pins)
            }
            export.writeText(contents)
            val uri = android.net.Uri.Builder().scheme("content").authority("$packageName.files").appendPath("export").appendPath(export.name).build()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (gpx) "application/gpx+xml" else "application/vnd.google-earth.kml+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                clipData = android.content.ClipData.newRawUri("Recorded trail", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, if (gpx) "Share GPX track" else "Share KML track"))
        } catch (_: Exception) { android.widget.Toast.makeText(this, "Could not share this track", android.widget.Toast.LENGTH_LONG).show() }
    }
    private fun addRecordingPin(withText: Boolean = false) {
        if (pinDialog?.isShowing == true) return
        if (!RecordingService.running) {
            android.widget.Toast.makeText(this, "Start recording before adding a pin", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val gps = RecordingService.latestGpsFix?.let { Location(it) }
            ?.takeIf { now - it.elapsedRealtimeNanos in 0..locationWindowNanos }
        val fix = gps ?: current?.let { Location(it) }
            ?.takeIf { now - it.elapsedRealtimeNanos in 0..locationWindowNanos }
        if (fix == null) {
            android.widget.Toast.makeText(this, "Waiting for a recent location fix", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val file = latestRecording()
        if (file == null) {
            android.widget.Toast.makeText(this, "Recording file is not ready yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val warning = if (gps == null || !fix.hasAccuracy() || fix.accuracy > 25f) {
            val source = if (gps == null) "No recent GPS fix; using ${fix.provider ?: "unknown"}" else "GPS accuracy is poor"
            "$source (±${if (fix.hasAccuracy()) "${fix.accuracy.toInt()} m" else "unknown"})."
        } else null
        if (withText) {
            showPinText(Bundle().apply {
                putString("track", file.name)
                putParcelable("fix", fix)
                putString("warning", warning)
            })
        } else if (warning != null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Low-accuracy pin")
                .setMessage("$warning Save anyway?")
                .setNegativeButton("Wait", null)
                .setPositiveButton("Save anyway") { _, _ -> saveRecordingPin(file, fix) }
                .show()
        } else saveRecordingPin(file, fix)
    }

    @Suppress("DEPRECATION")
    private fun showPinText(draft: Bundle) {
        val trackName = draft.getString("track") ?: return
        val fix = draft.getParcelable<Location>("fix") ?: return
        val file = File(File(filesDir, "recordings"), File(trackName).name)
        if (!file.exists() || pinDialog?.isShowing == true) return
        pinDraft = draft
        val editor = android.widget.EditText(this).apply {
            hint = "Text for this pin"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; gravity = Gravity.TOP
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setText(draft.getString("text", ""))
            setSelection(text.length)
        }
        pinEditor = editor
        val warning = draft.getString("warning")
        pinDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Pin text")
            .setMessage(warning?.let { "$it Save this location anyway?" })
            .setView(editor)
            .setPositiveButton(if (warning == null) "Save pin" else "Save anyway") { _, _ -> saveRecordingPin(file, fix, editor.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .create().apply {
                setOnDismissListener { pinDraft = null; pinEditor = null; pinDialog = null }
                window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                editor.requestFocus()
                show()
            }
    }

    private fun saveRecordingPin(file: File, fix: Location, text: String = "") {
        try {
            RecordedPins.append(file, fix, text)
            // Force the recording overlay to be rebuilt so the new pin appears immediately.
            displayedLength = -1L
            updateRecording()
            android.widget.Toast.makeText(this, "Pin added", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Could not add pin", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun updateRecording() {
        val running = RecordingService.running
        recordButton.contentDescription = if (running) "Stop recording" else "Start recording"
        recordButton.setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
            shape = if (running) android.graphics.drawable.GradientDrawable.RECTANGLE else android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.RED); setSize(dp(24), dp(24)); if (running) cornerRadius = dp(3).toFloat()
        })
        val file = latestRecording()
        if (file != null && (file.name != displayedTrack || file.length() != displayedLength)) {
            displayedTrack = file.name; displayedLength = file.length()
            val points = RecordedTrack.read(file)
            recordedPointCount = points.size
            lastRecordedPoint = points.lastOrNull()
            map.showRecording(points, RecordedPins.read(file))
            redrawMap()
        }
        redrawMap() // Also remove the live segment after a notification stop or stale fix.
        recordingStatusText.value = if (!running) null
            else if (recordedPointCount == 0) "● Recording • waiting for GPS"
            else "● Recording • $recordedPointCount points • every 30s"
    }

    private fun refreshTrails() {
        if (refreshingTrails || destroyed) return
        refreshingTrails = true
        
        trailStatusText.value = if (trailDocument == null) "Loading trails…" else "Refreshing trails…"
        val needsCache = trailDocument == null
        trailExecutor.execute {
            if (needsCache) {
                trailRepository.cached()?.let { cached -> runOnUiThread {
                    if (!destroyed) { showTrails(cached); trailStatusText.value = "${cached.name} • cached • refreshing…" }
                } }
            }
            val result = runCatching { trailRepository.refresh() }
            runOnUiThread {
                if (!destroyed) {
                    refreshingTrails = false
                    result.fold(onSuccess = { document ->
                        showTrails(document)
                        trailStatusText.value = null
                        
                    }, onFailure = {
                        
                        trailStatusText.value = if (trailDocument != null) "${trailDocument!!.name} • cached • refresh failed"
                                           else "Trails unavailable — check connection and map sharing"
                    })
                }
            }
        }
    }
    private fun showTrails(document: TrailKml.Document) {
        trailDocument = document
        map.showTrails(document, trailsVisible)
        redrawMap()
    }

    private fun redrawMap() {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val fix = current
        val last = lastRecordedPoint?.takeIf { point ->
            RecordingService.running && prefs.getString("recorded_track", null) == displayedTrack &&
                fix != null && fix.time >= point.time && fix.time - point.time <= 90_000L &&
                now - fix.elapsedRealtimeNanos <= 30_000_000_000L
        }
        val heading = facingHeading?.takeIf { android.os.SystemClock.elapsedRealtime() - headingTime < 5_000L }
        map.setPosition(fix, heading, last)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hasPermission() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestLocationPermission() = requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    private fun refreshStatus() {
        recenter.imageTintList = android.content.res.ColorStateList.valueOf(if (following) Color.rgb(21, 101, 192) else Color.DKGRAY)
        statusText.value = when {
            !hasPermission() -> "Location permission needed — tap Recenter"
            current == null -> "Waiting for location — check that Location is enabled"
            following -> "Following GPS • accuracy ±${current!!.accuracy.toInt()} m"
            else -> "Tap Recenter to follow GPS"
        }
    }
    @Suppress("MissingPermission")
    private fun startLocation() {
        if (!active || !hasPermission()) { refreshStatus(); return }
        manager.removeUpdates(this)
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
        var newest: Location? = null
        for (provider in providers) {
            if (!manager.allProviders.contains(provider) || !manager.isProviderEnabled(provider)) continue
            try {
                manager.requestLocationUpdates(provider, 1000L, 2f, this)
                val cached = manager.getLastKnownLocation(provider)
                if (cached != null && (newest == null || cached.elapsedRealtimeNanos > newest.elapsedRealtimeNanos)) newest = cached
            } catch (_: SecurityException) { }
        }
        newest?.let { if (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..locationWindowNanos) onLocationChanged(it) }
        refreshStatus()
    }
    override fun onLocationChanged(location: Location) {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (now - location.elapsedRealtimeNanos !in 0..locationWindowNanos) return
        recentLocations.addLast(Location(location))
        while (recentLocations.isNotEmpty() &&
            now - recentLocations.first().elapsedRealtimeNanos > locationWindowNanos) {
            recentLocations.removeFirst()
        }
        // The recorder owns the GPS stream while recording; do not let a newer
        // but much less accurate network estimate displace a good GPS fix.
        val serviceGps = if (RecordingService.running) RecordingService.latestGpsFix
            ?.takeIf { now - it.elapsedRealtimeNanos in 0..locationWindowNanos } else null
        val gps = (recentLocations.filter { it.provider == LocationManager.GPS_PROVIDER } +
            listOfNotNull(serviceGps)).filter { it.hasAccuracy() }
        val bestGps = gps.minByOrNull { it.accuracy }
        val chosen = if (bestGps != null) {
            val tolerance = maxOf(bestGps.accuracy * 2f, bestGps.accuracy + 5f)
            gps.filter { it.accuracy <= tolerance }.maxByOrNull { it.elapsedRealtimeNanos }
        } else {
            recentLocations.maxByOrNull { it.elapsedRealtimeNanos }
        } ?: return
        val previous = current
        current = Location(chosen)
        if (following && (previous == null || previous.latitude != chosen.latitude ||
                previous.longitude != chosen.longitude)) {
            map.controller.setCenter(LatLng(chosen.latitude, chosen.longitude))
        }
        redrawMap()
        refreshStatus()
    }
    override fun onProviderEnabled(provider: String) { startLocation() }
    override fun onProviderDisabled(provider: String) { current = null; redrawMap(); startLocation() }
    @Deprecated("Legacy callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) startLocation()
    }
    override fun onStart() { super.onStart(); map.onStart() }
    override fun onStop() { map.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); map.onLowMemory() }
    override fun onResume() { super.onResume(); active = true; phoneHeading.start(); map.onResume(); startLocation(); recordingHandler.removeCallbacks(recordingTick); recordingHandler.post(recordingTick) }
    override fun onPause() {
        pinGesture.cancel()
        recentLocations.clear()
        active = false
        phoneHeading.stop()
        recordingHandler.removeCallbacks(recordingTick)
        manager.removeUpdates(this)
        prefs.edit().putFloat("zoom", map.zoomLevelDouble.toFloat()).putFloat("lat", map.mapCenter.latitude.toFloat()).putFloat("lon", map.mapCenter.longitude.toFloat()).apply()
        map.onPause()
        super.onPause()
    }
    override fun onDestroy() { destroyed = true; pinDialog?.dismiss(); trailExecutor.shutdownNow(); map.onDestroy(); super.onDestroy() }
}
