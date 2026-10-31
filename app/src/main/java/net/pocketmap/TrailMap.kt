package net.pocketmap

import android.content.Context
import android.graphics.*
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import net.pocketmap.shared.map.MapArtwork
import net.pocketmap.shared.map.TrailMapContent
import net.pocketmap.shared.map.PositionOverlay
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.*
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position

/** Android lifecycle/controller adapter over the shared Compose map content. GPS stays outside. */
class TrailMap(context: Context, private val config: MapConfiguration, initial: Basemap, center: LatLng, zoom: Double, saved: Bundle?) : FrameLayout(context) {
    private val mapResources = ComposeMapResources(context.applicationContext)
    internal val tiles get() = mapResources.tiles
    private val runtime = createMapRuntime { resourceProvider = mapResources.provider }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val compose = ComposeView(context).apply { id = R.id.trail_map_compose }
    internal var mapState: MapState? = null
        private set
    private var selectedBasemap by mutableStateOf(initial)
    private var enabled by mutableStateOf(emptySet<String>())
    private var initialCenter = saved?.takeIf { it.containsKey("latitude") }?.let { LatLng(it.getDouble("latitude"), it.getDouble("longitude")) } ?: center
    private var initialZoom = saved?.getDouble("zoom", zoom) ?: zoom
    private var trails: TrailKml.Document? = null
    private var trailsVisible = true
    private var track = emptyList<RecordedTrack.Point>()
    private var pins = emptyList<RecordedPins.Pin>()
    private var trailJson by mutableStateOf(collection(emptyList()).toString())
    private var trackJson by mutableStateOf(collection(emptyList()).toString())
    private var pinJson by mutableStateOf(collection(emptyList()).toString())
    private var artwork by mutableStateOf(emptyList<MapArtwork>())
    private var current by mutableStateOf<Position?>(null)
    private var heading by mutableStateOf<Float?>(null)
    private var connector by mutableStateOf<Position?>(null)
    private var popup: PopupWindow? = null
    internal var styleLoads = 0
        private set
    private var pressedPointers = 0
    var onCameraChanged: () -> Unit = {}
    var onDrag: () -> Unit = {}
    var onError: (String) -> Unit = {}
    val zoomLevelDouble: Double get() = mapState?.cameraPosition?.zoom?.plus(1.0) ?: initialZoom
    val mapCenter: LatLng get() = mapState?.cameraPosition?.center?.let { LatLng(it.latitude, it.longitude) } ?: initialCenter
    val controller get() = this

    fun setCenter(point: LatLng) {
        initialCenter = point
        mapState?.let { it.setCameraPosition(it.cameraPosition.copy(center = Position(point.longitude, point.latitude), bearing = 0.0, pitch = 0.0)) }
    }
    fun setZoom(zoom: Double) {
        initialZoom = zoom
        mapState?.let { it.setCameraPosition(it.cameraPosition.copy(zoom = (zoom - 1.0).coerceIn(2.0, 18.0))) }
    }
    fun setBasemap(value: Basemap) { selectedBasemap = value }
    fun setOverlays(ids: Set<String>) { enabled = ids.toSet() }
    fun zoomTo(points: List<LatLng>, padding: Int) {
        if (points.isEmpty()) return
        scope.launch {
            val state = mapState ?: return@launch
            val fit = CameraFit(fitPadding = (padding / resources.displayMetrics.density).dp.let { DpPadding(it, it, it, it) })
            val camera = state.cameraForCoordinates(points.map { Position(it.longitude, it.latitude) }, fit)
            state.setCameraPosition(camera.copy(zoom = camera.zoom.coerceIn(2.0, 18.0)))
        }
    }
    fun showTrails(document: TrailKml.Document, visible: Boolean) {
        trails = document; trailsVisible = visible
        artwork = listOf(pinImage()) + trailImages()
        updateData()
    }
    fun setTrailsVisible(visible: Boolean) { trailsVisible = visible; updateData() }
    fun showRecording(points: List<RecordedTrack.Point>, recordedPins: List<RecordedPins.Pin>) {
        track = points; pins = recordedPins; updateData()
    }
    fun setPosition(location: Location?, facing: Float?, last: RecordedTrack.Point?) {
        current = location?.let { Position(it.longitude, it.latitude) }
        heading = facing
        connector = last?.let { Position(it.longitude, it.latitude) }
    }
    private fun pinImage(): MapArtwork {
        val drawable = context.getDrawable(R.drawable.ic_track_pin)!!
        val bitmap = Bitmap.createBitmap(dp(24), dp(24), Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height); drawable.draw(Canvas(bitmap))
        bitmap.density = resources.displayMetrics.densityDpi
        return MapArtwork("trail-pin", bitmap.asImageBitmap(), DpSize((bitmap.width / resources.displayMetrics.density).dp, (bitmap.height / resources.displayMetrics.density).dp))
    }
    private fun updateData() {
        trailJson = trailData()
        trackJson = recordingData()
        pinJson = collection(pins.map { pin -> feature("Point", JSONArray().put(pin.longitude).put(pin.latitude), JSONObject().put("name", if (pin.text.isBlank()) "Pin" else "Pin\n${pin.text}")) }).toString()
    }
    private fun iconKey(feature: TrailKml.Feature) = "trail-icon-${feature.style.iconId}-${feature.style.iconColor}"
    private fun trailImages(): List<MapArtwork> {
        val result = mutableListOf<MapArtwork>()
        trails?.features?.filter { it.kind == "Point" }?.distinctBy { iconKey(it) }?.forEach { feature ->
            val key = iconKey(feature)
            val resource = when (feature.style.iconId) { "1763" -> R.drawable.mymaps_1763; "1899" -> R.drawable.mymaps_1899; "1205" -> R.drawable.mymaps_1205; "503" -> R.drawable.mymaps_503; else -> 0 }
            val bitmap = if (resource != 0) {
                val original = BitmapFactory.decodeResource(resources, resource)
                Bitmap.createScaledBitmap(original, dp(28), (dp(28) * original.height.toFloat() / original.width).toInt().coerceAtLeast(1), true).copy(Bitmap.Config.ARGB_8888, true).apply {
                    if (feature.style.iconId == "503") Canvas(this).drawColor(feature.style.iconColor, PorterDuff.Mode.SRC_IN)
                }
            } else {
                Bitmap.createBitmap(dp(24), dp(36), Bitmap.Config.ARGB_8888).apply {
                    val canvas = Canvas(this); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
                    canvas.drawCircle(dp(12).toFloat(), dp(12).toFloat(), dp(10).toFloat(), paint)
                    canvas.drawPath(Path().apply { moveTo(dp(3).toFloat(), dp(15).toFloat()); lineTo(dp(12).toFloat(), dp(36).toFloat()); lineTo(dp(21).toFloat(), dp(15).toFloat()); close() }, paint)
                }
            }
            bitmap.density = resources.displayMetrics.densityDpi
            result.add(MapArtwork(key, bitmap.asImageBitmap(), DpSize((bitmap.width / resources.displayMetrics.density).dp, (bitmap.height / resources.displayMetrics.density).dp)))
        }
        return result
    }
    private fun trailData(): String = if (!trailsVisible || trails == null) collection(emptyList()).toString()
        else net.pocketmap.shared.trails.SharedTrailGeoJson.encode(TrailKml.shared(trails!!))
    private fun recordingData(): String {
        val segments = mutableListOf<MutableList<RecordedTrack.Point>>()
        track.forEachIndexed { index, point ->
            if (index == 0 || point.time - track[index-1].time > 90_000) segments.add(mutableListOf())
            segments.last().add(point)
        }
        return collection(segments.filter { it.size >= 2 }.map { points -> feature("LineString", JSONArray(points.map { listOf(it.longitude, it.latitude) }), JSONObject().put("name", "Recorded trail")) }).toString()
    }
    private fun feature(kind: String, coordinates: JSONArray, properties: JSONObject) = JSONObject().put("type", "Feature").put("properties", properties).put("geometry", JSONObject().put("type", kind).put("coordinates", coordinates))
    private fun collection(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()


    init {
        artwork = listOf(pinImage())
        addView(compose, LayoutParams(-1, -1))
        compose.setContent {
            val style = remember(selectedBasemap, enabled) {
                try { BaseStyle.Json(mapResources.style(config.style(selectedBasemap, enabled, tiles))) }
                catch (error: Exception) { onError(error.message ?: "Unable to load map"); BaseStyle.Empty }
            }
            val state = rememberMapState(runtime = runtime, baseStyle = style,
                initialCameraPosition = CameraPosition(center = Position(initialCenter.longitude, initialCenter.latitude), zoom = (initialZoom - 1.0).coerceIn(2.0, 18.0)),
                content = { TrailMapContent(trailJson, trackJson, pinJson, artwork) { name, offset ->
                    val density = resources.displayMetrics.density
                    showLabel(name, (offset.x.value * density).toInt(), (offset.y.value * density).toInt())
                } })
            SideEffect { mapState = state }
            LaunchedEffect(state) {
                state.events.collectLatest { event ->
                    when (event) {
                        is MapEvent.StyleLoadFailed -> onError(event.reason)
                        is MapEvent.SourceDataFailed -> onError(event.cause.message ?: "Unable to load map data")
                        MapEvent.StyleLoaded -> { styleLoads++; onCameraChanged() }
                        MapEvent.CameraMoved -> onCameraChanged()
                        else -> Unit
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                MaplibreMap(state = state,
                    // The shared header already owns credits/zoom and map controls.
                    overlay = {},
                    cameraConstraints = CameraConstraints { minZoom = 2.0; maxZoom = 18.0; maxPitch = 0.0 },
                    interactions = MapInteractions {
                        camera {
                            rotate { enabled = false }; pitch { enabled = false }
                            pan { onStart { popup?.dismiss(); if (pressedPointers == 1) onDrag() } }
                        }
                        callbacks { click { onEvent {
                            popup?.dismiss()
                            ClickResult.Pass
                        } } }
                    },
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope { while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            pressedPointers = event.changes.count { it.pressed }
                        } }
                    })
                PositionOverlay(state, current, heading, connector, Modifier.fillMaxSize())
            }
        }
    }
    private fun showLabel(name: String, x: Int, y: Int) {
        val label = TextView(context).apply {
            text = name; maxWidth = dp(280); maxLines = 12
            movementMethod = android.text.method.ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            setTextColor(Color.BLACK); setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.WHITE)
        }
        val screen = IntArray(2); getLocationOnScreen(screen)
        popup = PopupWindow(label, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(6).toFloat(); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            showAtLocation(this@TrailMap, Gravity.TOP or Gravity.START, screen[0]+x, screen[1]+y)
        }
    }
    // Compose owns renderer lifecycle via its surface; the Activity keeps this controller API.
    fun onStart() = Unit
    fun onResume() = Unit
    fun onPause() { popup?.dismiss() }
    fun onStop() = Unit
    fun onSaveInstanceState(state: Bundle) {
        state.putDouble("latitude", mapCenter.latitude); state.putDouble("longitude", mapCenter.longitude)
        state.putDouble("zoom", zoomLevelDouble)
    }
    fun onLowMemory() = Unit
    fun onDestroy() {
        popup?.dismiss(); scope.cancel(); compose.disposeComposition()
        mapState = null; runtime.close(); mapResources.close()
    }
}
