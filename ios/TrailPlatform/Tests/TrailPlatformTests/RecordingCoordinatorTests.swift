import Foundation
import XCTest
@testable import TrailPlatform

final class RecordingCoordinatorTests: XCTestCase {
    func testSamplingRejectsStaleAndChoosesRecentAccurateFix() {
        var sampler = RecordingSampler()
        func fix(_ seconds: Int64, _ accuracy: Double) -> RecordedFix {
            RecordedFix(latitude: 40.692, longitude: -74.771, time: seconds * 1000, accuracy: accuracy)
        }
        XCTAssertNil(sampler.consider(fix(1, 3), now: Date(timeIntervalSince1970: 100), continuous: 0))
        XCTAssertEqual(sampler.consider(fix(100, 3), now: Date(timeIntervalSince1970: 100), continuous: 0), fix(100, 3))
        XCTAssertNil(sampler.consider(fix(125, 3), now: Date(timeIntervalSince1970: 125), continuous: 25))
        XCTAssertNil(sampler.consider(fix(129, 5), now: Date(timeIntervalSince1970: 129), continuous: 29))
        // The newest fix is much worse; use the newest near the recent best.
        XCTAssertEqual(sampler.consider(fix(130, 100), now: Date(timeIntervalSince1970: 130), continuous: 30), fix(129, 5))
        XCTAssertNil(sampler.consider(fix(120, 3), now: Date(timeIntervalSince1970: 160), continuous: 60))
    }

    @MainActor func testFourHoursIgnoresWallClockChangesAndPreservesTrack() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try RecordingStore(directory: root)
        var elapsed = 10.0
        let recorder = RecordingCoordinator(store: store, clock: { elapsed })
        try recorder.start(at: Date(timeIntervalSince1970: 100_000))
        let session = try XCTUnwrap(recorder.recording)
        let fix = RecordedFix(latitude: 40.692, longitude: -74.771, time: 100_000_000, accuracy: 3)
        try recorder.receive(fix, at: Date(timeIntervalSince1970: 100_000))
        try recorder.addPin(text: "gate", at: Date(timeIntervalSince1970: 100_001))
        elapsed += RecordingCoordinator.maximumDuration
        // Wall clock moved backwards; elapsed continuous time still expires.
        XCTAssertTrue(try recorder.expireIfNeeded(at: Date(timeIntervalSince1970: 90_000)))
        XCTAssertNil(recorder.recording)
        XCTAssertNil(try store.activeSession())
        XCTAssertEqual(try store.points(in: session), [fix])
        XCTAssertEqual(try store.pins(in: session).first?.text, "gate")
        XCTAssertThrowsError(try recorder.addPin())
    }
}
