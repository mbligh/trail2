import Foundation
import XCTest
@testable import TrailPlatform

final class RecordingStoreTests: XCTestCase {
    @MainActor func testRecoveryAndUnicodePins() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try RecordingStore(directory: root)
        let session = try store.begin(at: Date(timeIntervalSince1970: 100))
        let fix = RecordedFix(latitude: 40.692, longitude: -74.771, time: 100_000, altitude: 80, accuracy: 3)
        try store.append(fix)
        let note = "Fence, broken\nGate <&> 🐴"
        try store.appendPin(RecordedPin(fix: fix, text: note))
        let recovered = try RecordingStore(directory: root)
        XCTAssertEqual(try recovered.activeSession(), session)
        XCTAssertEqual(try recovered.points(in: session), [fix])
        XCTAssertEqual(try recovered.pins(in: session), [RecordedPin(fix: fix, text: note)])
        XCTAssertThrowsError(try recovered.begin())
        let stopped = try recovered.stop(at: Date(timeIntervalSince1970: 200))
        XCTAssertNil(try recovered.activeSession())
        XCTAssertEqual(try recovered.sessions(), [stopped])
        XCTAssertEqual(try recovered.points(in: stopped), [fix])
        XCTAssertThrowsError(try recovered.append(fix))
    }

    @MainActor func testReadsAndroidCSVAndPinsAndSurvivesTruncatedTail() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try RecordingStore(directory: root)
        let session = try store.begin()
        let track = store.trackURL(session)
        try "40.1,-74.2,1000,,5.0\n40.2,-74.3,31000,90.0,\nbroken\n40.3,".write(to: track, atomically: true, encoding: .utf8)
        let fix = RecordedFix(latitude: 40.4, longitude: -74.5, time: 61000)
        try store.append(fix)
        let points = try store.points(in: session)
        XCTAssertEqual(points.count, 3)
        XCTAssertNil(points[0].altitude)
        XCTAssertEqual(points[1].altitude, 90)
        XCTAssertEqual(points.last, fix)
        let sidecar = track.deletingPathExtension().appendingPathExtension("pins")
        try "40.1,-74.2,1000,,5.0\n40.2,-74.3,31000,90.0,3.0,aGVsbG8=\n".write(to: sidecar, atomically: true, encoding: .utf8)
        XCTAssertEqual(try store.pins(in: session).map(\.text), ["", "hello"])
    }

    @MainActor func testInvalidFixesDoNotModifyTrack() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try RecordingStore(directory: root)
        let session = try store.begin()
        for fix in [RecordedFix(latitude: .nan, longitude: 0, time: 1),
                    RecordedFix(latitude: 91, longitude: 0, time: 1),
                    RecordedFix(latitude: 0, longitude: 181, time: 1),
                    RecordedFix(latitude: 0, longitude: 0, time: -1),
                    RecordedFix(latitude: 0, longitude: 0, time: 1, altitude: .infinity),
                    RecordedFix(latitude: 0, longitude: 0, time: 1, accuracy: -1)] {
            XCTAssertThrowsError(try store.append(fix))
        }
        XCTAssertTrue(try store.points(in: session).isEmpty)
    }
}
