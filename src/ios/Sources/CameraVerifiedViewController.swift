import Foundation
import UIKit
import AVFoundation
import CoreLocation
import CoreImage

protocol CameraVerifiedDelegate: AnyObject {
    func didCapturePhoto(result: VerifiedPhotoResult)
    func didFailCapture(error: Error)
}

struct VerifiedPhotoOptions {
    let quality: Int
    let direction: String
    let requireGPSVerification: Bool
    let maxGPSAgeMs: Int
    let minAccuracyMeters: Double
    let embedEXIF: Bool
    let watermark: Bool
    let resultType: String
    let allowFallback: Bool

    init(from dict: [String: Any]) {
        self.quality = dict["quality"] as? Int ?? 90
        self.direction = dict["direction"] as? String ?? "rear"
        self.requireGPSVerification = dict["requireGPSVerification"] as? Bool ?? true
        self.maxGPSAgeMs = dict["maxGPSAgeMs"] as? Int ?? 5000
        self.minAccuracyMeters = dict["minAccuracyMeters"] as? Double ?? 50.0
        self.embedEXIF = dict["embedEXIF"] as? Bool ?? true
        self.watermark = dict["watermark"] as? Bool ?? false
        self.resultType = dict["resultType"] as? String ?? "uri"
        self.allowFallback = dict["allowFallback"] as? Bool ?? false
    }
}

struct VerifiedPhotoResult {
    let uri: String
    let base64: String?
    let dataUrl: String?
    let exif: [String: Any]
    let path: String?

    func toJSObject() -> JSObject {
        let obj = JSObject()
        obj["uri"] = uri
        if let base64 = base64 { obj["base64"] = base64 }
        if let dataUrl = dataUrl { obj["dataUrl"] = dataUrl }
        obj["exif"] = exif
        if let path = path { obj["path"] = path }
        return obj
    }
}

class CameraVerifiedViewController: UIViewController {
    var options: VerifiedPhotoOptions!
    weak var delegate: CameraVerifiedDelegate?

    private let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let locationManager = CLLocationManager()
    private var currentLocation: CLLocation?
    private var locationTimestamp: Date?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var isCapturing = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
        setupLocation()
        setupUI()
    }

    private func setupCamera() {
        session.beginConfiguration()
        session.sessionPreset = .photo

        guard let camera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: options.direction == "front" ? .front : .back
        ) else {
            delegate?.didFailCapture(error: NSError(domain: "Camera", code: -1, userInfo: [NSLocalizedDescriptionKey: "No camera available"]))
            return
        }

        do {
            let input = try AVCaptureDeviceInput(device: camera)
            if session.canAddInput(input) {
                session.addInput(input)
            }
        } catch {
            delegate?.didFailCapture(error: error)
            return
        }

        if session.canAddOutput(photoOutput) {
            session.addOutput(photoOutput)
        }

        session.commitConfiguration()

        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer?.videoGravity = .resizeAspectFill
        previewLayer?.frame = view.bounds
        if let layer = previewLayer {
            view.layer.insertSublayer(layer, at: 0)
        }

        DispatchQueue.global(qos: .userInitiated).async {
            self.session.startRunning()
        }
    }

    private func setupLocation() {
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
    }

    private func setupUI() {
        let captureBtn = UIButton(type: .system)
        captureBtn.setImage(UIImage(systemName: "camera.circle.fill")?.withTintColor(.white, renderingMode: .alwaysOriginal), for: .normal)
        captureBtn.translatesAutoresizingMaskIntoConstraints = false
        captureBtn.addTarget(self, action: #selector(capturePhoto), for: .touchUpInside)
        view.addSubview(captureBtn)

        let cancelBtn = UIButton(type: .system)
        cancelBtn.setImage(UIImage(systemName: "xmark.circle.fill")?.withTintColor(.white, renderingMode: .alwaysOriginal), for: .normal)
        cancelBtn.translatesAutoresizingMaskIntoConstraints = false
        cancelBtn.addTarget(self, action: #selector(cancelCapture), for: .touchUpInside)
        view.addSubview(cancelBtn)

        let switchBtn = UIButton(type: .system)
        switchBtn.setImage(UIImage(systemName: "camera.rotate.fill")?.withTintColor(.white, renderingMode: .alwaysOriginal), for: .normal)
        switchBtn.translatesAutoresizingMaskIntoConstraints = false
        switchBtn.addTarget(self, action: #selector(switchCamera), for: .touchUpInside)
        view.addSubview(switchBtn)

        let statusLabel = UILabel()
        statusLabel.text = "Getting GPS fix..."
        statusLabel.textColor = .white
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.7)
        statusLabel.textAlignment = .center
        statusLabel.layer.cornerRadius = 8
        statusLabel.clipsToBounds = true
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.tag = 999
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            captureBtn.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            captureBtn.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
            captureBtn.widthAnchor.constraint(equalToConstant: 72),
            captureBtn.heightAnchor.constraint(equalToConstant: 72),

            cancelBtn.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            cancelBtn.centerYAnchor.constraint(equalTo: captureBtn.centerYAnchor),
            cancelBtn.widthAnchor.constraint(equalToConstant: 56),
            cancelBtn.heightAnchor.constraint(equalToConstant: 56),

            switchBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
            switchBtn.centerYAnchor.constraint(equalTo: captureBtn.centerYAnchor),
            switchBtn.widthAnchor.constraint(equalToConstant: 56),
            switchBtn.heightAnchor.constraint(equalToConstant: 56),

            statusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            statusLabel.heightAnchor.constraint(equalToConstant: 40)
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    @objc private func capturePhoto() {
        guard !isCapturing else { return }
        isCapturing = true

        guard let location = currentLocation else {
            isCapturing = false
            updateStatus("No GPS fix yet...")
            return
        }

        let age = Date().timeIntervalSince(locationTimestamp ?? Date()) * 1000
        if options.requireGPSVerification && (age > Double(options.maxGPSAgeMs) || location.horizontalAccuracy > options.minAccuracyMeters) {
            isCapturing = false
            updateStatus("GPS inaccurate (\(Int(location.horizontalAccuracy))m). Try outdoors.")
            return
        }

        let settings = AVCapturePhotoSettings()
        settings.flashMode = .auto
        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    @objc private func cancelCapture() {
        delegate?.didFailCapture(error: NSError(domain: "User", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cancelled by user"]))
        dismiss(animated: true)
    }

    @objc private func switchCamera() {
        session.beginConfiguration()
        session.inputs.forEach { session.removeInput($0) }

        let newPosition: AVCaptureDevice.Position = options.direction == "rear" ? .front : .back
        options = VerifiedPhotoOptions(from: [
            "quality": options.quality,
            "direction": newPosition == .front ? "front" : "rear",
            "requireGPSVerification": options.requireGPSVerification,
            "maxGPSAgeMs": options.maxGPSAgeMs,
            "minAccuracyMeters": options.minAccuracyMeters,
            "embedEXIF": options.embedEXIF,
            "watermark": options.watermark,
            "resultType": options.resultType,
            "allowFallback": options.allowFallback
        ])

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: newPosition),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            session.commitConfiguration()
            return
        }

        session.addInput(input)
        session.commitConfiguration()
    }

    private func updateStatus(_ message: String) {
        DispatchQueue.main.async {
            (self.view.viewWithTag(999) as? UILabel)?.text = message
        }
    }
}

extension CameraVerifiedViewController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        defer { isCapturing = false }

        if let error = error {
            delegate?.didFailCapture(error: error)
            dismiss(animated: true)
            return
        }

        guard let data = photo.fileDataRepresentation(),
              let image = UIImage(data: data),
              let location = currentLocation else {
            delegate?.didFailCapture(error: NSError(domain: "Camera", code: -2, userInfo: [NSLocalizedDescriptionKey: "No image data or location"]))
            dismiss(animated: true)
            return
        }

        let age = Date().timeIntervalSince(locationTimestamp ?? Date()) * 1000
        let result = writeEXIFAndSave(data: data, image: image, location: location, gpsAgeMs: Int(age))
        delegate?.didCapturePhoto(result: result)
        dismiss(animated: true)
    }

    private func writeEXIFAndSave(data: Data, image: UIImage, location: CLLocation, gpsAgeMs: Int) -> VerifiedPhotoResult {
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = "IMG_\(Int(Date().timeIntervalSince1970)).jpg"
        let fileURL = tempDir.appendingPathComponent(fileName)

        do {
            try data.write(to: fileURL)

            if options.embedEXIF {
                addEXIF(to: fileURL, location: location, gpsAgeMs: gpsAgeMs)
            }

            var finalImage = image
            if options.watermark {
                finalImage = addWatermark(to: image, location: location, timestamp: locationTimestamp ?? Date())
                if let jpegData = finalImage.jpegData(compressionQuality: CGFloat(options.quality) / 100) {
                    try jpegData.write(to: fileURL)
                }
            }

            var base64: String? = nil
            var dataUrl: String? = nil
            if options.resultType == "base64" || options.resultType == "dataUrl" {
                base64 = data.base64EncodedString()
                dataUrl = "data:image/jpeg;base64,\(base64!)"
            }

            let exif: [String: Any] = [
                "latitude": location.coordinate.latitude,
                "longitude": location.coordinate.longitude,
                "altitude": location.altitude,
                "accuracy": location.horizontalAccuracy,
                "timestamp": Int(location.timestamp.timeIntervalSince1970 * 1000),
                "isVerified": true,
                "verificationDetails": String(format: "VERIFIED|ACCURACY:%.1f|AGE_MS:%d", location.horizontalAccuracy, gpsAgeMs)
            ]

            return VerifiedPhotoResult(
                uri: fileURL.absoluteString,
                base64: base64,
                dataUrl: dataUrl,
                exif: exif,
                path: fileURL.path
            )
        } catch {
            return VerifiedPhotoResult(
                uri: "",
                base64: nil,
                dataUrl: nil,
                exif: ["isVerified": false, "verificationDetails": "SAVE_FAILED: \(error)"],
                path: nil
            )
        }
    }

    private func addEXIF(to url: URL, location: CLLocation, gpsAgeMs: Int) {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let type = CGImageSourceGetType(source),
              let dest = CGImageDestinationCreateWithURL(url as CFURL, type, 1, nil) else { return }

        var gpsDict: [CFString: Any] = [
            kCGImagePropertyGPSLatitude: abs(location.coordinate.latitude),
            kCGImagePropertyGPSLongitude: abs(location.coordinate.longitude),
            kCGImagePropertyGPSLatitudeRef: location.coordinate.latitude >= 0 ? "N" : "S",
            kCGImagePropertyGPSLongitudeRef: location.coordinate.longitude >= 0 ? "E" : "W",
            kCGImagePropertyGPSAltitude: location.altitude,
            kCGImagePropertyGPSAltitudeRef: location.altitude >= 0 ? 0 : 1,
            kCGImagePropertyGPSTimeStamp: dateToGPSTimeString(location.timestamp),
            kCGImagePropertyGPSDateStamp: dateToGPSDateString(location.timestamp),
            kCGImagePropertyGPSHPositioningError: location.horizontalAccuracy
        ]

        let verificationTag = String(format: "VERIFIED|ACCURACY:%.1f|AGE_MS:%d", location.horizontalAccuracy, gpsAgeMs)
        let exifDict: [CFString: Any] = [
            kCGImagePropertyExifUserComment: verificationTag,
            kCGImagePropertyExifSoftware: "PluginSecure/1.0"
        ]

        let properties: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: gpsDict,
            kCGImagePropertyExifDictionary: exifDict
        ]

        CGImageDestinationAddImageFromSource(dest, source, 0, properties as CFDictionary)
        CGImageDestinationFinalize(dest)
    }

    private func addWatermark(to image: UIImage, location: CLLocation, timestamp: Date) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: image.size)
        return renderer.image { ctx in
            image.draw(at: .zero)

            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
            formatter.timeZone = TimeZone.current
            let timeStr = formatter.string(from: timestamp)

            let watermarkText = "Lat: \(String(format: "%.6f", location.coordinate.latitude))  Lon: \(String(format: "%.6f", location.coordinate.longitude))  Acc: ±\(Int(location.horizontalAccuracy))m  \(timeStr)"

            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.monospacedSystemFont(ofSize: 16, weight: .medium),
                .foregroundColor: UIColor.white,
                .strokeColor: UIColor.black,
                .strokeWidth: -3
            ]

            let textSize = watermarkText.size(withAttributes: attrs)
            let margin: CGFloat = 20
            let rect = CGRect(
                x: margin,
                y: image.size.height - textSize.height - margin,
                width: textSize.width,
                height: textSize.height
            )

            let bgRect = rect.insetBy(dx: -8, dy: -4)
            UIColor.black.withAlphaComponent(0.6).setFill()
            UIBezierPath(roundedRect: bgRect, cornerRadius: 6).fill()

            watermarkText.draw(in: rect, withAttributes: attrs)
        }
    }

    private func dateToGPSTimeString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH/mm/ss"
        formatter.timeZone = TimeZone(abbreviation: "UTC")
        return formatter.string(from: date)
    }

    private func dateToGPSDateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy:MM:dd"
        formatter.timeZone = TimeZone(abbreviation: "UTC")
        return formatter.string(from: date)
    }
}

extension CameraVerifiedViewController: CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        currentLocation = locations.last
        locationTimestamp = Date()

        if let location = currentLocation {
            updateStatus(String(format: "GPS: %.5f, %.5f ±%.1fm", location.coordinate.latitude, location.coordinate.longitude, location.horizontalAccuracy))
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        updateStatus("GPS error: \(error.localizedDescription)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            manager.startUpdatingLocation()
        case .denied, .restricted:
            updateStatus("Location permission denied")
        default:
            break
        }
    }
}