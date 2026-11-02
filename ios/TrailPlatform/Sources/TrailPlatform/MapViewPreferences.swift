import Foundation

public struct MapViewSettings: Equatable {
    public var basemap = "bright"
    public var latitude = 40.692
    public var longitude = -74.771
    public var zoom = 14.5 // Same displayed 256-pixel-world zoom convention as Android.
    public var enabledLayers = Set<String>()
    public var trailsVisible = true
    public init() {}
}
public final class MapViewPreferences {
    private let defaults: UserDefaults
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    public func read(available: [String], layers: [String: Bool] = [:]) -> MapViewSettings {
        var value = MapViewSettings()
        value.enabledLayers = Set(layers.filter { $0.value }.keys)
        guard let raw = defaults.dictionary(forKey: "mapView") else { return value }
        if let name = raw["basemap"] as? String, available.contains(name) { value.basemap = name }
        if let lat = raw["latitude"] as? Double, let lon = raw["longitude"] as? Double,
           let zoom = raw["zoom"] as? Double, lat.isFinite, lon.isFinite, zoom.isFinite,
           (-85.05112878...85.05112878).contains(lat), (-180...180).contains(lon), (3...19).contains(zoom) {
            value.latitude = lat; value.longitude = lon; value.zoom = zoom
        }
        if let enabled = raw["layers"] as? [String] { value.enabledLayers = Set(enabled).intersection(layers.keys) }
        if let visible = raw["trailsVisible"] as? Bool { value.trailsVisible = visible }
        return value
    }
    public func save(_ value: MapViewSettings) {
        defaults.set(["basemap": value.basemap, "latitude": value.latitude, "longitude": value.longitude,
                      "zoom": value.zoom, "trailsVisible": value.trailsVisible, "layers":value.enabledLayers.sorted()], forKey: "mapView")
    }
}
