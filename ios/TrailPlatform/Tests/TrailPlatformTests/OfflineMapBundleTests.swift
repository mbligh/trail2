import Foundation
import SQLite3
import XCTest
@testable import TrailPlatform

final class OfflineMapBundleTests: XCTestCase {
    func testExistingAndroidAssetLayoutRoutesEntirelyOffline() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let outside = root.appendingPathExtension("pmtiles")
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: outside)
        }
        try Data("outside archive".utf8).write(to: outside)
        for path in ["styles", "maps", "fonts"] {
            try FileManager.default.createDirectory(at: root.appendingPathComponent(path), withIntermediateDirectories: true)
        }
        func write(_ path: String, _ value: String) throws {
            try Data(value.utf8).write(to: root.appendingPathComponent(path))
        }
        try write("resolved-sources.json", "{\"streets\":{\"type\":\"vector\",\"archive\":\"maps/seed.mbtiles\",\"url\":\"https://remote.invalid/source\"}}")
        try write("bundled-resources.json", "{\"https://remote.invalid/fonts/Test%20Font/0-255.pbf\":\"fonts/test.pbf\"}")
        try write("fonts/test.pbf", "font bytes")
        try write("styles/bright.json", "{\"version\":8,\"sources\":{\"streets\":{\"type\":\"vector\",\"url\":\"https://remote.invalid/source\"}},\"layers\":[]}")
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(root.appendingPathComponent("maps/seed.mbtiles").path, &db), SQLITE_OK)
        XCTAssertEqual(sqlite3_exec(db, "CREATE TABLE metadata(name TEXT,value TEXT); INSERT INTO metadata VALUES('maxzoom','14'); CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB); INSERT INTO tiles VALUES(2,1,2,x'010203');", nil, nil, nil), SQLITE_OK)
        sqlite3_close(db)
        try write("maps/local.pmtiles", "archive")
        try FileManager.default.createSymbolicLink(atPath: root.appendingPathComponent("maps/escape.pmtiles").path, withDestinationPath: outside.path)
        let bundle = try OfflineMapBundle(directory: root)
        XCTAssertEqual(bundle.archiveURL("asset://maps/local.pmtiles"), root.appendingPathComponent("maps/local.pmtiles"))
        for invalid in ["asset://../outside.pmtiles", "asset://%2e%2e/outside.pmtiles", "asset:///outside.pmtiles", "asset://maps/escape.pmtiles", "asset://maps/missing.pmtiles", "https://remote.invalid/archive.pmtiles", "asset://fonts/test.pbf"] {
            XCTAssertNil(bundle.archiveURL(invalid), invalid)
        }
        let style = try bundle.style()
        let rawStyle = try String(contentsOf: root.appendingPathComponent("styles/bright.json"), encoding: .utf8)
        XCTAssertEqual(try bundle.route(styleJson: rawStyle), style)
        XCTAssertThrowsError(try bundle.route(styleJson: "{}"))
        XCTAssertFalse(style.contains("https://remote.invalid/source"))
        XCTAssertFalse(style.contains("archive"))
        XCTAssertTrue(style.contains("maxzoom"))
        XCTAssertEqual(try bundle.read(url: "https://trail.ios.invalid/streets/2/1/1"), Data([1, 2, 3]))
        XCTAssertEqual(try bundle.read(url: "https://remote.invalid/fonts/Test%20Font/0-255.pbf"), Data("font bytes".utf8))
        XCTAssertEqual(try bundle.read(url: "asset://fonts/test.pbf"), Data("font bytes".utf8))
        XCTAssertNil(try bundle.read(url: "https://unlisted.invalid/tile"))
        XCTAssertNil(try bundle.read(url: "asset://../outside"))
        XCTAssertNil(try bundle.read(url: "asset://%2e%2e/outside"))
        XCTAssertNil(try bundle.read(url: "https://trail.ios.invalid/streets/2/4/1"))
    }
}
