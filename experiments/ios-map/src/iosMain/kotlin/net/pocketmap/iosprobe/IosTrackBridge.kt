package net.pocketmap.iosprobe

import net.pocketmap.shared.TrackStatistics
import net.pocketmap.shared.recording.RecordingMapData

/** Swift file I/O adapts the existing CSV to the exact shared calculation code. */
class IosTrackBridge {
    fun recording(points: List<IosRecordedPoint>, pins: List<IosRecordedPin>): IosRecordingGeometry =
        IosRecordingGeometry(RecordingMapData.track(points.map { RecordingMapData.Point(it.latitude, it.longitude, it.time) }),
            RecordingMapData.pins(pins.map { RecordingMapData.Pin(it.latitude, it.longitude, it.text) }))

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

/** Swift adapts already-validated native file records; no second CSV reader. */
data class IosRecordedPoint(val latitude: Double, val longitude: Double, val time: Long)
data class IosRecordedPin(val latitude: Double, val longitude: Double, val text: String)
data class IosRecordingGeometry(val track: String, val pins: String)
