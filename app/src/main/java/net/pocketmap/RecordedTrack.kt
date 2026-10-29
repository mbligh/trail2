package net.pocketmap

import java.io.File
import java.time.Instant
import net.pocketmap.shared.TrackStatistics

object RecordedTrack {
    data class Point(override val latitude: Double, override val longitude: Double, override val time: Long,
                     override val altitude: Double?, val accuracy: Float? = null) : TrackStatistics.Sample
    fun read(file: File): List<Point> = if (!file.exists()) emptyList() else file.useLines { lines ->
        lines.mapNotNull { line -> runCatching {
            val p = line.split(',')
            Point(p[0].toDouble(), p[1].toDouble(), p[2].toLong(), p.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDouble(), p.getOrNull(4)?.takeIf { it.isNotBlank() }?.toFloat())
        }.getOrNull() }.toList()
    }

    /** Calculated from saved GPS fixes; elapsed is first-to-last fix, not service uptime. */
    data class Summary(
        val distanceMeters: Double, val elapsedSeconds: Long, val movingSeconds: Long,
        val ascentMeters: Double, val descentMeters: Double
    )

    fun summarize(points: List<Point>): Summary {
        val result = TrackStatistics.summarize(points)
        return Summary(result.distanceMeters, result.elapsedSeconds, result.movingSeconds,
            result.ascentMeters, result.descentMeters)
    }

    private fun duration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) "%dh %02dm %02ds".format(java.util.Locale.US, hours, minutes, secs)
               else "%dm %02ds".format(java.util.Locale.US, minutes, secs)
    }

    /** Human-readable fallback for importers that ignore custom GPX/KML properties. */
    fun description(summary: Summary): String = java.lang.String.format(java.util.Locale.US,
        "Distance: %.2f km; Elapsed: %s; Moving: %s; Ascent: %.0f m; Descent: %.0f m",
        summary.distanceMeters / 1000.0, duration(summary.elapsedSeconds),
        duration(summary.movingSeconds), summary.ascentMeters, summary.descentMeters)

    fun displaySummary(summary: Summary, imperial: Boolean): String {
        val units = if (imperial) "mi" else "km"
        val distance = summary.distanceMeters / (if (imperial) 1609.344 else 1000.0)
        val height = if (imperial) 3.280839895 else 1.0
        val heightUnit = if (imperial) "ft" else "m"
        return java.lang.String.format(java.util.Locale.US,
            "Distance: %.2f %s\nElapsed time: %s\nMoving time (estimated): %s\nAscent: %.0f %s\nDescent: %.0f %s",
            distance, units, duration(summary.elapsedSeconds), duration(summary.movingSeconds),
            summary.ascentMeters * height, heightUnit, summary.descentMeters * height, heightUnit)
    }

    fun gpx(name: String, points: List<Point>): String = buildString {
        val stats = summarize(points)
        append("""<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="Trail" xmlns="http://www.topografix.com/GPX/1/1" xmlns:trail="https://mbligh.org/trail"><trk><name>""")
        append(name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        append("</name><desc>")
        append(description(stats))
        append("</desc><extensions>")
        append("<trail:distanceMeters>${stats.distanceMeters}</trail:distanceMeters>")
        append("<trail:elapsedSeconds>${stats.elapsedSeconds}</trail:elapsedSeconds>")
        append("<trail:movingSeconds>${stats.movingSeconds}</trail:movingSeconds>")
        append("<trail:ascentMeters>${stats.ascentMeters}</trail:ascentMeters>")
        append("<trail:descentMeters>${stats.descentMeters}</trail:descentMeters>")
        append("</extensions><trkseg>\n")
        points.forEachIndexed { index, p ->
            if (index > 0 && p.time - points[index - 1].time > 90_000) append("</trkseg><trkseg>\n")
            append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">")
            p.altitude?.let { append("<ele>$it</ele>") }
            append("<time>${Instant.ofEpochMilli(p.time)}</time></trkpt>\n")
        }
        append("</trkseg></trk></gpx>\n")
    }
    fun kml(name: String, points: List<Point>): String = buildString {
        val stats = summarize(points)
        fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>${xml(name)}</name>\n")
        append("""<description>${xml(description(stats))}</description>""")
        append("<ExtendedData>")
        append("""<Data name="distanceMeters"><value>${stats.distanceMeters}</value></Data>""")
        append("""<Data name="elapsedSeconds"><value>${stats.elapsedSeconds}</value></Data>""")
        append("""<Data name="movingSeconds"><value>${stats.movingSeconds}</value></Data>""")
        append("""<Data name="ascentMeters"><value>${stats.ascentMeters}</value></Data>""")
        append("""<Data name="descentMeters"><value>${stats.descentMeters}</value></Data>""")
        append("</ExtendedData>")
        append("<Style id=\"good\"><LineStyle><color>ff00aa00</color><width>6</width></LineStyle></Style>\n")
        append("<Style id=\"moderate\"><LineStyle><color>ff00ffff</color><width>6</width></LineStyle></Style>\n")
        append("<Style id=\"poor\"><LineStyle><color>ff0000ff</color><width>6</width></LineStyle></Style>\n")
        append("<Style id=\"unknown\"><LineStyle><color>ffff6515</color><width>6</width></LineStyle></Style>\n")
        data class Edge(val a: Point, val b: Point, val style: String)
        val edges = points.zipWithNext().mapNotNull { (a, b) ->
            if (b.time - a.time > 90_000) null else {
                val worst = listOfNotNull(a.accuracy, b.accuracy).maxOrNull()
                val style = when {
                    a.accuracy == null || b.accuracy == null -> "unknown"
                    worst!! < 5f -> "good"
                    worst <= 15f -> "moderate"
                    else -> "poor"
                }
                Edge(a, b, style)
            }
        }
        var i = 0
        while (i < edges.size) {
            val first = edges[i]
            val segment = mutableListOf(first.a, first.b)
            var j = i + 1
            while (j < edges.size && edges[j].style == first.style && edges[j].a == segment.last()) {
                segment.add(edges[j].b)
                j++
            }
            val label = when (first.style) {
                "good" -> "Good accuracy (&lt; 5 m)"
                "moderate" -> "Moderate accuracy (5–15 m)"
                "poor" -> "Poor accuracy (&gt; 15 m)"
                else -> "Accuracy unavailable"
            }
            append("<Placemark><name>$label</name><styleUrl>#${first.style}</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
            segment.forEach { point -> append("${point.longitude},${point.latitude},${point.altitude ?: 0.0}\n") }
            append("</coordinates></LineString></Placemark>\n")
            i = j
        }
        append("</Document></kml>\n")
    }

}
