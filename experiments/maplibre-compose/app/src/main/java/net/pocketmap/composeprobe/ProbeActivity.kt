package net.pocketmap.composeprobe

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import java.io.ByteArrayOutputStream

/**
 * Isolated toolchain/API probe. Nothing here is referenced by the real Trail2 app.
 * Uses a synthetic offline raster; production tiles will come from TileRepository.
 */
class ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = MapResourceProvider(
            accepts = { it.url.startsWith("https://trail.tiles.invalid/") },
            load = { request ->
                when {
                    request.url == "https://trail.tiles.invalid/style.json" ->
                        MapResourceLoad.Bytes(STYLE.encodeToByteArray())
                    request.url.matches(Regex("https://trail\\.tiles\\.invalid/tiles/[0-9]+/[0-9]+/[0-9]+\\.png")) ->
                        MapResourceLoad.Bytes(createTile())
                    else -> MapResourceLoad.NoContent()
                }
            }
        )
        DefaultMapRuntime.configure { resourceProvider = provider }
        setContent {
            val state = rememberMapState(
                baseStyle = BaseStyle.Uri("https://trail.tiles.invalid/style.json")
            )
            MaplibreMap(state = state, modifier = Modifier.fillMaxSize())
        }
    }

    private fun createTile(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(195, 224, 201))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    companion object {
        private val STYLE = """
            {
              "version":8,
              "sources":{"offline":{"type":"raster","tiles":["https://trail.tiles.invalid/tiles/{z}/{x}/{y}.png"],
                "tileSize":256,"minzoom":0,"maxzoom":2}},
              "layers":[{"id":"offline-raster","type":"raster","source":"offline"}]
            }
        """.trimIndent()
    }
}
