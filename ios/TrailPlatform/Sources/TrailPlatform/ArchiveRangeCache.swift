import Foundation
import CryptoKit

public enum ArchiveRangeError: Error { case response, changed, unavailable, oversized }
public struct ArchiveSnapshot {
    public let header: Data
    public let etag: String
    public let total: Int64
    fileprivate let directory: URL
}

/// Each archive version has its own directory; ranges from different strong ETags never mix.
public actor ArchiveRangeCache {
    private let root: URL
    private let configuration: URLSessionConfiguration
    private let validateRange: (Int64, Int, String) -> Int64?
    private let downloads = MapDownloadGate()
    private var writes = 0
    public init(directory: URL, configuration: URLSessionConfiguration = .ephemeral,
                validateRange: @escaping (Int64, Int, String) -> Int64? = ArchiveRangeCache.total) throws {
        root = directory; self.configuration = configuration; self.validateRange = validateRange
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    public nonisolated static func hash(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    public nonisolated static func total(_ offset: Int64, _ length: Int, _ header: String) -> Int64? {
        guard offset >= 0, length > 0, offset <= Int64.max - Int64(length) else { return nil }
        let prefix = "bytes \(offset)-\(offset + Int64(length) - 1)/"
        guard header.hasPrefix(prefix), !header.dropFirst(prefix.count).isEmpty,
              header.dropFirst(prefix.count).utf8.allSatisfy({ (48...57).contains($0) }), let value = Int64(header.dropFirst(prefix.count)), value >= offset + Int64(length) else { return nil }
        return value
    }
    public func open(url: URL, identity: String, networkEnabled: Bool, validateHeader: (Data) throws -> Void) async throws -> ArchiveSnapshot {
        let directory = root.appendingPathComponent(Self.hash(url.absoluteString + "\n" + identity))
        let record = directory.appendingPathComponent("current.json")
        var cached: ArchiveSnapshot?
        if let size = try? record.resourceValues(forKeys:[.fileSizeKey]).fileSize, size <= 4096,
           let data = try? Data(contentsOf: record), let meta = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let tag = meta["etag"] as? String, Self.strong(tag), let total = meta["total"] as? Int64, total >= 127 {
            let cohort = directory.appendingPathComponent(Self.hash(tag + "\n" + String(total)))
            if let size = try? cohort.appendingPathComponent("0-127.range").resourceValues(forKeys:[.fileSizeKey]).fileSize, size == 127,
               let header = try? Data(contentsOf: cohort.appendingPathComponent("0-127.range")), header.count == 127 {
                cached = ArchiveSnapshot(header: header, etag: tag, total: total, directory: cohort)
            }
        }
        if networkEnabled {
            do {
                let response = try await fetch(url: url, offset: 0, length: 127, expected: nil)
                try validateHeader(response.bytes)
                let cohort = directory.appendingPathComponent(Self.hash(response.etag + "\n" + String(response.total)))
                try FileManager.default.createDirectory(at: cohort, withIntermediateDirectories: true)
                try response.bytes.write(to: cohort.appendingPathComponent("0-127.range"), options: .atomic)
                try JSONSerialization.data(withJSONObject: ["etag": response.etag, "total": response.total]).write(to: record, options: .atomic)
                return ArchiveSnapshot(header: response.bytes, etag: response.etag, total: response.total, directory: cohort)
            } catch {
                if let cached { try validateHeader(cached.header); return cached }
                throw error
            }
        }
        guard let cached else { throw ArchiveRangeError.unavailable }
        try validateHeader(cached.header)
        return cached
    }
    public func read(url: URL, snapshot: ArchiveSnapshot, offset: Int64, length: Int, networkEnabled: Bool) async throws -> Data? {
        guard offset >= 0, (1...16*1024*1024).contains(length), offset <= snapshot.total - Int64(length) else { throw ArchiveRangeError.oversized }
        let file = snapshot.directory.appendingPathComponent("\(offset)-\(length).range")
        if let size = try? file.resourceValues(forKeys:[.fileSizeKey]).fileSize, size == length,
           let data = try? Data(contentsOf: file), data.count == length {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
            return data
        }
        guard networkEnabled else { return nil }
        let response = try await fetch(url: url, offset: offset, length: length, expected: snapshot)
        try response.bytes.write(to: file, options: .atomic)
        writes += 1
        if writes % 32 == 0 { prune() }
        return response.bytes
    }
    private func fetch(url: URL, offset: Int64, length: Int, expected: ArchiveSnapshot?) async throws -> RangeReply {
        await downloads.acquire(); defer { Task { await downloads.release() } }
        return try await RangeDownload.load(url: url, offset: offset, length: length, expected: expected,
                                            configuration: configuration, validateRange: validateRange)
    }
    fileprivate nonisolated static func strong(_ tag: String) -> Bool {
        tag.count >= 2 && tag.first == "\"" && tag.last == "\"" && !tag.hasPrefix("W/")
    }
    private func prune() {
        guard let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else { return }
        let entries = files.compactMap { $0 as? URL }.filter { $0.pathExtension == "range" && $0.lastPathComponent != "0-127.range" }
            .compactMap { url -> (URL, Int, Date)? in
                guard let value = try? url.resourceValues(forKeys: [.fileSizeKey,.contentModificationDateKey]), let size = value.fileSize else { return nil }
                return (url,size,value.contentModificationDate ?? .distantPast)
            }.sorted { $0.2 < $1.2 }
        var size = entries.reduce(0) { $0 + $1.1 }
        guard size > 500*1024*1024 else { return }
        for (file, bytes, _) in entries {
            if (try? FileManager.default.removeItem(at: file)) != nil { size -= bytes }
            if size <= 400*1024*1024 { break }
        }
    }
}

private struct RangeReply { let bytes: Data; let etag: String; let total: Int64 }
private final class RangeDownload: NSObject, URLSessionDataDelegate {
    private var bytes = Data()
    private var offset: Int64 = 0
    private var length = 0
    private var expected: ArchiveSnapshot?
    private var tag = ""
    private var total: Int64 = 0
    private var validateRange: ((Int64, Int, String) -> Int64?)!
    private var continuation: CheckedContinuation<RangeReply, Error>?
    private var session: URLSession?
    static func load(url: URL, offset: Int64, length: Int, expected: ArchiveSnapshot?, configuration: URLSessionConfiguration,
                     validateRange: @escaping (Int64, Int, String) -> Int64?) async throws -> RangeReply {
        guard url.scheme == "https" || (url.scheme == "http" && url.host == "localhost") else { throw ArchiveRangeError.response }
        return try await withCheckedThrowingContinuation { continuation in
            let loader = RangeDownload()
            loader.offset = offset; loader.length = length; loader.expected = expected
            loader.validateRange = validateRange; loader.continuation = continuation
            let bounded = configuration.copy() as! URLSessionConfiguration
            bounded.timeoutIntervalForResource = 40
            let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
            let session = URLSession(configuration: bounded, delegate: loader, delegateQueue: queue)
            loader.session = session
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
            request.setValue("bytes=\(offset)-\(offset + Int64(length) - 1)", forHTTPHeaderField: "Range")
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.setValue("Trail/2 iOS", forHTTPHeaderField: "User-Agent")
            if let expected { request.setValue(expected.etag, forHTTPHeaderField: "If-Range") }
            session.dataTask(with: request).resume()
        }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, http.statusCode == 206,
              let tag = http.value(forHTTPHeaderField: "ETag"), ArchiveRangeCache.strong(tag),
              let total = validateRange(offset, length, http.value(forHTTPHeaderField: "Content-Range") ?? ""),
              response.expectedContentLength == -1 || response.expectedContentLength == Int64(length),
              [nil, "identity"].contains(http.value(forHTTPHeaderField: "Content-Encoding")) else {
            completionHandler(.cancel); finish(.failure(ArchiveRangeError.response)); return
        }
        if let expected, expected.etag != tag || expected.total != total {
            completionHandler(.cancel); finish(.failure(ArchiveRangeError.changed)); return
        }
        self.tag = tag; self.total = total
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard continuation != nil else { return }
        guard data.count <= length - bytes.count else { dataTask.cancel(); finish(.failure(ArchiveRangeError.oversized)); return }
        bytes.append(data)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }
        else if bytes.count != length { finish(.failure(ArchiveRangeError.response)) }
        else { finish(.success(RangeReply(bytes: bytes, etag: tag, total: total))) }
    }
    private func finish(_ result: Result<RangeReply,Error>) {
        guard let continuation else { return }
        self.continuation = nil; continuation.resume(with: result)
        session?.invalidateAndCancel(); session = nil
    }
}
