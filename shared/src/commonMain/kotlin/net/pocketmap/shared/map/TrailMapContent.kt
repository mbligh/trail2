package net.pocketmap.shared.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position

/** Android supplies its original artwork; map layers and positioning are portable. */
data class MapArtwork(val key: String, val bitmap: ImageBitmap, val size: DpSize)

@Composable
@MaplibreComposable
fun TrailMapContent(trails: String, recording: String, pins: String, artwork: List<MapArtwork>, onFeatureClick: (String, androidx.compose.ui.unit.DpOffset) -> Unit) {
    val onClick: FeaturesClickHandler = { features ->
        val name = features.firstOrNull()?.properties?.get("name")?.jsonPrimitive?.content
        if (name.isNullOrBlank()) ClickResult.Pass else { onFeatureClick(name, screenOffset); ClickResult.Consume }
    }
    val features = rememberGeoJsonSource(GeoJsonData.JsonString(trails))
    val track = rememberGeoJsonSource(GeoJsonData.JsonString(recording))
    val savedPins = rememberGeoJsonSource(GeoJsonData.JsonString(pins))
    val layers = remember { Json.parseToJsonElement(LAYERS).jsonArray }
    for (item in layers) {
        val spec = item.jsonObject
        val id = spec.getValue("id").jsonPrimitive.content
        Layer(id, spec.getValue("type").jsonPrimitive.content,
            source = features, onClick = onClick, hitPadding = 12.dp) {
            spec["filter"]?.let { root("filter", it) }
            spec["layout"]?.jsonObject?.forEach { (k, v) -> layout(k, v) }
            spec["paint"]?.jsonObject?.forEach { (k, v) -> paint(k, v) }
        }
    }
    artwork.filter { it.key != "trail-pin" }.forEach { icon ->
        key(icon.key) {
            Layer("trail-points-${icon.key}", "symbol", source = features, onClick = onClick, hitPadding = 12.dp) {
                root("filter", Json.parseToJsonElement("""["all",["==",["geometry-type"],"Point"],["==",["get","icon"],"${icon.key}"]]"""))
                layout("icon-image", image(BitmapPainter(icon.bitmap), size = icon.size))
                layout("icon-size", Json.parseToJsonElement("""["get","scale"]"""))
                layout("icon-anchor", Json.parseToJsonElement("""["get","anchor"]"""))
                layout("icon-allow-overlap", JsonPrimitive(true))
            }
        }
    }
    Layer("trail-recording", "line", source = track, onClick = onClick, hitPadding = 12.dp) {
        layout("line-cap", JsonPrimitive("round")); layout("line-join", JsonPrimitive("round"))
        paint("line-color", JsonPrimitive("#1565c0")); paint("line-width", JsonPrimitive(4))
    }
    artwork.firstOrNull { it.key == "trail-pin" }?.let { pin ->
        Layer("trail-recording-pins", "symbol", source = savedPins, onClick = onClick, hitPadding = 12.dp) {
            layout("icon-image", image(BitmapPainter(pin.bitmap), size = pin.size))
            layout("icon-anchor", JsonPrimitive("bottom"))
            layout("icon-allow-overlap", JsonPrimitive(true))
        }
    }
}

@Composable
fun PositionOverlay(state: MapState, position: Position?, heading: Float?, connector: Position?, modifier: Modifier) {
    Canvas(modifier) {
        if (position == null || state.viewport == null) return@Canvas
        val screen = state.screenLocationFromPosition(position) ?: return@Canvas
        val center = Offset(screen.x.toPx(), screen.y.toPx())
        connector?.let { from ->
            state.screenLocationFromPosition(from)?.let { p ->
                drawLine(Color(0xff1565c0), Offset(p.x.toPx(), p.y.toPx()), center,
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        if (heading == null) {
            drawCircle(Color.White, 10.dp.toPx(), center)
            drawCircle(Color(0xff1565c0), 7.dp.toPx(), center)
        } else rotate(heading, center) {
            val triangle = Path().apply {
                moveTo(center.x, center.y - 15.dp.toPx())
                lineTo(center.x + 10.dp.toPx(), center.y + 10.dp.toPx())
                lineTo(center.x - 10.dp.toPx(), center.y + 10.dp.toPx()); close()
            }
            drawPath(triangle, Color.White, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round))
            drawPath(triangle, Color(0xff1565c0))
        }
    }
}

private const val LAYERS = """[
{"id":"trail-polygon-fill","type":"fill","filter":["==",["geometry-type"],"Polygon"],"paint":{"fill-color":["get","fillColor"],"fill-opacity":["get","fillOpacity"]}},
{"id":"trail-polygon-outline","type":"line","filter":["==",["geometry-type"],"Polygon"],"paint":{"line-color":["get","lineColor"],"line-opacity":["get","lineOpacity"],"line-width":2}},
{"id":"trail-lines","type":"line","filter":["==",["geometry-type"],"LineString"],"layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":["get","lineColor"],"line-opacity":["get","lineOpacity"],"line-width":["get","width"]}}
]"""
