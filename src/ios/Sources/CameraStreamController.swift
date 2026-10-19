import Foundation
import AVFoundation
import CoreImage
import UIKit

// Headless camera preview stream. Frames are delivered to JS through the
// "cameraFrame" event so the web layer can paint them into a canvas
// (getUserMedia polyfill). No view controller required.
class CameraStreamController: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let context = CIContext()
    private let queue = DispatchQueue(label: "com.al.pluginsecure.camerastream")

    private var onFrame: (([String: Any]) -> Void)?
    private var running = false
    private var fps = 15
    private var quality: CGFloat = 0.7
    private var lastFrameAt = Date.distantPast
    private var loggedFirstFrame = false

    func start(
        facing: String,
        fps: Int,
        quality: Int,
        torch: Bool,
        onFrame: @escaping ([String: Any]) -> Void
    ) -> Bool {
        self.onFrame = onFrame
        self.fps = max(1, min(fps, 30))
        self.quality = CGFloat(max(20, min(quality, 100))) / 100.0

        if running {
            applyOrientation()
            setTorch(torch)
            return true
        }

        session.beginConfiguration()
        session.sessionPreset = .medium
        session.inputs.forEach { session.removeInput($0) }

        guard let device = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: facing == "user" ? .front : .back
        ), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
            session.commitConfiguration()
            return false
        }
        session.addInput(input)

        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        if !session.outputs.contains(output), session.canAddOutput(output) {
            session.addOutput(output)
        }
        applyOrientation()

        session.commitConfiguration()
        let outConnection = output.connection(with: .video)
        PluginSecureDebug.append(
            "stream.start facing=\(facing) iface=\(PluginSecureDebug.interfaceOrientationRaw()) device=\(PluginSecureDebug.deviceOrientationRaw()) outOrient=\(outConnection?.videoOrientation.rawValue ?? -1) orientationSupported=\(outConnection?.isVideoOrientationSupported ?? false)"
        )
        setTorch(torch)

        running = true
        DispatchQueue.global(qos: .userInitiated).async {
            self.session.startRunning()
        }
        return true
    }

    func stop() {
        running = false
        if session.isRunning {
            session.stopRunning()
        }
    }

    // Pin the frames to the interface orientation derived from the app's
    // supported orientations. Deliberately NOT reacting to physical device
    // rotation: a portrait-locked host must keep portrait frames even while the
    // phone is turned, otherwise the preview flips to landscape and looks tilted.
    private func applyOrientation() {
        guard let connection = output.connection(with: .video),
              connection.isVideoOrientationSupported else { return }
        connection.videoOrientation = currentVideoOrientation()
    }

    private func currentVideoOrientation() -> AVCaptureVideoOrientation {
        if #available(iOS 13.0, *),
           let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            return AVCaptureVideoOrientation(rawValue: scene.interfaceOrientation.rawValue) ?? .portrait
        }
        return .portrait
    }

    func setTorch(_ enabled: Bool) {
        guard let device = session.inputs.compactMap({ ($0 as? AVCaptureDeviceInput)?.device }).first,
              device.hasTorch else { return }
        if let _ = try? device.lockForConfiguration() {
            device.torchMode = enabled ? .on : .off
            device.unlockForConfiguration()
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard running else { return }

        let now = Date()
        if now.timeIntervalSince(lastFrameAt) < 1.0 / Double(fps) {
            return
        }
        lastFrameAt = now

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return }
        let image = UIImage(cgImage: cgImage)
        guard let jpeg = image.jpegData(compressionQuality: quality) else { return }

        if !loggedFirstFrame {
            loggedFirstFrame = true
            PluginSecureDebug.append(
                "stream.firstFrame iface=\(PluginSecureDebug.interfaceOrientationRaw()) device=\(PluginSecureDebug.deviceOrientationRaw()) buffer=\(CVPixelBufferGetWidth(pixelBuffer))x\(CVPixelBufferGetHeight(pixelBuffer)) outOrient=\(connection.videoOrientation.rawValue) orientationSupported=\(connection.isVideoOrientationSupported)"
            )
        }

        // The capture connection is already oriented, so frames are upright.
        let torchAvailable = session.inputs
            .compactMap { ($0 as? AVCaptureDeviceInput)?.device }
            .first?.hasTorch ?? false
        onFrame?([
            "base64": jpeg.base64EncodedString(),
            "timestamp": Int(now.timeIntervalSince1970 * 1000),
            "rotation": 0,
            "frameWidth": CVPixelBufferGetWidth(pixelBuffer),
            "frameHeight": CVPixelBufferGetHeight(pixelBuffer),
            "torch": torchAvailable
        ])
    }
}
