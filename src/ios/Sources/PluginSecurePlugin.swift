import Foundation
import Capacitor
import CoreLocation
import AVFoundation
import UIKit

@objc(PluginSecurePlugin)
public class PluginSecurePlugin: CAPPlugin, CAPBridgedPlugin {
    private static let KEY_REQUIRE_LOCATION = "al_require_location"

    private static let methods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "verifyGPS", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkDeviceIntegrity", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureVerifiedPhoto", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startGPSMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopGPSMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "showNativeError", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hideNativeError", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkLocationPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestLocationPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isLocationRequired", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setLocationRequired", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCurrentLocation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startLocationUpdates", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopLocationUpdates", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLocationConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setLocationFeature", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkCameraPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestCameraPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openAppSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openLocationSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCameraConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCameraFeature", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureBurst", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startCameraStream", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopCameraStream", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCameraTorch", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startVideoRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopVideoRecording", returnType: CAPPluginReturnPromise)
    ]

    private static let CAMERA_FEATURES = [
        "cameraOverride", "cameraStream", "captureBurst", "videoRecorder", "watermark"
    ]

    // Capacitor 2 and 3 discover plugins by scanning the ObjC runtime for these
    // CAPBridgedPlugin class methods (CAPBridge.swift). The instance-property
    // protocol (identifier/jsName/pluginMethods) only arrives in Capacitor 4.
    @objc public static func pluginId() -> String! {
        return "PluginSecure"
    }

    @objc public static func jsName() -> String! {
        return "PluginSecure"
    }

    @objc public static func pluginMethods() -> [Any]! {
        return methods as [Any]
    }

    @objc public static func getMethod(_ methodName: String!) -> CAPPluginMethod! {
        guard let name = methodName else { return nil }
        return methods.first(where: { $0.name == name })
    }

    private let gpsHelper = GPSVerificationHelper()
    private var monitoringTimer: Timer?
    private var cameraCall: CAPPluginCall?
    private let cameraStream = CameraStreamController()

    private var locationManager: CLLocationManager?
    private var pendingLocationCall: CAPPluginCall?
    private var pendingLocationAction: (() -> Void)?

    // MARK: - Location permission gate

    private func locationIsRequired() -> Bool {
        if UserDefaults.standard.object(forKey: PluginSecurePlugin.KEY_REQUIRE_LOCATION) != nil {
            return UserDefaults.standard.bool(forKey: PluginSecurePlugin.KEY_REQUIRE_LOCATION)
        }
        if let value = getConfigValue("requireLocation") as? Bool {
            return value
        }
        return true
    }

    private func currentAuthStatus() -> CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return locationManager?.authorizationStatus ?? CLLocationManager.authorizationStatus()
        }
        return CLLocationManager.authorizationStatus()
    }

    private func isLocationGranted(_ status: CLAuthorizationStatus) -> Bool {
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    /// Returns true when the action may proceed. When location is required but
    /// unresolved, it triggers the OS prompt, stores the action and returns false.
    private func requireLocationOrRequest(_ call: CAPPluginCall, action: @escaping () -> Void) -> Bool {
        guard locationIsRequired() else { return true }
        let status = currentAuthStatus()
        if isLocationGranted(status) { return true }
        if status == .denied || status == .restricted {
            call.reject(
                "Izin lokasi diperlukan untuk menggunakan fitur ini. Aktifkan izin lokasi di pengaturan aplikasi.",
                "LOCATION_PERMISSION_DENIED"
            )
            return false
        }
        pendingLocationCall = call
        pendingLocationAction = action
        DispatchQueue.main.async {
            if self.locationManager == nil {
                self.locationManager = CLLocationManager()
            }
            self.locationManager?.delegate = self
            self.locationManager?.requestWhenInUseAuthorization()
        }
        return false
    }

    private func resolvePendingLocation() {
        guard let call = pendingLocationCall else { return }
        let status = currentAuthStatus()
        if isLocationGranted(status) {
            let action = pendingLocationAction
            pendingLocationCall = nil
            pendingLocationAction = nil
            notifyListeners("locationPermissionChanged", data: ["granted": true])
            action?()
        } else if status == .denied || status == .restricted {
            pendingLocationCall = nil
            pendingLocationAction = nil
            notifyListeners("locationPermissionChanged", data: ["granted": false])
            call.reject(
                "Izin lokasi diperlukan untuk menggunakan fitur ini. Aktifkan izin lokasi di pengaturan aplikasi.",
                "LOCATION_PERMISSION_DENIED"
            )
        }
        // .notDetermined -> keep waiting for the user's decision
    }

    /// Returns true when the camera action may proceed. When the permission is
    /// undetermined it triggers the OS prompt and re-dispatches the action.
    private func ensureCameraPermission(_ call: CAPPluginCall, action: @escaping () -> Void) -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                self.notifyListeners("cameraPermissionChanged", data: ["granted": granted])
                DispatchQueue.main.async {
                    if granted {
                        action()
                    } else {
                        call.reject(
                            "Izin kamera diperlukan untuk menggunakan fitur ini. Aktifkan izin kamera di pengaturan aplikasi.",
                            "CAMERA_PERMISSION_DENIED"
                        )
                    }
                }
            }
            return false
        default:
            call.reject(
                "Izin kamera diperlukan untuk menggunakan fitur ini. Aktifkan izin kamera di pengaturan aplikasi.",
                "CAMERA_PERMISSION_DENIED"
            )
            return false
        }
    }

    @objc func checkLocationPermission(_ call: CAPPluginCall) {
        let status = currentAuthStatus()
        call.resolve([
            "granted": isLocationGranted(status),
            "state": authStatusString(status),
            "required": locationIsRequired()
        ])
    }

    @objc func requestLocationPermission(_ call: CAPPluginCall) {
        let status = currentAuthStatus()
        if isLocationGranted(status) {
            call.resolve(["granted": true])
            return
        }
        _ = requireLocationOrRequest(call) {
            call.resolve(["granted": true])
        }
    }

    @objc func isLocationRequired(_ call: CAPPluginCall) {
        call.resolve(["enabled": locationIsRequired()])
    }

    @objc func setLocationRequired(_ call: CAPPluginCall) {
        let enabled = call.getBool("enabled", true)
        UserDefaults.standard.set(enabled, forKey: PluginSecurePlugin.KEY_REQUIRE_LOCATION)
        call.resolve(["enabled": enabled])
    }

    private func locationWatchEnabled() -> Bool {
        let key = "al_location_watch"
        if UserDefaults.standard.object(forKey: key) != nil {
            return UserDefaults.standard.bool(forKey: key)
        }
        if let value = getConfigValue("locationWatch") as? Bool {
            return value
        }
        return true
    }

    @objc func getLocationConfig(_ call: CAPPluginCall) {
        call.resolve(["locationWatch": locationWatchEnabled()])
    }

    @objc func setLocationFeature(_ call: CAPPluginCall) {
        guard let feature = call.getString("feature"), feature == "locationWatch" else {
            call.reject("Fitur lokasi tidak dikenal")
            return
        }
        let enabled = call.getBool("enabled", true)
        UserDefaults.standard.set(enabled, forKey: "al_location_watch")
        call.resolve(["locationWatch": enabled])
    }

    @objc func openAppSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let url = URL(string: UIApplication.openSettingsURLString),
               UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            call.resolve()
        }
    }

    @objc func openLocationSettings(_ call: CAPPluginCall) {
        // iOS has no public per-location-settings URL; the app settings page is the closest.
        openAppSettings(call)
    }

    private func authStatusString(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .authorizedAlways: return "granted"
        case .authorizedWhenInUse: return "granted"
        case .denied: return "denied"
        case .restricted: return "denied"
        case .notDetermined: return "prompt"
        @unknown default: return "prompt"
        }
    }

    @objc func verifyGPS(_ call: CAPPluginCall) {
        if !requireLocationOrRequest(call, action: { [weak self] in self?.verifyGPS(call) }) { return }
        let result = gpsHelper.verifyGPS()
        call.resolve(result.toJSObject())
    }

    @objc func checkDeviceIntegrity(_ call: CAPPluginCall) {
        let result = gpsHelper.checkDeviceIntegrity()
        call.resolve(result.toJSObject())
    }

    @objc func captureVerifiedPhoto(_ call: CAPPluginCall) {
        if !requireLocationOrRequest(call, action: { [weak self] in self?.captureVerifiedPhoto(call) }) { return }
        self.cameraCall = call
        DispatchQueue.main.async {
            var options = call.options as? [String: Any] ?? [:]
            if options["watermark"] == nil { options["watermark"] = self.cameraFlag("watermark") }
            let vc = CameraVerifiedViewController()
            vc.options = VerifiedPhotoOptions(from: options)
            vc.delegate = self
            self.bridge?.viewController?.present(vc, animated: true)
        }
    }

    @objc func startGPSMonitoring(_ call: CAPPluginCall) {
        if !requireLocationOrRequest(call, action: { [weak self] in self?.startGPSMonitoring(call) }) { return }
        let interval = call.getInt("intervalMs", 30000) ?? 30000
        // Timers only fire on a runloop; the Capacitor bridge queue has none.
        DispatchQueue.main.async {
            self.monitoringTimer?.invalidate()
            self.monitoringTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(interval)/1000, repeats: true) { _ in
                let result = self.gpsHelper.verifyGPS()
                if result.isMock {
                    self.notifyListeners("gpsMockDetected", data: result.toJSObject())
                }
            }
        }
        call.resolve()
    }

    @objc func stopGPSMonitoring(_ call: CAPPluginCall) {
        monitoringTimer?.invalidate()
        monitoringTimer = nil
        call.resolve()
    }

    // MARK: - Realtime location updates

    @objc func startLocationUpdates(_ call: CAPPluginCall) {
        if !requireLocationOrRequest(call, action: { [weak self] in self?.startLocationUpdates(call) }) { return }
        let options = call.options as? [String: Any]
        gpsHelper.onUpdate = { [weak self] payload in
            self?.notifyListeners("locationUpdate", data: payload)
        }
        gpsHelper.startUpdatingLocation(options: options)
        call.resolve()
    }

    @objc func stopLocationUpdates(_ call: CAPPluginCall) {
        gpsHelper.onUpdate = nil
        gpsHelper.stopUpdatingLocation()
        call.resolve()
    }

    @objc func getCurrentLocation(_ call: CAPPluginCall) {
        if !requireLocationOrRequest(call, action: { [weak self] in self?.getCurrentLocation(call) }) { return }
        gpsHelper.requestCurrentLocation { payload in
            if let payload = payload {
                call.resolve(payload)
            } else {
                call.reject("Lokasi belum tersedia. Coba lagi di area terbuka.", "LOCATION_UNAVAILABLE")
            }
        }
    }

    // MARK: - Camera feature flags

    private func cameraFlag(_ name: String) -> Bool {
        let key = "al_camera_\(name)"
        if UserDefaults.standard.object(forKey: key) != nil {
            return UserDefaults.standard.bool(forKey: key)
        }
        if let value = getConfigValue(name) as? Bool {
            return value
        }
        return true
    }

    private func cameraConfigDict() -> [String: Any] {
        var dict: [String: Any] = [:]
        for feature in PluginSecurePlugin.CAMERA_FEATURES {
            dict[feature] = cameraFlag(feature)
        }
        return dict
    }

    @objc func getCameraConfig(_ call: CAPPluginCall) {
        call.resolve(cameraConfigDict())
    }

    @objc func setCameraFeature(_ call: CAPPluginCall) {
        guard let feature = call.getString("feature"),
              PluginSecurePlugin.CAMERA_FEATURES.contains(feature) else {
            call.reject("Fitur kamera tidak dikenal")
            return
        }
        let enabled = call.getBool("enabled", true)
        UserDefaults.standard.set(enabled, forKey: "al_camera_\(feature)")
        call.resolve(cameraConfigDict())
    }

    @objc func captureBurst(_ call: CAPPluginCall) {
        if !cameraFlag("captureBurst") {
            call.reject("Fitur captureBurst dimatikan")
            return
        }
        if !requireLocationOrRequest(call, action: { [weak self] in self?.captureBurst(call) }) { return }

        var options = call.options as? [String: Any] ?? [:]
        if options["direction"] == nil { options["direction"] = "front" }
        options["resultType"] = "base64"
        if options["burstCount"] == nil { options["burstCount"] = call.getInt("count", 5) ?? 5 }
        if options["burstIntervalMs"] == nil { options["burstIntervalMs"] = call.getInt("intervalMs", 250) ?? 250 }
        if options["watermark"] == nil { options["watermark"] = cameraFlag("watermark") }

        self.cameraCall = call
        DispatchQueue.main.async {
            let vc = CameraVerifiedViewController()
            vc.options = VerifiedPhotoOptions(from: options)
            vc.delegate = self
            self.bridge?.viewController?.present(vc, animated: true)
        }
    }

    @objc func startCameraStream(_ call: CAPPluginCall) {
        if !cameraFlag("cameraStream") {
            call.reject("Fitur cameraStream dimatikan")
            return
        }
        if !ensureCameraPermission(call, action: { [weak self] in self?.startCameraStream(call) }) { return }
        let facing = call.getString("facingMode") ?? "environment"
        let fps = call.getInt("fps", 15) ?? 15
        let quality = call.getInt("quality", 70) ?? 70
        let torch = call.getBool("torch", false)

        DispatchQueue.main.async {
            let started = self.cameraStream.start(facing: facing, fps: fps, quality: quality, torch: torch) { data in
                self.notifyListeners("cameraFrame", data: data)
            }
            if started {
                call.resolve(["started": true, "rotation": 0])
            } else {
                call.reject("Camera stream tidak dapat dimulai")
            }
        }
    }

    @objc func stopCameraStream(_ call: CAPPluginCall) {
        cameraStream.stop()
        call.resolve()
    }

    @objc func setCameraTorch(_ call: CAPPluginCall) {
        let enabled = call.getBool("enabled", false)
        cameraStream.setTorch(enabled)
        call.resolve()
    }

    @objc func startVideoRecording(_ call: CAPPluginCall) {
        call.reject("Native video recording belum tersedia di iOS")
    }

    @objc func stopVideoRecording(_ call: CAPPluginCall) {
        call.reject("Native video recording belum tersedia di iOS")
    }
}

extension PluginSecurePlugin: CameraVerifiedDelegate {
    func didCapturePhoto(result: VerifiedPhotoResult) {
        cameraCall?.resolve(result.toJSObject())
        cameraCall = nil
    }

    func didFailCapture(error: Error) {
        cameraCall?.reject(error.localizedDescription)
        cameraCall = nil
    }
}

extension PluginSecurePlugin {
    @objc func showNativeError(_ call: CAPPluginCall) {
        let title = call.getString("title") ?? "Halaman Tidak Ditemukan"
        let message = call.getString("message") ?? "Halaman yang Anda tuju tidak dapat dimuat."
        let code = call.getString("code") ?? ""
        let canRetry = call.getBool("canRetry", true)

        DispatchQueue.main.async {
            let alert = UIAlertController(title: title, message: "\(message)\n\(code.isEmpty ? "" : "(\(code))")", preferredStyle: .alert)
            if canRetry {
                alert.addAction(UIAlertAction(title: "Coba Lagi", style: .default, handler: { _ in
                    self.notifyListeners("nativeErrorRetry", data: [:])
                    self.bridge?.webView?.reload()
                }))
            }
            alert.addAction(UIAlertAction(title: "Tutup", style: .cancel, handler: nil))
            self.bridge?.viewController?.present(alert, animated: true, completion: nil)
            call.resolve()
        }
    }

    @objc func hideNativeError(_ call: CAPPluginCall) {
        call.resolve()
    }

    // MARK: - Camera permission gate

    @objc func checkCameraPermission(_ call: CAPPluginCall) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            call.resolve(["granted": true, "state": "granted"])
        case .notDetermined:
            call.resolve(["granted": false, "state": "prompt"])
        default:
            call.resolve(["granted": false, "state": "denied"])
        }
    }

    @objc func requestCameraPermission(_ call: CAPPluginCall) {
        if AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
            call.resolve(["granted": true, "state": "granted"])
            return
        }
        AVCaptureDevice.requestAccess(for: .video) { granted in
            self.notifyListeners("cameraPermissionChanged", data: ["granted": granted])
            DispatchQueue.main.async {
                call.resolve(["granted": granted, "state": granted ? "granted" : "denied"])
            }
        }
    }
}

extension PluginSecurePlugin: CLLocationManagerDelegate {
    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        resolvePendingLocation()
    }

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        resolvePendingLocation()
    }
}