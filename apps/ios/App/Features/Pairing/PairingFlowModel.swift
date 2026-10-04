import DLNet
import DLSecurity
import Foundation
import Observation

/// A nonsecret checkpoint is written BEFORE submitting. A restart can distinguish saved pending
/// credentials from approved hosts even if the app exits between HostStore.save and the response.
struct PairingCheckpoint: Codable, Equatable, Sendable {
    let hostId: String
    let deviceName: String
}

struct PairingServices: Sendable {
    var pair: @Sendable (PairingClient.Attempt, Bool) async -> PairingClient.Result
    var wait: @Sendable (PairedHost) async throws -> PairingApprovalPoller.Approval
    var discard: @Sendable (PairedHost) async throws -> Void
    var hosts: @Sendable () async -> [PairedHost]
    var readCheckpoint: @Sendable () throws -> PairingCheckpoint?
    var writeCheckpoint: @Sendable (PairingCheckpoint?) throws -> Void

    static func live(store: HostStore) -> Self {
        let client = PairingClient(store: store)
        let poller = PairingApprovalPoller(store: store)
        let file = HostStore.defaultFileURL().deletingLastPathComponent()
            .appendingPathComponent("pending-pairing.json")
        return Self(
            pair: { await client.pair($0, replacing: $1) },
            wait: { try await poller.wait(for: $0) },
            discard: { try await poller.discardPendingPairing(for: $0) },
            hosts: { await store.all() },
            readCheckpoint: {
                guard FileManager.default.fileExists(atPath: file.path) else { return nil }
                return try JSONDecoder().decode(PairingCheckpoint.self, from: Data(contentsOf: file))
            },
            writeCheckpoint: { checkpoint in
                if let checkpoint {
                    try FileManager.default.createDirectory(
                        at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try JSONEncoder().encode(checkpoint).write(to: file, options: .atomic)
                } else if FileManager.default.fileExists(atPath: file.path) {
                    try FileManager.default.removeItem(at: file)
                }
            })
    }
}

@MainActor @Observable
final class PairingFlowModel {
    enum Page: Equatable {
        case welcome, explanation, submitting, pending, failure
    }

    private(set) var page: Page = .welcome
    private(set) var failure = PairingFailure.invalidQR
    private(set) var pendingHost: PairedHost?
    private(set) var pairedHost: PairedHost?
    private(set) var conflictPresented = false
    private(set) var renamePresented = false
    private(set) var isCancelling = false
    private(set) var scanHint: PairingText?
    var deviceName: String

    @ObservationIgnored private let services: PairingServices
    @ObservationIgnored private let gate: LocalNetworkPermissionGate
    @ObservationIgnored private let clock: () -> Int
    @ObservationIgnored private var attempt: PairingClient.Attempt?
    @ObservationIgnored private var operation: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var active = true
    @ObservationIgnored private var restored = false

    init(
        services: PairingServices, gate: LocalNetworkPermissionGate, deviceName: String,
        clock: @escaping () -> Int = { Int(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.services = services
        self.gate = gate
        self.deviceName = deviceName
        self.clock = clock
    }

    func restore() async {
        guard !restored else { return }
        restored = true
        do {
            let checkpoint = try services.readCheckpoint()
            let hosts = await services.hosts()
            // Do not override a scan submitted while storage was loading.
            guard page == .welcome, attempt == nil else { return }
            if let checkpoint, let host = hosts.first(where: { $0.hostId == checkpoint.hostId }) {
                deviceName = checkpoint.deviceName
                pendingHost = host
                page = .pending
                startPolling()
            } else {
                try services.writeCheckpoint(nil)
                pairedHost = hosts.first
            }
        } catch {
            showFailure(PairingFailure(.storage))
        }
    }

    /// Invalid live scans stay on the scanner with a short hint; no request is submitted.
    @discardableResult
    func receive(_ text: String, fromCamera: Bool = false) -> Bool {
        guard page == .welcome else { return false }
        do {
            let qr = try PairingQRPayload(text)
            if let expiry = qr.expiresAt, clock() > expiry {
                showFailure(PairingFailure(.qrExpired))
                return true
            }
            scanHint = nil
            pairedHost = nil
            attempt = PairingClient.Attempt(qr: qr, deviceName: deviceName)
            if qr.urls.contains(where: { gate.requiresExplanation(for: normalized($0)) }) {
                page = .explanation
                gate.markExplanationShown()
            } else {
                submit()
            }
            return true
        } catch {
            if fromCamera {
                scanHint = .invalidQR
                return false
            }
            showFailure(.invalidQR)
            return true
        }
    }

    func continueAfterExplanation() {
        guard page == .explanation else { return }
        submit()
    }

    func replace() {
        guard conflictPresented else { return }
        conflictPresented = false
        submit(replacing: true)
    }

    func chooseNewName() {
        conflictPresented = false
        renamePresented = true
    }

    func renameAndRetry(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let old = attempt, !trimmed.isEmpty, trimmed != old.deviceName else { return }
        deviceName = trimmed
        // A changed body is a NEW logical request; replacement instead reuses the old requestId.
        attempt = PairingClient.Attempt(qr: old.qr, deviceName: trimmed, hostId: old.hostId)
        renamePresented = false
        submit()
    }

    func returnToWelcome() {
        guard page != .pending, page != .submitting else { return }
        operation?.cancel()
        generation += 1
        conflictPresented = false
        renamePresented = false
        attempt = nil
        scanHint = nil
        page = .welcome
    }

    func showFailure(_ failure: PairingFailure) {
        self.failure = failure
        page = .failure
    }

    func setActive(_ active: Bool) {
        self.active = active
        guard page == .pending, !isCancelling else { return }
        if active {
            startPolling()
        } else {
            generation += 1
            operation?.cancel()
            operation = nil
        }
    }

    func cancelPending() async {
        guard let host = pendingHost, page == .pending, !isCancelling else { return }
        isCancelling = true
        generation += 1
        let polling = operation
        polling?.cancel()
        operation = nil
        await polling?.value
        do {
            try await services.discard(host)
            try services.writeCheckpoint(nil)
            pendingHost = nil
            attempt = nil
            page = .welcome
        } catch {
            // Keep the waiting page and expose a retryable error; do not claim cleanup succeeded.
            scanHint = .cancelStorageFailure
        }
        isCancelling = false
    }

    /// Joins an in-flight submission (used by deterministic offline fixtures/tests).
    func waitForSubmission() async {
        guard page == .submitting else { return }
        await operation?.value
    }

    private func submit(replacing: Bool = false) {
        guard let attempt else { return }
        generation += 1
        let ticket = generation
        page = .submitting
        operation = Task {
            do {
                try services.writeCheckpoint(.init(hostId: attempt.hostId, deviceName: attempt.deviceName))
            } catch {
                showFailure(PairingFailure(.storage))
                return
            }
            let result = await services.pair(attempt, replacing)
            guard ticket == generation else { return }
            switch result {
            case .paired(let host): finish(host)
            case .pending(let host, _):
                pendingHost = host
                page = .pending
                operation = nil
                startPolling()
            case .sameName:
                page = .welcome
                conflictPresented = true
            case .failed(let error): showFailure(PairingFailure(error))
            }
        }
    }

    private func startPolling() {
        guard active, !isCancelling, operation == nil, let host = pendingHost else { return }
        generation += 1
        let ticket = generation
        operation = Task {
            do {
                let approval = try await services.wait(host)
                guard ticket == generation, !Task.isCancelled else { return }
                operation = nil
                switch approval {
                case .approved: finish(host)
                case .rejected:
                    try services.writeCheckpoint(nil)
                    pendingHost = nil
                    showFailure(.rejected)
                case .pending, .unknown: break  // live poller only returns terminal states
                }
            } catch is CancellationError {
                // Scene backgrounding retains the checkpoint and credentials.
            } catch {
                guard ticket == generation else { return }
                operation = nil
                showFailure(PairingFailure(.storage))
            }
        }
    }

    private func finish(_ host: PairedHost) {
        do {
            try services.writeCheckpoint(nil)
            pendingHost = nil
            attempt = nil
            operation = nil
            pairedHost = host
            page = .welcome
        } catch {
            showFailure(PairingFailure(.storage))
        }
    }

    private func normalized(_ address: String) -> String {
        if address.lowercased().hasPrefix("https://") { return address }
        if address.lowercased().hasPrefix("http://") { return "https://" + address.dropFirst(7) }
        return "https://" + address
    }
}
