import Foundation
import TrailIosProbe
import UIKit

/// Native adapters are compiled with this host, independently of the Android app.
@MainActor final class RecordingHost: NSObject, IosPlatformActions {
    let store: RecordingStore
    let location: TrailLocationController
    private(set) var model: IosRecordingHost!
    private(set) var controller: UIViewController!
    private var trailRepository: TrailRepository!
    private var trailDocument: IosTrailDocument?
    private var trailTask: Task<Void, Never>?
    private var displayed: RecordingSession?

    private var testFix: RecordedFix?
    private var offlineResources: OfflineResources?

    init(testing: Bool = false) {
        do {
            if testing {
                let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                       appropriateFor: nil, create: true)
                store = try RecordingStore(directory: root.appendingPathComponent("Trail/integration-fixture"))
            } else { store = try RecordingStore.applicationStore() }
        }
        catch { fatalError("Cannot open recording storage: \(error)") }
        location = TrailLocationController(store: store)
        super.init()
        let style: String
        if testing {
            style = "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#ffffff\"}}]}"
        } else {
            let resources = OfflineResources()
            offlineResources = resources
            do { style = try resources.style() }
            catch { fatalError("Cannot prepare offline map style: \(error)") }
        }
        model = IosRecordingHost(actions: self, resources: offlineResources, styleJson: style)
        for id in ["1763", "1899", "1205", "503"] {
            if let url = Bundle.main.resourceURL?.appendingPathComponent("trail-icons/mymaps_\(id).png"),
               let data = try? Data(contentsOf: url) { model.installIcon(id: id, png: Self.kotlinBytes(data)) }
        }
        controller = model.viewController()
        do { trailRepository = try TrailRepository(directory: store.directory.appendingPathComponent("map-cache")) }
        catch { show(error) }
        location.onChange = { [weak self] in self?.refresh() }
        refresh()
    }

    func selfTest(phase: String) throws {
        if phase == "write" {
            // Only the dedicated opt-in fixture directory is cleared. A prior CI
            // session must not let the relaunch assertion pass on old data.
            for url in try FileManager.default.contentsOfDirectory(at: store.directory, includingPropertiesForKeys: nil) {
                try FileManager.default.removeItem(at: url)
            }
            var clock = 0.0
            let recorder = RecordingCoordinator(store: store, clock: { clock })
            try recorder.start(at: Date(timeIntervalSince1970: 1000))
            for (index, coordinates) in [(40.689, -74.777), (40.692, -74.771), (40.695, -74.765)].enumerated() {
                clock = Double(index * 30)
                let fix = RecordedFix(latitude: coordinates.0, longitude: coordinates.1,
                                      time: Int64(1_000_000 + index * 30_000), altitude: 80, accuracy: 3)
                try recorder.receive(fix, at: Date(timeIntervalSince1970: 1000 + clock))
            }
            try recorder.addPin(text: "Gate, broken\n🐴 <&>", at: Date(timeIntervalSince1970: 1060))
            displayed = try recorder.stop(at: Date(timeIntervalSince1970: 1061))
        } else {
            displayed = try store.sessions().first
        }
        guard let session = displayed, try store.points(in: session).count == 3,
              try store.pins(in: session).first?.text == "Gate, broken\n🐴 <&>",
              try store.activeSession() == nil else {
            throw RecordingStoreError.corruptSession
        }
        let stats = try summary(session)
        guard stats.distanceMeters > 100, stats.elapsedSeconds == 60 else {
            throw RecordingStoreError.corruptSession
        }
        for contents in [RecordingExports.gpx(name: "Fixture", points: try store.points(in: session), pins: try store.pins(in: session), summary: stats),
                         RecordingExports.kml(name: "Fixture", points: try store.points(in: session), pins: try store.pins(in: session), summary: stats)] {
            guard XMLParser(data: Data(contents.utf8)).parse() else { throw RecordingStoreError.corruptSession }
        }
        testFix = RecordedFix(latitude: 40.692, longitude: -74.771, time: 1_030_000)
        refresh()
        print("PLATFORM_SELF_TEST_PASS:\(phase)")
        fflush(stdout)
    }

    func trailSelfTest(phase: String, url: URL) async throws {
        trailRepository = try TrailRepository(directory: store.directory.appendingPathComponent("trail-test-cache"), url: url)
        let repository = trailRepository!
        var parsed: IosTrailDocument?
        if phase == "write" {
            _ = try await repository.refresh { parsed = try self.parseTrails($0) }
        } else {
            let prior = try repository.cached { parsed = try self.parseTrails($0) }
            var validationReached = false
            do {
                _ = try await repository.refresh {
                    validationReached = true
                    _ = try self.parseTrails($0)
                }
                throw RecordingStoreError.corruptSession
            } catch is TrailDownloadError {
                guard phase == "offline" else { throw RecordingStoreError.corruptSession }
            } catch {
                // The invalid phase must fail in the common parser, and both
                // phases must retain the byte-for-byte previous valid cache.
                guard phase == "invalid", validationReached, !(error is RecordingStoreError) else { throw error }
            }
            guard try repository.cached(validate: { _ = try self.parseTrails($0) }) == prior else {
                throw RecordingStoreError.corruptSession
            }
        }
        guard let document = parsed, document.featureCount == 5,
              document.geoJson.contains("Cached trail fixture") else { throw RecordingStoreError.corruptSession }
        trailDocument = document
        model.showTrails(document: document, status: "Validated trails · \(phase)")
        model.verifyTrails()
        print("TRAIL_CACHE_TEST_PASS:\(phase)")
        fflush(stdout)
    }

    nonisolated private static func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        return bytes
    }

    nonisolated private static func parseTrailData(_ data: Data) throws -> IosTrailDocument {
        try IosTrailBridge().parse(bytes: kotlinBytes(data))
    }

    private func parseTrails(_ data: Data) throws -> IosTrailDocument { try Self.parseTrailData(data) }

    private func refreshTrails(loadCache: Bool = false) {
        guard trailTask == nil, let repository = trailRepository else { return }
        trailTask = Task { [weak self] in
            guard let self = self else { return }
            defer { self.trailTask = nil }
            if loadCache {
                do {
                    let parsed = try await Task.detached(priority: .utility) {
                        var document: IosTrailDocument?
                        _ = try repository.cached { document = try Self.parseTrailData($0) }
                        guard let document = document else { throw RecordingStoreError.corruptSession }
                        return document
                    }.value
                    self.trailDocument = parsed
                    self.model.showTrails(document: parsed, status: "\(parsed.name) · cached")
                } catch { self.model.setTrailStatus(status: "No saved trails") }
            }
            self.model.setTrailStatus(status: "Refreshing trails…")
            do {
                let parsed = try await Task.detached(priority: .utility) {
                    var document: IosTrailDocument?
                    _ = try await repository.refresh { document = try Self.parseTrailData($0) }
                    guard let document = document else { throw RecordingStoreError.corruptSession }
                    return document
                }.value
                self.trailDocument = parsed
                self.model.showTrails(document: parsed, status: parsed.name)
            } catch {
                self.model.setTrailStatus(status: self.trailDocument == nil ? "Trails unavailable — retry Refresh trails" : "Refresh failed — saved trails retained")
            }
        }
    }

    func start() {
        do {
            if let recovered = try store.activeSession() {
                displayed = recovered
                alert("Recovered recording", "An interrupted recording is saved. Finish it before starting a new recording.")
                _ = try store.stop()
            }
        } catch { show(error) }
        location.startForegroundUpdates()
        refreshTrails(loadCache: true)
    }

    func refresh() {
        do {
            if let active = location.recorder.recording { displayed = active }
            if displayed == nil { displayed = try store.sessions().first }
            let points = try displayed.map { try store.points(in: $0) } ?? []
            let pins = try displayed.map { try store.pins(in: $0) } ?? []
            let fix = testFix ?? location.latestFix
            let lines = points.count >= 2 ? [["type": "Feature", "properties": [:],
                    "geometry": ["type": "LineString", "coordinates": points.map { [$0.longitude, $0.latitude] }]]] as [[String: Any]] : []
            let markers: [[String: Any]] = pins.map { pin in
                ["type": "Feature", "properties": ["name": pin.text.isEmpty ? "Pin" : pin.text],
                 "geometry": ["type": "Point", "coordinates": [pin.fix.longitude, pin.fix.latitude]]]
            }
            func collection(_ features: [[String: Any]]) throws -> String {
                let data = try JSONSerialization.data(withJSONObject: ["type": "FeatureCollection", "features": features])
                return String(decoding: data, as: UTF8.self)
            }
            model.update(status: location.message, recording: location.recorder.recording != nil,
                         hasFix: fix != nil, latitude: fix?.latitude ?? 0, longitude: fix?.longitude ?? 0,
                         heading: location.heading ?? -1, track: try collection(lines), pins: try collection(markers))
        } catch { show(error) }
    }

    func perform(action: String) {
        if action.hasPrefix("platformRenderPass:") {
            print("IOS_PLATFORM_RENDER_PASS:" + action.dropFirst("platformRenderPass:".count))
            fflush(stdout)
            return
        }
        if action.hasPrefix("trailRenderPass:") {
            print("IOS_TRAIL_RENDER_PASS:" + action.dropFirst("trailRenderPass:".count))
            fflush(stdout)
            return
        }
        if action.hasPrefix("offlineRenderPass:") {
            print("IOS_OFFLINE_RENDER_PASS:" + action.dropFirst("offlineRenderPass:".count))
            fflush(stdout)
            return
        }
        do {
            switch action {
            case "REFRESH_TRAILS": refreshTrails()
            case "TOGGLE_TRAILS": model.toggleTrails()
            case "ZOOM_TO_TRAILS": model.zoomToTrails()
            case "LEGEND": alert("Trails", "Line colors and point icons follow the My Maps Trails layer.")
            case "record":
                if location.recorder.recording != nil {
                    displayed = try location.stopRecording()
                    if let session = displayed { alert("Recording saved", try summary(session).displaySummary) }
                } else { try location.startRecording() }
            case "pin": try location.addPin()
            case "pinText":
                let prompt = UIAlertController(title: "Pin note", message: nil, preferredStyle: .alert)
                prompt.addTextField()
                prompt.addAction(UIAlertAction(title: "Cancel", style: .cancel))
                prompt.addAction(UIAlertAction(title: "Save", style: .default) { [weak self, weak prompt] _ in
                    do { try self?.location.addPin(text: prompt?.textFields?.first?.text ?? "") }
                    catch { self?.show(error) }
                })
                controller.present(prompt, animated: true)
            case "report":
                guard let fix = location.latestFix,
                      Date().timeIntervalSince1970 - Double(fix.time) / 1000 <= 45 else {
                    alert("Location unavailable", "Wait for a fresh location fix."); return
                }
                let text = "\(fix.latitude), \(fix.longitude)\nhttps://maps.google.com/?q=\(fix.latitude),\(fix.longitude)"
                let share = UIActivityViewController(activityItems: [text], applicationActivities: nil)
                share.popoverPresentationController?.sourceView = controller.view
                controller.present(share, animated: true)
            case "SHARE_GPX": try shareRecording(format: "gpx")
            case "SHARE_KML": try shareRecording(format: "kml")
            case "credits": alert("Map", "MapLibre Compose / Metal. © OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors · Natural Earth. Bundled Tewksbury street map; other iOS basemaps and layers are not connected yet.")
            case "recenter": location.startForegroundUpdates()
            default:
                if action.hasPrefix("label:") { alert("Pin", String(action.dropFirst(6))) }
                else { alert("Not connected yet", "Additional map layers are later iOS adapter stages.") }
            }
            refresh()
        } catch { show(error) }
    }

    private func summary(_ session: RecordingSession) throws -> TrackExportStatistics {
        let csv = try String(contentsOf: store.trackURL(session), encoding: .utf8)
        let result = IosTrackBridge().summarize(csv: csv)
        return TrackExportStatistics(distanceMeters: result.distanceMeters, elapsedSeconds: result.elapsedSeconds,
                                     movingSeconds: result.movingSeconds, ascentMeters: result.ascentMeters,
                                     descentMeters: result.descentMeters)
    }

    private func shareRecording(format: String) throws {
        guard let session = displayed else { alert("No recording", "Record a track first."); return }
        let points = try store.points(in: session)
        let pins = try store.pins(in: session)
        let stats = try summary(session)
        let contents = format == "gpx"
            ? RecordingExports.gpx(name: "Trail recording", points: points, pins: pins, summary: stats)
            : RecordingExports.kml(name: "Trail recording", points: points, pins: pins, summary: stats)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("Trail-exports/" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let url = root.appendingPathComponent("trail." + format)
        try Data(contents.utf8).write(to: url, options: .atomic)
        let share = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        share.popoverPresentationController?.sourceView = controller.view
        controller.present(share, animated: true)
    }

    private func show(_ error: Error) { alert("Trail", error.localizedDescription) }
    private func alert(_ title: String, _ message: String) {
        guard controller.presentedViewController == nil else { return }
        let prompt = UIAlertController(title: title, message: message, preferredStyle: .alert)
        prompt.addAction(UIAlertAction(title: "OK", style: .default))
        controller.present(prompt, animated: true)
    }
}
