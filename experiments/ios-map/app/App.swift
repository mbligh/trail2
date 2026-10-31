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
        if arguments.contains("--platform-host") || arguments.contains("--platform-self-test") || arguments.contains("--offline-map-test") || arguments.contains("--trail-cache-test") {
            let testing = arguments.contains("--platform-self-test") || arguments.contains("--trail-cache-test")
            let host = RecordingHost(testing: testing)
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
        if arguments.contains("--platform-host") { recordingHost?.start() }
        return true
    }
    func applicationDidEnterBackground(_ application: UIApplication) {
        recordingHost?.location.enteredBackground()
    }
    func applicationWillEnterForeground(_ application: UIApplication) {
        recordingHost?.location.enteredForeground()
    }
}
