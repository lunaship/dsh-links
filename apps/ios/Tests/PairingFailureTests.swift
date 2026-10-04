import DLNet
import Foundation
import Testing

@testable import DeepLinks

struct PairingFailureTests {
    @Test func everyErrorHasThreeSuggestionsAndStableReason() {
        let cases: [(PairingError, PairingText)] = [
            (.qrExpired, .expired), (.noLANAddress, .noAddress), (.invalidAddress, .invalidAddress),
            (.fingerprintRequired, .fingerprintRequired), (.certificateChanged, .certificateChanged),
            (.http(code: .invalidCode, status: 401, hint: nil), .invalidCode),
            (.http(code: .conflict, status: 409, hint: nil), .nameTaken),
            (.http(code: .badRequest, status: 415, hint: "ignored"), .badRequest),
            (.http(code: .tooManyAttempts, status: 429, hint: nil), .tooManyAttempts),
            (.http(code: .hostUnavailable, status: 503, hint: nil), .hostUnavailable),
            (.http(code: .other, status: 418, hint: nil), .httpFailure),
            (.transport(.timedOut), .networkFailure), (.invalidResponse, .invalidResponse),
            (.storage, .storageFailure), (.cancelled, .cancelled),
        ]
        for (error, reason) in cases {
            let failure = PairingFailure(error)
            #expect(failure.reason == reason)
            #expect(failure.suggestions.count == 3)
        }
        for failure in [PairingFailure.invalidQR, .rejected, .cameraDenied, .cameraUnavailable, .noPhotoQR] {
            #expect(failure.suggestions.count == 3)
        }
    }

    @Test func hostHintsWinExcept415AndHTTPStatusIsFormatted() {
        let copy = PairingCopy(locale: Locale(identifier: "en"))
        for code in [PairingError.HTTPCode.invalidCode, .conflict, .tooManyAttempts, .hostUnavailable, .other] {
            #expect(copy.reason(PairingFailure(.http(code: code, status: 401, hint: "host hint"))) == "host hint")
        }
        #expect(PairingFailure(.http(code: .badRequest, status: 415, hint: "ignored")).hint == nil)
        #expect(copy.reason(PairingFailure(.http(code: .hostUnavailable, status: 503, hint: nil))).contains("503"))
    }

    @Test func transportDoesNotClaimPermissionWasDenied() {
        let failure = PairingFailure(.transport(.notConnectedToInternet))
        #expect(failure.reason == .networkFailure)
        #expect(failure.offersSettings)
        #expect(failure.suggestions == [.tipWifi, .tipTailscale, .tipHost])
    }
}
