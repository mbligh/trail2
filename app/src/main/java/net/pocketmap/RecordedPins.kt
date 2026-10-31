package net.pocketmap

import android.location.Location
import java.io.File
import java.util.Base64

/**
 * User-created point markers associated with a recorded track.
 *
 * Pins live in a sidecar file so the existing CSV track format and line geometry remain
 * unchanged. The sidecar name is derived from the track CSV name and is ignored by the
 * recording picker, which only lists .csv files.
 */
object RecordedPins {
    data class Pin(
        val latitude: Double,
        val longitude: Double,
        val time: Long,
        val altitude: Double?,
        val accuracy: Float?,
        val text: String = ""
    )

    private fun sidecar(track: File) =
        File(track.parentFile, track.nameWithoutExtension + ".pins")

    fun append(track: File, location: Location, text: String = "") {
        sidecar(track).appendText(
            buildString {
                append(location.latitude)
                append(',')
                append(location.longitude)
                append(',')
                append(location.time)
                append(',')
                if (location.hasAltitude()) append(location.altitude)
                append(',')
                if (location.hasAccuracy()) append(location.accuracy)
                // An optional sixth field keeps old pins readable and arbitrary notes on one line.
                if (text.isNotEmpty()) {
                    append(',')
                    append(Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
                }
                append('\n')
            }
        )
    }

    fun read(track: File): List<Pin> {
        val file = sidecar(track)
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(',', limit = 6)
                if (parts.size < 3) return@mapNotNull null
                val latitude = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val longitude = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val time = parts[2].toLongOrNull() ?: return@mapNotNull null
                if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null
                Pin(
                    latitude,
                    longitude,
                    time,
                    parts.getOrNull(3)?.toDoubleOrNull(),
                    parts.getOrNull(4)?.toFloatOrNull(),
                    parts.getOrNull(5)?.let { encoded ->
                        runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault("")
                    } ?: ""
                )
            }.toList()
        }
    }

    private fun exportPins(pins: List<Pin>) = pins.map {
        net.pocketmap.shared.recording.RecordingExportXml.Pin(it.latitude, it.longitude, it.time, it.altitude, it.text)
    }
    fun addToKml(kml: String, pins: List<Pin>): String =
        net.pocketmap.shared.recording.RecordingExportXml.addToKml(kml, exportPins(pins))
    fun addToGpx(gpx: String, pins: List<Pin>): String =
        net.pocketmap.shared.recording.RecordingExportXml.addToGpx(gpx, exportPins(pins))
}
