import Foundation
import Darwin

/// Darwin's continuous clock advances during device sleep and ignores wall-clock changes.
public func continuousSeconds() -> Double {
    var info = mach_timebase_info_data_t()
    mach_timebase_info(&info)
    return Double(mach_continuous_time()) * Double(info.numer) / Double(info.denom) / 1_000_000_000
}

public struct RecordingSampler {
    private var recent: [(fix: RecordedFix, received: Double)] = []
    private var lastWindow: Double?
    private var lastSavedTime: Int64?

    public init() {}

    /// First fresh fix immediately; then newest reasonably accurate fix in the
    /// previous ten seconds at thirty-second intervals, matching Android policy.
    public mutating func consider(_ fix: RecordedFix, now: Date, continuous: Double) -> RecordedFix? {
        guard fix.isValid, continuous.isFinite else { return nil }
        let age = now.timeIntervalSince1970 - Double(fix.time) / 1000
        guard age >= -5, age <= 45 else { return nil }
        recent.removeAll { continuous - $0.received > 10 || $0.fix.time == fix.time }
        recent.append((fix, continuous))
        guard lastWindow == nil || continuous - lastWindow! >= 30 else { return nil }
        let fresh = recent.map(\.fix).filter { lastSavedTime == nil || $0.time > lastSavedTime! }
        let accurate = fresh.filter { $0.accuracy != nil }
        let selected: RecordedFix?
        if let best = accurate.compactMap(\.accuracy).min() {
            selected = accurate.filter { $0.accuracy! <= max(best * 2, best + 5) }.max { $0.time < $1.time }
        } else {
            selected = fresh.max { $0.time < $1.time }
        }
        guard let selected else { return nil }
        lastWindow = continuous
        lastSavedTime = selected.time
        return selected
    }
}

@MainActor public final class RecordingCoordinator {
    public static let maximumDuration: Double = 4 * 60 * 60
    public let store: RecordingStore
    public private(set) var recording: RecordingSession?
    public private(set) var latestFix: RecordedFix?
    private var sampler = RecordingSampler()
    private var deadline: Double?
    private let clock: () -> Double

    public init(store: RecordingStore, clock: @escaping () -> Double = continuousSeconds) {
        self.store = store; self.clock = clock
    }

    /// Recovered files remain visible through store.activeSession(). Starting is
    /// explicit and refuses to overwrite an unfinished recording.
    public func start(at date: Date = Date()) throws {
        let session = try store.begin(at: date)
        recording = session
        deadline = clock() + Self.maximumDuration
        sampler = RecordingSampler()
        latestFix = nil
    }

    /// Returns true when the four-hour deadline stopped recording.
    @discardableResult public func expireIfNeeded(at date: Date = Date()) throws -> Bool {
        guard recording != nil, let deadline, clock() >= deadline else { return false }
        _ = try stop(at: date)
        return true
    }

    public func receive(_ fix: RecordedFix, at date: Date = Date()) throws {
        if try expireIfNeeded(at: date) { return }
        guard recording != nil else { return }
        let age = date.timeIntervalSince1970 - Double(fix.time) / 1000
        guard fix.isValid, age >= -5, age <= 45 else { return }
        if latestFix == nil || fix.time > latestFix!.time { latestFix = fix }
        if let sample = sampler.consider(fix, now: date, continuous: clock()) {
            try store.append(sample)
        }
    }

    public func addPin(text: String = "", at date: Date = Date()) throws {
        guard !(try expireIfNeeded(at: date)), recording != nil, let fix = latestFix,
              date.timeIntervalSince1970 - Double(fix.time) / 1000 <= 45 else {
            throw RecordingStoreError.noActiveRecording
        }
        try store.appendPin(RecordedPin(fix: fix, text: text))
    }

    @discardableResult public func stop(at date: Date = Date()) throws -> RecordingSession {
        let stopped = try store.stop(at: date)
        haltAfterStorageFailure()
        return stopped
    }

    /// Stop acquisition even if final metadata cannot be written. Keep the disk
    /// recovery pointer untouched so an I/O failure never discards saved points.
    func haltAfterStorageFailure() {
        recording = nil; latestFix = nil; deadline = nil
        sampler = RecordingSampler()
    }
}
