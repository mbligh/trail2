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
        if arguments.contains("--platform-host") || arguments.contains("--platform-self-test") {
            let testing = arguments.contains("--platform-self-test")
            let host = RecordingHost(testing: testing)
            recordingHost = host
            window.rootViewController = host.controller
            if testing {
                do { try host.selfTest(phase: arguments.last ?? "read") }
                catch { fatalError("Platform self-test failed: \(error)") }
            }
        } else { window.rootViewController = IosMapProbeKt.iosMapProbeViewController() }
        window.makeKeyAndVisible()
        self.window = window
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
