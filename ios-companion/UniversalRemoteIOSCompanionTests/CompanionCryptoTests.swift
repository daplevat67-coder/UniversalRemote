import XCTest
import CryptoKit
@testable import UniversalRemote_iOS_Companion

final class CompanionCryptoTests: XCTestCase {
    func testHmacIsDeterministicAndSensitiveToTranscript() {
        let key = Data("pairing-secret".utf8)
        let a = CompanionCrypto.hmac(key: key, text: "pair\nchallenge-a")
        let b = CompanionCrypto.hmac(key: key, text: "pair\nchallenge-a")
        let c = CompanionCrypto.hmac(key: key, text: "pair\nchallenge-b")
        XCTAssertTrue(CompanionCrypto.constantTimeEqual(a, b))
        XCTAssertFalse(CompanionCrypto.constantTimeEqual(a, c))
    }

    func testConstantTimeEqualRejectsDifferentLengths() {
        XCTAssertFalse(CompanionCrypto.constantTimeEqual(Data([1, 2, 3]), Data([1, 2])))
    }

    func testDecryptSessionPayloadRoundTrip() throws {
        let sessionKey = Data((0..<32).map(UInt8.init))
        let plaintext = #"{"action":"ping"}"#
        let payload = try makePayload(sessionKey: sessionKey, plaintext: plaintext)
        XCTAssertEqual(try CompanionCrypto.decryptSessionPayload(key: sessionKey, encoded: payload), plaintext)
    }

    func testDecryptSessionPayloadRejectsTampering() throws {
        let sessionKey = Data((0..<32).map(UInt8.init))
        let payload = try makePayload(sessionKey: sessionKey, plaintext: #"{"action":"identify"}"#)
        var raw = try XCTUnwrap(Data(base64Encoded: payload))
        raw[raw.index(before: raw.endIndex)] ^= 0x01
        XCTAssertThrowsError(try CompanionCrypto.decryptSessionPayload(key: sessionKey, encoded: raw.base64EncodedString()))
    }

    private func makePayload(sessionKey: Data, plaintext: String) throws -> String {
        let derived = CompanionCrypto.hmac(key: sessionKey, text: "UniversalRemote Companion command encryption v2").prefix(32)
        let nonceBytes = Data((0..<12).map { UInt8($0 + 1) })
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let sealed = try AES.GCM.seal(Data(plaintext.utf8), using: SymmetricKey(data: derived), nonce: nonce)
        var result = Data(nonceBytes)
        result.append(sealed.ciphertext)
        result.append(sealed.tag)
        return result.base64EncodedString()
    }
}
