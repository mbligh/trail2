package net.pocketmap

import java.io.File
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

    private fun decimal(value: Double, digits: Int) = java.lang.String.format(java.util.Locale.US, "%.$digits" + "f", value)
    fun description(summary: Summary): String = net.pocketmap.shared.recording.TrackSummaryText.description(exportStats(summary), ::decimal)
    fun displaySummary(summary: Summary, imperial: Boolean): String =
        net.pocketmap.shared.recording.TrackSummaryText.display(exportStats(summary), imperial, ::decimal)

    internal fun exportFixes(points: List<Point>) = points.map {
        net.pocketmap.shared.recording.RecordingExportXml.Fix(it.latitude, it.longitude, it.time, it.altitude, it.accuracy?.toDouble())
    }
    private fun exportStats(s: Summary) = TrackStatistics.Summary(s.distanceMeters, s.elapsedSeconds, s.movingSeconds, s.ascentMeters, s.descentMeters)
    fun gpx(name: String, points: List<Point>): String {
        val stats = summarize(points)
        return net.pocketmap.shared.recording.RecordingExportXml.gpx(name, exportFixes(points), exportStats(stats), description(stats))
    }
    fun kml(name: String, points: List<Point>): String {
        val stats = summarize(points)
        return net.pocketmap.shared.recording.RecordingExportXml.kml(name, exportFixes(points), exportStats(stats), description(stats))
    }
}
