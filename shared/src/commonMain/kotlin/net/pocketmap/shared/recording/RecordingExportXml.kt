package net.pocketmap.shared.recording

import kotlin.time.Instant
import net.pocketmap.shared.TrackStatistics

/** Platform-independent XML; callers own files, sharing and localized presentation. */
object RecordingExportXml {
    data class Fix(override val latitude: Double, override val longitude: Double,
        override val time: Long, override val altitude: Double?, val accuracy: Double?) : TrackStatistics.Sample
    data class Pin(val latitude: Double, val longitude: Double, val time: Long,
        val altitude: Double?, val text: String)

    fun gpx(name: String, points: List<Fix>, stats: TrackStatistics.Summary, description: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="Trail" xmlns="http://www.topografix.com/GPX/1/1" xmlns:trail="https://mbligh.org/trail"><trk><name>""")
        append(xml(name))
        append("</name><desc>")
        append(xml(description))
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
            append("<time>${Instant.fromEpochMilliseconds(p.time)}</time></trkpt>\n")
        }
        append("</trkseg></trk></gpx>\n")
    }
    fun kml(name: String, points: List<Fix>, stats: TrackStatistics.Summary, description: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>${xml(name)}</name>\n")
        append("""<description>${xml(description)}</description>""")
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
        data class Edge(val a: Fix, val b: Fix, val style: String)
        val edges = points.zipWithNext().mapNotNull { (a, b) ->
            if (b.time - a.time > 90_000) null else {
                val worst = listOfNotNull(a.accuracy, b.accuracy).maxOrNull()
                val style = when {
                    a.accuracy == null || b.accuracy == null -> "unknown"
                    worst!! < 5.0 -> "good"
                    worst <= 15.0 -> "moderate"
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

    fun addToKml(kml: String, pins: List<Pin>): String {
        if (pins.isEmpty()) return kml
        val style = """
            <Style id="recorded-pin">
              <IconStyle>
                <scale>1.1</scale>
                <Icon><href>https://maps.google.com/mapfiles/kml/pushpin/blue-pushpin.png</href></Icon>
              </IconStyle>
            </Style>
        """.trimIndent()
        val placemarks = pins.joinToString("\n") { pin ->
            val altitude = pin.altitude ?: 0.0
            """
            <Placemark>
              <name>Pin</name>
              ${if (pin.text.isNotEmpty()) "<description>${xml(pin.text)}</description>" else ""}
              <styleUrl>#recorded-pin</styleUrl>
              <TimeStamp><when>${Instant.fromEpochMilliseconds(pin.time)}</when></TimeStamp>
              <Point><coordinates>${pin.longitude},${pin.latitude},$altitude</coordinates></Point>
            </Placemark>
            """.trimIndent()
        }
        val insertion = "\n$style\n$placemarks\n"
        return if (kml.contains("</Document>")) {
            kml.replaceFirst("</Document>", insertion + "</Document>")
        } else kml
    }

    fun addToGpx(gpx: String, pins: List<Pin>): String {
        if (pins.isEmpty()) return gpx
        val waypoints = pins.joinToString("\n") { pin ->
            buildString {
                append("""<wpt lat="${pin.latitude}" lon="${pin.longitude}">""")
                pin.altitude?.let { append("<ele>$it</ele>") }
                append("<time>${Instant.fromEpochMilliseconds(pin.time)}</time>")
                append("<name>Pin</name>")
                if (pin.text.isNotEmpty()) append("<desc>${xml(pin.text)}</desc>")
                append("<sym>Pin, Blue</sym></wpt>")
            }
        }
        return if (gpx.contains("<trk>")) {
            gpx.replaceFirst("<trk>", waypoints + "\n<trk>")
        } else gpx
    }

    /** XML 1.0 text, retaining valid UTF-16 pairs and dropping illegal controls/surrogates. */
    private fun xml(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            when {
                c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    append(c); append(text[index++])
                }
                c.code == 9 || c.code == 10 || c.code == 13 || c.code in 0x20..0xd7ff || c.code in 0xe000..0xfffd -> append(c)
            }
        }
    }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
