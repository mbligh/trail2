import Foundation

/// Reads the existing tools/prepare_maps output without a network fallback.
/// Font/sprite URL identities and immutable MBTiles are shared with Android.
public final class OfflineMapBundle {
    private let root: URL
    private let resources: [String: String]
    private let definitions: [String: [String: Any]]
    private let archives: [String: MBTilesArchive]

    public init(directory: URL) throws {
        root = directory.standardizedFileURL.resolvingSymlinksInPath()
        let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("bundled-resources.json"))) as? [String: String] ?? [:]
        resources = Dictionary(raw.map { ($0.key.removingPercentEncoding ?? $0.key, $0.value) }, uniquingKeysWith: { first, _ in first })
        definitions = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("resolved-sources.json"))) as? [String: [String: Any]] ?? [:]
        var opened: [String: MBTilesArchive] = [:]
        for (id, definition) in definitions {
            guard id.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil,
                  let relative = definition["archive"] as? String,
                  let url = Self.safeFile(relative, root: root), FileManager.default.fileExists(atPath: url.path) else { continue }
            opened[id] = try MBTilesArchive(url: url)
        }
        archives = opened
    }

    private static func safeFile(_ relative: String, root: URL) -> URL? {
        guard !relative.hasPrefix("/"), !relative.split(separator: "/").contains("..") else { return nil }
        let file = root.appendingPathComponent(relative).standardizedFileURL.resolvingSymlinksInPath()
        return file.path.hasPrefix(root.path + "/") ? file : nil
    }

    public func style(named name: String = "bright") throws -> String {
        guard ["bright", "hybrid"].contains(name), let url = Self.safeFile("styles/\(name).json", root: root),
              var style = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let sources = style["sources"] as? [String: [String: Any]] else {
            throw MapArchiveError.sqlite("Offline style is unavailable")
        }
        var routed: [String: [String: Any]] = [:]
        for (id, original) in sources {
            var source = definitions[id] ?? original
            // All tile sources use a local route, even if a particular archive
            // is absent. A missing seed cannot silently fetch remote imagery.
            if source["type"] as? String == "vector" || source["type"] as? String == "raster" {
                source.removeValue(forKey: "url")
                source["tiles"] = ["https://trail.ios.invalid/\(id)/{z}/{x}/{y}"]
                if let archive = archives[id] {
                    for key in ["minzoom", "maxzoom"] where source[key] == nil {
                        if let value = try archive.metadata(key), let zoom = Int(value) { source[key] = zoom }
                    }
                    if source["bounds"] == nil, let value = try archive.metadata("bounds") {
                        let bounds = value.split(separator: ",").compactMap { Double($0) }
                        if bounds.count == 4 { source["bounds"] = bounds }
                    }
                }
            }
            for key in ["archive", "cacheVersion", "cacheIdentity", "vector_layers"] { source.removeValue(forKey: key) }
            routed[id] = source
        }
        style["sources"] = routed
        return String(decoding: try JSONSerialization.data(withJSONObject: style, options: [.sortedKeys]), as: UTF8.self)
    }

    public func read(url: String) throws -> Data? {
        if let relative = resources[url.removingPercentEncoding ?? url], let path = Self.safeFile(relative, root: root) {
            return try Data(contentsOf: path)
        }
        if url.hasPrefix("asset://"), let decoded = String(url.dropFirst(8)).removingPercentEncoding,
           let path = Self.safeFile(decoded, root: root) { return try Data(contentsOf: path) }
        guard let parsed = URL(string: url), parsed.scheme == "https", parsed.host == "trail.ios.invalid" else { return nil }
        let path = parsed.path.split(separator: "/")
        guard path.count == 4, let z = Int(path[1]), let x = Int(path[2]), let y = Int(path[3]),
              let archive = archives[String(path[0])] else { return nil }
        return try archive.tile(z: z, x: x, y: y)
    }
}
