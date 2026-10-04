import Foundation

enum PairingText: String, Equatable, Sendable {
    case welcomeTitle
    case welcomeBody
    case stepOne
    case stepOneBody
    case stepTwo
    case stepTwoBody
    case stepThree
    case stepThreeBody
    case scan
    case photo
    case demo
    case disclaimer
    case close
    case scanTitle
    case scanBody
    case scanPreview
    case lanTitle
    case lanBody
    case continueAction
    case back
    case cancel
    case waitTitle
    case waitBody
    case waitHint
    case deviceName
    case route
    case lan
    case tailscale
    case cancelPairing
    case submitting
    case photoReading
    case failTitle
    case networkTitle
    case suggestions
    case expired
    case noAddress
    case invalidAddress
    case fingerprintRequired
    case certificateChanged
    case invalidCode
    case nameTaken
    case badRequest
    case tooManyAttempts
    case hostUnavailable
    case httpFailure
    case networkFailure
    case invalidResponse
    case storageFailure
    case cancelled
    case invalidQR
    case rejected
    case cameraDenied
    case cameraUnavailable
    case noPhotoQR
    case cancelStorageFailure
    case settings
    case tipWifi
    case tipTailscale
    case tipHost
    case tipRefresh
    case tipClock
    case tipScan
    case tipCertificate
    case tipWait
    case tipStorage
    case tipRestart
    case tipCorrectQR
    case tipApproval
    case tipCameraSettings
    case tipPhoto
    case tipClearPhoto
    case sameNameTitle
    case sameNameBody
    case replace
    case rename
    case renameTitle
    case renameBody
    case retry

    var fallback: String {
        switch self {
        case .welcomeTitle: "Put DSH in your pocket"
        case .welcomeBody: "Approve, answer, and follow progress away from your computer."
        case .stepOne: "Open dsh on your computer"
        case .stepOneBody: "Open the Mobile Connection panel."
        case .stepTwo: "Scan the QR code in the panel"
        case .stepTwoBody: "Use the same network, or install Tailscale on both devices."
        case .stepThree: "Approve on your computer"
        case .stepThreeBody: "When confirmation is enabled, approve in the panel."
        case .scan: "Scan to pair"
        case .photo: "Recognize from Photos"
        case .demo: "Try the demo first"
        case .disclaimer: "Unofficial community project. Not affiliated with DeepSeek."
        case .close: "Close scanner"
        case .scanTitle: "Scan the pairing QR code on your computer"
        case .scanBody: "Find it in the Mobile Connection panel in dsh."
        case .scanPreview: "Camera preview"
        case .lanTitle: "Connect to your computer"
        case .lanBody: "DeepLinks needs local network access to connect to dsh on your computer."
        case .continueAction: "Continue"
        case .back: "Back"
        case .cancel: "Cancel"
        case .waitTitle: "Waiting for computer approval"
        case .waitBody: "A pairing request was sent to %@."
        case .waitHint: "Approve this phone in the Mobile Connection panel on your computer."
        case .deviceName: "Phone name"
        case .route: "Connection"
        case .lan: "Local network"
        case .tailscale: "Tailscale"
        case .cancelPairing: "Cancel pairing"
        case .submitting: "Sending pairing request…"
        case .photoReading: "Recognizing QR code…"
        case .failTitle: "Pairing failed"
        case .networkTitle: "Unable to connect to your computer"
        case .suggestions: "Try these steps"
        case .expired: "Refresh the QR code on your computer."
        case .noAddress: "This QR code has no direct connection address."
        case .invalidAddress: "The computer address in this QR code is invalid."
        case .fingerprintRequired: "This QR code is missing the computer certificate fingerprint."
        case .certificateChanged: "The computer certificate changed. Pair again."
        case .invalidCode: "This pairing code is invalid or expired. Refresh it on your computer."
        case .nameTaken: "A phone with this name already exists. Use a different name."
        case .badRequest: "The pairing request format is unsupported. Update the computer plugin."
        case .tooManyAttempts: "Too many pairing attempts. Wait a moment and try again."
        case .hostUnavailable: "The computer service is temporarily unavailable (HTTP %d)."
        case .httpFailure: "Pairing failed (HTTP %d)."
        case .networkFailure: "None of the addresses in the QR code could be reached."
        case .invalidResponse: "The computer returned an invalid pairing response."
        case .storageFailure: "Pairing credentials could not be saved on this phone."
        case .cancelled: "The pairing request was interrupted."
        case .invalidQR: "This is not a valid DeepLinks pairing QR code."
        case .rejected: "The computer rejected the request, or it timed out."
        case .cameraDenied: "Camera access is off. Enable it in Settings or use Photos."
        case .cameraUnavailable: "The camera is unavailable. Use a QR code image from Photos."
        case .noPhotoQR: "No pairing QR code could be recognized in this image."
        case .cancelStorageFailure: "The local record could not be removed. Tap Cancel pairing to retry."
        case .settings: "Open Settings"
        case .tipWifi: "Connect your phone and computer to the same Wi-Fi."
        case .tipTailscale: "For Tailscale, confirm that both devices are online in the same network."
        case .tipHost: "Keep dsh running and check the Mobile Connection panel on your computer."
        case .tipRefresh: "Refresh the QR code in the computer panel."
        case .tipClock: "Check that your phone and computer clocks are correct."
        case .tipScan: "Return and scan the current QR code again."
        case .tipCertificate: "Confirm that the QR code comes from the computer you want to pair with."
        case .tipWait: "Wait for the pairing cooldown before retrying."
        case .tipStorage: "Check that this phone has free storage space."
        case .tipRestart: "Close and reopen DeepLinks, then try again."
        case .tipCorrectQR: "Select the pairing QR code from the dsh panel."
        case .tipApproval: "Check the pairing request in the computer panel."
        case .tipCameraSettings: "Enable camera access for DeepLinks in Settings."
        case .tipPhoto: "Use Recognize from Photos on the welcome screen."
        case .tipClearPhoto: "Choose a clear image showing the entire QR code."
        case .sameNameTitle: "A phone with this name already exists"
        case .sameNameBody:
            "It may be from a previous installation. Replace it, or choose another name. Approval will replace the old phone when computer confirmation is enabled."
        case .replace: "Replace it"
        case .rename: "Choose another name"
        case .renameTitle: "Phone name"
        case .renameBody: "Enter a different name for this phone."
        case .retry: "Pair"
        }
    }
}

struct PairingCopy {
    var locale: Locale

    func text(_ key: PairingText) -> String {
        L10n.string("pairing.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }

    func format(_ key: PairingText, _ argument: CVarArg) -> String {
        String(format: text(key), locale: locale, arguments: [argument])
    }

    func reason(_ failure: PairingFailure) -> String {
        if let hint = failure.hint { return hint }
        if failure.reason == .hostUnavailable || failure.reason == .httpFailure {
            return format(failure.reason, failure.status ?? 0)
        }
        return text(failure.reason)
    }
}
