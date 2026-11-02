import TrailIosProbe
import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    var recordingHost: RecordingHost?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let arguments = ProcessInfo.processInfo.arguments
        if !arguments.contains("--metal-probe") {
            let testing = arguments.contains("--platform-self-test") || arguments.contains("--trail-cache-test")
            let basemapFlag = arguments.firstIndex(of: "--basemap-self-test")
            let layerFlag = arguments.firstIndex(of: "--layer-self-test")
            let mapFlag = layerFlag ?? basemapFlag
            let phase = mapFlag.flatMap { arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil }
            let origin = mapFlag.flatMap { arguments.indices.contains($0 + 2) ? arguments[$0 + 2] : nil }
            let normal = !testing && !arguments.contains("--offline-map-test") && mapFlag == nil
            let host = RecordingHost(testing: testing, networkEnabled: (normal || phase == "write") && !(origin?.hasPrefix("asset://") ?? false), basemapPhase: basemapFlag == nil ? nil : phase, fixtureOrigin: origin, layerPhase: layerFlag == nil ? nil : phase)
            recordingHost = host
            window.rootViewController = host.controller
            if arguments.contains("--platform-self-test") {
                do { try host.selfTest(phase: arguments.last ?? "read") }
                catch { fatalError("Platform self-test failed: \(error)") }
            }
        } else { window.rootViewController = IosMapProbeKt.iosMapProbeViewController() }
        window.makeKeyAndVisible()
        self.window = window
        if let flag = arguments.firstIndex(of: "--trail-cache-test"), arguments.count > flag + 2,
           let url = URL(string: arguments[flag + 2]) {
            Task { @MainActor in
                do { try await recordingHost?.trailSelfTest(phase: arguments[flag + 1], url: url) }
                catch { fatalError("Trail cache self-test failed: \(error)") }
            }
        }
        if let flag = arguments.firstIndex(of: "--layer-self-test"), arguments.count > flag + 1 {
            do { try recordingHost?.layerSelfTest(phase: arguments[flag + 1]) }
            catch { fatalError("Layer self-test failed: \(error)") }
        } else if let flag = arguments.firstIndex(of: "--basemap-self-test"), arguments.count > flag + 1 {
            do { try recordingHost?.basemapSelfTest(phase: arguments[flag + 1]) }
            catch { fatalError("Basemap self-test failed: \(error)") }
        } else if arguments.contains("--platform-host") || arguments.count == 1 { recordingHost?.start() }
        return true
    }
    func applicationDidEnterBackground(_ application: UIApplication) {
        recordingHost?.saveMapSettings()
        recordingHost?.location.enteredBackground()
    }
    func applicationWillEnterForeground(_ application: UIApplication) {
        recordingHost?.location.enteredForeground()
    }
}
