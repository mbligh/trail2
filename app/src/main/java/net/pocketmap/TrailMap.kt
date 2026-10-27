package net.pocketmap

import android.content.Context
import android.graphics.*
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/** Native MapLibre renderer. Location acquisition and recording remain outside this class. */
class TrailMap(context: Context, private val config: MapConfiguration, initial: Basemap, center: LatLng, zoom: Double, saved: Bundle?) : FrameLayout(context) {
    private val tiles: TileRepository
    private val view: MapView
    private var native: MapLibreMap? = null
    private var basemap = initial
    private var enabled = emptySet<String>()
    private var centerBeforeReady = center
    private var zoomBeforeReady = zoom
    private var generation = 0
    private var destroyed = false
    private var trails: TrailKml.Document? = null
    private var trailsVisible = true
    private var track = emptyList<RecordedTrack.Point>()
    private var pins = emptyList<RecordedPins.Pin>()
    private var current: Location? = null
    private var heading: Float? = null
    private var connector: RecordedTrack.Point? = null
    private var popup: PopupWindow? = null
    var onCameraChanged: () -> Unit = {}
    var onDrag: () -> Unit = {}
    var onError: (String) -> Unit = {}
    // osmdroid used a 256px world. Preserve the saved scale and displayed zoom.
    val zoomLevelDouble: Double get() = native?.cameraPosition?.zoom?.plus(1.0) ?: zoomBeforeReady
    val mapCenter: LatLng get() = native?.cameraPosition?.target ?: centerBeforeReady
    val controller get() = this

    fun setCenter(point: LatLng) { centerBeforeReady = point; native?.moveCamera(CameraUpdateFactory.newLatLng(point)) }
    fun setZoom(zoom: Double) { zoomBeforeReady = zoom; native?.moveCamera(CameraUpdateFactory.zoomTo((zoom-1.0).coerceIn(2.0, 18.0))) }
    fun setBasemap(value: Basemap) { basemap = value; loadStyle() }
    fun setOverlays(ids: Set<String>) { enabled = ids; loadStyle() }
    fun zoomTo(points: List<LatLng>, padding: Int) {
        if (points.isEmpty()) return
        native?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding))
    }
    fun showTrails(document: TrailKml.Document, visible: Boolean) { trails = document; trailsVisible = visible; updateData() }
    fun setTrailsVisible(visible: Boolean) { trailsVisible = visible; updateData() }
    fun showRecording(points: List<RecordedTrack.Point>, recordedPins: List<RecordedPins.Pin>) { track = points; pins = recordedPins; updateData() }
    fun setPosition(location: Location?, facing: Float?, last: RecordedTrack.Point?) { current = location; heading = facing; connector = last; positionView.invalidate() }

    private fun loadStyle() {
        val map = native ?: return
        val request = ++generation
        try {
            val json = config.style(basemap, enabled, tiles)
            val sources = json.getJSONObject("sources")
            listOf("trail-features", "trail-track", "trail-pins").forEach { id -> sources.put(id, JSONObject().put("type", "geojson").put("data", collection(emptyList()))) }
            val layers = json.getJSONArray("layers")
            fun layer(raw: String) { layers.put(JSONObject(raw)) }
            layer("""{"id":"trail-polygon-fill","type":"fill","source":"trail-features","filter":["==",["geometry-type"],"Polygon"],"paint":{"fill-color":["get","fillColor"],"fill-opacity":["get","fillOpacity"]}}""")
            layer("""{"id":"trail-polygon-outline","type":"line","source":"trail-features","filter":["==",["geometry-type"],"Polygon"],"paint":{"line-color":["get","lineColor"],"line-opacity":["get","lineOpacity"],"line-width":2}}""")
            layer("""{"id":"trail-lines","type":"line","source":"trail-features","filter":["==",["geometry-type"],"LineString"],"layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":["get","lineColor"],"line-opacity":["get","lineOpacity"],"line-width":["get","width"]}}""")
            layer("""{"id":"trail-points","type":"symbol","source":"trail-features","filter":["==",["geometry-type"],"Point"],"layout":{"icon-image":["get","icon"],"icon-size":["get","scale"],"icon-anchor":["get","anchor"],"icon-allow-overlap":true}}""")
            layer("""{"id":"trail-recording","type":"line","source":"trail-track","layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"#1565c0","line-width":4}}""")
            layer("""{"id":"trail-recording-pins","type":"symbol","source":"trail-pins","layout":{"icon-image":"trail-pin","icon-anchor":"bottom","icon-allow-overlap":true}}""")
            map.setStyle(Style.Builder().fromJson(json.toString())) { style ->
                if (destroyed || request != generation) return@setStyle
                addPinImage(style)
                updateData(style)
                onCameraChanged()
            }
        } catch (error: Exception) { onError(error.message ?: "Unable to load map") }
    }

    private fun addPinImage(style: Style) {
        val drawable = context.getDrawable(R.drawable.ic_track_pin)!!
        val bitmap = Bitmap.createBitmap(dp(24), dp(24), Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height); drawable.draw(Canvas(bitmap))
        bitmap.density = resources.displayMetrics.densityDpi
        style.addImage("trail-pin", bitmap)
    }
    private fun iconKey(feature: TrailKml.Feature) = "trail-icon-${feature.style.iconId}-${feature.style.iconColor}"
    private fun addTrailImages(style: Style) {
        trails?.features?.filter { it.kind == "Point" }?.distinctBy { iconKey(it) }?.forEach { feature ->
            val key = iconKey(feature)
            if (style.getImage(key) != null) return@forEach
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
            style.addImage(key, bitmap)
        }
    }
    private fun updateData(style: Style? = native?.style) {
        if (style == null || !style.isFullyLoaded) return
        addTrailImages(style)
        style.getSourceAs<GeoJsonSource>("trail-features")?.setGeoJson(trailData())
        style.getSourceAs<GeoJsonSource>("trail-track")?.setGeoJson(recordingData())
        style.getSourceAs<GeoJsonSource>("trail-pins")?.setGeoJson(collection(pins.map { pin -> feature("Point", JSONArray().put(pin.longitude).put(pin.latitude), JSONObject().put("name", "Pin")) }).toString())
    }
    private fun trailData(): String {
        val features = if (!trailsVisible) emptyList() else trails?.features?.map { f ->
            val coordinates = JSONArray(f.points.map { listOf(it.longitude, it.latitude) })
            val geometry = when (f.kind) { "Point" -> coordinates.getJSONArray(0); "Polygon" -> JSONArray().put(closed(coordinates)).apply { f.holes.forEach { put(closed(JSONArray(it.map { p -> listOf(p.longitude, p.latitude) }))) } }; else -> coordinates }
            val p = JSONObject().put("name", f.name).put("lineColor", color(f.style.lineColor)).put("lineOpacity", if (f.kind == "Polygon" && !f.style.outline) 0 else Color.alpha(f.style.lineColor) / 255.0)
                .put("fillColor", color(f.style.fillColor)).put("fillOpacity", if (f.style.fill) Color.alpha(f.style.fillColor) / 255.0 else 0.0).put("width", f.style.width.toInt().coerceAtLeast(2))
                .put("icon", iconKey(f)).put("scale", f.style.iconScale).put("anchor", if (f.style.iconId == "1763") "center" else "bottom")
            feature(f.kind, geometry, p)
        } ?: emptyList()
        return collection(features).toString()
    }
    private fun recordingData(): String {
        val segments = mutableListOf<MutableList<RecordedTrack.Point>>()
        track.forEachIndexed { index, point ->
            if (index == 0 || point.time - track[index-1].time > 90_000) segments.add(mutableListOf())
            segments.last().add(point)
        }
        return collection(segments.filter { it.size >= 2 }.map { points -> feature("LineString", JSONArray(points.map { listOf(it.longitude, it.latitude) }), JSONObject().put("name", "Recorded trail")) }).toString()
    }
    private fun closed(ring: JSONArray): JSONArray {
        if (ring.length() > 0 && ring.getJSONArray(0).toString() != ring.getJSONArray(ring.length()-1).toString()) ring.put(ring.getJSONArray(0))
        return ring
    }
    private fun color(value: Int) = String.format(java.util.Locale.US, "#%06x", value and 0xffffff)
    private fun feature(kind: String, coordinates: JSONArray, properties: JSONObject) = JSONObject().put("type", "Feature").put("properties", properties).put("geometry", JSONObject().put("type", kind).put("coordinates", coordinates))
    private fun collection(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private val positionView: View by lazy { object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val map = native ?: return
            val fix = current ?: return
            val pixel = map.projection.toScreenLocation(LatLng(fix.latitude, fix.longitude))
            connector?.let { last ->
                val from = map.projection.toScreenLocation(LatLng(last.latitude, last.longitude))
                paint.color = Color.rgb(21,101,192); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(4).toFloat(); paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(from.x, from.y, pixel.x, pixel.y, paint)
            }
            paint.style = Paint.Style.FILL
            val facing = heading
            if (facing == null) {
                paint.color = Color.WHITE; canvas.drawCircle(pixel.x, pixel.y, dp(10).toFloat(), paint)
                paint.color = Color.rgb(21,101,192); canvas.drawCircle(pixel.x, pixel.y, dp(7).toFloat(), paint)
            } else {
                val saved = canvas.save(); canvas.translate(pixel.x, pixel.y); canvas.rotate(facing)
                val triangle = Path().apply { moveTo(0f,-dp(15).toFloat()); lineTo(dp(10).toFloat(),dp(10).toFloat()); lineTo(-dp(10).toFloat(),dp(10).toFloat()); close() }
                paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(4).toFloat(); paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(triangle,paint)
                paint.color = Color.rgb(21,101,192); paint.style = Paint.Style.FILL; canvas.drawPath(triangle,paint)
                canvas.restoreToCount(saved)
            }
        }
    } }
    init {
        MapLibre.getInstance(context)
        tiles = TileRepository(context)
        view = MapView(context, MapLibreMapOptions.createFromAttributes(context)
            .compassEnabled(false).attributionEnabled(false).logoEnabled(false)
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false))
        addView(view, LayoutParams(-1, -1))
        addView(positionView, LayoutParams(-1, -1))
        view.onCreate(saved)
        view.addOnDidFailLoadingMapListener { message -> if (!destroyed) onError(message) }
        view.getMapAsync { map ->
            if (destroyed) return@getMapAsync
            native = map
            map.setMinZoomPreference(2.0)
            map.setMaxZoomPreference(18.0)
            map.cameraPosition = CameraPosition.Builder().target(centerBeforeReady).zoom((zoomBeforeReady - 1.0).coerceIn(2.0, 18.0)).bearing(0.0).tilt(0.0).build()
            map.addOnCameraMoveListener { positionView.invalidate(); onCameraChanged() }
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) { popup?.dismiss(); if (detector.pointersCount == 1) onDrag() }
                override fun onMove(detector: MoveGestureDetector) {}
                override fun onMoveEnd(detector: MoveGestureDetector) {}
            })
            map.addOnMapClickListener { point ->
                popup?.dismiss()
                val pixel = map.projection.toScreenLocation(point)
                val radius = dp(12).toFloat()
                val hit = map.queryRenderedFeatures(RectF(pixel.x-radius, pixel.y-radius, pixel.x+radius, pixel.y+radius), "trail-points", "trail-lines", "trail-polygon-outline", "trail-polygon-fill", "trail-recording-pins", "trail-recording").firstOrNull()
                val name = hit?.getStringProperty("name")
                if (!name.isNullOrBlank()) {
                    val label = TextView(context).apply { text = name; setTextColor(Color.BLACK); setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.WHITE) }
                    val screen = IntArray(2); getLocationOnScreen(screen)
                    popup = PopupWindow(label, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true).apply {
                        elevation = dp(6).toFloat(); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
                        showAtLocation(this@TrailMap, Gravity.TOP or Gravity.START, screen[0] + pixel.x.toInt(), screen[1] + pixel.y.toInt())
                    }
                }
                hit != null
            }
            loadStyle()
        }
    }

    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onSaveInstanceState(state: Bundle) = view.onSaveInstanceState(state)
    fun onLowMemory() = view.onLowMemory()
    fun onDestroy() { destroyed = true; popup?.dismiss(); view.onDestroy(); tiles.close() }
}
