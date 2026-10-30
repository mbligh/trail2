package net.pocketmap.iosprobe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import net.pocketmap.shared.map.MapArtwork
import net.pocketmap.shared.map.PositionOverlay
import net.pocketmap.shared.map.TrailMapContent
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** A synthetic, network-free renderer probe, not the iOS Trail application. */
fun iosMapProbeViewController() = ComposeUIViewController {
    // Even accidental external resource requests cannot use the network in this probe.
    val runtime = remember { createMapRuntime {
        resourceProvider = MapResourceProvider(accepts = { true }, load = { MapResourceLoad.NoContent() })
    } }
    DisposableEffect(runtime) { onDispose { runtime.close() } }
    val artwork = remember {
        // Synthetic test artwork exercises managed images. Production Android assets stay intact.
        val bitmap = ImageBitmap(24, 24)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(Offset(12f, 12f), 11f, Paint().apply { color = Color(0xff1565c0) })
        canvas.drawCircle(Offset(12f, 12f), 4f, Paint().apply { color = Color.White })
        listOf(MapArtwork("trail-pin", bitmap, DpSize(24.dp, 24.dp)))
    }
    var label by remember { mutableStateOf("Trail iOS renderer check") }
    val state = rememberMapState(runtime = runtime,
        baseStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#ffffff"}}]}"""),
        initialCameraPosition = CameraPosition(center = Position(-74.771, 40.692), zoom = 13.5),
        content = { TrailMapContent(TRAILS, RECORDING, PINS, artwork) { name, _ -> label = name } })
    Column(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)) {
        BasicText(label, style = TextStyle(color = Color.Black, fontSize = 18.sp), modifier = Modifier.padding(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MaplibreMap(state = state, overlay = {},
                cameraConstraints = CameraConstraints { minZoom = 2.0; maxZoom = 18.0; maxPitch = 0.0 },
                interactions = MapInteractions { camera { rotate { enabled = false }; pitch { enabled = false } } },
                modifier = Modifier.fillMaxSize())
            PositionOverlay(state, Position(-74.771, 40.692), 45f, Position(-74.775, 40.689), Modifier.fillMaxSize())
        }
    }
}

private const val TRAILS = """{"type":"FeatureCollection","features":[
{"type":"Feature","properties":{"name":"Trail area","fillColor":"#008000","fillOpacity":0.5,"lineColor":"#008000","lineOpacity":1},"geometry":{"type":"Polygon","coordinates":[[[-74.778,40.687],[-74.764,40.687],[-74.764,40.697],[-74.778,40.697],[-74.778,40.687]],[[-74.773,40.690],[-74.773,40.694],[-74.769,40.694],[-74.769,40.690],[-74.773,40.690]]]}},
{"type":"Feature","properties":{"name":"Shared trail line","lineColor":"#ff0000","lineOpacity":1,"width":5},"geometry":{"type":"LineString","coordinates":[[-74.779,40.695],[-74.763,40.689]]}}
]}"""
private const val RECORDING = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Shared recording"},"geometry":{"type":"LineString","coordinates":[[-74.777,40.688],[-74.765,40.696]]}}]}"""
private const val PINS = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Shared pin label"},"geometry":{"type":"Point","coordinates":[-74.767,40.690]}}]}"""
