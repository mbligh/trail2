import Foundation

/// Local-time names for sharing; the internal recording session ID stays a UUID.
public enum RecordingFilename {
    public static func name(for date: Date, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "'Trail_'yyyy-MM-dd_HH-mm-ss"
        return formatter.string(from: date)
    }
}
