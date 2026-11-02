import Foundation
import TrailIosProbe

/// Immutable bundle reader may be called on the renderer's worker dispatcher.
/// SQLite readers serialize their own access; UI actions remain on the main actor.
final class OfflineResources: NSObject, IosMapResourceReader {
    private let bundle: OfflineMapBundle
    // Renderer URLs belong to one pinned archive session; durable ranges use URL/ETag identities.
    private let routeSession = UUID().uuidString
    private let archiveCache: ArchiveRangeCache
    private let archiveLock = NSLock()
    private var archiveReads = 0
    var archiveReadCount: Int { archiveLock.lock(); defer { archiveLock.unlock() }; return archiveReads }
    private var archives: [String: NativePmtilesSource] = [:]
    private let contourOrigin: String?
    private let cache: MapResourceCache
    private let networkEnabled: Bool
    private let fixtureOrigin: String?
    private let configuration: IosMapConfiguration

    init(networkEnabled: Bool = false, fixtureOrigin: String? = nil, cacheDirectory: URL? = nil, contourOrigin: String? = nil, archiveDirectory: URL? = nil) {
        self.contourOrigin = contourOrigin
        self.networkEnabled = networkEnabled
        self.fixtureOrigin = fixtureOrigin?.hasPrefix("asset://") == true ? nil : fixtureOrigin
        do {
            guard let root = Bundle.main.resourceURL else { throw MapArchiveError.sqlite("No app resource directory") }
            bundle = try OfflineMapBundle(directory: root.appendingPathComponent("mapAssets"))
            let caches = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            archiveCache = try ArchiveRangeCache(directory: archiveDirectory ?? caches.appendingPathComponent("Trail/archive-ranges"), validateRange: { offset, length, header in
                let total = IosHttpByteRange().total(offset: offset, length: Int32(length), header: header)
                return total < 0 ? nil : total
            })
            cache = try MapResourceCache(directory: cacheDirectory ?? caches.appendingPathComponent("Trail/map-resources"))
            configuration = IosMapConfiguration(reader: BundledMapAssets(root: root))
        } catch { fatalError("Cannot load bundled offline map: \(error)") }
        super.init()
    }

    var basemaps: [IosBasemapOption] { configuration.basemaps }
    var layers: [IosLayerOption] { configuration.layers }
    func style(name: String = "bright", enabled: Set<String> = []) throws -> String {
        let raw = try configuration.styleWithLayers(name: name, enabled: Array(enabled))
        var style = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as! [String: Any]
        var sources = style["sources"] as! [String: [String: Any]]
        for (id, definition) in sources {
            guard let source = definition["url"] as? String, source.hasPrefix("pmtiles://") else { continue }
            let identity = String(decoding: try JSONSerialization.data(withJSONObject: definition, options: [.sortedKeys]), as: UTF8.self)
            let address = contourOrigin.map { $0.hasPrefix("asset://") ? $0 : $0 + "/contours.pmtiles" } ?? String(source.dropFirst("pmtiles://".count))
            let key = ArchiveRangeCache.hash(routeSession + "\n" + address + "\n" + identity)
            let candidate: NativePmtilesSource
            if address.hasPrefix("asset://") {
                guard let file = bundle.archiveURL(address) else { throw ArchiveRangeError.response }
                candidate = try NativePmtilesSource(localURL: file, cache: archiveCache)
            } else {
                guard let url = URL(string: address), url.scheme == "https" || (contourOrigin != nil && url.host == "localhost") else { throw ArchiveRangeError.response }
                candidate = NativePmtilesSource(url: url, identity: identity, cache: archiveCache, networkEnabled: networkEnabled)
            }
            archiveLock.lock()
            if archives[key] == nil { archives[key] = candidate }
            archiveLock.unlock()
            var routed = definition
            routed["url"] = "https://trail.pmtiles.ios.invalid/\(key)/source.json"
            sources[id] = routed
        }
        style["sources"] = sources
        return try bundle.route(styleJson: String(decoding: JSONSerialization.data(withJSONObject: style), as: UTF8.self))
    }
    private func archive(_ url: String) -> (NativePmtilesSource, [Substring])? {
        guard let parsed = URL(string: url), parsed.host == "trail.pmtiles.ios.invalid" else { return nil }
        let path = parsed.path.split(separator: "/")
        guard let key = path.first else { return nil }
        archiveLock.lock(); defer { archiveLock.unlock() }
        guard let source = archives[String(key)] else { return nil }
        archiveReads += 1
        return (source,path)
    }

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

    func read(url: String, completion: IosResourceCompletion) {
        Task {
            do {
                var data: Data?
                if let (archive, path) = archive(url) {
                    if path.count == 2, path[1] == "source.json" {
                        data = try await archive.metadata(route: "https://trail.pmtiles.ios.invalid/" + String(path[0]))
                    } else if path.count == 4, let z = Int32(path[1]), let x = Int32(path[2]), let y = Int32(path[3]) {
                        data = try await archive.tile(z: z, x: x, y: y)
                    }
                    if data != nil { print("IOS_PMTILES_HIT:\(url)"); fflush(stdout) }
                } else { data = try bundle.read(url: url) }
                if data == nil, let remote = bundle.remoteResource(url: url, fixtureOrigin: fixtureOrigin) {
                    data = try await cache.read(url: remote.url, identity: remote.identity, networkEnabled: networkEnabled)
                    if data != nil {
                        print("IOS_MAP_CACHE_HIT:\(url)")
                        fflush(stdout)
                    }
                }
                guard let data else { completion.complete(bytes: nil); return }
                let bytes = KotlinByteArray(size: Int32(data.count))
                for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }
                if url.hasPrefix("https://trail.ios.invalid/") {
                    print("IOS_OFFLINE_TILE_HIT:\(url)"); fflush(stdout)
                }
                completion.complete(bytes: bytes)
            } catch {
                print("IOS_OFFLINE_RESOURCE_ERROR:\(error)"); fflush(stdout)
                completion.complete(bytes: nil)
            }
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
