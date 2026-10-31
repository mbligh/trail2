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
