import Foundation
import XCTest
@testable import TrailPlatform

private final class TrailURLProtocol: URLProtocol {
    static var status = 200
    static var chunks = [Data]()
    static var headers = [String: String]()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: Self.status,
            httpVersion: "HTTP/1.1", headerFields: Self.headers)!, cacheStoragePolicy: .notAllowed)
        for chunk in Self.chunks { client?.urlProtocol(self, didLoad: chunk) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class TrailRepositoryTests: XCTestCase {
    func testValidationFailureHTTPFailureAndOversizePreserveLastMapAcrossRelaunch() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [TrailURLProtocol.self]
        let repository = try TrailRepository(directory: root, configuration: config)
        let valid = Data("validated fixture".utf8)
        func validate(_ data: Data) throws {
            guard data == valid else { throw CocoaError(.fileReadCorruptFile) }
        }
        TrailURLProtocol.status = 200
        TrailURLProtocol.headers = [:]
        TrailURLProtocol.chunks = [valid.prefix(4), valid.dropFirst(4)]
        let downloaded = try await repository.refresh(validate: validate)
        XCTAssertEqual(downloaded, valid)

        for scenario in 0..<4 {
            TrailURLProtocol.status = scenario == 1 ? 503 : 200
            TrailURLProtocol.headers = scenario == 2 ? ["Content-Length": String(TrailRepository.maxBytes + 1)] : [:]
            TrailURLProtocol.chunks = scenario == 3
                ? [Data(repeating: 65, count: TrailRepository.maxBytes), Data([66])]
                : [Data("invalid fixture".utf8)]
            do {
                _ = try await repository.refresh(validate: validate)
                XCTFail("Invalid response accepted: \(scenario)")
            } catch {}
            let reopened = try TrailRepository(directory: root, configuration: config)
            XCTAssertEqual(try reopened.cached(validate: validate), valid)
        }
        // Cached files also go through validation; disk corruption is not trusted.
        try Data("corrupt".utf8).write(to: root.appendingPathComponent("trails.kml"))
        XCTAssertThrowsError(try repository.cached(validate: validate))
    }
}
