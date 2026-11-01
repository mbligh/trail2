package net.pocketmap.iosprobe

import net.pocketmap.shared.TrackStatistics
import net.pocketmap.shared.recording.TrackSummaryText
import net.pocketmap.shared.recording.RecordingMapData
import net.pocketmap.shared.recording.RecordingExportXml

/** Swift supplies validated native records to the common calculations and writers. */
interface IosDecimalFormatter { fun format(value: Double, digits: Int): String }

class IosTrackBridge {
    fun locationReport(latitude: Double, longitude: Double, formatter: IosDecimalFormatter): String =
        net.pocketmap.shared.LocationReportText.message(latitude, longitude, formatter::format)

    fun presentation(summary: IosTrackSummary, formatter: IosDecimalFormatter): IosSummaryPresentation {
        val s = TrackStatistics.Summary(summary.distanceMeters, summary.elapsedSeconds, summary.movingSeconds, summary.ascentMeters, summary.descentMeters)
        return IosSummaryPresentation(TrackSummaryText.description(s, formatter::format), TrackSummaryText.display(s, true, formatter::format))
    }

    fun recording(points: List<IosRecordedPoint>, pins: List<IosRecordedPin>): IosRecordingGeometry =
        IosRecordingGeometry(RecordingMapData.track(points.map { RecordingMapData.Point(it.latitude, it.longitude, it.time) }),
            RecordingMapData.pins(pins.map { RecordingMapData.Pin(it.latitude, it.longitude, it.text) }))

    private fun fixes(points: List<IosExportFix>) = points.map {
        RecordingExportXml.Fix(it.latitude, it.longitude, it.time, if (it.hasAltitude) it.altitude else null,
            if (it.hasAccuracy) it.accuracy else null)
    }

    @Throws(Exception::class)
    fun exportRecording(name: String, points: List<IosExportFix>, pins: List<IosExportPin>, summaryText: String): IosRecordingExports {
        val samples = fixes(points)
        val stats = TrackStatistics.summarize(samples)
        val markers = pins.map { RecordingExportXml.Pin(it.fix.latitude, it.fix.longitude, it.fix.time,
            if (it.fix.hasAltitude) it.fix.altitude else null, it.text) }
        return IosRecordingExports(
            RecordingExportXml.addToGpx(RecordingExportXml.gpx(name, samples, stats, summaryText), markers),
            RecordingExportXml.addToKml(RecordingExportXml.kml(name, samples, stats, summaryText), markers))
    }

    fun summarize(points: List<IosExportFix>): IosTrackSummary {
        val result = TrackStatistics.summarize(fixes(points))
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

/** Explicit availability flags avoid nullable numeric bridging in Swift initializers. */
data class IosExportFix(val latitude: Double, val longitude: Double, val time: Long,
    val altitude: Double, val hasAltitude: Boolean, val accuracy: Double, val hasAccuracy: Boolean)
data class IosExportPin(val fix: IosExportFix, val text: String)
data class IosRecordingExports(val gpx: String, val kml: String)

data class IosSummaryPresentation(val exportDescription: String, val displaySummary: String)
