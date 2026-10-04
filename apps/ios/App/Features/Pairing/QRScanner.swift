import AVFoundation
import SwiftUI
import UIKit
import VisionKit

struct QRScanner: UIViewControllerRepresentable {
    var receive: @MainActor (String) -> Bool
    var failure: @MainActor (PairingFailure) -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        QRScannerController(receive: receive, failure: failure)
    }

    func updateUIViewController(_ controller: QRScannerController, context: Context) {
        controller.receive = receive
        controller.failure = failure
    }
}

@MainActor
final class QRScannerController: UIViewController, DataScannerViewControllerDelegate {
    var receive: (String) -> Bool
    var failure: (PairingFailure) -> Void
    private var scanner: DataScannerViewController?
    private var capture: QRCaptureEngine?
    private var preview: AVCaptureVideoPreviewLayer?
    private var accepted = false
    private var visible = false

    init(receive: @escaping (String) -> Bool, failure: @escaping (PairingFailure) -> Void) {
        self.receive = receive
        self.failure = failure
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        visible = true
        Task {
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            let authorized: Bool
            if status == .notDetermined {
                authorized = await AVCaptureDevice.requestAccess(for: .video)
            } else {
                authorized = status == .authorized
            }
            guard visible else { return }
            guard authorized else {
                failure(.cameraDenied)
                return
            }
            startCamera()
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        visible = false
        scanner?.stopScanning()
        capture?.stop()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
        let angle: CGFloat =
            switch view.window?.windowScene?.effectiveGeometry.interfaceOrientation {
            case .landscapeLeft: 0
            case .landscapeRight: 180
            case .portraitUpsideDown: 270
            default: 90
            }
        if let connection = preview?.connection, connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }

    private func startCamera() {
        if DataScannerViewController.isSupported, DataScannerViewController.isAvailable {
            let controller = DataScannerViewController(
                recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
                recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false,
                isPinchToZoomEnabled: true, isGuidanceEnabled: false, isHighlightingEnabled: false)
            controller.delegate = self
            addChild(controller)
            controller.view.frame = view.bounds
            controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(controller.view)
            controller.didMove(toParent: self)
            scanner = controller
            do {
                try controller.startScanning()
                return
            } catch {
                controller.willMove(toParent: nil)
                controller.view.removeFromSuperview()
                controller.removeFromParent()
                scanner = nil
            }
        }
        // Older/unsupported hardware and runtime scanner unavailability use the system camera API.
        let engine = QRCaptureEngine(
            receive: { [weak self] text in self?.recognize(text) },
            failure: { [weak self] in
                guard let self, self.visible, !self.accepted else { return }
                self.failure(.cameraUnavailable)
            })
        let layer = AVCaptureVideoPreviewLayer(session: engine.session)
        layer.videoGravity = .resizeAspectFill
        view.layer.insertSublayer(layer, at: 0)
        preview = layer
        capture = engine
        engine.start()
    }

    private func recognize(_ text: String) {
        guard visible, !accepted else { return }
        if receive(text) {
            accepted = true
            scanner?.stopScanning()
            capture?.stop()
        }
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]
    ) {
        recognizeItems(addedItems)
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]
    ) {
        recognizeItems(updatedItems)
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController,
        becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable
    ) {
        guard visible, !accepted else { return }
        failure(.cameraUnavailable)
    }

    private func recognizeItems(_ items: [RecognizedItem]) {
        for item in items {
            if case .barcode(let barcode) = item, let text = barcode.payloadStringValue { recognize(text) }
        }
    }
}

/// AVFoundation session configuration/start/stop live exclusively on this serial queue.
/// The only cross-queue session access is attaching the system preview layer on MainActor.
private final class QRCaptureEngine: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "dev.deeplinks.pairing.camera")
    private let receive: @MainActor @Sendable (String) -> Void
    private let failure: @MainActor @Sendable () -> Void

    init(
        receive: @escaping @MainActor @Sendable (String) -> Void,
        failure: @escaping @MainActor @Sendable () -> Void
    ) {
        self.receive = receive
        self.failure = failure
    }

    func start() {
        queue.async { [self] in
            do {
                guard let device = AVCaptureDevice.default(for: .video) else { throw CameraError.unavailable }
                let input = try AVCaptureDeviceInput(device: device)
                let output = AVCaptureMetadataOutput()
                session.beginConfiguration()
                guard session.canAddInput(input), session.canAddOutput(output) else {
                    session.commitConfiguration()
                    throw CameraError.unavailable
                }
                session.addInput(input)
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: queue)
                guard output.availableMetadataObjectTypes.contains(.qr) else {
                    session.commitConfiguration()
                    throw CameraError.unavailable
                }
                output.metadataObjectTypes = [.qr]
                session.commitConfiguration()
                session.startRunning()
            } catch {
                Task { @MainActor [failure] in failure() }
            }
        }
    }

    func stop() { queue.async { [self] in session.stopRunning() } }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        let values = metadataObjects.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
        for text in values { Task { @MainActor [receive] in receive(text) } }
    }

    private enum CameraError: Error { case unavailable }
}
