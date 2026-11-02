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
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.pocketmap.shared.map.MapZoom
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import net.pocketmap.shared.map.MapArtwork
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import net.pocketmap.shared.map.PositionOverlay
import net.pocketmap.shared.map.TrailMapContent
import net.pocketmap.shared.ui.MapLayerChoice
import net.pocketmap.shared.ui.MapChoice
import net.pocketmap.shared.ui.BasemapArtwork
import net.pocketmap.shared.ui.ReportArtwork
import net.pocketmap.shared.ui.PinArtwork
import net.pocketmap.shared.ui.RecenterArtwork
import net.pocketmap.shared.ui.TrailLegendDialog
import net.pocketmap.shared.ui.TrailScreen
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.*
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

interface IosPlatformActions { fun perform(action: String) }
interface IosResourceCompletion { fun complete(bytes: ByteArray?) }
interface IosMapResourceReader { fun read(url: String, completion: IosResourceCompletion) }
data class IosMapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

/** iOS integration host with native storage and the common map renderer. */
class IosRecordingHost(private val actions: IosPlatformActions,
    private val resources: IosMapResourceReader?, styleJson: String) {
    private var legendShown by mutableStateOf(false)
    private var legendCheck by mutableStateOf(false)
    fun showLegend() { legendShown = true }
    private fun dismissLegend() { legendShown = false }
    fun verifyLegend() { showLegend(); legendCheck = true }
    private var styleJson by mutableStateOf(styleJson)
    private var choices by mutableStateOf(listOf(IosBasemapOption("bright", "Bright", "", "")))
    private var selectedBasemap by mutableStateOf("bright")
    private var initialCamera = IosMapCamera(40.692, -74.771, 14.5)
    private var camera = initialCamera
    private var layerOptions by mutableStateOf(emptyList<IosLayerOption>())
    private var enabledLayers by mutableStateOf(emptySet<String>())
    private var layerCheck by mutableStateOf("")
    fun setLayers(options: List<IosLayerOption>, enabled: List<String>) { layerOptions = options; enabledLayers = enabled.toSet() }
    fun verifyLayers(phase: String) { layerCheck = phase }
    private var basemapCheck by mutableStateOf("")
    fun configureMaps(choices: List<IosBasemapOption>, selected: String, camera: IosMapCamera, trailsVisible: Boolean) {
        this.choices = choices; selectedBasemap = selected; initialCamera = camera; this.camera = camera
        this.trailsVisible = trailsVisible
    }
    fun selectBasemap(name: String, style: String) { selectedBasemap = name; styleJson = style }
    fun currentCamera() = camera
    fun areTrailsVisible() = trailsVisible
    fun verifyBasemaps(phase: String) { basemapCheck = phase }
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
    private var recordingCheck by mutableStateOf(false)
    fun verifyRecording() { recordingCheck = true }
    private var trailCheck by mutableStateOf(false)
    fun verifyTrails() { trailCheck = true }
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
                val bytes = suspendCancellableCoroutine<ByteArray?> { continuation ->
                    val reader = resources
                    if (reader == null) continuation.resume(null)
                    else reader.read(request.url, object : IosResourceCompletion {
                        override fun complete(bytes: ByteArray?) { if (continuation.isActive) continuation.resume(bytes) }
                    })
                }
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
        val style = remember(styleJson) { BaseStyle.Json(styleJson) }
        val map = rememberMapState(runtime = runtime,
            baseStyle = style,
            initialCameraPosition = CameraPosition(center = Position(initialCamera.longitude, initialCamera.latitude), zoom = MapZoom.renderer(initialCamera.zoom)),
            content = { TrailMapContent(if (trailsVisible) trails?.geoJson ?: EMPTY else EMPTY, track, pins, artwork + trailArtwork) { name, _ -> actions.perform("label:$name") } })
        var styleLoads by remember { mutableStateOf(0) }
        LaunchedEffect(map) { map.events.collect { if (it == MapEvent.StyleLoaded) styleLoads++ } }
        LaunchedEffect(map) {
            snapshotFlow { map.cameraPosition }.collect {
                camera = IosMapCamera(it.center.latitude, it.center.longitude, MapZoom.displayed(it.zoom))
            }
        }
        LaunchedEffect(basemapCheck, mapSize) {
            if (basemapCheck.isEmpty() || mapSize == IntSize.Zero) return@LaunchedEffect
            val before = map.cameraPosition
            val names = if (basemapCheck == "write") choices.map { it.id } else listOf("opentopomap")
            for (name in names) {
                val needsLoad = selectedBasemap != name
                val generation = styleLoads
                actions.perform("basemap:$name")
                var ready = false
                repeat(120) {
                    if (!ready && map.viewport != null && (!needsLoad || styleLoads > generation)) {
                        val bounds = with(density) { DpRect(0.dp, 0.dp, mapSize.width.toDp(), mapSize.height.toDp()) }
                        val features = map.queryRenderedFeatures(bounds, setOf("trail-lines", "trail-recording", "trail-recording-pins"))
                        val labels = features.mapNotNull { it.properties?.get("name")?.jsonPrimitive?.content }.toSet()
                        ready = setOf("Basemap trail", "Recorded trail", "Pin\nBasemap pin").all { it in labels }
                    }
                    if (!ready) delay(250)
                }
                check(ready) { "Overlays did not survive basemap $name" }
                val after = map.cameraPosition
                check(kotlin.math.abs(before.zoom - after.zoom) < 0.001 &&
                    kotlin.math.abs(before.center.latitude - after.center.latitude) < 0.000001 &&
                    kotlin.math.abs(before.center.longitude - after.center.longitude) < 0.000001) { "Basemap changed camera" }
                actions.perform("basemapRenderPass:$name")
            }
            actions.perform("basemapAllPass:$basemapCheck")
        }
        LaunchedEffect(layerCheck, mapSize) {
            if (layerCheck.isEmpty() || mapSize == IntSize.Zero) return@LaunchedEffect
            val before = map.cameraPosition
            suspend fun visible(): Pair<Int, Boolean> {
                if (map.viewport == null) return 0 to false
                val bounds = with(density) { DpRect(0.dp, 0.dp, mapSize.width.toDp(), mapSize.height.toDp()) }
                val features = map.queryRenderedFeatures(bounds)
                val names = features.mapNotNull { it.properties?.get("name")?.jsonPrimitive?.content }.toSet()
                return features.count { it.properties?.containsKey("elevation_ft") == true } to
                    setOf("Basemap trail", "Recorded trail", "Pin\nBasemap pin").all { it in names }
            }
            suspend fun awaitState(contours: Boolean, generation: Int = -1) {
                repeat(160) {
                    val (count, overlays) = visible()
                    if (styleLoads > generation && overlays && (count > 0) == contours) return
                    delay(250)
                }
                error("Contour/overlay state did not render: $contours")
            }
            if (layerCheck == "write") {
                check("contours" !in enabledLayers)
                awaitState(false)
                actions.perform("layerDisabledPass")
                var generation = styleLoads
                actions.perform("layer:contours"); awaitState(true, generation)
                generation = styleLoads
                actions.perform("layer:contours"); awaitState(false, generation)
                delay(500); check(visible().first == 0)
                generation = styleLoads
                actions.perform("layer:contours"); awaitState(true, generation)
                for (name in listOf("hybrid", "bright")) {
                    generation = styleLoads
                    actions.perform("basemap:$name"); awaitState(true, generation)
                }
            } else {
                check("contours" in enabledLayers)
                awaitState(true)
            }
            val after = map.cameraPosition
            check(kotlin.math.abs(before.zoom - after.zoom) < 0.001 &&
                kotlin.math.abs(before.center.latitude - after.center.latitude) < 0.000001 &&
                kotlin.math.abs(before.center.longitude - after.center.longitude) < 0.000001) { "Layer switch moved camera" }
            actions.perform("layerAllPass:$layerCheck")
        }
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
        LaunchedEffect(mapSize, map, trails, trailCheck) {
            if (mapSize == IntSize.Zero || trails == null || !trailCheck) return@LaunchedEffect
            suspend fun names(): Set<String> {
                if (map.viewport == null) return emptySet()
                val bounds = with(density) { DpRect(0.dp, 0.dp, mapSize.width.toDp(), mapSize.height.toDp()) }
                return map.queryRenderedFeatures(bounds, setOf("trail-lines") +
                    trailArtwork.map { "trail-points-${it.key}" })
                    .mapNotNull { it.properties?.get("name")?.toString() }.toSet()
            }
            val expected = listOf("Cached trail fixture", "Icon 1763", "Icon 1899", "Icon 1205", "Icon 503").map { "\"$it\"" }
            suspend fun awaitVisible(): Boolean {
                repeat(120) {
                    if (names().containsAll(expected)) return true
                    delay(500)
                }
                return false
            }
            check(awaitVisible()) { "Imported trail and icons did not render" }
            actions.perform("TOGGLE_TRAILS")
            delay(1000)
            check(names().isEmpty()) { "Hide trails left imported features visible" }
            delay(500)
            check(names().isEmpty()) { "Hide trails did not remain hidden" }
            actions.perform("TOGGLE_TRAILS")
            actions.perform("ZOOM_TO_TRAILS")
            check(awaitVisible()) { "Show/zoom trails did not restore imported features" }
            actions.perform("trailRenderPass:${expected.size}:hide-show-zoom")
        }
        LaunchedEffect(mapSize, map, track, recordingCheck) {
            if (mapSize.width == 0 || mapSize.height == 0 || (resources == null && (track == EMPTY || !recordingCheck))) return@LaunchedEffect
            repeat(120) {
                if (map.viewport != null) {
                    val bounds = with(density) { DpRect(0.dp, 0.dp, mapSize.width.toDp(), mapSize.height.toDp()) }
                    val features = map.queryRenderedFeatures(bounds,
                        if (resources == null) setOf("trail-recording", "trail-recording-pins") else null)
                    suspend fun recordingAt(longitude: Double, latitude: Double): Boolean {
                        val screen = map.screenLocationFromPosition(Position(longitude, latitude)) ?: return false
                        return map.queryRenderedFeatures(DpRect(screen.x - 2.dp, screen.y - 2.dp,
                            screen.x + 2.dp, screen.y + 2.dp), setOf("trail-recording")).isNotEmpty()
                    }
                    val ready = if (resources == null) {
                        val names = features.mapNotNull { it.properties?.get("name")?.jsonPrimitive?.content }
                        "Recorded trail" in names && "Pin\nGate, broken\n🐴 <&>" in names &&
                            recordingAt(-74.7755, 40.690) && recordingAt(-74.7665, 40.694) &&
                            !recordingAt(-74.771, 40.692)
                    } else features.any { it.properties?.containsKey("class") == true }
                    if (ready) {
                        actions.perform(if (resources == null) "platformRenderPass:segmented-recording-and-pin"
                            else "offlineRenderPass:${features.size}")
                        return@LaunchedEffect
                    }
                }
                delay(500)
            }
            println("IOS_OFFLINE_RENDER_TIMEOUT")
        }
        LaunchedEffect(legendCheck) {
            if (legendCheck) {
                delay(15_000)
                dismissLegend()
                actions.perform("legendDismissPass")
            }
        }
        Box(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)) {
            TrailScreen(
                status = status, trailStatus = trailStatus, recordingStatus = if (recording) "Recording" else null,
                basemaps = choices.map { MapChoice(it.id, it.label) }, selectedBasemap = selectedBasemap,
                onBasemapSelected = { actions.perform("basemap:$it") }, basemapIcon = { BasemapArtwork() }, zoomLabel = (MapZoom.displayed(map.cameraPosition.zoom) * 10).toInt().let { "${it / 10}.${it % 10}" },
                onMapCredits = { actions.perform("credits") }, trailsVisible = trailsVisible,
                mapLayers = layerOptions.map { MapLayerChoice(it.id, it.label, it.id in enabledLayers) }, onLayerToggled = { actions.perform("layer:$it") }, onMenuAction = { actions.perform(it.name) },
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
            if (legendShown) TrailLegendDialog(onDismiss = ::dismissLegend)
        }
    }

    private companion object { const val EMPTY = """{"type":"FeatureCollection","features":[]}""" }
}
