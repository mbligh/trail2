import Foundation
import SQLite3
import zlib

public enum MapArchiveError: Error { case sqlite(String), invalidGzip, oversizedTile }

/// Immutable standard MBTiles seeds. SQLite is opened read-only; XYZ requests
/// convert to TMS rows, and gzipped vector tiles are decoded before rendering.
public final class MBTilesArchive {
    private var database: OpaquePointer?
    private let lock = NSLock()

    public init(url: URL) throws {
        let status = sqlite3_open_v2(url.path, &database, SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX, nil)
        guard status == SQLITE_OK else {
            let reason = database.map { String(cString: sqlite3_errmsg($0)) } ?? "Cannot open MBTiles"
            sqlite3_close(database); database = nil
            throw MapArchiveError.sqlite(reason)
        }
    }

    deinit { sqlite3_close(database) }

    public func metadata(_ name: String) throws -> String? {
        lock.lock(); defer { lock.unlock() }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, "SELECT value FROM metadata WHERE name=? LIMIT 1", -1, &statement, nil) == SQLITE_OK else {
            throw MapArchiveError.sqlite(String(cString: sqlite3_errmsg(database)))
        }
        defer { sqlite3_finalize(statement) }
        let status = name.withCString { sqlite3_bind_text(statement, 1, $0, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
        guard status == SQLITE_OK else { throw MapArchiveError.sqlite("Cannot bind metadata name") }
        let row = sqlite3_step(statement)
        if row == SQLITE_DONE { return nil }
        guard row == SQLITE_ROW, let value = sqlite3_column_text(statement, 0) else {
            throw MapArchiveError.sqlite(String(cString: sqlite3_errmsg(database)))
        }
        return String(cString: value)
    }

    public func tile(z: Int, x: Int, y: Int) throws -> Data? {
        guard (0...30).contains(z), x >= 0, y >= 0, x < 1 << z, y < 1 << z else { return nil }
        lock.lock(); defer { lock.unlock() }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1", -1, &statement, nil) == SQLITE_OK else {
            throw MapArchiveError.sqlite(String(cString: sqlite3_errmsg(database)))
        }
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_int64(statement, 1, Int64(z))
        sqlite3_bind_int64(statement, 2, Int64(x))
        sqlite3_bind_int64(statement, 3, Int64((1 << z) - 1 - y))
        let status = sqlite3_step(statement)
        if status == SQLITE_DONE { return nil }
        guard status == SQLITE_ROW else { throw MapArchiveError.sqlite(String(cString: sqlite3_errmsg(database))) }
        let size = Int(sqlite3_column_bytes(statement, 0))
        guard size <= 16 * 1024 * 1024 else { throw MapArchiveError.oversizedTile }
        guard size > 0, let bytes = sqlite3_column_blob(statement, 0) else { return nil }
        let data = Data(bytes: bytes, count: size)
        return try Self.uncompressed(data)
    }

    static func uncompressed(_ data: Data) throws -> Data {
        guard data.count >= 2, data[data.startIndex] == 0x1f, data[data.startIndex + 1] == 0x8b else { return data }
        guard data.count <= 16 * 1024 * 1024 else { throw MapArchiveError.oversizedTile }
        var stream = z_stream()
        guard inflateInit2_(&stream, 16 + MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
            throw MapArchiveError.invalidGzip
        }
        defer { inflateEnd(&stream) }
        return try data.withUnsafeBytes { raw -> Data in
            stream.next_in = UnsafeMutablePointer(mutating: raw.bindMemory(to: Bytef.self).baseAddress!)
            stream.avail_in = uInt(data.count)
            var result = Data()
            var buffer = [UInt8](repeating: 0, count: 32 * 1024)
            while true {
                let status = buffer.withUnsafeMutableBytes { bytes -> Int32 in
                    stream.next_out = bytes.bindMemory(to: Bytef.self).baseAddress!
                    stream.avail_out = uInt(bytes.count)
                    return inflate(&stream, Z_NO_FLUSH)
                }
                guard status == Z_OK || status == Z_STREAM_END else { throw MapArchiveError.invalidGzip }
                let count = buffer.count - Int(stream.avail_out)
                guard result.count + count <= 32 * 1024 * 1024 else { throw MapArchiveError.oversizedTile }
                result.append(contentsOf: buffer.prefix(count))
                if status == Z_STREAM_END {
                    guard stream.avail_in == 0 else { throw MapArchiveError.invalidGzip }
                    return result
                }
                guard count > 0 else { throw MapArchiveError.invalidGzip }
            }
        }
    }
}
