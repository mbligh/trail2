import Foundation
import XCTest
@testable import TrailPlatform

final class LocalArchiveReaderTests: XCTestCase {
    func testBoundedSparseFileReadsAndPinnedDescriptor() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        _ = FileManager.default.createFile(atPath: url.path, contents: Data(repeating: 7, count: 127))
        let writer = try FileHandle(forWritingTo: url)
        try writer.seek(toOffset: 512*1024*1024)
        try writer.write(contentsOf: Data([4,5,6]))
        try writer.close()
        let reader = try LocalArchiveReader(url: url)
        let tail = try await reader.read(offset: 512*1024*1024, length: 3)
        XCTAssertEqual(tail, Data([4,5,6]))
        // Atomic bundle replacement must not mix bytes from different files.
        try Data(repeating: 9, count: 127).write(to: url, options: .atomic)
        let header = try await reader.read(offset: 0, length: 127)
        XCTAssertEqual(header, Data(repeating: 7, count: 127))
        let invalid: [(Int64,Int)] = [(-1,1),(0,0),(0,16*1024*1024+1),(Int64.max,127),(512*1024*1024,4)]
        for (offset,length) in invalid {
            do { _ = try await reader.read(offset: offset, length: length); XCTFail("Accepted invalid range") }
            catch {}
        }
    }
    func testTruncatedFileDoesNotReturnPartialRange() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        try Data(repeating: 7, count: 127).write(to: url)
        let reader = try LocalArchiveReader(url: url)
        let writer = try FileHandle(forWritingTo: url)
        try writer.truncate(atOffset: 126)
        try writer.close()
        do { _ = try await reader.read(offset: 0, length: 127); XCTFail("Accepted truncated archive") }
        catch {}
        XCTAssertThrowsError(try LocalArchiveReader(url: url))
    }
}
