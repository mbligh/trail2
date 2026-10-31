import Foundation
import XCTest
@testable import TrailPlatform

// XML assertions live in the simulator host, which links the actual Kotlin writer.
final class RecordingExportsTests: XCTestCase {
    func testLocalizedPresentationOfSharedStatistics() {
        let summary = TrackExportStatistics(distanceMeters: 1609.344, elapsedSeconds: 3780,
                                            movingSeconds: 3630, ascentMeters: 30, descentMeters: 20)
        XCTAssertTrue(summary.displaySummary.contains("1.00 mi"))
        XCTAssertTrue(summary.displaySummary.contains("98 ft"))
        XCTAssertTrue(summary.displaySummary.contains("1h 03m 00s"))
        XCTAssertTrue(summary.description.contains("Distance: 1.61 km"))
        XCTAssertTrue(summary.description.contains("Moving: 1h 00m 30s"))
    }
}
