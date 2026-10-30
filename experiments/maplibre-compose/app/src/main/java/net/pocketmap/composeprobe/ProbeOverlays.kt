package net.pocketmap.composeprobe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position

/** Fixture data only: exercises the production layer conventions without recording GPS. */
internal object OverlayFixture {
    const val TRAIL = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Styled trail"},"geometry":{"type":"LineString","coordinates":[[-74.779,40.688],[-74.764,40.696]]}}]}"""
    const val AREA = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Area with a hole"},"geometry":{"type":"Polygon","coordinates":[[[-74.781,40.684],[-74.761,40.684],[-74.761,40.700],[-74.781,40.700],[-74.781,40.684]],[[-74.773,40.690],[-74.773,40.694],[-74.769,40.694],[-74.769,40.690],[-74.773,40.690]]]}}]}"""
    const val PIN = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Pin annotation survives style reload"},"geometry":{"type":"Point","coordinates":[-74.771,40.692]}}]}"""
    fun recording(revision: Int) = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"Recorded trail $revision"},"geometry":{"type":"LineString","coordinates":[[-74.777,40.697],[-74.770,40.687],[-74.765,40.69]]}}]}"""
}

@Composable
@MaplibreComposable
internal fun ProbeOverlays(trailsVisible: Boolean, revision: Int, onPin: (String) -> Unit) {
    val trails = rememberGeoJsonSource(GeoJsonData.JsonString(OverlayFixture.TRAIL))
    val area = rememberGeoJsonSource(GeoJsonData.JsonString(OverlayFixture.AREA))
    val track = rememberGeoJsonSource(GeoJsonData.JsonString(OverlayFixture.recording(revision)))
    val pins = rememberGeoJsonSource(GeoJsonData.JsonString(OverlayFixture.PIN))
    Layer("trail-polygon-fill", "fill", source = area) {
        layout("visibility", JsonPrimitive(if (trailsVisible) "visible" else "none"))
        paint("fill-color", JsonPrimitive("#008000")); paint("fill-opacity", JsonPrimitive(0.2))
    }
    LineLayer("trail-lines", trails, visible = trailsVisible, color = const(Color(0xff008000)), width = const(3.dp))
    LineLayer("trail-recording", track, color = const(Color(0xff1565c0)), width = const(4.dp))
    SymbolLayer("trail-recording-pins", pins,
        iconImage = image(painterResource(R.drawable.ic_track_pin), size = DpSize(24.dp, 24.dp)),
        iconAnchor = const(SymbolAnchor.Bottom), iconAllowOverlap = const(true), hitPadding = 12.dp,
        onClick = { features ->
            features.firstOrNull()?.properties?.get("name")?.jsonPrimitive?.content?.let(onPin)
            ClickResult.Consume
        })
}

@Composable
internal fun PositionOverlay(state: MapState, position: Position, heading: Float?, modifier: Modifier) {
    Canvas(modifier) {
        // Subscribe to rendered viewport changes, so dragging moves the overlay too.
        if (state.viewport == null) return@Canvas
        val screen = state.screenLocationFromPosition(position) ?: return@Canvas
        val center = Offset(screen.x.toPx(), screen.y.toPx())
        if (heading == null) {
            drawCircle(Color.White, 10.dp.toPx(), center)
            drawCircle(Color(0xff1565c0), 7.dp.toPx(), center)
        } else rotate(heading, center) {
            val triangle = Path().apply {
                moveTo(center.x, center.y - 15.dp.toPx())
                lineTo(center.x + 10.dp.toPx(), center.y + 10.dp.toPx())
                lineTo(center.x - 10.dp.toPx(), center.y + 10.dp.toPx()); close()
            }
            drawPath(triangle, Color.White, style = Stroke(4.dp.toPx()))
            drawPath(triangle, Color(0xff1565c0))
        }
    }
}
