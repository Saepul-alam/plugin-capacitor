import Foundation
import CoreLocation
import MachO.dyld
import UIKit

class GPSVerificationHelper: NSObject, CLLocationManagerDelegate {
    let locationManager = CLLocationManager()
    var lastLocation: CLLocation?
    var locationTimestamp: Date?
    var isUpdatingLocation = false

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        lastLocation = locations.last
        locationTimestamp = Date()
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

        if UIApplication.shared.canOpenURL(URL(string: "cydia://")!) {
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

    func startUpdatingLocation() {
        if !isUpdatingLocation {
            locationManager.requestWhenInUseAuthorization()
            locationManager.startUpdatingLocation()
            isUpdatingLocation = true
        }
    }

    func stopUpdatingLocation() {
        if isUpdatingLocation {
            locationManager.stopUpdatingLocation()
            isUpdatingLocation = false
        }
    }
}