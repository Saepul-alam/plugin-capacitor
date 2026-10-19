import Foundation
import CoreLocation

@objc public class WahanaPlugin: NSObject {

    @objc public func isLocationSimulated(_ location: CLLocation) -> Bool {
        if #available(iOS 15.0, *) {
            return location.sourceInformation?.isSimulatedBySoftware ?? false
        }
        return false
    }

    @objc public func formatLocation(_ location: CLLocation) -> [String: Any] {
        var coords: [String: Any] = [
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "isMock": isLocationSimulated(location)
        ]

        if location.verticalAccuracy >= 0 {
            coords["altitude"] = location.altitude
            coords["altitudeAccuracy"] = location.verticalAccuracy
        } else {
            coords["altitude"] = NSNull()
            coords["altitudeAccuracy"] = NSNull()
        }

        if location.course >= 0 {
            coords["heading"] = location.course
        } else {
            coords["heading"] = NSNull()
        }

        if location.speed >= 0 {
            coords["speed"] = location.speed
        } else {
            coords["speed"] = NSNull()
        }

        return [
            "timestamp": Int64(location.timestamp.timeIntervalSince1970 * 1000),
            "coords": coords
        ]
    }

    @objc public func calculateStatus(enabled: Bool, granted: Bool, isMock: Bool) -> [String: Any] {
        let usable = enabled && granted && !isMock
        return [
            "enabled": enabled,
            "granted": granted,
            "isMock": isMock,
            "usable": usable
        ]
    }
}
