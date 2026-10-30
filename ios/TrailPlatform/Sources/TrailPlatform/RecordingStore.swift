import Foundation

public struct RecordedFix: Equatable, Codable {
    public let latitude: Double
    public let longitude: Double
    public let time: Int64 // Unix milliseconds, matching Android CSV.
    public let altitude: Double?
    public let accuracy: Double?

    public init(latitude: Double, longitude: Double, time: Int64,
                altitude: Double? = nil, accuracy: Double? = nil) {
        self.latitude = latitude; self.longitude = longitude; self.time = time
        self.altitude = altitude; self.accuracy = accuracy
    }

    public var isValid: Bool {
        latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude)
            && (-180...180).contains(longitude) && time >= 0
            && (altitude == nil || altitude!.isFinite)
            && (accuracy == nil || (accuracy!.isFinite && accuracy! >= 0))
    }

    fileprivate var csv: String {
        "\(latitude),\(longitude),\(time),\(altitude.map(String.init(describing:)) ?? ""),\(accuracy.map(String.init(describing:)) ?? "")"
    }

    fileprivate init?(fields: [Substring]) {
        guard fields.count >= 3, let lat = Double(fields[0]), let lon = Double(fields[1]),
              let time = Int64(fields[2]) else { return nil }
        let alt = fields.count > 3 ? Double(fields[3]) : nil
        let accuracy = fields.count > 4 ? Double(fields[4]) : nil
        self.init(latitude: lat, longitude: lon, time: time, altitude: alt, accuracy: accuracy)
        guard isValid else { return nil }
    }
}

public struct RecordedPin: Equatable {
    public let fix: RecordedFix
    public let text: String
    public init(fix: RecordedFix, text: String = "") { self.fix = fix; self.text = text }
}

public struct RecordingSession: Codable, Equatable {
    public let id: UUID
    public let startedAt: Date
    public let stoppedAt: Date?
    public var filename: String { id.uuidString.lowercased() + ".csv" }
}

public enum RecordingStoreError: Error { case invalidFix, alreadyRecording, noActiveRecording, corruptSession }

/// Main-actor ownership serializes writes with UI/location callbacks. Files remain
/// readable while the device is locked after its first unlock following a boot.
@MainActor public final class RecordingStore {
    public let directory: URL
    private let files = FileManager.default
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var activeURL: URL { directory.appendingPathComponent("active.json") }

    public init(directory: URL) throws {
        self.directory = directory
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        #if os(iOS)
        try files.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                ofItemAtPath: directory.path)
        #endif
    }

    public static func applicationStore() throws -> RecordingStore {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        return try RecordingStore(directory: root.appendingPathComponent("Trail/recordings", isDirectory: true))
    }

    public func activeSession() throws -> RecordingSession? {
        guard files.fileExists(atPath: activeURL.path) else { return nil }
        let session = try decoder.decode(RecordingSession.self, from: Data(contentsOf: activeURL))
        guard session.stoppedAt == nil, files.fileExists(atPath: trackURL(session).path) else {
            throw RecordingStoreError.corruptSession
        }
        // Stop commits metadata before removing its pointer. If the process dies
        // between those writes, the completed recording must not become active.
        let metadata = try decoder.decode(RecordingSession.self, from: Data(contentsOf: metadataURL(session)))
        guard metadata.id == session.id else { throw RecordingStoreError.corruptSession }
        if metadata.stoppedAt != nil {
            try? files.removeItem(at: activeURL)
            return nil
        }
        return session
    }

    @discardableResult public func begin(at date: Date = Date()) throws -> RecordingSession {
        guard try activeSession() == nil else { throw RecordingStoreError.alreadyRecording }
        let session = RecordingSession(id: UUID(), startedAt: date, stoppedAt: nil)
        // Create the track before publishing the active pointer; a failure cannot
        // produce an active session referencing a missing file.
        try Data().write(to: trackURL(session), options: .atomic)
        try writeSession(session)
        try encoder.encode(session).write(to: activeURL, options: .atomic)
        return session
    }

    public func trackURL(_ session: RecordingSession) -> URL {
        directory.appendingPathComponent(session.filename)
    }

    private func metadataURL(_ session: RecordingSession) -> URL {
        directory.appendingPathComponent(session.id.uuidString.lowercased() + ".json")
    }

    private func pinsURL(_ session: RecordingSession) -> URL {
        directory.appendingPathComponent(session.id.uuidString.lowercased() + ".pins")
    }

    private func writeSession(_ session: RecordingSession) throws {
        try encoder.encode(session).write(to: metadataURL(session), options: .atomic)
    }

    public func append(_ fix: RecordedFix) throws {
        guard fix.isValid else { throw RecordingStoreError.invalidFix }
        guard let session = try activeSession() else { throw RecordingStoreError.noActiveRecording }
        try appendLine(fix.csv, to: trackURL(session))
    }

    public func appendPin(_ pin: RecordedPin) throws {
        guard pin.fix.isValid else { throw RecordingStoreError.invalidFix }
        guard let session = try activeSession() else { throw RecordingStoreError.noActiveRecording }
        let text = pin.text.isEmpty ? "" : "," + Data(pin.text.utf8).base64EncodedString()
        try appendLine(pin.fix.csv + text, to: pinsURL(session))
    }

    private func appendLine(_ line: String, to url: URL) throws {
        if !files.fileExists(atPath: url.path) { try Data().write(to: url, options: .atomic) }
        let handle = try FileHandle(forUpdating: url)
        defer { try? handle.close() }
        let length = try handle.seekToEnd()
        // Discard an interrupted final write, even if its partial timestamp would
        // happen to parse. A saved row always ends in a newline.
        if length > 0 {
            try handle.seek(toOffset: length - 1)
            if try handle.read(upToCount: 1) != Data([10]) {
                var cursor = length
                var completeLength: UInt64 = 0
                while cursor > 0 {
                    let start = cursor > 4096 ? cursor - 4096 : 0
                    try handle.seek(toOffset: start)
                    let block = try handle.read(upToCount: Int(cursor - start)) ?? Data()
                    if let last = block.lastIndex(of: 10) {
                        completeLength = start + UInt64(last - block.startIndex) + 1
                        break
                    }
                    cursor = start
                }
                try handle.truncate(atOffset: completeLength)
            }
            try handle.seekToEnd()
        }
        try handle.write(contentsOf: Data((line + "\n").utf8))
        try handle.synchronize()
    }

    private func completeLines(_ contents: String) -> [Substring] {
        var lines = contents.split(separator: "\n", omittingEmptySubsequences: false)
        if !contents.hasSuffix("\n"), !lines.isEmpty { lines.removeLast() }
        return lines
    }

    public func points(in session: RecordingSession) throws -> [RecordedFix] {
        try completeLines(String(contentsOf: trackURL(session), encoding: .utf8)).compactMap {
            RecordedFix(fields: $0.split(separator: ",", omittingEmptySubsequences: false))
        }
    }

    public func pins(in session: RecordingSession) throws -> [RecordedPin] {
        let url = pinsURL(session)
        guard files.fileExists(atPath: url.path) else { return [] }
        return try completeLines(String(contentsOf: url, encoding: .utf8)).compactMap { line in
            let fields = line.split(separator: ",", maxSplits: 5, omittingEmptySubsequences: false)
            guard let fix = RecordedFix(fields: fields) else { return nil }
            let text = fields.count > 5
                ? Data(base64Encoded: String(fields[5])).flatMap { String(data: $0, encoding: .utf8) } ?? "" : ""
            return RecordedPin(fix: fix, text: text)
        }
    }

    /// Recovered sessions are exposed for explicit user recovery, never silently
    /// restarted as background recording after process termination.
    public func sessions() throws -> [RecordingSession] {
        try files.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" && $0.lastPathComponent != "active.json" }
            .map { try decoder.decode(RecordingSession.self, from: Data(contentsOf: $0)) }
            .sorted { $0.startedAt > $1.startedAt }
    }

    @discardableResult public func stop(at date: Date = Date()) throws -> RecordingSession {
        guard let current = try activeSession() else { throw RecordingStoreError.noActiveRecording }
        let stopped = RecordingSession(id: current.id, startedAt: current.startedAt, stoppedAt: date)
        try writeSession(stopped)
        try files.removeItem(at: activeURL)
        return stopped
    }
}
