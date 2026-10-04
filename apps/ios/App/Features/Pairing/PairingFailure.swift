import DLNet
import Foundation

/// Presentation-only mapping of I3.8 errors; never changes host credentials.
struct PairingFailure: Equatable, Sendable {
    let reason: PairingText
    var hint: String?
    var status: Int?
    let suggestions: [PairingText]
    var isNetwork = false
    var offersSettings = false

    init(_ error: PairingError) {
        let network: [PairingText] = [.tipWifi, .tipTailscale, .tipHost]
        let code: [PairingText] = [.tipRefresh, .tipClock, .tipScan]
        hint = nil
        status = nil
        switch error {
        case .qrExpired:
            reason = .expired
            suggestions = code
        case .noLANAddress, .invalidAddress:
            reason = error == .noLANAddress ? .noAddress : .invalidAddress
            suggestions = network
        case .fingerprintRequired, .certificateChanged:
            reason = error == .fingerprintRequired ? .fingerprintRequired : .certificateChanged
            suggestions = [.tipRefresh, .tipCertificate, .tipScan]
        case .http(let code, let status, let hint):
            self.status = status
            self.hint = code == .badRequest ? nil : hint
            switch code {
            case .invalidCode: reason = .invalidCode
            case .conflict: reason = .nameTaken
            case .badRequest: reason = .badRequest
            case .tooManyAttempts: reason = .tooManyAttempts
            case .hostUnavailable: reason = .hostUnavailable
            case .other: reason = .httpFailure
            }
            suggestions =
                code == .tooManyAttempts ? [.tipWait, .tipRefresh, .tipScan] : [.tipRefresh, .tipHost, .tipScan]
        case .transport:
            reason = .networkFailure
            isNetwork = true
            // URLError alone does not establish local-network denial (I3.7 / TN3179).
            offersSettings = true
            suggestions = network
        case .invalidResponse:
            reason = .invalidResponse
            suggestions = [.tipHost, .tipRefresh, .tipScan]
        case .storage:
            reason = .storageFailure
            suggestions = [.tipStorage, .tipRestart, .tipScan]
        case .cancelled:
            reason = .cancelled
            suggestions = [.tipRefresh, .tipHost, .tipScan]
        }
    }

    init(reason: PairingText, suggestions: [PairingText], offersSettings: Bool = false) {
        self.reason = reason
        self.suggestions = suggestions
        self.offersSettings = offersSettings
    }

    static let invalidQR = Self(reason: .invalidQR, suggestions: [.tipRefresh, .tipCorrectQR, .tipScan])
    static let rejected = Self(reason: .rejected, suggestions: [.tipApproval, .tipRefresh, .tipScan])
    static let cameraDenied = Self(
        reason: .cameraDenied, suggestions: [.tipCameraSettings, .tipPhoto, .tipScan], offersSettings: true)
    static let cameraUnavailable = Self(reason: .cameraUnavailable, suggestions: [.tipPhoto, .tipRestart, .tipScan])
    static let noPhotoQR = Self(reason: .noPhotoQR, suggestions: [.tipCorrectQR, .tipClearPhoto, .tipScan])
}
