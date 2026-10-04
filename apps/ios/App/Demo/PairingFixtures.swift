import DLNet
import DLSecurity
import Foundation

/// Shared by snapshots and pairing tests. Contains no live address, token, or camera capture.
enum PairingFixtures {
    static let deviceName = "iPhone"
    static let qr =
        #"{"type":"dsh-link","pairingCode":"123456","name":"Demo Mac","urls":["https://192.0.2.10:18640"],"certFingerprint":"abababababababababababababababababababababababababababababababab"}"#
    static let host = PairedHost(
        hostId: "demo-host", name: "Demo Mac", primaryUrl: "https://192.0.2.10:18640",
        certFingerprint: String(repeating: "ab", count: 32), pairedAt: 0)

    static var offlineServices: PairingServices {
        PairingServices(
            pair: { _, _ in .failed(.invalidResponse) }, wait: { _ in .unknown }, discard: { _ in }, hosts: { [] },
            readCheckpoint: { nil }, writeCheckpoint: { _ in })
    }

    @MainActor
    static func conflictModel() async -> PairingFlowModel {
        let model = PairingFlowModel(
            services: PairingServices(
                pair: { _, _ in .sameName(nil) }, wait: { _ in .unknown }, discard: { _ in }, hosts: { [] },
                readCheckpoint: { nil }, writeCheckpoint: { _ in }),
            gate: LocalNetworkPermissionGate(storage: ExplanationStorage()), deviceName: deviceName)
        model.receive(qr)
        await model.waitForSubmission()
        return model
    }

    @MainActor private final class ExplanationStorage: LocalNetworkPermissionStorage {
        var hasShownExplanation = true
    }
}
