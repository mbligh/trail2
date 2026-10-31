import Foundation

/// Calculated by the shared Kotlin TrackStatistics implementation, not by a
/// separate iOS distance/elevation algorithm. Swift owns localized presentation;
/// the application uses the shared Kotlin XML writers for exports.
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
