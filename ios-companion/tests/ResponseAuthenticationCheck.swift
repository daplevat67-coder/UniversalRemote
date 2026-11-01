import Foundation

@main
struct ResponseAuthenticationCheck {
    static func main() {
        let session = Data((0..<32).map { UInt8($0) })
        let body = "{\"ok\":true,\"message\":\"Готово\"}"
        let signature = CompanionCrypto.responseSignature(sessionKey: session, binding: "request:known-request", status: 200, body: body)
        precondition(signature == "fXUuCrQSr+xYyq9NlgWtNRSTd2L9/lfz+jwdtaN/SdQ=", "Java/Swift UTF-8 vector mismatch")
        precondition(signature != CompanionCrypto.responseSignature(sessionKey: session, binding: "request:other-request", status: 200, body: body))
        precondition(signature != CompanionCrypto.responseSignature(sessionKey: session, binding: "pair:known-request", status: 200, body: body))
        precondition(signature != CompanionCrypto.responseSignature(sessionKey: session, binding: "request:known-request", status: 401, body: body))
        precondition(signature != CompanionCrypto.responseSignature(sessionKey: session, binding: "request:known-request", status: 200, body: body + " "))
        print("Swift response authentication checks passed")
    }
}
