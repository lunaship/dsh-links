import DLNet
import Foundation
import Vision

struct PairingPhotoDecoder {
    static func decode(_ data: Data) async -> Result<String, PhotoError> {
        await Task.detached(priority: .userInitiated) {
            do {
                let request = VNDetectBarcodesRequest()
                request.symbologies = [.qr]
                // Data-backed Vision handler reads EXIF orientation; no photo-library permission needed.
                try VNImageRequestHandler(data: data).perform([request])
                let texts = request.results?.compactMap(\.payloadStringValue) ?? []
                if let text = texts.first(where: { (try? PairingQRPayload($0)) != nil }) { return .success(text) }
                return .failure(texts.isEmpty ? .notFound : .invalid)
            } catch {
                return .failure(.notFound)
            }
        }.value
    }

    enum PhotoError: Error, Equatable, Sendable { case notFound, invalid }
}
