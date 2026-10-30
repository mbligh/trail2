package net.pocketmap.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

/** Original Android artwork, with identical viewport paths and display sizes.
 * Keep the source vector XMLs as Android regression references. */
object TrailControlArtwork {
    val basemap: ImageVector by lazy {
        ImageVector.Builder("Trail basemap", 40.dp, 40.dp, 48f, 48f).apply {
            addPath(
                pathData = PathParser().parsePathString("M10,3 C6.1,3 3,6.1 3,10 L3,38 C3,41.9 6.1,45 10,45 L38,45 C41.9,45 45,41.9 45,38 L45,10 C45,6.1 41.9,3 38,3 Z").toNodes(),
                fill = SolidColor(Color(0xffffffff)), stroke = SolidColor(Color(0xffe5e5e5)),
                strokeLineWidth = 0.8f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M9,17 L18,12 L18,34 L9,39 Z").toNodes(),
                fill = SolidColor(Color(0xff2db463)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M18,12 L28,17 L28,39 L18,34 Z").toNodes(),
                fill = SolidColor(Color(0xff4a90e2)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M28,17 L39,13 L39,34 L28,39 Z").toNodes(),
                fill = SolidColor(Color(0xff6bc36b)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M9,34 L22,20 L25,23 L12,37 Z").toNodes(),
                fill = SolidColor(Color(0xffffd666)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M29,7 C23.5,7 19,11.5 19,17 C19,24 29,35 29,35 C29,35 39,24 39,17 C39,11.5 34.5,7 29,7 Z").toNodes(),
                fill = SolidColor(Color(0xffffffff)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M29,8 C24.1,8 20,12 20,17 C20,23 29,33.4 29,33.4 C29,33.4 38,23 38,17 C38,12 33.9,8 29,8 Z").toNodes(),
                fill = SolidColor(Color(0xffef3e42)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M29,13.5 A3.5,3.5 0,1 1,28.99,13.5 Z").toNodes(),
                fill = SolidColor(Color(0xffffffff)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
        }.build()
    }
    val report: ImageVector by lazy {
        ImageVector.Builder("Trail report", 28.dp, 28.dp, 32f, 32f).apply {
            addPath(
                pathData = PathParser().parsePathString("M6,3H8V29H6Z M8,4H27L23,10L27,17H8Z").toNodes(),
                fill = SolidColor(Color(0xffe53935)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
        }.build()
    }
    val pin: ImageVector by lazy {
        ImageVector.Builder("Trail pin", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString("M12,2 C8.13,2 5,5.13 5,9 C5,14.25 12,22 12,22 C12,22 19,14.25 19,9 C19,5.13 15.87,2 12,2 Z").toNodes(),
                fill = SolidColor(Color(0xff1565c0)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
            addPath(
                pathData = PathParser().parsePathString("M12,6.5 a2.5,2.5 0,1 0,0.01,0 Z").toNodes(),
                fill = SolidColor(Color(0xffffffff)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
        }.build()
    }
    val recenter: ImageVector by lazy {
        ImageVector.Builder("Trail recenter", 28.dp, 28.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString("M12,3 L12,6 M12,18 L12,21 M3,12 L6,12 M18,12 L21,12 M12,6 A6,6 0,1 1,12,18 A6,6 0,1 1,12,6").toNodes(),
                fill = SolidColor(Color.Transparent), stroke = SolidColor(Color(0xff333333)),
                strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round
            )
            addPath(
                pathData = PathParser().parsePathString("M12,9 A3,3 0,1 1,12,15 A3,3 0,1 1,12,9").toNodes(),
                fill = SolidColor(Color(0xff333333)), stroke = null,
                strokeLineWidth = 0f, strokeLineCap = StrokeCap.Butt
            )
        }.build()
    }
}

@Composable fun BasemapArtwork(modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(TrailControlArtwork.basemap), null, modifier.size(40.dp))
}
@Composable fun ReportArtwork(modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(TrailControlArtwork.report), null, modifier.size(28.dp).testTag("report-artwork"))
}
@Composable fun PinArtwork(modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(TrailControlArtwork.pin), null, modifier.size(24.dp).testTag("pin-artwork"))
}
@Composable fun RecenterArtwork(following: Boolean, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(TrailControlArtwork.recenter), null, modifier.size(28.dp).testTag("recenter-artwork"),
        colorFilter = ColorFilter.tint(if (following) Color(0xff1565c0) else Color.DarkGray))
}
