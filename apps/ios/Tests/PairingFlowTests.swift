import DLNet
import DLSecurity
import Foundation
import Testing

@testable import DeepLinks

@MainActor @Suite(.serialized)
struct PairingFlowTests {
    @MainActor private final class ExplanationStorage: LocalNetworkPermissionStorage {
        var hasShownExplanation = false
    }

    private final class CheckpointStorage: @unchecked Sendable {
        private let lock = NSLock()
        private var checkpoint: PairingCheckpoint?
        var value: PairingCheckpoint? { lock.withLock { checkpoint } }
        func write(_ value: PairingCheckpoint?) { lock.withLock { checkpoint = value } }
    }

    private actor Backend {
        enum Approval { case sleep, approved, rejected, late }
        var calls: [(PairingClient.Attempt, Bool)] = []
        var results: [PairingClient.Result]
        var saved: [PairedHost] = []
        var deleted: [String] = []
        var approval: Approval = .sleep
        var waits = 0
        var late: CheckedContinuation<PairingApprovalPoller.Approval, Never>?
        var discardFails = false

        init(_ results: [PairingClient.Result]) { self.results = results }
        func pair(_ attempt: PairingClient.Attempt, _ replacing: Bool) -> PairingClient.Result {
            calls.append((attempt, replacing))
            let result = results.removeFirst()
            if case .pending(let host, _) = result { saved.append(host) }
            if case .paired(let host) = result { saved.append(host) }
            return result
        }
        func wait(_ host: PairedHost) async throws -> PairingApprovalPoller.Approval {
            waits += 1
            switch approval {
            case .sleep:
                try await Task.sleep(for: .seconds(3600))
                return .unknown
            case .approved: return .approved
            case .rejected:
                try discard(host)
                return .rejected
            case .late: return await withCheckedContinuation { late = $0 }
            }
        }
        func discard(_ host: PairedHost) throws {
            if discardFails { throw CocoaError(.fileWriteUnknown) }
            deleted.append(host.hostId)
            saved.removeAll { $0.hostId == host.hostId }
        }
        func setHosts(_ hosts: [PairedHost]) { saved = hosts }
        func setApproval(_ value: Approval) { approval = value }
        func setDiscardFails(_ value: Bool) { discardFails = value }
        func finishLate() {
            late?.resume(returning: .approved)
            late = nil
        }
    }

    private struct Context {
        let model: PairingFlowModel
        let backend: Backend
        let checkpoint: CheckpointStorage
        let explanation: ExplanationStorage
    }

    private func context(_ results: [PairingClient.Result], explained: Bool = true) -> Context {
        let backend = Backend(results)
        let checkpoint = CheckpointStorage()
        let explanation = ExplanationStorage()
        explanation.hasShownExplanation = explained
        let services = PairingServices(
            pair: { await backend.pair($0, $1) }, wait: { try await backend.wait($0) },
            discard: { try await backend.discard($0) }, hosts: { await backend.saved },
            readCheckpoint: { checkpoint.value }, writeCheckpoint: { checkpoint.write($0) })
        let model = PairingFlowModel(
            services: services, gate: LocalNetworkPermissionGate(storage: explanation), deviceName: "iPhone",
            clock: { 100 })
        return Context(model: model, backend: backend, checkpoint: checkpoint, explanation: explanation)
    }

    private func waitUntil(_ condition: () async -> Bool) async throws {
        // Yield cooperatively with an actual deadline; no arbitrary UI/network sleeps.
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while !(await condition()) {
            try #require(ContinuousClock.now < deadline)
            await Task.yield()
        }
    }

    @Test func expiredAndMalformedNeverSubmit() async {
        let c = context([])
        c.model.receive("{}")
        #expect(c.model.failure == .invalidQR)
        c.model.returnToWelcome()
        c.model.receive(PairingFixtures.qr.replacingOccurrences(of: "\"name\"", with: "\"expiresAt\":99,\"name\""))
        #expect(c.model.failure.reason == .expired)
        #expect(await c.backend.calls.isEmpty)
        #expect(c.checkpoint.value == nil)
    }

    @Test func invalidLiveScanKeepsScannerAndAllowsNextCode() async {
        let c = context([.paired(PairingFixtures.host)])
        #expect(!c.model.receive("not a QR", fromCamera: true))
        #expect(c.model.page == .welcome && c.model.scanHint == .invalidQR)
        #expect(c.model.receive(PairingFixtures.qr, fromCamera: true))
        await c.model.waitForSubmission()
        #expect(c.model.pairedHost == PairingFixtures.host)
        #expect(c.model.scanHint == nil)
    }

    @Test func explanationPrecedesFirstLANRequest() async {
        let c = context([.paired(PairingFixtures.host)], explained: false)
        let lan = PairingFixtures.qr.replacingOccurrences(of: "192.0.2.10", with: "192.168.10.10")
        c.model.receive(lan)
        #expect(c.model.page == .explanation)
        #expect(c.explanation.hasShownExplanation)
        #expect(await c.backend.calls.isEmpty)
        c.model.continueAfterExplanation()
        await c.model.waitForSubmission()
        #expect(await c.backend.calls.count == 1)
        #expect(c.model.pairedHost == PairingFixtures.host)
    }

    @Test func replacementReusesAttemptAndRenameCreatesNewRequest() async throws {
        let c = context([.sameName(nil), .sameName(nil), .paired(PairingFixtures.host)])
        c.model.receive(PairingFixtures.qr)
        await c.model.waitForSubmission()
        #expect(c.model.conflictPresented)
        c.model.replace()
        await c.model.waitForSubmission()
        c.model.chooseNewName()
        c.model.renameAndRetry("iPhone")
        #expect(c.model.renamePresented)
        c.model.renameAndRetry("   New iPhone   ")
        await c.model.waitForSubmission()
        let calls = await c.backend.calls
        try #require(calls.count == 3)
        #expect(calls[0].0.requestId == calls[1].0.requestId)
        #expect(calls[0].0.qr == calls[1].0.qr)
        #expect(calls[1].1)
        #expect(calls[2].0.requestId != calls[1].0.requestId)
        #expect(calls[2].0.deviceName == "New iPhone")
        #expect(!calls[2].1)
        #expect(c.checkpoint.value == nil)
    }

    @Test func cancelOnlyDeletesPendingHostAndStopsPolling() async throws {
        let c = context([.pending(PairingFixtures.host, expiresAt: 1)])
        var other = PairingFixtures.host
        other.hostId = "other"
        await c.backend.setHosts([other])
        c.model.receive(PairingFixtures.qr)
        await c.model.waitForSubmission()
        try await waitUntil { await c.backend.waits == 1 }
        c.model.returnToWelcome()
        #expect(c.model.page == .pending)
        await c.model.cancelPending()
        #expect(c.model.page == .welcome)
        #expect(await c.backend.deleted == [PairingFixtures.host.hostId])
        #expect(await c.backend.saved == [other])
        #expect(c.checkpoint.value == nil)
    }

    @Test func lateApprovalCannotOverrideCancel() async throws {
        let c = context([.pending(PairingFixtures.host, expiresAt: nil)])
        await c.backend.setApproval(.late)
        c.model.receive(PairingFixtures.qr)
        await c.model.waitForSubmission()
        try await waitUntil { await c.backend.late != nil }
        let cancelling = Task { await c.model.cancelPending() }
        try await waitUntil { c.model.isCancelling }
        await c.backend.finishLate()
        await cancelling.value
        #expect(c.model.pairedHost == nil)
        #expect(c.model.page == .welcome)
        #expect(await c.backend.deleted == [PairingFixtures.host.hostId])
    }

    @Test func backgroundKeepsRecordAndForegroundRestartsPolling() async throws {
        let c = context([.pending(PairingFixtures.host, expiresAt: nil)])
        c.model.receive(PairingFixtures.qr)
        await c.model.waitForSubmission()
        try await waitUntil { await c.backend.waits == 1 }
        c.model.setActive(false)
        #expect(c.model.page == .pending && c.checkpoint.value != nil)
        #expect(await c.backend.deleted.isEmpty)
        c.model.setActive(true)
        try await waitUntil { await c.backend.waits == 2 }
        await c.model.cancelPending()
    }

    @Test func checkpointRestoresPendingWithoutTreatingItAsPaired() async throws {
        let c = context([])
        c.checkpoint.write(.init(hostId: PairingFixtures.host.hostId, deviceName: "Saved phone"))
        await c.backend.setHosts([PairingFixtures.host])
        await c.model.restore()
        #expect(c.model.page == .pending)
        #expect(c.model.deviceName == "Saved phone")
        #expect(c.model.pairedHost == nil)
        await c.model.cancelPending()
    }

    @Test func approvedAndRejectedResolveCheckpoint() async throws {
        for approval in [Backend.Approval.approved, .rejected] {
            let c = context([.pending(PairingFixtures.host, expiresAt: nil)])
            await c.backend.setApproval(approval)
            c.model.receive(PairingFixtures.qr)
            await c.model.waitForSubmission()
            try await waitUntil { c.model.page != .pending }
            #expect(c.checkpoint.value == nil)
            if approval == .approved {
                #expect(c.model.pairedHost == PairingFixtures.host)
                #expect(await c.backend.deleted.isEmpty)
            } else {
                #expect(c.model.failure == .rejected)
                #expect(c.model.pairedHost == nil)
            }
        }
    }

    @Test func cleanupFailureKeepsCancelRetryAvailable() async {
        let c = context([.pending(PairingFixtures.host, expiresAt: nil)])
        c.model.receive(PairingFixtures.qr)
        await c.model.waitForSubmission()
        await c.backend.setDiscardFails(true)
        await c.model.cancelPending()
        #expect(c.model.page == .pending && c.model.scanHint == .cancelStorageFailure)
        #expect(c.checkpoint.value != nil)
        await c.backend.setDiscardFails(false)
        await c.model.cancelPending()
        #expect(c.model.page == .welcome && c.checkpoint.value == nil)
    }
}
