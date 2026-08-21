import Foundation
import CryptoKit

struct CompanionCrypto {
    static func hmac(key: Data, text: String) -> Data {
        let symmetric = SymmetricKey(data: key)
        let code = HMAC<SHA256>.authenticationCode(for: Data(text.utf8), using: symmetric)
        return Data(code)
    }

    static func base64(_ data: Data) -> String { data.base64EncodedString() }

    static func fromBase64(_ value: String) -> Data? { Data(base64Encoded: value) }

    static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in a.indices { diff |= a[i] ^ b[i] }
        return diff == 0
    }

    static func decryptSessionPayload(key: Data, encoded: String) throws -> String {
        guard let raw = Data(base64Encoded: encoded), raw.count > 28 else { throw CryptoError.invalidPayload }
        let derived = hmac(key: key, text: "UniversalRemote Companion command encryption v2").prefix(32)
        let nonceData = raw.prefix(12)
        let cipherAndTag = raw.dropFirst(12)
        guard cipherAndTag.count >= 16 else { throw CryptoError.invalidPayload }
        let ciphertext = cipherAndTag.dropLast(16)
        let tag = cipherAndTag.suffix(16)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(ciphertext), tag: Data(tag))
        let clear = try AES.GCM.open(box, using: SymmetricKey(data: derived))
        guard let text = String(data: clear, encoding: .utf8) else { throw CryptoError.invalidPayload }
        return text
    }

    enum CryptoError: Error { case invalidPayload }
}
