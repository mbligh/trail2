import Foundation
import XCTest
@testable import TrailPlatform

private final class ArchiveURLProtocol: URLProtocol {
    static var etag = "\"A\""
    static var mode = "valid"
    static var payload = UInt8(65)
    static var requests = [URLRequest]()
    static var file: Data { Data("PMTiles\u{3}".utf8) + Data(repeating: 0, count:119) + Data(repeating:payload,count:64) }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let pair = request.value(forHTTPHeaderField:"Range")!.dropFirst(6).split(separator:"-").map { Int($0)! }
        let start = pair[0], end = pair[1]
        var body = Self.file.subdata(in:start..<(end+1))
        let contentRange = Self.mode == "range" ? "bytes 0-0/191" : "bytes \(start)-\(end)/\(Self.file.count)"
        var headers = ["Content-Range":contentRange, "ETag":Self.etag, "Content-Length":String(body.count)]
        if Self.mode == "encoding" { headers["Content-Encoding"] = "gzip" }
        if Self.mode == "short" { body = body.dropLast() }
        if Self.mode == "long" { body.append(99) }
        if Self.mode == "header" { body = Data(repeating:0,count:body.count) }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url:request.url!,statusCode:Self.mode == "full" ? 200 : 206,
            httpVersion:"HTTP/1.1",headerFields:headers)!,cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self,didLoad:body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
final class ArchiveRangeCacheTests: XCTestCase {
    private func validate(_ data: Data) throws {
        guard data.count == 127, data.prefix(8) == Data("PMTiles\u{3}".utf8) else { throw ArchiveRangeError.response }
    }
    func testOfflineCacheAndETagCohortsNeverMix() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ArchiveURLProtocol.self]
        let cache = try ArchiveRangeCache(directory:root,configuration:config)
        let url = URL(string:"https://archive.invalid/map.pmtiles")!
        ArchiveURLProtocol.etag = "\"A\""; ArchiveURLProtocol.mode = "valid"; ArchiveURLProtocol.payload = 65; ArchiveURLProtocol.requests = []
        let a = try await cache.open(url:url,identity:"v1",networkEnabled:true,validateHeader:validate)
        let original = try await cache.read(url:url,snapshot:a,offset:127,length:4,networkEnabled:true)
        XCTAssertEqual(original,Data(repeating:65,count:4))
        XCTAssertEqual(ArchiveURLProtocol.requests.last?.value(forHTTPHeaderField:"If-Range"),"\"A\"")
        let count = ArchiveURLProtocol.requests.count
        let reopened = try ArchiveRangeCache(directory:root,configuration:config)
        let offline = try await reopened.open(url:url,identity:"v1",networkEnabled:false,validateHeader:validate)
        let cached = try await reopened.read(url:url,snapshot:offline,offset:127,length:4,networkEnabled:false)
        XCTAssertEqual(cached,original); XCTAssertEqual(ArchiveURLProtocol.requests.count,count)
        do { _ = try await reopened.open(url:url,identity:"v2",networkEnabled:false,validateHeader:validate); XCTFail("Versions mixed") } catch {}
        ArchiveURLProtocol.etag = "\"B\""; ArchiveURLProtocol.payload = 66
        do { _ = try await cache.read(url:url,snapshot:a,offset:140,length:4,networkEnabled:true); XCTFail("Changed ETag accepted") } catch {}
        let absent = try await cache.read(url:url,snapshot:a,offset:140,length:4,networkEnabled:false)
        XCTAssertNil(absent)
        let b = try await cache.open(url:url,identity:"v1",networkEnabled:true,validateHeader:validate)
        let latest = try await cache.read(url:url,snapshot:b,offset:127,length:4,networkEnabled:true)
        XCTAssertEqual(latest,Data(repeating:66,count:4))
        let retained = try await cache.read(url:url,snapshot:a,offset:127,length:4,networkEnabled:false)
        XCTAssertEqual(retained,original)
        ArchiveURLProtocol.mode = "header"
        let fallback = try await cache.open(url:url,identity:"v1",networkEnabled:true,validateHeader:validate)
        XCTAssertEqual(fallback.etag,b.etag); XCTAssertEqual(fallback.header,b.header)
    }
    func testBadRangeResponsesNeverBecomeOfflineEntries() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ArchiveURLProtocol.self]
        let cache = try ArchiveRangeCache(directory:root,configuration:config)
        let url = URL(string:"https://archive.invalid/map.pmtiles")!
        ArchiveURLProtocol.etag = "\"A\""; ArchiveURLProtocol.mode = "valid"
        let snapshot = try await cache.open(url:url,identity:"v1",networkEnabled:true,validateHeader:validate)
        for mode in ["range","short","long","full","encoding","weak"] {
            ArchiveURLProtocol.mode = mode
            ArchiveURLProtocol.etag = mode == "weak" ? "W/\"A\"" : "\"A\""
            do { _ = try await cache.read(url:url,snapshot:snapshot,offset:150,length:4,networkEnabled:true); XCTFail("Accepted \(mode)") } catch {}
            let value = try await cache.read(url:url,snapshot:snapshot,offset:150,length:4,networkEnabled:false)
            XCTAssertNil(value)
        }
    }
}
