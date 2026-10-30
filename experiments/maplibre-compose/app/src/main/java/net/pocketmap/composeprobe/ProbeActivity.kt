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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Not a Trail2 release: a real, bundled, entirely offline MapLibre Compose
 * rendering experiment using the same Tewksbury MBTiles/bright style/fonts.
 */
class ProbeActivity : ComponentActivity() {
    internal var selected by mutableStateOf("bright")
        private set

    internal val offlineResources get() =
        (application as ProbeApplication).offlineResources
    internal fun showStyle(style: String) {
        require(style in STYLES)
        selected = style
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offlineResources.allowRemoteNetwork = !intent.getBooleanExtra("offline-only", false)
        (savedInstanceState?.getString("style") ?: intent.getStringExtra("style"))
            ?.takeIf { it in STYLES }?.let { selected = it }
        setContent {
            // Recreate the map state on style changes: changing an initial
            // argument to rememberMapState alone does not switch the style.
            key(selected) {
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
                        selected + " • tap to switch (sample contours are synthetic)",
                        modifier = Modifier.align(Alignment.TopCenter)
                            .clickable {
                                selected = STYLES[(STYLES.indexOf(selected) + 1) % STYLES.size]
                            }
                            .padding(12.dp)
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("style", selected)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private val STYLES = listOf("bright", "earth", "sample-contours", "nj-contours")
    }

}
