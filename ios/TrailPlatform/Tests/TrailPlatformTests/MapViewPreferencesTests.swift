import Foundation
import XCTest
@testable import TrailPlatform

final class MapViewPreferencesTests: XCTestCase {
    func testCameraBasemapAndTrailVisibilityRoundTripAndInvalidCameraFallsBack() {
        let suite = "Trail-map-test-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = MapViewPreferences(defaults: defaults)
        var value = MapViewSettings()
        value.basemap = "opentopomap"; value.latitude = 40.691; value.longitude = -74.773
        value.zoom = 15; value.trailsVisible = false
        preferences.save(value)
        XCTAssertEqual(MapViewPreferences(defaults: defaults).read(available: ["bright", "opentopomap"]), value)
        XCTAssertEqual(preferences.read(available: ["bright"]).basemap, "bright")
        for bad in [Double.nan, .infinity, 200] {
            defaults.set(["latitude": bad, "longitude": 0, "zoom": 15], forKey: "mapView")
            XCTAssertEqual(preferences.read(available: ["bright"]), MapViewSettings())
        }
    }
}
