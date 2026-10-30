import Foundation
import XCTest
@testable import TrailPlatform

final class RecordingExportsTests: XCTestCase {
    func testXMLPreservesNotesAndBreaksAtOutage() {
        let points = [RecordedFix(latitude: 40.1, longitude: -74.1, time: 0, altitude: 80, accuracy: 3),
                      RecordedFix(latitude: 40.2, longitude: -74.2, time: 30_000, accuracy: 8),
                      RecordedFix(latitude: 40.3, longitude: -74.3, time: 180_000, altitude: 90, accuracy: 20)]
        let pins = [RecordedPin(fix: points[0], text: "Gate <&> 🐴\nNote\u{1}")]
        let summary = TrackExportStatistics(distanceMeters: 1609.344, elapsedSeconds: 180,
                                            movingSeconds: 30, ascentMeters: 30, descentMeters: 20)
        let gpx = RecordingExports.gpx(name: "A & B", points: points, pins: pins, summary: summary)
        let kml = RecordingExports.kml(name: "A & B", points: points, pins: pins, summary: summary)
        XCTAssertTrue(XMLParser(data: Data(gpx.utf8)).parse())
        XCTAssertTrue(XMLParser(data: Data(kml.utf8)).parse())
        XCTAssertEqual(gpx.components(separatedBy: "<trkseg>").count - 1, 2)
        XCTAssertEqual(kml.components(separatedBy: "<LineString>").count - 1, 1)
        XCTAssertTrue(kml.contains("<styleUrl>#moderate</styleUrl>"))
        for text in [gpx, kml] {
            XCTAssertTrue(text.contains("Gate &lt;&amp;&gt; 🐴\nNote"))
            XCTAssertFalse(text.contains("\u{1}"))
            XCTAssertTrue(text.contains("ascentMeters"))
            XCTAssertTrue(text.contains("descentMeters"))
        }
        XCTAssertTrue(gpx.range(of: "<wpt ")!.lowerBound < gpx.range(of: "<trk>")!.lowerBound)
        XCTAssertTrue(summary.displaySummary.contains("1.00 mi"))
        XCTAssertTrue(summary.displaySummary.contains("98 ft"))
    }
}
