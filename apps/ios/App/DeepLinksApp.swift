import DLNet
import DLSecurity
import SwiftUI
import UIKit

@MainActor
struct RootView: View {
    let pairing: PairingFlowModel
    @State private var selectedHostId: String?

    var body: some View {
        PairingFlowView(model: pairing, onPaired: { selectedHostId = $0 })
    }
}

@main @MainActor
struct DeepLinksApp: App {
    @State private var pairing: PairingFlowModel

    init() {
        let environment = ProcessInfo.processInfo.environment
        // App-hosted tests must not load a developer's paired hosts or poll a real computer.
        let testing = environment["XCTestConfigurationFilePath"] != nil || environment["XCTestBundlePath"] != nil
        _pairing = State(
            initialValue: PairingFlowModel(
                services: testing ? PairingFixtures.offlineServices : .live(store: HostStore()),
                gate: LocalNetworkPermissionGate(), deviceName: UIDevice.current.name))
    }

    var body: some Scene {
        WindowGroup { RootView(pairing: pairing) }
    }
}
