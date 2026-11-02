import Foundation
import TrailIosProbe

/// The common reader only sees already-fetched bytes. Missing ranges suspend in Swift,
/// so no network wait blocks a renderer thread or the main actor.
actor NativePmtilesSource {
    private let url: URL
    private let identity: String
    private let cache: ArchiveRangeCache
    private let networkEnabled: Bool
    private var snapshot: ArchiveSnapshot?
    private var opening: Task<ArchiveSnapshot, Error>?
    init(url: URL, identity: String, cache: ArchiveRangeCache, networkEnabled: Bool) {
        self.url = url; self.identity = identity; self.cache = cache; self.networkEnabled = networkEnabled
    }
    private func open() async throws -> ArchiveSnapshot {
        if let snapshot { return snapshot }
        if let opening { return try await opening.value }
        let task = Task { try await cache.open(url: url, identity: identity, networkEnabled: networkEnabled, validateHeader: Self.validateHeader) }
        opening = task
        do { let value = try await task.value; snapshot = value; opening = nil; return value }
        catch { opening = nil; throw error }
    }
    nonisolated private static func validateHeader(_ data: Data) throws {
        let metadata = try IosPmtilesArchive(reader: ArchiveBuffer(header: data)).metadata()
        guard metadata.tileType == 1 else { throw ArchiveRangeError.response }
    }
    func metadata(route: String) async throws -> Data {
        let value = try await open()
        let metadata = try IosPmtilesArchive(reader: ArchiveBuffer(header: value.header)).metadata()
        return try JSONSerialization.data(withJSONObject: ["tilejson":"3.0.0", "minzoom":metadata.minZoom,
            "maxzoom":metadata.maxZoom, "tiles":[route + "/{z}/{x}/{y}"]])
    }
    func tile(z: Int32, x: Int32, y: Int32) async throws -> Data? {
        let value = try await open()
        let buffer = ArchiveBuffer(header: value.header)
        let archive = IosPmtilesArchive(reader: buffer)
        for _ in 0..<8 {
            buffer.missing = nil
            let result = try archive.tile(z: z, x: x, y: y)
            guard let (offset, length) = buffer.missing else { return result.map(PmtilesBytes.data) }
            guard let data = try await cache.read(url: url, snapshot: value, offset: offset, length: length, networkEnabled: networkEnabled) else { return nil }
            try buffer.install(data, offset: offset, length: length)
        }
        throw ArchiveRangeError.response
    }
}

private final class ArchiveBuffer: NSObject, IosPmtilesRangeReader {
    var missing: (Int64, Int)?
    private var ranges: [String: Data]
    private var count = 127
    init(header: Data) { ranges = ["0:127":header]; super.init() }
    func install(_ data: Data, offset: Int64, length: Int) throws {
        guard data.count == length, count <= 32*1024*1024 - length else { throw ArchiveRangeError.oversized }
        ranges["\(offset):\(length)"] = data; count += data.count
    }
    func read(offset: Int64, length: Int32) -> KotlinByteArray? {
        guard let data = ranges["\(offset):\(length)"] else { missing = (offset,Int(length)); return nil }
        return PmtilesBytes.bytes(data)
    }
    func inflate(bytes: KotlinByteArray, maxBytes: Int32) -> KotlinByteArray? {
        let data = PmtilesBytes.data(bytes)
        guard data.count >= 2, data[0] == 0x1f, data[1] == 0x8b,
              let result = try? MBTilesArchive.uncompressed(data, maxBytes: Int(maxBytes)) else { return nil }
        return PmtilesBytes.bytes(result)
    }
}
private enum PmtilesBytes {
    static func bytes(_ data: Data) -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(data.count))
        for (i,b) in data.enumerated() { result.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return result
    }
    static func data(_ bytes: KotlinByteArray) -> Data {
        Data((0..<Int(bytes.size)).map { UInt8(bitPattern: bytes.get(index: Int32($0))) })
    }
}
