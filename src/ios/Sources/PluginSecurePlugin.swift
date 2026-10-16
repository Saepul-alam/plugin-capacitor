import Foundation
import Capacitor
import CoreLocation

@objc(PluginSecurePlugin)
public class PluginSecurePlugin: CAPPlugin, CAPBridgedPlugin {
    private static let methods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "verifyGPS", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkDeviceIntegrity", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureVerifiedPhoto", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startGPSMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopGPSMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkBiometricAvailability", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "authenticateBiometric", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "scanBarcode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "showNativeError", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hideNativeError", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "saveAppState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLastAppState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "enableStatePreservation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "restoreLastAppState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "enableNativeFeel", returnType: CAPPluginReturnPromise)
    ]

    // Capacitor 2 discovers plugins by scanning the ObjC runtime for these
    // CAPBridgedPlugin class methods (CAPBridge.swift) - the Capacitor 3-style
    // instance properties (identifier/jsName/pluginMethods) do not exist here.
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
    private var barcodeCall: CAPPluginCall?

    @objc func verifyGPS(_ call: CAPPluginCall) {
        let result = gpsHelper.verifyGPS()
        call.resolve(result.toJSObject())
    }

    @objc func checkDeviceIntegrity(_ call: CAPPluginCall) {
        let result = gpsHelper.checkDeviceIntegrity()
        call.resolve(result.toJSObject())
    }

    @objc func captureVerifiedPhoto(_ call: CAPPluginCall) {
        self.cameraCall = call
        DispatchQueue.main.async {
            let vc = CameraVerifiedViewController()
            vc.options = VerifiedPhotoOptions(from: call.options as? [String: Any] ?? [:])
            vc.delegate = self
            self.bridge?.viewController.present(vc, animated: true)
        }
    }

    @objc func checkBiometricAvailability(_ call: CAPPluginCall) {
        let result = BiometricHelper.checkAvailability()
        var ret: [String: Any] = [
            "isAvailable": result.isAvailable,
            "biometryType": result.biometryType
        ]
        if let reason = result.reason {
            ret["reason"] = reason
        }
        call.resolve(ret)
    }

    @objc func authenticateBiometric(_ call: CAPPluginCall) {
        let title = call.getString("title") ?? "Verifikasi Biometrik"
        BiometricHelper.authenticate(reason: title) { success, error in
            if success {
                call.resolve(["success": true])
            } else {
                call.resolve(["success": false, "error": error ?? "Authentication failed"])
            }
        }
    }

    @objc func scanBarcode(_ call: CAPPluginCall) {
        self.barcodeCall = call
        DispatchQueue.main.async {
            let vc = BarcodeScanViewController()
            vc.promptText = call.getString("prompt")
            vc.delegate = self
            self.bridge?.viewController.present(vc, animated: true)
        }
    }

    @objc func startGPSMonitoring(_ call: CAPPluginCall) {
        let interval = call.getInt("intervalMs", 30000) ?? 30000
        monitoringTimer?.invalidate()
        monitoringTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(interval)/1000, repeats: true) { _ in
            let result = self.gpsHelper.verifyGPS()
            if result.isMock {
                self.notifyListeners("gpsMockDetected", data: result.toJSObject())
            }
        }
        call.resolve()
    }

    @objc func stopGPSMonitoring(_ call: CAPPluginCall) {
        monitoringTimer?.invalidate()
        monitoringTimer = nil
        call.resolve()
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

extension PluginSecurePlugin: BarcodeScanDelegate {
    func didScanBarcode(content: String, format: String) {
        barcodeCall?.resolve([
            "hasContent": true,
            "content": content,
            "format": format
        ])
        barcodeCall = nil
    }

    func didCancelBarcodeScan() {
        barcodeCall?.resolve([
            "hasContent": false,
            "content": "",
            "format": "NONE"
        ])
        barcodeCall = nil
    }

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
            self.bridge?.viewController.present(alert, animated: true, completion: nil)
            call.resolve()
        }
    }

    @objc func hideNativeError(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc func saveAppState(_ call: CAPPluginCall) {
        let url = call.getString("url") ?? bridge?.webView?.url?.absoluteString
        let route = call.getString("route")
        UserDefaults.standard.set(url, forKey: "al_last_url")
        if let r = route { UserDefaults.standard.set(r, forKey: "al_last_route") }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "al_last_timestamp")
        call.resolve()
    }

    @objc func getLastAppState(_ call: CAPPluginCall) {
        let url = UserDefaults.standard.string(forKey: "al_last_url")
        let route = UserDefaults.standard.string(forKey: "al_last_route")
        let timestamp = UserDefaults.standard.double(forKey: "al_last_timestamp")
        call.resolve([
            "lastUrl": url as Any,
            "lastRoute": route as Any,
            "timestamp": timestamp * 1000,
            "isExpired": false
        ])
    }

    @objc func enableStatePreservation(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc func restoreLastAppState(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let savedUrl = UserDefaults.standard.string(forKey: "al_last_url"), let url = URL(string: savedUrl) {
                self.bridge?.webView?.load(URLRequest(url: url))
                call.resolve(["restored": true])
            } else {
                call.resolve(["restored": false])
            }
        }
    }

    @objc func enableNativeFeel(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.bridge?.webView?.scrollView.bounces = false
            call.resolve()
        }
    }
}