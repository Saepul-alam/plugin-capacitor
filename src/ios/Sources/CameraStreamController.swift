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
        if session.canAddOutput(output) {
            session.addOutput(output)
        }

        session.commitConfiguration()
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

        onFrame?([
            "base64": jpeg.base64EncodedString(),
            "timestamp": Int(now.timeIntervalSince1970 * 1000)
        ])
    }
}
