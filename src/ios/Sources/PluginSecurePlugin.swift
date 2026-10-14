import Foundation
import Capacitor
import CoreLocation

@objc(PluginSecurePlugin)
public class PluginSecurePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PluginSecure"
    public let jsName = "PluginSecure"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "verifyGPS", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkDeviceIntegrity", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureVerifiedPhoto", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startGPSMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopGPSMonitoring", returnType: CAPPluginReturnPromise)
    ]

    private let gpsHelper = GPSVerificationHelper()
    private var monitoringTimer: Timer?
    private var cameraCall: CAPPluginCall?

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
            vc.options = call.options
            vc.delegate = self
            self.bridge?.viewController?.present(vc, animated: true)
        }
    }

    @objc func startGPSMonitoring(_ call: CAPPluginCall) {
        let interval = call.getInt("intervalMs", 30000)
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