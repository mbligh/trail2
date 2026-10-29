package net.pocketmap.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MapChoice(val id: String, val label: String)
data class MapLayerChoice(val id: String, val label: String, val enabled: Boolean)

/** Host actions are platform-neutral; Android and iOS supply implementations. */
enum class TrailMenuAction { REFRESH_TRAILS, ZOOM_TO_TRAILS, TOGGLE_TRAILS, LEGEND, SHARE_KML, SHARE_GPX }

/** Shared Compose screen. Map contents remain a platform-native slot until validated. */
@Composable
fun TrailScreen(
    status: String,
    trailStatus: String?,
    recordingStatus: String?,
    basemaps: List<MapChoice>,
    selectedBasemap: String,
    onBasemapSelected: (String) -> Unit,
    basemapIcon: @Composable () -> Unit,
    zoomLabel: String,
    onMapCredits: () -> Unit,
    trailsVisible: Boolean,
    mapLayers: List<MapLayerChoice>,
    onLayerToggled: (String) -> Unit,
    onMenuAction: (TrailMenuAction) -> Unit,
    map: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            BasemapControl(basemaps, selectedBasemap, onBasemapSelected, basemapIcon)
            Column(Modifier.weight(1f)) {
                BasicText(status,
                    Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                    style = TextStyle(color = Color.Black, fontSize = 14.sp))
                if (trailStatus != null) BasicText(trailStatus,
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    style = TextStyle(color = Color.Black, fontSize = 14.sp))
                if (recordingStatus != null) BasicText(recordingStatus,
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    style = TextStyle(color = Color.Red, fontSize = 14.sp))
            }
            Column(
                modifier = Modifier.size(48.dp).background(Color.White, RoundedCornerShape(12.dp))
                    .clickable(onClick = onMapCredits)
                    .semantics { contentDescription = "Map attribution, licences, and zoom level" }
                    .testTag("map-credits"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText("ⓘ", Modifier.size(48.dp, 24.dp),
                    style = TextStyle(fontSize = 16.sp, color = Color.DarkGray, textAlign = TextAlign.Center))
                BasicText(zoomLabel, Modifier.size(48.dp, 24.dp),
                    style = TextStyle(fontSize = 12.sp, color = Color.DarkGray, textAlign = TextAlign.Center))
            }
            TrailMenuControl(trailsVisible, mapLayers, onLayerToggled, onMenuAction)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { map() }
    }
}

@Composable
private fun BasemapControl(
    basemaps: List<MapChoice>,
    selected: String,
    onSelected: (String) -> Unit,
    icon: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(48.dp).clickable { expanded = true }
                .semantics { contentDescription = "Choose basemap: $selected" }
                .testTag("basemap-control"),
            contentAlignment = Alignment.Center
        ) {
            // The host supplies its original artwork (Android: R.drawable.basemap_menu).
            // The shared control owns the hit target and dropdown, not its artwork.
            icon()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            basemaps.forEach { choice ->
                DropdownMenuItem(onClick = {
                    expanded = false
                    onSelected(choice.id)
                }, modifier = Modifier.testTag("basemap-${choice.id}")) {
                    Text((if (choice.id == selected) "✓  " else "    ") + choice.label)
                }
            }
        }
    }
}

@Composable
private fun TrailMenuControl(
    trailsVisible: Boolean,
    mapLayers: List<MapLayerChoice>,
    onLayerToggled: (String) -> Unit,
    onAction: (TrailMenuAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(48.dp).clickable { expanded = true; layersOpen = false }
                .semantics { contentDescription = "Menu" }.testTag("trail-menu"),
            contentAlignment = Alignment.Center
        ) { BasicText("☰", style = TextStyle(fontSize = 24.sp, color = Color.Black)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; layersOpen = false }) {
            if (layersOpen) {
                DropdownMenuItem(onClick = { layersOpen = false }) { Text("‹  Back") }
                mapLayers.forEach { layer ->
                    DropdownMenuItem(
                        onClick = { onLayerToggled(layer.id) },
                        modifier = Modifier.testTag("map-layer-${layer.id}")
                    ) {
                        Text((if (layer.enabled) "✓  " else "    ") + layer.label)
                    }
                }
            } else {
                val actions = listOf(
                    TrailMenuAction.REFRESH_TRAILS to "Refresh trails",
                    TrailMenuAction.ZOOM_TO_TRAILS to "Zoom to trails",
                    TrailMenuAction.TOGGLE_TRAILS to if (trailsVisible) "Hide trails" else "Show trails",
                    TrailMenuAction.LEGEND to "Legend",
                    TrailMenuAction.SHARE_KML to "Share recorded KML…",
                    TrailMenuAction.SHARE_GPX to "Share recorded GPX…"
                )
                actions.forEach { (action, title) ->
                    DropdownMenuItem(
                        onClick = { expanded = false; onAction(action) },
                        modifier = Modifier.testTag("menu-${action.name}")
                    ) { Text(title) }
                }
                if (mapLayers.isNotEmpty()) DropdownMenuItem(
                    onClick = { layersOpen = true }, modifier = Modifier.testTag("menu-layers")
                ) { Text("Map layers  ›") }
            }
        }
    }
}
