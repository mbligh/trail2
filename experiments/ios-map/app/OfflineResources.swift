import Foundation
import TrailIosProbe

/// Immutable bundle reader may be called on the renderer's worker dispatcher.
/// SQLite readers serialize their own access; UI actions remain on the main actor.
final class OfflineResources: NSObject, IosMapResourceReader {
    private let bundle: OfflineMapBundle

    override init() {
        do {
            guard let root = Bundle.main.resourceURL else { throw MapArchiveError.sqlite("No app resource directory") }
            bundle = try OfflineMapBundle(directory: root.appendingPathComponent("mapAssets"))
        } catch { fatalError("Cannot load bundled offline map: \(error)") }
        super.init()
    }

    func style() throws -> String { try bundle.style() }

    func read(url: String) -> KotlinByteArray? {
        do {
            guard let data = try bundle.read(url: url) else { return nil }
            let bytes = KotlinByteArray(size: Int32(data.count))
            for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }
            if url.hasPrefix("https://trail.ios.invalid/") {
                print("IOS_OFFLINE_TILE_HIT:\(url)")
                fflush(stdout)
            }
            return bytes
        } catch {
            print("IOS_OFFLINE_RESOURCE_ERROR:\(error)")
            fflush(stdout)
            return nil
        }
    }
}
