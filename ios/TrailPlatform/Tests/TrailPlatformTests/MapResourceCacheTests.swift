import Foundation
import XCTest
@testable import TrailPlatform

private final class MapURLProtocol: URLProtocol {
    static var status = 200
    static var chunks = [Data]()
    static var requests = 0
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests += 1
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: Self.status,
            httpVersion: "HTTP/1.1", headerFields: [:])!, cacheStoragePolicy: .notAllowed)
        for chunk in Self.chunks { client?.urlProtocol(self, didLoad: chunk) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
final class MapResourceCacheTests: XCTestCase {
    func testOfflineRelaunchIdentityAndFailedDownloads() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MapURLProtocol.self]
        let url = URL(string: "https://map.invalid/1/2/3.png")!
        let cache = try MapResourceCache(directory: root, configuration: config)
        let tile = Data([1, 2, 3])
        MapURLProtocol.status = 200; MapURLProtocol.chunks = [tile]; MapURLProtocol.requests = 0
        let downloaded = try await cache.read(url: url, identity: "v1", networkEnabled: true)
        XCTAssertEqual(downloaded, tile)
        let reopened = try MapResourceCache(directory: root, configuration: config)
        let offline = try await reopened.read(url: url, identity: "v1", networkEnabled: false)
        XCTAssertEqual(offline, tile); XCTAssertEqual(MapURLProtocol.requests, 1)
        let absent = try await reopened.read(url: url, identity: "v2", networkEnabled: false)
        XCTAssertNil(absent)
        for scenario in 0..<3 {
            MapURLProtocol.status = scenario == 0 ? 503 : 200
            MapURLProtocol.chunks = scenario == 1 ? [] : (scenario == 2 ? [Data(repeating: 65, count: MapResourceCache.maxBytes), Data([66])] : [tile])
            do { _ = try await cache.read(url: url, identity: "failed-\(scenario)", networkEnabled: true); XCTFail("Bad response cached") }
            catch {}
            let value = try await reopened.read(url: url, identity: "failed-\(scenario)", networkEnabled: false)
            XCTAssertNil(value)
        }
        let original = try await reopened.read(url: url, identity: "v1", networkEnabled: false)
        XCTAssertEqual(original, tile)
    }
}
