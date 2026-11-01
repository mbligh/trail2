import Foundation
import TrailIosProbe

/// Immutable bundle reader may be called on the renderer's worker dispatcher.
/// SQLite readers serialize their own access; UI actions remain on the main actor.
final class OfflineResources: NSObject, IosMapResourceReader {
    private let bundle: OfflineMapBundle
    private let configuration: IosMapConfiguration

    override init() {
        do {
            guard let root = Bundle.main.resourceURL else { throw MapArchiveError.sqlite("No app resource directory") }
            bundle = try OfflineMapBundle(directory: root.appendingPathComponent("mapAssets"))
            configuration = IosMapConfiguration(reader: BundledMapAssets(root: root))
        } catch { fatalError("Cannot load bundled offline map: \(error)") }
        super.init()
    }

    func style() throws -> String { try bundle.route(styleJson: configuration.style(name: "bright")) }

    static func verifyConfiguration() throws {
        let resources = OfflineResources()
        guard resources.configuration.basemapNames == ["bright", "hybrid", "njsatellite", "openstreetmap", "opentopomap"] else {
            throw RecordingStoreError.corruptSession
        }
        for name in resources.configuration.basemapNames {
            let style = try resources.bundle.route(styleJson: resources.configuration.style(name: name))
            guard let parsed = try JSONSerialization.jsonObject(with: Data(style.utf8)) as? [String: Any],
                  let sources = parsed["sources"] as? [String: [String: Any]], !sources.isEmpty,
                  parsed["layers"] is [Any], sources["nj-contours"] == nil else { throw RecordingStoreError.corruptSession }
            for source in sources.values where ["vector", "raster"].contains(source["type"] as? String ?? "") {
                guard source["url"] == nil, let tiles = source["tiles"] as? [String],
                      tiles.allSatisfy({ $0.hasPrefix("https://trail.ios.invalid/") }),
                      source["archive"] == nil else { throw RecordingStoreError.corruptSession }
            }
        }
    }

    func read(url: String) -> KotlinByteArray? {
        do {
            guard let data = try bundle.read(url: url) else { return nil }
            let bytes = KotlinByteArray(size: Int32(data.count))
            for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }
            if url.hasPrefix("https://trail.ios.invalid/") {
                print("IOS_OFFLINE_TILE_HIT:\(url)")
                fflush(stdout)
            }
            return bytes
        } catch {
            print("IOS_OFFLINE_RESOURCE_ERROR:\(error)")
            fflush(stdout)
            return nil
        }
    }
}


private final class BundledMapAssets: NSObject, IosMapAssetReader {
    let root: URL
    init(root: URL) { self.root = root; super.init() }
    func read(path: String) -> String? {
        guard !path.hasPrefix("/"), !path.split(separator: "/").contains("..") else { return nil }
        let file = path == "basemaps.json" ? root.appendingPathComponent(path)
            : root.appendingPathComponent("mapAssets").appendingPathComponent(path)
        return try? String(contentsOf: file, encoding: .utf8)
    }
}
