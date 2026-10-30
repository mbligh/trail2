#if os(iOS)
import CoreLocation
import Foundation
import UIKit

/// Native location ownership stays outside MapLibre. When-in-use authorization
/// can continue an explicitly started recording with the background indicator.
@MainActor public final class TrailLocationController: NSObject, CLLocationManagerDelegate {
    public let recorder: RecordingCoordinator
    public private(set) var latestFix: RecordedFix?
    public private(set) var heading: Double?
    public private(set) var message = "Location not started"
    public var onChange: (() -> Void)?
    private let manager = CLLocationManager()
    private var timer: Timer?
    private var headingTimer: Timer?
    private var wantsUpdates = false

    public init(store: RecordingStore) {
        recorder = RecordingCoordinator(store: store)
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .fitness
        manager.pausesLocationUpdatesAutomatically = false
    }

    public func startForegroundUpdates() {
        wantsUpdates = true
        switch manager.authorizationStatus {
        case .notDetermined:
            message = "Allow location to show your position"
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
            message = manager.accuracyAuthorization == .fullAccuracy ? "Waiting for GPS" : "Precise location is disabled"
        case .restricted, .denied:
            message = "Location access is disabled in Settings"
        @unknown default:
            message = "Location authorization is unavailable"
        }
        onChange?()
    }

    /// Start only from visible UI. Do not start implicitly on permission changes,
    /// relaunch, or foreground notifications.
    public func startRecording() throws {
        guard UIApplication.shared.applicationState == .active,
              manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways else {
            throw LocationControllerError.permissionRequired
        }
        guard manager.accuracyAuthorization == .fullAccuracy else {
            throw LocationControllerError.preciseLocationRequired
        }
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        guard modes.contains("location") else { throw LocationControllerError.backgroundModeMissing }
        try recorder.start()
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.checkExpiry() }
        }
        message = "Recording"
        onChange?()
    }

    @discardableResult public func stopRecording() throws -> RecordingSession {
        let session = try recorder.stop()
        disableBackgroundRecording()
        message = "Recording saved"
        onChange?()
        return session
    }

    public func addPin(text: String = "") throws {
        try recorder.addPin(text: text)
        onChange?()
    }

    /// Foreground navigation does not retain background location privileges.
    public func enteredBackground() {
        if recorder.recording == nil {
            manager.stopUpdatingLocation()
            manager.stopUpdatingHeading()
        }
    }

    public func enteredForeground() {
        checkExpiry()
        if wantsUpdates { startForegroundUpdates() }
    }

    private func checkExpiry() {
        do {
            if try recorder.expireIfNeeded() {
                disableBackgroundRecording()
                message = "Recording stopped after 4 hours; track saved"
                onChange?()
            }
        } catch { failSaving(error) }
    }

    private func disableBackgroundRecording() {
        timer?.invalidate(); timer = nil
        manager.allowsBackgroundLocationUpdates = false
        if UIApplication.shared.applicationState != .active {
            manager.stopUpdatingLocation()
            manager.stopUpdatingHeading()
        }
    }

    private func failSaving(_ error: Error) {
        // Preserve any already-written points and surface disk errors to the UI.
        // A failed stop keeps its recovery pointer; never silently discard it.
        if recorder.recording != nil { _ = try? recorder.stop() }
        recorder.haltAfterStorageFailure()
        disableBackgroundRecording()
        manager.stopUpdatingLocation()
        message = "Recording stopped: \(error.localizedDescription)"
        onChange?()
    }

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            if recorder.recording != nil { _ = try? recorder.stop() }
            recorder.haltAfterStorageFailure()
            disableBackgroundRecording()
            latestFix = nil; heading = nil
        }
        if recorder.recording != nil && manager.accuracyAuthorization != .fullAccuracy {
            _ = try? recorder.stop()
            recorder.haltAfterStorageFailure()
            disableBackgroundRecording()
            message = "Recording stopped: precise location is disabled"
            onChange?()
            return
        }
        if wantsUpdates { startForegroundUpdates() }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let now = Date()
        do {
            for location in locations.sorted(by: { $0.timestamp < $1.timestamp }) {
                guard location.horizontalAccuracy >= 0,
                      now.timeIntervalSince(location.timestamp) <= 45,
                      now.timeIntervalSince(location.timestamp) >= -5 else { continue }
                let fix = RecordedFix(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                                      time: Int64(location.timestamp.timeIntervalSince1970 * 1000),
                                      altitude: location.verticalAccuracy >= 0 ? location.altitude : nil,
                                      accuracy: location.horizontalAccuracy)
                guard fix.isValid else { continue }
                if latestFix == nil || fix.time > latestFix!.time { latestFix = fix }
                try recorder.receive(fix, at: now)
            }
            if recorder.recording == nil { disableBackgroundRecording() }
            message = recorder.recording == nil ? "GPS ready" : "Recording"
            onChange?()
        } catch { failSaving(error) }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        heading = newHeading.headingAccuracy >= 0 && abs(newHeading.timestamp.timeIntervalSinceNow) <= 5
            ? (newHeading.trueHeading >= 0 ? newHeading.trueHeading : nil) : nil
        headingTimer?.invalidate()
        if heading != nil {
            headingTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in
                Task { @MainActor [weak self] in self?.heading = nil; self?.onChange?() }
            }
        }
        onChange?()
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == .locationUnknown { return } // transient reception loss
        message = error.localizedDescription
        onChange?()
    }
}

public enum LocationControllerError: Error {
    case permissionRequired, preciseLocationRequired, backgroundModeMissing
}
#endif
