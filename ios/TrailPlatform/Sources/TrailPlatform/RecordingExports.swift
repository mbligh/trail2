import Foundation

/// Calculated by the shared Kotlin TrackStatistics implementation, not by a
/// separate iOS distance/elevation algorithm. Swift owns file/XML presentation.
public struct TrackExportStatistics {
    public let distanceMeters: Double
    public let elapsedSeconds: Int64
    public let movingSeconds: Int64
    public let ascentMeters: Double
    public let descentMeters: Double

    public init(distanceMeters: Double, elapsedSeconds: Int64, movingSeconds: Int64,
                ascentMeters: Double, descentMeters: Double) {
        self.distanceMeters = distanceMeters; self.elapsedSeconds = elapsedSeconds
        self.movingSeconds = movingSeconds; self.ascentMeters = ascentMeters; self.descentMeters = descentMeters
    }

    public var description: String {
        String(format: "Distance: %.2f km; Elapsed: %@; Moving: %@; Ascent: %.0f m; Descent: %.0f m",
               locale: Locale(identifier: "en_US_POSIX"), distanceMeters / 1000,
               Self.duration(elapsedSeconds), Self.duration(movingSeconds), ascentMeters, descentMeters)
    }

    public var displaySummary: String {
        String(format: "Distance: %.2f mi\nElapsed time: %@\nMoving time (estimated): %@\nAscent: %.0f ft\nDescent: %.0f ft",
               locale: Locale(identifier: "en_US_POSIX"), distanceMeters / 1609.344,
               Self.duration(elapsedSeconds), Self.duration(movingSeconds), ascentMeters * 3.280839895,
               descentMeters * 3.280839895)
    }

    private static func duration(_ seconds: Int64) -> String {
        let minutes = seconds / 60 % 60
        if seconds >= 3600 { return String(format: "%lldh %02lldm %02llds", seconds / 3600, minutes, seconds % 60) }
        return String(format: "%lldm %02llds", minutes, seconds % 60)
    }
}

public enum RecordingExports {
    private static func xml(_ value: String) -> String {
        let clean = value.unicodeScalars.filter {
            $0.value == 9 || $0.value == 10 || $0.value == 13 || (0x20...0xd7ff).contains($0.value)
                || (0xe000...0xfffd).contains($0.value) || (0x10000...0x10ffff).contains($0.value)
        }
        return String(String.UnicodeScalarView(clean)).replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }

    private static func iso(_ time: Int64) -> String {
        ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(time) / 1000))
    }

    public static func gpx(name: String, points: [RecordedFix], pins: [RecordedPin], summary: TrackExportStatistics) -> String {
        var result = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"Trail\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:trail=\"https://mbligh.org/trail\">"
        for pin in pins {
            result += "<wpt lat=\"\(pin.fix.latitude)\" lon=\"\(pin.fix.longitude)\">"
            if let altitude = pin.fix.altitude { result += "<ele>\(altitude)</ele>" }
            result += "<time>\(iso(pin.fix.time))</time><name>Pin</name><desc>\(xml(pin.text))</desc><sym>Pin, Blue</sym></wpt>"
        }
        result += "<trk><name>\(xml(name))</name><desc>\(xml(summary.description))</desc><extensions>"
        for (key, value) in values(summary) { result += "<trail:\(key)>\(value)</trail:\(key)>" }
        result += "</extensions><trkseg>"
        for (index, point) in points.enumerated() {
            if index > 0 && point.time - points[index - 1].time > 90_000 { result += "</trkseg><trkseg>" }
            result += "<trkpt lat=\"\(point.latitude)\" lon=\"\(point.longitude)\">"
            if let altitude = point.altitude { result += "<ele>\(altitude)</ele>" }
            result += "<time>\(iso(point.time))</time></trkpt>"
        }
        return result + "</trkseg></trk></gpx>\n"
    }

    public static func kml(name: String, points: [RecordedFix], pins: [RecordedPin], summary: TrackExportStatistics) -> String {
        var result = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>\(xml(name))</name><description>\(xml(summary.description))</description><ExtendedData>"
        for (key, value) in values(summary) { result += "<Data name=\"\(key)\"><value>\(value)</value></Data>" }
        result += "</ExtendedData>"
        for (id, color) in [("good", "ff00aa00"), ("moderate", "ff00ffff"), ("poor", "ff0000ff"), ("unknown", "ffff6515")] {
            result += "<Style id=\"\(id)\"><LineStyle><color>\(color)</color><width>6</width></LineStyle></Style>"
        }
        // Every adjacent pair retains its accuracy class and GPS gaps remain gaps.
        for (a, b) in zip(points, points.dropFirst()) where b.time - a.time <= 90_000 && b.time > a.time {
            let style: String
            if let x = a.accuracy, let y = b.accuracy { style = max(x, y) < 5 ? "good" : max(x, y) <= 15 ? "moderate" : "poor" }
            else { style = "unknown" }
            result += "<Placemark><styleUrl>#\(style)</styleUrl><LineString><tessellate>1</tessellate><coordinates>\(a.longitude),\(a.latitude),\(a.altitude ?? 0) \(b.longitude),\(b.latitude),\(b.altitude ?? 0)</coordinates></LineString></Placemark>"
        }
        for pin in pins {
            result += "<Placemark><name>Pin</name><description>\(xml(pin.text))</description><TimeStamp><when>\(iso(pin.fix.time))</when></TimeStamp><Point><coordinates>\(pin.fix.longitude),\(pin.fix.latitude),\(pin.fix.altitude ?? 0)</coordinates></Point></Placemark>"
        }
        return result + "</Document></kml>\n"
    }

    private static func values(_ summary: TrackExportStatistics) -> [(String, String)] {
        [("distanceMeters", String(summary.distanceMeters)), ("elapsedSeconds", String(summary.elapsedSeconds)),
         ("movingSeconds", String(summary.movingSeconds)), ("ascentMeters", String(summary.ascentMeters)),
         ("descentMeters", String(summary.descentMeters))]
    }
}
