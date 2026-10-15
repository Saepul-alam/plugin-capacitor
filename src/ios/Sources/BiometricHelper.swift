import Foundation
import LocalAuthentication

public class BiometricHelper {
    public struct BiometricAvailability {
        public let isAvailable: Bool
        public let biometryType: String
        public let reason: String?
    }

    public static func checkAvailability() -> BiometricAvailability {
        let context = LAContext()
        var error: NSError?

        let canAuth = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)

        var typeStr = "none"
        if #available(iOS 11.0, *) {
            switch context.biometryType {
            case .faceID:
                typeStr = "face"
            case .touchID:
                typeStr = "fingerprint"
            default:
                typeStr = "none"
            }
        } else {
            typeStr = canAuth ? "fingerprint" : "none"
        }

        return BiometricAvailability(
            isAvailable: canAuth,
            biometryType: typeStr,
            reason: error?.localizedDescription
        )
    }

    public static func authenticate(reason: String, completion: @escaping (Bool, String?) -> Void) {
        let context = LAContext()
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { success, error in
            DispatchQueue.main.async {
                completion(success, error?.localizedDescription)
            }
        }
    }
}

