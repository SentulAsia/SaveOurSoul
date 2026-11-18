import CoreLocation
import UIKit

/// Location for SOS frames. While an SOS is active it runs continuous background updates,
/// which is also what keeps the app alive on iOS so retries and the mesh keep working.
@MainActor
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private(set) var latest: CLLocation?
    private var waiters: [CheckedContinuation<CLLocation?, Never>] = []

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        UIDevice.current.isBatteryMonitoringEnabled = true
    }

    func requestPermission() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
    }

    var authorized: Bool {
        [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus)
    }

    /// Start continuous updates that keep running in the background (blue pill in the status bar).
    func startSosTracking() {
        guard authorized else { return }
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.startUpdatingLocation()
    }

    func stopSosTracking() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
    }

    /// Whatever is cached right now: never delays the first SOS frame.
    var quickFix: CLLocation? { latest ?? manager.location }

    /// A fresh fix within `timeout`, else the best cached one.
    func bestFix(timeout: TimeInterval = 8) async -> CLLocation? {
        guard authorized else { return nil }
        if let l = latest, -l.timestamp.timeIntervalSinceNow < 30 { return l }
        manager.requestLocation()
        let fresh: CLLocation? = await withCheckedContinuation { cont in
            waiters.append(cont)
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1e9))
                self.resumeWaiters(with: nil)
            }
        }
        return fresh ?? quickFix
    }

    var batteryPct: UInt8? {
        let level = UIDevice.current.batteryLevel
        return level < 0 ? nil : UInt8((level * 100).rounded())
    }

    private func resumeWaiters(with loc: CLLocation?) {
        let w = waiters
        waiters.removeAll()
        w.forEach { $0.resume(returning: loc) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            self.latest = loc
            self.resumeWaiters(with: loc)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.resumeWaiters(with: nil) }
    }
}
