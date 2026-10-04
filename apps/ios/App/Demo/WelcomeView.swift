import SwiftUI

/// Existing offline demo entry; production pairing is owned by PairingFlowView.
struct WelcomeView: View {
    @State private var demo = false

    var body: some View {
        PairingWelcomePage(demo: { demo = true })
            .navigationDestination(isPresented: $demo) { DemoSceneList() }
    }
}
