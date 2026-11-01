import Foundation
import CryptoKit

/// Renderer requests use immutable seeds first, then these URL/version-scoped bytes.
/// Downloads are bounded while streaming; incomplete/error responses never become cache entries.
public final class MapResourceCache {
    public static let maxBytes = 8 * 1024 * 1024
    private let downloads = MapDownloadGate()
    private let directory: URL
    private let configuration: URLSessionConfiguration
    public init(directory: URL, configuration: URLSessionConfiguration = .ephemeral) throws {
        self.directory = directory
        self.configuration = configuration
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    public func read(url: URL, identity: String, networkEnabled: Bool) async throws -> Data? {
        let hash = SHA256.hash(data: Data((identity + "\n" + url.absoluteString).utf8)).map { String(format: "%02x", $0) }.joined()
        let file = directory.appendingPathComponent(hash)
        if let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
           size > 0, size <= Self.maxBytes, let data = try? Data(contentsOf: file), data.count == size { return data }
        guard networkEnabled else { return nil }
        await downloads.acquire()
        defer { Task { await downloads.release() } }
        let bytes = try await BoundedResourceDownload.load(url: url, configuration: configuration, maxBytes: Self.maxBytes)
        guard !bytes.isEmpty else { throw TrailDownloadError.response }
        try bytes.write(to: file, options: .atomic)
        return bytes
    }
}


private actor MapDownloadGate {
    private var active = 0
    private var waiting = [CheckedContinuation<Void, Never>]()
    func acquire() async {
        if active < 4 { active += 1; return }
        await withCheckedContinuation { waiting.append($0) }
    }
    func release() {
        if !waiting.isEmpty { waiting.removeFirst().resume() }
        else { active -= 1 }
    }
}
