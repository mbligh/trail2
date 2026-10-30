import Foundation
import SQLite3
import XCTest
@testable import TrailPlatform

final class MBTilesArchiveTests: XCTestCase {
    func testReadOnlyTMSAndGzippedVectorInteroperability() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("seed.mbtiles")
        let gzip = try XCTUnwrap(Data(base64Encoded: "H4sIAAAAAAACAytLTS7JL1JIy6woKS1KBQCYPrteDgAAAA=="))
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        let hex = gzip.map { String(format: "%02x", $0) }.joined()
        let sql = "CREATE TABLE metadata(name TEXT,value TEXT); INSERT INTO metadata VALUES('maxzoom','14'); CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB); INSERT INTO tiles VALUES(2,1,2,x'\(hex)'); INSERT INTO tiles VALUES(2,1,1,x'89504e47');"
        XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
        sqlite3_close(db)
        let original = try Data(contentsOf: url)
        let archive = try MBTilesArchive(url: url)
        XCTAssertEqual(try archive.metadata("maxzoom"), "14")
        XCTAssertNil(try archive.metadata("missing"))
        XCTAssertEqual(try archive.tile(z: 2, x: 1, y: 1), Data("vector fixture".utf8))
        XCTAssertEqual(try archive.tile(z: 2, x: 1, y: 2), Data([0x89, 0x50, 0x4e, 0x47]))
        XCTAssertNil(try archive.tile(z: 2, x: 3, y: 3))
        XCTAssertNil(try archive.tile(z: -1, x: 0, y: 0))
        XCTAssertNil(try archive.tile(z: 31, x: 0, y: 0))
        XCTAssertNil(try archive.tile(z: 2, x: 4, y: 0))
        XCTAssertNil(try archive.tile(z: 2, x: 0, y: -1))
        XCTAssertEqual(try Data(contentsOf: url), original)
        XCTAssertThrowsError(try MBTilesArchive(url: root.appendingPathComponent("missing.mbtiles")))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("missing.mbtiles").path))
        XCTAssertThrowsError(try MBTilesArchive.uncompressed(gzip.dropLast()))
    }
}
