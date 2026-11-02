package net.pocketmap.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The original trail meanings and colors, shared by Android and iOS. */
@Composable
fun TrailLegendDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trail-legend"),
        backgroundColor = Color.White,
        contentColor = Color.Black,
        title = { Text("Legend", color = Color.Black) },
        text = {
            Column {
                listOf(
                    Color(0xffdb4436) to "Blocked",
                    Color(0xfff8971b) to "Unknown",
                    Color(0xfff4eb37) to "TTA trail",
                    Color(0xff009d57) to "Public trail"
                ).forEachIndexed { index, (color, label) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp, 5.dp).background(color).testTag("legend-swatch-$index"))
                        Text(label, Modifier.padding(start = 12.dp), color = Color.Black, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("legend-done")) { Text("Done") }
        }
    )
}
