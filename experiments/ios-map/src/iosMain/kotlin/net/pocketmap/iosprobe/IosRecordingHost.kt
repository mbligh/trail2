package net.pocketmap.iosprobe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
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

/** iOS integration host. This deliberately has a blank offline canvas until the
 * native tile-storage adapter is integrated; it is not a production basemap. */
class IosRecordingHost(private val actions: IosPlatformActions,
    private val resources: IosMapResourceReader?, private val styleJson: String) {
    private var status by mutableStateOf("Location not started")
    private var recording by mutableStateOf(false)
    private var position by mutableStateOf<Position?>(null)
    private var heading by mutableStateOf<Float?>(null)
    private var track by mutableStateOf(EMPTY)
    private var pins by mutableStateOf(EMPTY)
    private var following by mutableStateOf(true)

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
        var pointers by remember { mutableStateOf(0) }
        var mapSize by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val map = rememberMapState(runtime = runtime,
            baseStyle = BaseStyle.Json(styleJson),
            initialCameraPosition = CameraPosition(center = Position(-74.771, 40.692), zoom = 13.5),
            content = { TrailMapContent(EMPTY, track, pins, artwork) { name, _ -> actions.perform("label:$name") } })
        LaunchedEffect(position, following) {
            if (following) position?.let { map.setCameraPosition(map.cameraPosition.copy(center = it)) }
        }
        LaunchedEffect(mapSize, map) {
            if (resources == null || mapSize.width == 0 || mapSize.height == 0) return@LaunchedEffect
            repeat(120) {
                if (map.viewport != null) {
                    val bounds = with(density) { DpRect(0.dp, 0.dp, mapSize.width.toDp(), mapSize.height.toDp()) }
                    val features = map.queryRenderedFeatures(bounds, null)
                    if (features.any { it.properties?.containsKey("class") == true }) {
                        actions.perform("offlineRenderPass:${features.size}")
                        return@LaunchedEffect
                    }
                }
                delay(500)
            }
            println("IOS_OFFLINE_RENDER_TIMEOUT")
        }
        Box(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)) {
            TrailScreen(
                status = status, trailStatus = if (resources == null) "iOS integration — offline canvas" else "Tewksbury offline map", recordingStatus = if (recording) "Recording" else null,
                basemaps = listOf(MapChoice("bright", if (resources == null) "Offline test canvas" else "Bright")), selectedBasemap = "bright",
                onBasemapSelected = {}, basemapIcon = { BasemapArtwork() }, zoomLabel = ((map.cameraPosition.zoom + 1) * 10).toInt().let { "${it / 10}.${it % 10}" },
                onMapCredits = { actions.perform("credits") }, trailsVisible = true,
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
