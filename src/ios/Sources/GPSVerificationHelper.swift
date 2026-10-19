import Foundation
import CoreLocation
import MachO.dyld
import UIKit

class GPSVerificationHelper: NSObject, CLLocationManagerDelegate {
    // CoreLocation asserts if a manager is used from a thread other than the
    // one it was created on. Capacitor instantiates plugins on a background
    // queue, so the manager is created explicitly on the main thread.
    private(set) var locationManager: CLLocationManager!
    var lastLocation: CLLocation?
    var locationTimestamp: Date?
    var isUpdatingLocation = false

    /// Called with a payload dictionary on every fix when realtime updates are active.
    var onUpdate: (([String: Any]) -> Void)?
    private var pendingCurrentCompletion: (([String: Any]?) -> Void)?

    private var isSimulatorEnv: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    override init() {
        super.init()
        GPSVerificationHelper.runOnMainSync { [weak self] in
            guard let self = self else { return }
            let manager = CLLocationManager()
            manager.delegate = self
            manager.desiredAccuracy = kCLLocationAccuracyBest
            manager.distanceFilter = 10
            self.locationManager = manager
        }
    }

    private static func runOnMainSync(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.sync(execute: block)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        lastLocation = locations.last
        locationTimestamp = Date()

        guard let location = lastLocation else { return }

        if let completion = pendingCurrentCompletion {
            pendingCurrentCompletion = nil
            completion(buildLocationPayload(location))
        }
        onUpdate?(buildLocationPayload(location))
    }

    /// JS payload mirroring the Android `locationUpdate` shape.
    func buildLocationPayload(_ location: CLLocation) -> [String: Any] {
        let ageMs = max(0, Date().timeIntervalSince(location.timestamp) * 1000)
        return [
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "altitude": location.altitude,
            "accuracy": location.horizontalAccuracy,
            "altitudeAccuracy": location.verticalAccuracy,
            "speed": location.speed,
            "heading": location.course,
            "timestamp": location.timestamp.timeIntervalSince1970 * 1000,
            "provider": isSimulatorEnv ? "simulator" : "gps",
            "ageMs": ageMs,
            "isMock": isSimulatorEnv
        ]
    }

    struct GPSVerificationResult {
        let isMock: Bool
        let isJailbroken: Bool
        let details: [String: Any]

        func toJSObject() -> [String: Any] {
            return [
                "isMock": isMock,
                "isJailbroken": isJailbroken,
                "details": details
            ]
        }
    }

    struct DeviceIntegrityResult {
        let isRooted: Bool
        let isJailbroken: Bool
        let details: [String]

        func toJSObject() -> [String: Any] {
            return [
                "isRooted": isRooted,
                "isJailbroken": isJailbroken,
                "details": details
            ]
        }
    }

    func verifyGPS() -> GPSVerificationResult {
        var details: [String: Any] = [:]
        var isMock = false
        var suspiciousApps: [String] = []
        var isSimulator = false

        #if targetEnvironment(simulator)
        isSimulator = true
        isMock = true
        #endif

        // iOS has no public API equivalent to Android's Location.isFromMockProvider
        // (CLLocation.isSimulatedBySoftware does not exist in any released SDK),
        // so software-simulation detection relies on the simulator flag, the dyld
        // image scan and the jailbreak checks below.

        let jailbreakPaths = [
            "/Applications/Cydia.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/bin/bash", "/usr/sbin/sshd", "/etc/apt",
            "/private/var/lib/apt", "/private/var/tmp/cydia.log",
            "/Applications/FakeLocation.app",
            "/Library/Application Support/Cydia",
            "/var/cache/apt",
            "/var/lib/cydia"
        ]

        var jailbreakPathsFound: [String] = []
        for path in jailbreakPaths {
            if FileManager.default.fileExists(atPath: path) {
                jailbreakPathsFound.append(path)
            }
        }

        let imageCount = _dyld_image_count()
        for i in 0..<imageCount {
            if let name = _dyld_get_image_name(i) {
                let nameStr = String(cString: name)
                let suspiciousKeywords = ["LocationFaker", "FakeLocation", "GPSpoofer", "Anywhere", "LocationHandle", "FakeGPS"]
                for keyword in suspiciousKeywords {
                    if nameStr.localizedCaseInsensitiveContains(keyword) {
                        suspiciousApps.append(nameStr)
                        isMock = true
                    }
                }
            }
        }

        let testPath = "/private/test_jailbreak_\(UUID().uuidString).txt"
        do {
            try "test".write(toFile: testPath, atomically: true, encoding: .utf8)
            try FileManager.default.removeItem(atPath: testPath)
            jailbreakPathsFound.append("Sandbox escape possible")
        } catch {}

        // UIApplication is main-thread only; verifyGPS is invoked from the
        // Capacitor background queue.
        var canOpenCydia = false
        let cydiaCheck = { canOpenCydia = UIApplication.shared.canOpenURL(URL(string: "cydia://")!) }
        if Thread.isMainThread {
            cydiaCheck()
        } else {
            DispatchQueue.main.sync(execute: cydiaCheck)
        }
        if canOpenCydia {
            jailbreakPathsFound.append("Cydia URL scheme detected")
        }

        details["mockLocationEnabled"] = isMock
        details["suspiciousApps"] = suspiciousApps
        details["locationProvider"] = "CLLocationManager"
        details["accuracy"] = lastLocation?.horizontalAccuracy ?? -1
        details["timestamp"] = locationTimestamp?.timeIntervalSince1970 ?? 0
        details["isSimulator"] = isSimulator
        details["jailbreakPathsFound"] = jailbreakPathsFound

        return GPSVerificationResult(
            isMock: isMock,
            isJailbroken: !jailbreakPathsFound.isEmpty || !suspiciousApps.isEmpty,
            details: details
        )
    }

    func checkDeviceIntegrity() -> DeviceIntegrityResult {
        var details: [String] = []
        var isJailbroken = false

        let jailbreakPaths = [
            "/Applications/Cydia.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/bin/bash", "/usr/sbin/sshd", "/etc/apt",
            "/private/var/lib/apt", "/private/var/tmp/cydia.log"
        ]

        for path in jailbreakPaths {
            if FileManager.default.fileExists(atPath: path) {
                isJailbroken = true
                details.append("Found: \(path)")
            }
        }

        return DeviceIntegrityResult(isRooted: false, isJailbroken: isJailbroken, details: details)
    }

    func startUpdatingLocation(options: [String: Any]? = nil) {
        // CLLocationManager must be driven from the main thread. Capacitor runs
        // plugin methods on a background queue, which otherwise trips a
        // CoreLocation assertion and aborts the app.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let highAccuracy = (options?["highAccuracy"] as? Bool) ?? true
            self.locationManager.desiredAccuracy = highAccuracy ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
            if let filter = options?["distanceFilter"] as? Double {
                self.locationManager.distanceFilter = filter
            }

            // Continuous tracking: keep updates running even when the device is
            // stationary instead of letting iOS pause them automatically.
            self.locationManager.pausesLocationUpdatesAutomatically = false
            self.locationManager.activityType = .other

            // Background updates require the "location" UIBackgroundMode in Info.plist;
            // enabling the flag without it raises an exception.
            if let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String],
               modes.contains("location") {
                self.locationManager.allowsBackgroundLocationUpdates = true
                self.locationManager.showsBackgroundLocationIndicator = true
            }

            self.requestAlwaysIfPossible()
            self.locationManager.startUpdatingLocation()
            self.isUpdatingLocation = true
        }
    }

    /// Escalate authorization so background updates stay reliable: first ask for
    /// When-In-Use, then for Always once the user has granted When-In-Use.
    private func requestAlwaysIfPossible() {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = locationManager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }

        switch status {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            locationManager.requestAlwaysAuthorization()
        default:
            break
        }
    }

    func stopUpdatingLocation() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if self.isUpdatingLocation {
                self.locationManager.stopUpdatingLocation()
                self.isUpdatingLocation = false
            }
        }
    }

    /// One-shot fix. Calls back with nil if no fix is available before the timeout.
    func requestCurrentLocation(timeout: TimeInterval = 8, completion: @escaping ([String: Any]?) -> Void) {
        // Must run on the main thread; requestLocation() asserts otherwise.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                completion(nil)
                return
            }
            if let location = self.lastLocation, Date().timeIntervalSince(location.timestamp) < 30 {
                completion(self.buildLocationPayload(location))
                return
            }
            self.pendingCurrentCompletion = completion
            self.locationManager.requestWhenInUseAuthorization()
            self.locationManager.requestLocation()
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                guard let self = self, let cb = self.pendingCurrentCompletion else { return }
                self.pendingCurrentCompletion = nil
                cb(self.lastLocation.map { self.buildLocationPayload($0) })
            }
        }
    }
}
