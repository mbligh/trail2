import Foundation

public enum TrailDownloadError: Error { case response, tooLarge }

/// Native I/O only. The caller supplies the shared KML validator before either
/// cached bytes or a new response can become the displayed map.
public final class TrailRepository {
    public static let mapURL = URL(string: "https://www.google.com/maps/d/kml?mid=1zy-nQvF6-6P7JSs3paMU8byWHoE&forcekml=1")!
    public static let maxBytes = 5 * 1024 * 1024
    private let cache: URL
    private let url: URL
    private let configuration: URLSessionConfiguration

    public init(directory: URL, url: URL = TrailRepository.mapURL,
                configuration: URLSessionConfiguration = .ephemeral) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        cache = directory.appendingPathComponent("trails.kml")
        self.url = url
        self.configuration = configuration
    }

    public func cached(validate: (Data) throws -> Void) throws -> Data {
        let size = try cache.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= Self.maxBytes else { throw TrailDownloadError.tooLarge }
        let bytes = try Data(contentsOf: cache)
        guard bytes.count <= Self.maxBytes else { throw TrailDownloadError.tooLarge }
        try validate(bytes)
        return bytes
    }

    public func refresh(validate: (Data) throws -> Void) async throws -> Data {
        let bytes = try await BoundedTrailDownload.load(url: url, configuration: configuration)
        try validate(bytes)
        // Foundation's atomic replacement leaves the last validated map intact
        // on invalid responses, interrupted downloads and write failures.
        try bytes.write(to: cache, options: .atomic)
        return bytes
    }
}

private final class BoundedTrailDownload: NSObject, URLSessionDataDelegate {
    private var bytes = Data()
    private var continuation: CheckedContinuation<Data, Error>?
    private var session: URLSession?

    static func load(url: URL, configuration: URLSessionConfiguration) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            let download = BoundedTrailDownload()
            download.continuation = continuation
            let queue = OperationQueue()
            queue.maxConcurrentOperationCount = 1
            let bounded = configuration.copy() as! URLSessionConfiguration
            bounded.timeoutIntervalForResource = 40
            let session = URLSession(configuration: bounded, delegate: download, delegateQueue: queue)
            download.session = session
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
            request.setValue("Trail/2 iOS", forHTTPHeaderField: "User-Agent")
            session.dataTask(with: request).resume()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            completionHandler(.cancel); finish(.failure(TrailDownloadError.response)); return
        }
        guard response.expectedContentLength <= Int64(TrailRepository.maxBytes) else {
            completionHandler(.cancel); finish(.failure(TrailDownloadError.tooLarge)); return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard continuation != nil else { return }
        guard bytes.count + data.count <= TrailRepository.maxBytes else {
            dataTask.cancel(); finish(.failure(TrailDownloadError.tooLarge)); return
        }
        bytes.append(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error.map { .failure($0) } ?? .success(bytes))
    }

    private func finish(_ result: Result<Data, Error>) {
        guard let continuation = continuation else { return }
        self.continuation = nil
        continuation.resume(with: result)
        session?.invalidateAndCancel()
        session = nil
    }
}
