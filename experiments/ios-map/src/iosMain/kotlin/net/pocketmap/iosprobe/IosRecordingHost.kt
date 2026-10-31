package net.pocketmap.iosprobe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.camera.CameraFit
import org.jetbrains.skia.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.pocketmap.shared.map.MapArtwork
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import net.pocketmap.shared.map.PositionOverlay
import net.pocketmap.shared.map.TrailMapContent
import net.pocketmap.shared.ui.MapChoice
import net.pocketmap.shared.ui.BasemapArtwork
import net.pocketmap.shared.ui.ReportArtwork
import net.pocketmap.shared.ui.PinArtwork
import net.pocketmap.shared.ui.RecenterArtwork
import net.pocketmap.shared.ui.TrailScreen
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.*
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

interface IosPlatformActions { fun perform(action: String) }
interface IosMapResourceReader { fun read(url: String): ByteArray? }

/** iOS integration host with native storage and the common map renderer. */
class IosRecordingHost(private val actions: IosPlatformActions,
    private val resources: IosMapResourceReader?, private val styleJson: String) {
    private var status by mutableStateOf("Location not started")
    private var recording by mutableStateOf(false)
    private var position by mutableStateOf<Position?>(null)
    private var heading by mutableStateOf<Float?>(null)
    private var track by mutableStateOf(EMPTY)
    private var pins by mutableStateOf(EMPTY)
    private var following by mutableStateOf(true)

    private var trails by mutableStateOf<IosTrailDocument?>(null)
    private var trailStatus by mutableStateOf("Trails not loaded")
    private var trailsVisible by mutableStateOf(true)
    private var zoomRequest by mutableStateOf(0)
    private val icons = mutableMapOf<String, ImageBitmap>()

    fun installIcon(id: String, png: ByteArray) {
        icons[id] = Image.makeFromEncoded(png).toComposeImageBitmap()
    }
    fun showTrails(document: IosTrailDocument, status: String) {
        trails = document
        trailStatus = status
    }
    fun setTrailStatus(status: String) { trailStatus = status }
    fun toggleTrails() { trailsVisible = !trailsVisible }
    fun zoomToTrails() { following = false; zoomRequest++ }

    fun update(status: String, recording: Boolean, hasFix: Boolean, latitude: Double,
               longitude: Double, heading: Double, track: String, pins: String) {
        this.status = status
        this.recording = recording
        this.position = if (hasFix) Position(longitude, latitude) else null
        this.heading = heading.takeIf { it.isFinite() && it >= 0 }?.toFloat()
        this.track = track
        this.pins = pins
    }

    fun viewController() = ComposeUIViewController {
        val runtime = remember { createMapRuntime {
            resourceProvider = MapResourceProvider(accepts = { true }, load = { request ->
                val bytes = withContext(Dispatchers.Default) { resources?.read(request.url) }
                if (bytes == null) MapResourceLoad.NoContent() else MapResourceLoad.Bytes(bytes)
            })
        } }
        DisposableEffect(runtime) { onDispose { runtime.close() } }
        val artwork = remember {
            val image = ImageBitmap(24, 24)
            Canvas(image).drawCircle(Offset(12f, 12f), 11f, Paint().apply { color = Color(0xff1565c0) })
            listOf(MapArtwork("trail-pin", image, DpSize(24.dp, 24.dp)))
        }
        val trailArtwork = remember(trails) {
            trails?.document?.features?.filter { it.kind == "Point" }?.distinctBy(::iconKey)?.map { feature ->
                val original = icons[feature.style.iconId]
                val width = if (original == null) 24 else 28
                val height = if (original == null) 36 else (28f * original.height / original.width).toInt().coerceAtLeast(1)
                val bitmap = ImageBitmap(width, height)
                val canvas = Canvas(bitmap)
                if (original != null) {
                    canvas.drawImageRect(original, dstSize = IntSize(width, height), paint = Paint().apply {
                        if (feature.style.iconId == "503") colorFilter = ColorFilter.tint(Color(feature.style.iconColor), BlendMode.SrcIn)
                    })
                } else {
                    val paint = Paint().apply { color = Color.Red }
                    canvas.drawCircle(Offset(12f, 12f), 10f, paint)
                    canvas.drawPath(androidx.compose.ui.graphics.Path().apply {
                        moveTo(3f, 15f); lineTo(12f, 36f); lineTo(21f, 15f); close()
                    }, paint)
                }
                MapArtwork(iconKey(feature), bitmap, DpSize(width.dp, height.dp))
            } ?: emptyList()
        }
        var pointers by remember { mutableStateOf(0) }
        var mapSize by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val map = rememberMapState(runtime = runtime,
            baseStyle = BaseStyle.Json(styleJson),
            initialCameraPosition = CameraPosition(center = Position(-74.771, 40.692), zoom = 13.5),
            content = { TrailMapContent(if (trailsVisible) trails?.geoJson ?: EMPTY else EMPTY, track, pins, artwork + trailArtwork) { name, _ -> actions.perform("label:$name") } })
        LaunchedEffect(position, following) {
            if (following) position?.let { map.setCameraPosition(map.cameraPosition.copy(center = it)) }
        }
        LaunchedEffect(zoomRequest) {
            if (zoomRequest > 0) trails?.document?.features?.flatMap { it.points }?.let { points ->
                val camera = map.cameraForCoordinates(points.map { Position(it.longitude, it.latitude) },
                    CameraFit(fitPadding = DpPadding(32.dp, 32.dp, 32.dp, 32.dp)))
                map.setCameraPosition(camera.copy(zoom = camera.zoom.coerceIn(2.0, 18.0)))
            }
        }
        LaunchedEffect(mapSize, map, trails, trailsVisible) {
            if (mapSize == IntSize.Zero || trails == null) return@LaunchedEffect
            repeat(120) {
                if (map.viewport != null) {
                    val bounds = with(density) { DpRect(0.dp, 0.dp, mapSize.width.toDp(), mapSize.height.toDp()) }
                    val features = map.queryRenderedFeatures(bounds, setOf("trail-lines") +
                        trailArtwork.map { "trail-points-${it.key}" })
                    val names = features.mapNotNull { it.properties?.get("name")?.toString() }.toSet()
                    if (trailsVisible && names.containsAll(listOf("Cached trail fixture", "Icon 1763", "Icon 1899", "Icon 1205", "Icon 503").map { "\"$it\"" })) {
                        actions.perform("trailRenderPass:${features.size}")
                        return@LaunchedEffect
                    }
                }
                delay(500)
            }
        }
        LaunchedEffect(mapSize, map, track) {
            if (mapSize.width == 0 || mapSize.height == 0 || (resources == null && track == EMPTY)) return@LaunchedEffect
            repeat(120) {
                if (map.viewport != null) {
                    val bounds = with(density) { DpRect(0.dp, 0.dp, mapSize.width.toDp(), mapSize.height.toDp()) }
                    val features = map.queryRenderedFeatures(bounds,
                        if (resources == null) setOf("trail-recording") else null)
                    val ready = if (resources == null) features.isNotEmpty()
                        else features.any { it.properties?.containsKey("class") == true }
                    if (ready) {
                        actions.perform(if (resources == null) "platformRenderPass:${features.size}"
                            else "offlineRenderPass:${features.size}")
                        return@LaunchedEffect
                    }
                }
                delay(500)
            }
            println("IOS_OFFLINE_RENDER_TIMEOUT")
        }
        Box(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)) {
            TrailScreen(
                status = status, trailStatus = trailStatus, recordingStatus = if (recording) "Recording" else null,
                basemaps = listOf(MapChoice("bright", if (resources == null) "Offline test canvas" else "Bright")), selectedBasemap = "bright",
                onBasemapSelected = {}, basemapIcon = { BasemapArtwork() }, zoomLabel = ((map.cameraPosition.zoom + 1) * 10).toInt().let { "${it / 10}.${it % 10}" },
                onMapCredits = { actions.perform("credits") }, trailsVisible = trailsVisible,
                mapLayers = emptyList(), onLayerToggled = {}, onMenuAction = { actions.perform(it.name) },
                map = {
                    Box(Modifier.fillMaxSize()) {
                        MaplibreMap(state = map, overlay = {}, modifier = Modifier.fillMaxSize().onSizeChanged { mapSize = it }.pointerInput(Unit) {
                            awaitPointerEventScope { while (true) {
                                pointers = awaitPointerEvent(PointerEventPass.Initial).changes.count { it.pressed }
                            } }
                        },
                            cameraConstraints = CameraConstraints { minZoom = 2.0; maxZoom = 18.0; maxPitch = 0.0 },
                            interactions = MapInteractions { camera { rotate { enabled = false }; pitch { enabled = false }; pan { onStart { if (pointers == 1) following = false } } } })
                        PositionOverlay(map, position, heading, null, Modifier.fillMaxSize())
                    }
                }, recording = recording, following = following, pinEnabled = recording && position != null,
                onRecord = { actions.perform("record") }, onReport = { actions.perform("report") },
                onPin = { actions.perform("pin") }, onPinWithText = { actions.perform("pinText") },
                onRecenter = { following = true; actions.perform("recenter") },
                reportIcon = { ReportArtwork() }, pinIcon = { PinArtwork() }, recenterIcon = { RecenterArtwork(following) }
            )
        }
    }

    private companion object { const val EMPTY = """{"type":"FeatureCollection","features":[]}""" }
}
