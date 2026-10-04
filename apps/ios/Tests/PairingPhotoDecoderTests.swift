import Foundation
import Testing

@testable import DeepLinks

struct PairingPhotoDecoderTests {
    private func fixture(_ name: String) throws -> Data {
        let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("App/Demo/fixtures")
        return try Data(contentsOf: directory.appendingPathComponent("\(name).png"))
    }

    @Test func recognizesDeepLinksAndRejectsOtherQRCodes() async throws {
        let valid = await PairingPhotoDecoder.decode(try fixture("pairing-valid"))
        #expect(valid == .success(PairingFixtures.qr))
        let unrelated = await PairingPhotoDecoder.decode(try fixture("pairing-unrelated"))
        #expect(unrelated == .failure(.invalid))
    }

    @Test func unreadableImageDoesNotProduceAPairingRequest() async {
        #expect(await PairingPhotoDecoder.decode(Data([0, 1, 2])) == .failure(.notFound))
    }
}
