import XCTest
@testable import TrailPlatform

final class RecordingFilenameTests: XCTestCase {
    func testLocalNameUsesSecondsWithoutISOTimeMarkers() {
        let date = ISO8601DateFormatter().date(from: "2026-11-07T21:30:05Z")!.addingTimeInterval(0.987)
        XCTAssertEqual("Trail_2026-11-07_16-30-05",
            RecordingFilename.name(for: date, timeZone: TimeZone(identifier: "America/New_York")!))
        XCTAssertEqual("Trail_2026-11-08_06-30-05",
            RecordingFilename.name(for: date, timeZone: TimeZone(identifier: "Asia/Tokyo")!))
    }
}
