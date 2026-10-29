package net.pocketmap.composeprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Not a Trail2 release: a real, bundled, entirely offline MapLibre Compose
 * rendering experiment using the same Tewksbury MBTiles/bright style/fonts.
 */
class ProbeActivity : ComponentActivity() {
    private lateinit var offline: OfflineProbeResources
    private var selected by mutableStateOf("bright")

    internal val offlineResources get() = offline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offline = OfflineProbeResources(applicationContext)
        val provider = MapResourceProvider(
            accepts = { offline.accepts(it.url) },
            load = { request ->
                val data = offline.load(request.url)
                if (data == null) MapResourceLoad.NoContent()
                else MapResourceLoad.Bytes(data)
            }
        )
        DefaultMapRuntime.configure { resourceProvider = provider }
        setContent {
            val state = rememberMapState(
                baseStyle = BaseStyle.Uri(OfflineProbeResources.HOST + "style-$selected.json"),
                initialCameraPosition = CameraPosition(
                    center = Position(latitude = 40.692, longitude = -74.771),
                    zoom = 12.5
                )
            )
            Box(Modifier.fillMaxSize()) {
                MaplibreMap(state = state, modifier = Modifier.fillMaxSize())
                BasicText(
                    if (selected == "bright") "Bright (offline) • tap for Natural Earth"
                    else "Natural Earth (offline) • tap for Bright",
                    modifier = Modifier.align(Alignment.TopCenter)
                        .clickable { selected = if (selected == "bright") "earth" else "bright" }
                        .padding(12.dp)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Map rendering closes before the activity is destroyed; keep databases
        // alive for in-flight callbacks until this point.
        offline.close()
    }
}
