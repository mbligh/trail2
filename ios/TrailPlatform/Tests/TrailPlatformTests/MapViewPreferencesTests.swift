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
        value.basemap = "openstreetmap"; value.latitude = 40.691; value.longitude = -74.773
        value.zoom = 15; value.trailsVisible = false
        value.enabledLayers = ["contours"]
        preferences.save(value)
        XCTAssertEqual(MapViewPreferences(defaults: defaults).read(available: ["bright", "openstreetmap"], layers: ["contours":false]), value)
        XCTAssertEqual(preferences.read(available: ["bright"]).basemap, "bright")
        // An app update can remove a basemap. Keep camera, overlay and trail state.
        defaults.set(["basemap":"opentopomap", "latitude":40.691, "longitude":-74.773,
                      "zoom":15.0, "trailsVisible":false, "layers":["contours"]], forKey:"mapView")
        var migrated = value
        migrated.basemap = "bright"
        XCTAssertEqual(preferences.read(available:["bright", "openstreetmap"], layers:["contours":false]), migrated)
        for bad in [Double.nan, .infinity, 200] {
            defaults.set(["latitude": bad, "longitude": 0, "zoom": 15], forKey: "mapView")
            XCTAssertEqual(preferences.read(available: ["bright"]), MapViewSettings())
        }
    }
}
