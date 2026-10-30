package net.pocketmap.iosprobe

import net.pocketmap.shared.TrackStatistics

/** Swift file I/O adapts the existing CSV to the exact shared calculation code. */
class IosTrackBridge {
    fun summarize(csv: String): IosTrackSummary {
        val points = csv.lineSequence().mapNotNull { line ->
            val p = line.split(',')
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val sampleTime = p.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            val sampleAltitude = p.getOrNull(3)?.toDoubleOrNull()
            object : TrackStatistics.Sample {
                override val latitude = lat
                override val longitude = lon
                override val time = sampleTime
                override val altitude = sampleAltitude
            }
        }.toList()
        val result = TrackStatistics.summarize(points)
        return IosTrackSummary(result.distanceMeters, result.elapsedSeconds, result.movingSeconds,
            result.ascentMeters, result.descentMeters)
    }
}

data class IosTrackSummary(val distanceMeters: Double, val elapsedSeconds: Long,
    val movingSeconds: Long, val ascentMeters: Double, val descentMeters: Double)
