import Foundation

/// A pinned, read-only descriptor. Actor isolation serializes seek/read pairs and
/// keeps archive I/O off the main actor without loading a whole archive into RAM.
public actor LocalArchiveReader {
    private let file: FileHandle
    private let size: UInt64
    public init(url: URL) throws {
        file = try FileHandle(forReadingFrom: url)
        size = try file.seekToEnd()
        guard size >= 127 else { try file.close(); throw ArchiveRangeError.response }
    }
    deinit { try? file.close() }
    public func read(offset: Int64, length: Int) throws -> Data {
        guard offset >= 0, length > 0, length <= 16*1024*1024,
              UInt64(offset) <= size, UInt64(length) <= size - UInt64(offset) else {
            throw ArchiveRangeError.response
        }
        try file.seek(toOffset: UInt64(offset))
        guard let bytes = try file.read(upToCount: length), bytes.count == length else { throw ArchiveRangeError.response }
        return bytes
    }
}
