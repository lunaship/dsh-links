import DLUI
import SwiftUI

/// 1.2. Content scrolls independently of the actions at accessibility sizes.
struct PairingWelcomePage: View {
    @Environment(\.locale) private var locale
    var busy: PairingText?
    var scan: () -> Void = {}
    var photo: () -> Void = {}
    var demo: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "terminal")
                    .font(.largeTitle)
                    .imageScale(.large)
                    .foregroundStyle(DLColor.accent)
                    .accessibilityHidden(true)
                Text(copy.text(.welcomeTitle)).font(.largeTitle.bold())
                Text(copy.text(.welcomeBody)).foregroundStyle(DLColor.secondaryLabel)
                step("1.circle", title: .stepOne, detail: .stepOneBody, copy: copy)
                step("2.circle", title: .stepTwo, detail: .stepTwoBody, copy: copy)
                step("3.circle", title: .stepThree, detail: .stepThreeBody, copy: copy)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(24)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if let busy {
                    ProgressView(copy.text(busy))
                }
                Button(action: scan) {
                    Label(copy.text(.scan), systemImage: "qrcode.viewfinder")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.glassProminent)
                .tint(DLColor.brandFill)
                Button(copy.text(.photo), action: photo).frame(minHeight: 44)
                Button(copy.text(.demo), action: demo).frame(minHeight: 44)
                    .foregroundStyle(DLColor.secondaryLabel)
                Text(copy.text(.disclaimer)).font(.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .multilineTextAlignment(.center)
            }
            .disabled(busy != nil)
            .padding(16)
            .background(DLColor.background)
        }
        .background(DLColor.background)
    }

    private func step(_ image: String, title: PairingText, detail: PairingText, copy: PairingCopy) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: image).font(.title3).foregroundStyle(DLColor.accent)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(copy.text(title)).font(.headline)
                Text(copy.text(detail)).foregroundStyle(DLColor.secondaryLabel)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The first-use explanation is a route in the pairing flow, not a fake permission prompt.
struct LocalNetworkExplanationPage: View {
    @Environment(\.locale) private var locale
    var proceed: () -> Void = {}
    var back: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "network").font(.largeTitle).foregroundStyle(DLColor.accent)
                    .accessibilityHidden(true)
                Text(copy.text(.lanBody)).font(.body)
            }.padding(24)
        }
        .navigationTitle(copy.text(.lanTitle))
        .navigationBarBackButtonHidden()
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                Button(copy.text(.continueAction), action: proceed)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .buttonStyle(.glassProminent).tint(DLColor.brandFill)
                Button(copy.text(.back), action: back).frame(minHeight: 44)
            }.padding(16).background(DLColor.background)
        }
        .background(DLColor.background)
    }
}

/// 1.5. No implicit navigation back: every exit must cancel and delete the pending local record.
struct PairingPendingPage: View {
    @Environment(\.locale) private var locale
    let computerName: String
    let deviceName: String
    var viaTailscale = false
    var cancelling = false
    var cleanupFailed = false
    var cancel: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                ProgressView().accessibilityLabel(copy.text(.waitTitle))
                Text(copy.text(.waitTitle)).font(.title3.bold()).accessibilityAddTraits(.isHeader)
                Text(copy.format(.waitBody, computerName))
                Text(copy.text(.waitHint)).foregroundStyle(DLColor.secondaryLabel)
                LabeledContent(copy.text(.deviceName), value: deviceName)
                LabeledContent(copy.text(.route), value: copy.text(viaTailscale ? .tailscale : .lan))
                if cleanupFailed {
                    DLBanner(copy.text(.cancelStorageFailure))
                }
            }.padding(24)
        }
        .navigationBarBackButtonHidden()
        .safeAreaInset(edge: .bottom) {
            Button(copy.text(.cancelPairing), action: cancel)
                .frame(maxWidth: .infinity, minHeight: 44)
                .buttonStyle(.glass)
                .disabled(cancelling)
                .padding(16).background(DLColor.background)
        }
        .background(DLColor.background)
    }
}

/// 1.6. Always three actionable suggestions, preserving Android's HTTP hint priority.
struct PairingFailurePage: View {
    @Environment(\.locale) private var locale
    let failure: PairingFailure
    var back: () -> Void = {}
    var settings: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                    .foregroundStyle(DLColor.err).accessibilityHidden(true)
                Text(copy.text(failure.isNetwork ? .networkTitle : .failTitle))
                    .font(.title3.bold()).accessibilityAddTraits(.isHeader)
                Text(copy.reason(failure)).foregroundStyle(DLColor.secondaryLabel)
                Text(copy.text(.suggestions)).font(.headline)
                ForEach(Array(failure.suggestions.enumerated()), id: \.offset) { _, tip in
                    Label(copy.text(tip), systemImage: "checkmark.circle")
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(DLColor.label)
                }
                if failure.offersSettings {
                    Button(copy.text(.settings), action: settings).frame(minHeight: 44)
                }
            }.padding(24)
        }
        .navigationBarBackButtonHidden()
        .safeAreaInset(edge: .bottom) {
            Button(copy.text(.back), action: back)
                .frame(maxWidth: .infinity, minHeight: 44)
                .buttonStyle(.glass).padding(16).background(DLColor.background)
        }
        .background(DLColor.background)
    }
}

/// 1.3 overlay. Snapshots use a fixture camera surface, never a live capture session.
struct PairingScannerPage<Camera: View>: View {
    @Environment(\.locale) private var locale
    let camera: Camera
    var hint: PairingText?
    var close: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        ZStack(alignment: .topLeading) {
            camera.ignoresSafeArea().accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 24) {
                Button(action: close) {
                    Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.glass).accessibilityLabel(copy.text(.close))
                Spacer()
                VStack(spacing: 16) {
                    Text(copy.text(.scanTitle)).font(.headline)
                    Text(copy.text(hint ?? .scanBody)).font(.body)
                }
                .multilineTextAlignment(.center)
                .padding(16)
                .background(DLColor.background)
                .preferredColorScheme(.dark)
                Spacer()
            }.padding(16)
        }
        .background(DLColor.background)
        .preferredColorScheme(.dark)
    }
}
