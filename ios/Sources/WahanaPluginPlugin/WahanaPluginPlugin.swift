import Foundation
import Capacitor
import CoreLocation

/**
 * WahanaPluginPlugin for iOS.
 * Provides high-accuracy real-time location streaming and anti-mock / simulation detection.
 */
@objc(WahanaPluginPlugin)
public class WahanaPluginPlugin: CAPPlugin, CLLocationManagerDelegate {
    public let identifier = "WahanaPluginPlugin"
    public let jsName = "WahanaPlugin"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getCurrentPosition", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "watchPosition", returnType: CAPPluginReturnCallback),
        CAPPluginMethod(name: "stopWatch", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isLocationEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkLocationStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openLocationSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openAppSettings", returnType: CAPPluginReturnPromise)
    ]

    private let implementation = WahanaPlugin()
    private var locationManager: CLLocationManager?
    private var watchCalls: [String: CAPPluginCall] = [:]
    private var singleCallQueue: [CAPPluginCall] = []
    private var pendingPermissionCall: CAPPluginCall?
    private var lastLocation: CLLocation?

    override public func load() {
        super.load()
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.locationManager = CLLocationManager()
            self.locationManager?.delegate = self
            self.locationManager?.desiredAccuracy = kCLLocationAccuracyBest
            self.locationManager?.distanceFilter = kCLDistanceFilterNone
        }
    }

    deinit {
        locationManager?.stopUpdatingLocation()
        locationManager?.delegate = nil
    }

    private func getAuthorizationStatus() -> CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return locationManager?.authorizationStatus ?? CLLocationManager().authorizationStatus
        } else {
            return CLLocationManager.authorizationStatus()
        }
    }

    private func isPermissionGranted() -> Bool {
        let status = getAuthorizationStatus()
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    private func getPermissionString() -> String {
        switch getAuthorizationStatus() {
        case .authorizedAlways, .authorizedWhenInUse:
            return "granted"
        case .denied, .restricted:
            return "denied"
        case .notDetermined:
            return "prompt"
        @unknown default:
            return "prompt"
        }
    }

    @objc func getCurrentPosition(_ call: CAPPluginCall) {
        guard CLLocationManager.locationServicesEnabled() else {
            call.reject("Location services (GPS) are disabled")
            return
        }

        guard isPermissionGranted() else {
            call.reject("Location permission not granted")
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let manager = self.locationManager else {
                call.reject("Location manager unavailable")
                return
            }

            // Check if cached location is fresh (< 5 seconds old)
            if let last = self.lastLocation, fabs(last.timestamp.timeIntervalSinceNow) < 5.0 {
                let isMock = self.implementation.isLocationSimulated(last)
                if isMock {
                    self.emitLocationStatusChanged()
                }
                call.resolve(self.implementation.formatLocation(last))
                return
            }

            self.singleCallQueue.append(call)
            manager.requestLocation()
        }
    }

    @objc func watchPosition(_ call: CAPPluginCall) {
        guard CLLocationManager.locationServicesEnabled() else {
            call.reject("Location services (GPS) are disabled")
            return
        }

        guard isPermissionGranted() else {
            call.reject("Location permission not granted")
            return
        }

        let callbackId = call.callbackId
        call.keepAlive = true

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let manager = self.locationManager else {
                call.reject("Location manager unavailable")
                return
            }

            let enableHighAccuracy = call.getBool("enableHighAccuracy", true)
            manager.desiredAccuracy = enableHighAccuracy ? kCLLocationAccuracyBestForNavigation : kCLLocationAccuracyHundredMeters
            manager.distanceFilter = kCLDistanceFilterNone

            self.watchCalls[callbackId] = call
            manager.startUpdatingLocation()

            if let last = self.lastLocation {
                call.resolve(self.implementation.formatLocation(last))
            }
        }
    }

    @objc func stopWatch(_ call: CAPPluginCall) {
        let id = call.getString("id") ?? call.callbackId

        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }

            self.watchCalls.removeValue(forKey: id)
            if self.watchCalls.isEmpty && self.singleCallQueue.isEmpty {
                self.locationManager?.stopUpdatingLocation()
            }
            call.resolve()
        }
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        let perm = getPermissionString()
        call.resolve([
            "location": perm,
            "coarseLocation": perm
        ])
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        if isPermissionGranted() {
            checkPermissions(call)
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let manager = self.locationManager else {
                call.reject("Location manager unavailable")
                return
            }

            self.pendingPermissionCall = call
            manager.requestWhenInUseAuthorization()
        }
    }

    @objc func isLocationEnabled(_ call: CAPPluginCall) {
        call.resolve([
            "enabled": CLLocationManager.locationServicesEnabled()
        ])
    }

    @objc func checkLocationStatus(_ call: CAPPluginCall) {
        let enabled = CLLocationManager.locationServicesEnabled()
        let granted = isPermissionGranted()
        let isMock = lastLocation != nil ? implementation.isLocationSimulated(lastLocation!) : false

        call.resolve(implementation.calculateStatus(enabled: enabled, granted: granted, isMock: isMock))
    }

    @objc func openLocationSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let url = URL(string: UIApplication.openSettingsURLString), UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
        }
        call.resolve()
    }

    @objc func openAppSettings(_ call: CAPPluginCall) {
        openLocationSettings(call)
    }

    private func emitLocationStatusChanged() {
        let enabled = CLLocationManager.locationServicesEnabled()
        let granted = isPermissionGranted()
        let isMock = lastLocation != nil ? implementation.isLocationSimulated(lastLocation!) : false

        notifyListeners("locationStatusChanged", data: implementation.calculateStatus(enabled: enabled, granted: granted, isMock: isMock))
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        emitLocationStatusChanged()

        if let call = pendingPermissionCall {
            pendingPermissionCall = nil
            checkPermissions(call)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        emitLocationStatusChanged()

        if let call = pendingPermissionCall {
            pendingPermissionCall = nil
            checkPermissions(call)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lastLocation = location

        let isMock = implementation.isLocationSimulated(location)
        let formatted = implementation.formatLocation(location)

        for call in singleCallQueue {
            call.resolve(formatted)
        }
        singleCallQueue.removeAll()

        notifyListeners("location", data: formatted)

        for (_, call) in watchCalls {
            call.resolve(formatted)
        }

        if isMock {
            emitLocationStatusChanged()
        }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        for call in singleCallQueue {
            call.reject("Location error: \(error.localizedDescription)")
        }
        singleCallQueue.removeAll()
    }
}
