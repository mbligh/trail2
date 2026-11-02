import Foundation
import TrailIosProbe

/// Exercise the actual linked common reader with native bounded zlib in both launch phases.
final class PmtilesValidation: NSObject, IosPmtilesRangeReader {
    private let data: Data
    init(data: Data) { self.data = data; super.init() }
    func read(offset: Int64, length: Int32) -> KotlinByteArray? {
        guard offset >= 0, length >= 0, offset <= Int64(data.count) - Int64(length) else { return nil }
        return Self.bytes(data.subdata(in: Int(offset)..<(Int(offset) + Int(length))))
    }
    func inflate(bytes: KotlinByteArray, maxBytes: Int32) -> KotlinByteArray? {
        let input = Self.data(bytes)
        guard input.count >= 2, input[0] == 0x1f, input[1] == 0x8b,
              let output = try? MBTilesArchive.uncompressed(input, maxBytes: Int(maxBytes)) else { return nil }
        return Self.bytes(output)
    }
    private static func bytes(_ data: Data) -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(data.count))
        for (i, value) in data.enumerated() { result.set(index: Int32(i), value: Int8(bitPattern: value)) }
        return result
    }
    private static func data(_ bytes: KotlinByteArray) -> Data {
        Data((0..<Int(bytes.size)).map { UInt8(bitPattern: bytes.get(index: Int32($0))) })
    }
    static func verify() throws {
        let fixtures = [
            "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"
        ]
        for fixture in fixtures {
            let original = Data(base64Encoded: fixture)!
            let reader = PmtilesValidation(data: original)
            let archive = IosPmtilesArchive(reader: reader)
            let metadata = try archive.metadata()
            guard metadata.minZoom == 1, metadata.maxZoom == 1, metadata.tileType == 1,
                  let a = try archive.tile(z: 1, x: 0, y: 0), Self.data(a) == Data("tile-A".utf8),
                  let b = try archive.tile(z: 1, x: 1, y: 0), Self.data(b) == Data("tile-B".utf8),
                  try archive.tile(z: 0, x: 0, y: 0) == nil,
                  try archive.tile(z: 1, x: 2, y: 0) == nil,
                  reader.data == original else { throw RecordingStoreError.corruptSession }
        }
        let invalid = IosPmtilesArchive(reader: PmtilesValidation(data: Data("bad".utf8)))
        do { _ = try invalid.tile(z: 0, x: 0, y: 0) }
        catch { return } // Malformed archive errors must cross the Swift bridge safely.
        throw RecordingStoreError.corruptSession
    }
}
