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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.interaction.MapInteractions
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

    internal lateinit var mapState: MapState
        private set
    internal var following by mutableStateOf(true)
        private set
    internal val mapReady get() = ::mapState.isInitialized && mapState.viewport != null
    private var fix by mutableStateOf(Position(latitude = 40.692, longitude = -74.771))
    private var pressedPointers = 0
    internal var fixtureEnabled by mutableStateOf(false)
    internal var trailsVisible by mutableStateOf(true)
    internal var recordingRevision by mutableStateOf(0)
    internal var pinText by mutableStateOf("")
    internal var heading by mutableStateOf<Float?>(null)

    internal fun updateFix(position: Position) {
        fix = position
        if (following && ::mapState.isInitialized) {
            mapState.setCameraPosition(mapState.cameraPosition.copy(center = fix, bearing = 0.0, pitch = 0.0))
        }
    }

    internal fun recenter() {
        following = true
        updateFix(fix)
    }

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
        following = savedInstanceState?.getBoolean("following", true) ?: true
        fixtureEnabled = savedInstanceState?.getBoolean("fixture", false) ?: false
        trailsVisible = savedInstanceState?.getBoolean("trails", true) ?: true
        recordingRevision = savedInstanceState?.getInt("recording-revision", 0) ?: 0
        savedInstanceState?.takeIf { it.containsKey("fix-lat") }?.let {
            fix = Position(latitude = it.getDouble("fix-lat"), longitude = it.getDouble("fix-lon"))
        }
        setContent {
                // rememberMapState updates baseStyle on recomposition and saves its
                // camera. Keep the same state across style changes.
                val state = rememberMapState(
                    baseStyle = BaseStyle.Uri(OfflineProbeResources.HOST + "style-$selected.json"),
                    initialCameraPosition = CameraPosition(
                        center = Position(latitude = 40.692, longitude = -74.771),
                        zoom = 12.5
                    ),
                    content = { if (fixtureEnabled) ProbeOverlays(trailsVisible, recordingRevision) { pinText = it } }
                )
                SideEffect { mapState = state }
                Box(Modifier.fillMaxSize()) {
                    MaplibreMap(state = state,
                        cameraConstraints = CameraConstraints { minZoom = 2.0; maxZoom = 18.0; maxPitch = 0.0 },
                        interactions = MapInteractions {
                            camera {
                                rotate { enabled = false }
                                pitch { enabled = false }
                                pan { onStart { if (pressedPointers == 1) following = false } }
                            }
                        },
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    pressedPointers = event.changes.count { it.pressed }
                                }
                            }
                        })
                    if (fixtureEnabled) PositionOverlay(state, fix, heading, Modifier.fillMaxSize())
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("style", selected)
        outState.putBoolean("following", following)
        outState.putBoolean("fixture", fixtureEnabled)
        outState.putBoolean("trails", trailsVisible)
        outState.putInt("recording-revision", recordingRevision)
        outState.putDouble("fix-lat", fix.latitude)
        outState.putDouble("fix-lon", fix.longitude)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private val STYLES = listOf("bright", "earth", "sample-contours", "nj-contours", "satellite", "hybrid")
    }

}
