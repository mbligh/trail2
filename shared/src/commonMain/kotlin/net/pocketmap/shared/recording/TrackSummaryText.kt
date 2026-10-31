package net.pocketmap.shared.recording

import net.pocketmap.shared.TrackStatistics

/** Shared labels, units and duration layout; platforms keep their decimal formatter. */
object TrackSummaryText {
    private fun duration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        fun two(value: Long) = value.toString().padStart(2, '0')
        return if (hours > 0) "${hours}h ${two(minutes)}m ${two(secs)}s" else "${minutes}m ${two(secs)}s"
    }
    fun description(s: TrackStatistics.Summary, format: (Double, Int) -> String): String =
        "Distance: ${format(s.distanceMeters / 1000.0, 2)} km; Elapsed: ${duration(s.elapsedSeconds)}; Moving: ${duration(s.movingSeconds)}; Ascent: ${format(s.ascentMeters, 0)} m; Descent: ${format(s.descentMeters, 0)} m"

    fun display(s: TrackStatistics.Summary, imperial: Boolean, format: (Double, Int) -> String): String {
        val distance = s.distanceMeters / (if (imperial) 1609.344 else 1000.0)
        val height = if (imperial) 3.280839895 else 1.0
        val units = if (imperial) "mi" else "km"
        val heightUnit = if (imperial) "ft" else "m"
        return "Distance: ${format(distance, 2)} $units\nElapsed time: ${duration(s.elapsedSeconds)}\nMoving time (estimated): ${duration(s.movingSeconds)}\nAscent: ${format(s.ascentMeters * height, 0)} $heightUnit\nDescent: ${format(s.descentMeters * height, 0)} $heightUnit"
    }
}
