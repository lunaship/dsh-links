import DLNet
import DLUI
import PhotosUI
import SwiftUI

struct PairingFlowView: View {
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @Bindable var model: PairingFlowModel
    /// I4.2 supplies the Home navigation destination. This module only hands off the saved host.
    var onPaired: (String) -> Void = { _ in }
    @State private var scanning = false
    @State private var pickingPhoto = false
    @State private var photo: PhotosPickerItem?
    @State private var readingPhoto = false
    @State private var demo = false
    @State private var newName = ""

    var body: some View {
        let copy = PairingCopy(locale: locale)
        NavigationStack {
            PairingWelcomePage(
                busy: model.page == .submitting ? .submitting : readingPhoto ? .photoReading : nil,
                scan: {
                    photo = nil
                    scanning = true
                }, photo: { pickingPhoto = true }, demo: { demo = true }
            )
            .disabled(model.conflictPresented || model.renamePresented)
            .navigationDestination(isPresented: destinationPresented) {
                destination
            }
            .navigationDestination(isPresented: $demo) { DemoSceneList() }
            .photosPicker(isPresented: $pickingPhoto, selection: $photo, matching: .images)
            .fullScreenCover(isPresented: $scanning) {
                PairingScannerPage(
                    camera: QRScanner(
                        receive: { text in
                            let accepted = model.receive(text, fromCamera: true)
                            if accepted { scanning = false }
                            return accepted
                        },
                        failure: { failure in
                            scanning = false
                            model.showFailure(failure)
                        }),
                    hint: model.scanHint, close: { scanning = false })
            }
            .alert(copy.text(.sameNameTitle), isPresented: conflictPresented) {
                Button(copy.text(.replace), role: .destructive) { model.replace() }
                Button(copy.text(.rename)) {
                    newName = model.deviceName
                    model.chooseNewName()
                }
                Button(copy.text(.cancel), role: .cancel) { model.returnToWelcome() }
            } message: {
                Text(copy.text(.sameNameBody))
            }
            .sheet(isPresented: renamePresented) {
                NavigationStack {
                    Form {
                        Section {
                            TextField(copy.text(.deviceName), text: $newName)
                                .textInputAutocapitalization(.words)
                        } footer: {
                            Text(copy.text(.renameBody))
                        }
                    }
                    .navigationTitle(copy.text(.renameTitle))
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(copy.text(.cancel)) { model.returnToWelcome() }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(copy.text(.retry)) { model.renameAndRetry(newName) }
                                .disabled(
                                    newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                        || newName.trimmingCharacters(in: .whitespacesAndNewlines) == model.deviceName)
                        }
                    }
                }
                .presentationDetents([.medium, .large])
                .onAppear { newName = model.deviceName }
            }
        }
        .tint(DLColor.accent)
        .task { await model.restore() }
        .task(id: photo) {
            guard let photo else { return }
            readingPhoto = true
            defer { readingPhoto = false }
            do {
                let loaded = try await photo.loadTransferable(type: Data.self)
                guard !Task.isCancelled else { return }
                guard let data = loaded else {
                    model.showFailure(.noPhotoQR)
                    return
                }
                let result = await PairingPhotoDecoder.decode(data)
                guard !Task.isCancelled else { return }
                switch result {
                case .success(let text): model.receive(text)
                case .failure(let error): model.showFailure(error == .invalid ? .invalidQR : .noPhotoQR)
                }
            } catch {
                if !Task.isCancelled { model.showFailure(.noPhotoQR) }
            }
            self.photo = nil
        }
        .onChange(of: model.pairedHost) { _, host in
            if let host { onPaired(host.hostId) }
        }
        .onChange(of: scenePhase) { _, phase in
            model.setActive(phase == .active)
            if phase != .active { scanning = false }
        }
        .onDisappear { model.setActive(false) }
        .onAppear {
            newName = model.deviceName
            model.setActive(scenePhase == .active)
        }
    }

    @ViewBuilder private var destination: some View {
        switch model.page {
        case .explanation:
            LocalNetworkExplanationPage(proceed: model.continueAfterExplanation, back: model.returnToWelcome)
        case .pending:
            PairingPendingPage(
                computerName: model.pendingHost?.name ?? "", deviceName: model.deviceName,
                viaTailscale: model.pendingHost.map {
                    PairingClient.tailnetSpare(urls: [$0.primaryUrl], primary: "") != nil
                } ?? false,
                cancelling: model.isCancelling, cleanupFailed: model.scanHint == .cancelStorageFailure,
                cancel: { Task { await model.cancelPending() } })
        case .failure:
            PairingFailurePage(failure: model.failure, back: model.returnToWelcome, settings: openSettings)
        case .welcome, .submitting:
            ProgressView(PairingCopy(locale: locale).text(.submitting))
                .navigationBarBackButtonHidden()
        }
    }

    private var destinationPresented: Binding<Bool> {
        Binding(
            get: { [.explanation, .pending, .failure].contains(model.page) },
            set: { if !$0 { model.returnToWelcome() } })
    }

    private var conflictPresented: Binding<Bool> {
        Binding(get: { model.conflictPresented }, set: { _ in })
    }

    private var renamePresented: Binding<Bool> {
        Binding(get: { model.renamePresented }, set: { if !$0 { model.returnToWelcome() } })
    }

    private func openSettings() {
        if let url = LocalNetworkPermissionGate.settingsURL { openURL(url) }
    }
}
