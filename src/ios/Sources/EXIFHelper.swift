import Foundation
import ImageIO
import CoreLocation

struct EXIFHelper {
    static func readGPSFromImage(at url: URL) -> [String: Any]? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
            return nil
        }

        var result: [String: Any] = [:]

        if let gpsDict = properties[kCGImagePropertyGPSDictionary] as? [CFString: Any] {
            if let lat = gpsDict[kCGImagePropertyGPSLatitude] as? Double,
               let latRef = gpsDict[kCGImagePropertyGPSLatitudeRef] as? String,
               let lon = gpsDict[kCGImagePropertyGPSLongitude] as? Double,
               let lonRef = gpsDict[kCGImagePropertyGPSLongitudeRef] as? String {
                result["latitude"] = latRef == "N" ? lat : -lat
                result["longitude"] = lonRef == "E" ? lon : -lon
            }
            if let alt = gpsDict[kCGImagePropertyGPSAltitude] as? Double {
                result["altitude"] = alt
            }
            if let acc = gpsDict[kCGImagePropertyGPSHPositioningError] as? Double {
                result["accuracy"] = acc
            }
        }

        if let exifDict = properties[kCGImagePropertyExifDictionary] as? [CFString: Any],
           let userComment = exifDict[kCGImagePropertyExifUserComment] as? String {
            result["verificationDetails"] = userComment
            result["isVerified"] = userComment.contains("VERIFIED")
        }

        return result.isEmpty ? nil : result
    }
}