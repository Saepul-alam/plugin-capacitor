import UIKit
import AVFoundation
import AudioToolbox

protocol BarcodeScanDelegate: AnyObject {
    func didScanBarcode(content: String, format: String)
    func didCancelBarcodeScan()
}

class BarcodeScanViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    weak var delegate: BarcodeScanDelegate?
    var promptText: String?

    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var isScanned = false
    private var isTorchOn = false

    private let viewfinder = UIView()
    private let promptLabel = UILabel()
    private let cancelButton = UIButton(type: .system)
    private let torchButton = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
        setupUI()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    private func setupCamera() {
        let session = AVCaptureSession()
        guard let videoCaptureDevice = AVCaptureDevice.default(for: .video) else { return }

        guard let videoInput = try? AVCaptureDeviceInput(device: videoCaptureDevice),
              session.canAddInput(videoInput) else { return }
        session.addInput(videoInput)

        let metadataOutput = AVCaptureMetadataOutput()
        guard session.canAddOutput(metadataOutput) else { return }
        session.addOutput(metadataOutput)

        metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        metadataOutput.metadataObjectTypes = [
            .qr, .code128, .code39, .code93, .ean13, .ean8, .upce, .dataMatrix
        ]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        self.previewLayer = preview
        self.captureSession = session

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    private func setupUI() {
        // Viewfinder reticle
        viewfinder.translatesAutoresizingMaskIntoConstraints = false
        viewfinder.layer.borderColor = UIColor.white.cgColor
        viewfinder.layer.borderWidth = 2.0
        viewfinder.layer.cornerRadius = 8.0
        viewfinder.backgroundColor = .clear
        view.addSubview(viewfinder)

        // Instruction label
        promptLabel.translatesAutoresizingMaskIntoConstraints = false
        promptLabel.text = promptText ?? "Arahkan kamera ke barcode / kode QR"
        promptLabel.textColor = .white
        promptLabel.textAlignment = .center
        promptLabel.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        promptLabel.backgroundColor = UIColor(white: 0, alpha: 0.6)
        promptLabel.layer.cornerRadius = 8
        promptLabel.clipsToBounds = true
        view.addSubview(promptLabel)

        // Cancel button
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.setTitle("Batal", for: .normal)
        cancelButton.setTitleColor(.white, for: .normal)
        cancelButton.titleLabel?.font = UIFont.systemFont(ofSize: 16, weight: .bold)
        cancelButton.backgroundColor = UIColor(white: 0, alpha: 0.6)
        cancelButton.layer.cornerRadius = 24
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        view.addSubview(cancelButton)

        // Torch button
        torchButton.translatesAutoresizingMaskIntoConstraints = false
        torchButton.setTitle("Flash", for: .normal)
        torchButton.setTitleColor(.white, for: .normal)
        torchButton.titleLabel?.font = UIFont.systemFont(ofSize: 16, weight: .bold)
        torchButton.backgroundColor = UIColor(white: 0, alpha: 0.6)
        torchButton.layer.cornerRadius = 24
        torchButton.addTarget(self, action: #selector(torchTapped), for: .touchUpInside)
        view.addSubview(torchButton)

        NSLayoutConstraint.activate([
            viewfinder.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            viewfinder.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            viewfinder.widthAnchor.constraint(equalToConstant: 260),
            viewfinder.heightAnchor.constraint(equalToConstant: 260),

            promptLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            promptLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            promptLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            promptLabel.heightAnchor.constraint(equalToConstant: 44),

            cancelButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
            cancelButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 48),
            cancelButton.widthAnchor.constraint(equalToConstant: 100),
            cancelButton.heightAnchor.constraint(equalToConstant: 48),

            torchButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
            torchButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -48),
            torchButton.widthAnchor.constraint(equalToConstant: 100),
            torchButton.heightAnchor.constraint(equalToConstant: 48)
        ])
    }

    @objc private func cancelTapped() {
        captureSession?.stopRunning()
        dismiss(animated: true) { [weak self] in
            self?.delegate?.didCancelBarcodeScan()
        }
    }

    @objc private func torchTapped() {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            isTorchOn = !isTorchOn
            device.torchMode = isTorchOn ? .on : .off
            device.unlockForConfiguration()
        } catch {}
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        if isScanned { return }

        if let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
           let stringValue = metadataObject.stringValue {
            isScanned = true
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)

            captureSession?.stopRunning()
            dismiss(animated: true) { [weak self] in
                self?.delegate?.didScanBarcode(content: stringValue, format: metadataObject.type.rawValue)
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if captureSession?.isRunning == true {
            captureSession?.stopRunning()
        }
    }
}

