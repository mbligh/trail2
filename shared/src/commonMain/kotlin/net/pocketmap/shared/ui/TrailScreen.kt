package net.pocketmap.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared map screen layout; platform-native views are supplied in the four slots. */
@Composable
fun TrailScreen(
    status: String,
    trailStatus: String?,
    recordingStatus: String?,
    basemap: @Composable () -> Unit,
    info: @Composable () -> Unit,
    menu: @Composable () -> Unit,
    map: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp)) { basemap() }
            Column(Modifier.weight(1f)) {
                BasicText(status, Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                    style = TextStyle(color = Color.Black, fontSize = 14.sp))
                if (trailStatus != null) BasicText(trailStatus,
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    style = TextStyle(color = Color.Black, fontSize = 14.sp))
                if (recordingStatus != null) BasicText(recordingStatus,
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    style = TextStyle(color = Color.Red, fontSize = 14.sp))
            }
            Box(Modifier.size(48.dp)) { info() }
            Box(Modifier.size(48.dp)) { menu() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { map() }
    }
}
