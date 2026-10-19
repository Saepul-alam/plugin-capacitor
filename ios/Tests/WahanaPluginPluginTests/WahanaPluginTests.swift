import XCTest
import CoreLocation
@testable import WahanaPluginPlugin

class WahanaPluginTests: XCTestCase {

    func testCalculateStatus_AllValid() {
        let implementation = WahanaPlugin()
        let result = implementation.calculateStatus(enabled: true, granted: true, isMock: false)

        XCTAssertEqual(result["enabled"] as? Bool, true)
        XCTAssertEqual(result["granted"] as? Bool, true)
        XCTAssertEqual(result["isMock"] as? Bool, false)
        XCTAssertEqual(result["usable"] as? Bool, true)
    }

    func testCalculateStatus_GpsDisabled() {
        let implementation = WahanaPlugin()
        let result = implementation.calculateStatus(enabled: false, granted: true, isMock: false)

        XCTAssertEqual(result["usable"] as? Bool, false)
    }

    func testCalculateStatus_PermissionDenied() {
        let implementation = WahanaPlugin()
        let result = implementation.calculateStatus(enabled: true, granted: false, isMock: false)

        XCTAssertEqual(result["usable"] as? Bool, false)
    }

    func testCalculateStatus_MockDetected() {
        let implementation = WahanaPlugin()
        let result = implementation.calculateStatus(enabled: true, granted: true, isMock: true)

        XCTAssertEqual(result["usable"] as? Bool, false)
    }

    func testFormatLocation() {
        let implementation = WahanaPlugin()
        let location = CLLocation(latitude: -6.2088, longitude: 106.8456)
        let formatted = implementation.formatLocation(location)

        XCTAssertNotNil(formatted["timestamp"])
        guard let coords = formatted["coords"] as? [String: Any] else {
            XCTFail("coords should be dictionary")
            return
        }

        XCTAssertEqual(coords["latitude"] as? Double, -6.2088)
        XCTAssertEqual(coords["longitude"] as? Double, 106.8456)
        XCTAssertEqual(coords["isMock"] as? Bool, false)
    }
}
