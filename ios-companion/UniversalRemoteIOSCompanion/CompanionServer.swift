import Foundation
import Combine
import Network
import UIKit
import AudioToolbox
import CryptoKit
import Security
import Darwin

final class CompanionServer: ObservableObject {
    struct PairedController: Identifiable {
        let id: String
        let name: String
        let expiresAt: Date
    }

    @Published var running = false
    @Published var status = "Остановлен"
    @Published var pairCode = "———— ———— ————"
    @Published var pairCodeExpiresAt = Date.distantPast
    @Published var paired: [PairedController] = []
    @Published var lastAction = "Команд пока не было"

    private let server = ServerCore()
    private var timer: Timer?

    init() {
        server.onState = { [weak self] running, status in
            Task { @MainActor in self?.running = running; self?.status = status }
        }
        server.onPairCode = { [weak self] code, expiry in
            Task { @MainActor in self?.pairCode = Self.group(code); self?.pairCodeExpiresAt = expiry }
        }
        server.onPairedChanged = { [weak self] items in
            Task { @MainActor in self?.paired = items }
        }
        server.onAction = { [weak self] action in
            Task { @MainActor in self?.lastAction = action }
        }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self else { return }
            if self.running && Date() >= self.pairCodeExpiresAt { self.server.rotatePairCode() }
        }
    }

    deinit { timer?.invalidate() }

    var remainingText: String {
        let sec = max(0, Int(pairCodeExpiresAt.timeIntervalSinceNow))
        return String(format: "%d:%02d", sec / 60, sec % 60)
    }

    func start() { server.start() }
    func stop() { server.stop() }
    func rotatePairCode() { server.rotatePairCode() }
    func revoke(_ id: String) { server.revoke(id) }
    func revokeAll() { server.revokeAll() }

    private static func group(_ code: String) -> String {
        guard code.count == 12 else { return code }
        return "\(code.prefix(4)) \(code.dropFirst(4).prefix(4)) \(code.suffix(4))"
    }
}

private final class ServerCore: @unchecked Sendable {
    struct Challenge { let remote: String; let nonce: Data; let expiresAt: Date }
    struct PairRecord { let id: String; let name: String; let expiresAt: Date }
    struct Request { let method: String; let path: String; let headers: [String: String]; let body: String }

    var onState: ((Bool, String) -> Void)?
    var onPairCode: ((String, Date) -> Void)?
    var onPairedChanged: (([CompanionServer.PairedController]) -> Void)?
    var onAction: ((String) -> Void)?

    private let queue = DispatchQueue(label: "uremote.ios.companion", qos: .userInitiated)
    private let store = KeychainStore()
    private var listener: NWListener?
    private var challenges: [String: Challenge] = [:]
    private var challengeAttempts: [String: [Date]] = [:]
    private var pairAttempts: [String: [Date]] = [:]
    private var replay: [String: Date] = [:]
    private var pairCode = ""
    private var pairCodeExpiry = Date.distantPast
    private let deviceId: String
    private var advertisementId = UUID().uuidString
    private let deviceName: String

    private let challengeWindow: TimeInterval = 60
    private let maxChallengeAttempts = 5
    private let pairWindow: TimeInterval = 60
    private let maxPairAttempts = 5
    private let maxChallenges = 32

    init() {
        deviceName = UIDevice.current.name
        if let existing = store.getString("device_id") {
            deviceId = existing
        } else {
            let id = UUID().uuidString
            deviceId = id
            store.putString("device_id", id)
        }
    }

    func start() {
        queue.async {
            guard self.listener == nil else { return }
            do {
                let port = NWEndpoint.Port(rawValue: 45123)!
                let l = try NWListener(using: .tcp, on: port)
                self.advertisementId = UUID().uuidString
                let txt = NetService.data(fromTXTRecord: [
                    "platform": Data("ios".utf8),
                    "v": Data("2".utf8),
                    "id": Data(self.advertisementId.utf8)
                ])
                l.service = NWListener.Service(name: "UniversalRemote iOS \(self.deviceName)", type: "_uremote._tcp", domain: nil, txtRecord: txt)
                l.stateUpdateHandler = { [weak self] state in
                    guard let self else { return }
                    switch state {
                    case .ready: self.onState?(true, "Готов к pairing • порт 45123")
                    case .failed(let e): self.onState?(false, "Ошибка listener: \(e.localizedDescription)"); self.stopLocked()
                    case .cancelled: self.onState?(false, "Остановлен")
                    default: break
                    }
                }
                l.newConnectionHandler = { [weak self] c in self?.accept(c) }
                self.listener = l
                self.rotatePairCodeLocked()
                self.refreshPaired()
                l.start(queue: self.queue)
            } catch {
                self.onState?(false, "Не удалось открыть порт: \(error.localizedDescription)")
            }
        }
    }

    func stop() { queue.async { self.stopLocked() } }
    private func stopLocked() {
        listener?.cancel()
        listener = nil
        challenges.removeAll()
        challengeAttempts.removeAll()
        pairAttempts.removeAll()
        replay.removeAll()
        onState?(false, "Остановлен")
    }

    func rotatePairCode() { queue.async { self.rotatePairCodeLocked() } }
    private func rotatePairCodeLocked() {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        pairCode = String((0..<12).compactMap { _ in alphabet.randomElement() })
        pairCodeExpiry = Date().addingTimeInterval(300)
        challenges.removeAll()
        onPairCode?(pairCode, pairCodeExpiry)
    }

    func revoke(_ id: String) { queue.async { self.revokeLocked(id); self.refreshPaired() } }
    func revokeAll() { queue.async { self.store.keys(prefix: "session_").forEach { self.revokeLocked(String($0.dropFirst("session_".count))) }; self.refreshPaired() } }
    private func revokeLocked(_ id: String) { store.delete("session_\(id)"); store.delete("name_\(id)"); store.delete("expiry_\(id)") }

    private func refreshPaired() {
        let now = Date()
        var list: [CompanionServer.PairedController] = []
        for key in store.keys(prefix: "session_") {
            let id = String(key.dropFirst("session_".count))
            let expiryMs = Double(store.getString("expiry_\(id)") ?? "0") ?? 0
            let expiry = Date(timeIntervalSince1970: expiryMs / 1000)
            if expiry <= now { revokeLocked(id); continue }
            let name = store.getString("name_\(id)") ?? "UniversalRemote"
            list.append(.init(id: id, name: name, expiresAt: expiry))
        }
        onPairedChanged?(list.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending })
    }

    private func accept(_ connection: NWConnection) {
        guard let remote = remoteIPv4(connection.endpoint) else {
            connection.cancel()
            return
        }
        var started = false
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready where !started:
                started = true
                guard connection.currentPath?.usesInterfaceType(.wifi) == true,
                      self.isCurrentWifiPeer(remote) else {
                    connection.cancel()
                    return
                }
                self.receive(connection, remote: remote, buffer: Data(), deadline: Date().addingTimeInterval(4))
            case .failed, .cancelled:
                connection.cancel()
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func receive(_ c: NWConnection, remote: String, buffer: Data, deadline: Date) {
        if Date() > deadline || buffer.count > 16_384 { c.cancel(); return }
        c.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, complete, error in
            guard let self else { c.cancel(); return }
            var next = buffer
            if let data { next.append(data) }
            if let request = self.parseRequest(next) {
                let response = self.route(request, remote: remote)
                let payload = self.httpResponse(code: response.0, body: response.1)
                c.send(content: payload, completion: .contentProcessed { _ in c.cancel() })
                return
            }
            if complete || error != nil { c.cancel(); return }
            self.receive(c, remote: remote, buffer: next, deadline: deadline)
        }
    }

    private func parseRequest(_ data: Data) -> Request? {
        let marker = Data("\r\n\r\n".utf8)
        guard let split = data.range(of: marker),
              let head = String(data: data.subdata(in: 0..<split.lowerBound), encoding: .utf8) else { return nil }
        let lines = head.components(separatedBy: "\r\n")
        guard let first = lines.first else { return nil }
        let p = first.split(separator: " ")
        guard p.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines.dropFirst().prefix(32) {
            if let i = line.firstIndex(of: ":") {
                headers[String(line[..<i]).lowercased()] = String(line[line.index(after: i)...]).trimmingCharacters(in: .whitespaces)
            }
        }
        let len = Int(headers["content-length"] ?? "0") ?? 0
        guard (0...8192).contains(len) else { return nil }
        let headerBytes = split.upperBound
        guard data.count >= headerBytes + len else { return nil }
        let bodyData = data.subdata(in: headerBytes..<(headerBytes + len))
        let body = String(data: bodyData, encoding: .utf8) ?? ""
        return Request(method: String(p[0]).uppercased(), path: String(p[1]).split(separator: "?").first.map(String.init) ?? "/", headers: headers, body: body)
    }

    private func route(_ r: Request, remote: String) -> (Int, String) {
        cleanup()
        switch (r.method, r.path) {
        case ("GET", "/v2/info"):
            let obj: [String: Any] = [
                "ok": true, "name": deviceName, "advertisementId": advertisementId,
                "version": 2, "platform": "ios", "accessibility": false,
                "pairCodeExpiresIn": max(0, Int(pairCodeExpiry.timeIntervalSinceNow)),
                "actions": ["ping", "identify", "brightness_down", "brightness_50", "brightness_up"]
            ]
            return (200, json(obj))
        case ("GET", "/v2/challenge"):
            guard Date() < pairCodeExpiry else {
                rotatePairCodeLocked()
                return (409, json(["ok": false, "message": "Pairing-код обновлён; используйте новый код с экрана iPhone/iPad"]))
            }
            guard allowAttempt(remote: remote, table: &challengeAttempts, window: challengeWindow, limit: maxChallengeAttempts) else {
                return (429, json(["ok": false, "message": "Слишком много запросов pairing challenge; повторите позже"]))
            }
            // One active challenge per IP prevents one peer from exhausting the global pool.
            challenges = challenges.filter { $0.value.remote != remote }
            guard challenges.count < maxChallenges else {
                return (429, json(["ok": false, "message": "Слишком много pairing challenge"]))
            }
            let id = randomToken(18), nonce = randomData(32)
            challenges[id] = Challenge(remote: remote, nonce: nonce, expiresAt: Date().addingTimeInterval(90))
            return (200, json(["ok": true, "challenge": id, "serverNonce": nonce.base64EncodedString(), "advertisementId": advertisementId, "expiresIn": 90]))
        case ("POST", "/v2/pair"):
            return pair(r.body, remote: remote)
        case ("POST", "/v2/command"):
            guard let auth = authenticate(r) else { return (401, json(["ok": false, "message": "Недействительная сессия/подпись"])) }
            guard let data = auth.plaintext.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let action = o["action"] as? String else { return (400, json(["ok": false, "message": "Некорректная команда"])) }
            return execute(action)
        case ("POST", "/v2/revoke-self"):
            guard let auth = authenticate(r) else { return (401, json(["ok": false, "message": "Недействительная сессия"])) }
            revokeLocked(auth.clientId); refreshPaired(); return (200, json(["ok": true, "message": "Пульт отозван на iOS Companion"]))
        default:
            return (404, json(["ok": false, "message": "Not found"]))
        }
    }

    private func pair(_ body: String, remote: String) -> (Int, String) {
        guard allowAttempt(remote: remote, table: &pairAttempts, window: pairWindow, limit: maxPairAttempts) else {
            return (429, json(["ok": false, "message": "Слишком много попыток pairing; повторите позже"]))
        }
        guard Date() < pairCodeExpiry,
              let data = body.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let challengeId = o["challenge"] as? String,
              let clientNonceText = o["clientNonce"] as? String,
              let proofText = o["proof"] as? String,
              let clientIdRaw = o["clientId"] as? String,
              let clientNameRaw = o["clientName"] as? String,
              let challenge = challenges.removeValue(forKey: challengeId),
              challenge.remote == remote, challenge.expiresAt > Date(),
              let proof = Data(base64Encoded: proofText)
        else { return (401, json(["ok": false, "message": "Pairing-код/challenge недействителен"])) }
        let clientId = String(clientIdRaw.prefix(80))
        let clientName = String(clientNameRaw.prefix(80))
        guard !clientId.isEmpty else { return (400, json(["ok": false, "message": "Некорректный clientId"])) }
        let serverNonce = challenge.nonce.base64EncodedString()
        let codeBytes = Data(pairCode.utf8)
        let transcript = "pair\n\(challengeId)\n\(serverNonce)\n\(clientNonceText)\n\(clientId)\n\(clientName)"
        let expected = CompanionCrypto.hmac(key: codeBytes, text: transcript)
        guard CompanionCrypto.constantTimeEqual(expected, proof) else { return (401, json(["ok": false, "message": "Неверный pairing-код"])) }
        let sessionTranscript = "session\n\(challengeId)\n\(serverNonce)\n\(clientNonceText)\n\(clientId)"
        let session = CompanionCrypto.hmac(key: codeBytes, text: sessionTranscript)
        let expiry = Date().addingTimeInterval(30 * 24 * 60 * 60)
        store.put("session_\(clientId)", data: session)
        store.putString("name_\(clientId)", clientName)
        store.putString("expiry_\(clientId)", String(Int64(expiry.timeIntervalSince1970 * 1000)))
        rotatePairCodeLocked(); refreshPaired()
        return (200, json(["ok": true, "message": "iOS Companion сопряжён", "deviceId": deviceId, "sessionExpiresAt": Int64(expiry.timeIntervalSince1970 * 1000)]))
    }

    private struct Auth { let clientId: String; let plaintext: String }
    private func authenticate(_ r: Request) -> Auth? {
        guard let clientId = r.headers["x-ur-client"],
              let timestamp = Int64(r.headers["x-ur-timestamp"] ?? ""),
              let nonce = r.headers["x-ur-nonce"], nonce.count >= 16,
              let sigText = r.headers["x-ur-signature"], let sig = Data(base64Encoded: sigText),
              abs(Int64(Date().timeIntervalSince1970 * 1000) - timestamp) <= 90_000,
              let expiryMs = Int64(store.getString("expiry_\(clientId)") ?? ""), expiryMs > Int64(Date().timeIntervalSince1970 * 1000),
              let key = store.get("session_\(clientId)")
        else { return nil }
        let replayKey = "\(clientId):\(nonce)"
        if replay[replayKey] != nil { return nil }
        let signed = "\(r.method)\n\(r.path)\n\(timestamp)\n\(nonce)\n\(r.body)"
        guard CompanionCrypto.constantTimeEqual(CompanionCrypto.hmac(key: key, text: signed), sig) else { return nil }
        guard let data = r.body.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let payload = o["payload"] as? String,
              let clear = try? CompanionCrypto.decryptSessionPayload(key: key, encoded: payload) else { return nil }
        replay[replayKey] = Date()
        return Auth(clientId: clientId, plaintext: clear)
    }

    private func execute(_ action: String) -> (Int, String) {
        switch action {
        case "ping":
            onAction?("Ping от сопряжённого UniversalRemote")
            return (200, json(["ok": true, "message": "iOS Companion доступен"]))
        case "identify":
            DispatchQueue.main.async {
                AudioServicesPlaySystemSound(1007)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
            onAction?("Найти устройство: звук/отклик отправлен")
            return (200, json(["ok": true, "message": "Команда Find выполнена в iOS Companion"]))
        case "brightness_down", "brightness_50", "brightness_up":
            DispatchQueue.main.async {
                let current = UIScreen.main.brightness
                switch action {
                case "brightness_down": UIScreen.main.brightness = max(0.05, current - 0.15)
                case "brightness_up": UIScreen.main.brightness = min(1.0, current + 0.15)
                default: UIScreen.main.brightness = 0.5
                }
            }
            onAction?("Яркость экрана изменена")
            return (200, json(["ok": true, "message": "Яркость iPhone/iPad изменена Companion-приложением"]))
        default:
            return (409, json(["ok": false, "message": "iOS не разрешает эту системную команду стороннему Companion"]))
        }
    }

    private func cleanup() {
        let now = Date()
        challenges = challenges.filter { $0.value.expiresAt > now }
        replay = replay.filter { now.timeIntervalSince($0.value) < 300 }
        cleanupAttempts(&challengeAttempts, now: now, window: challengeWindow)
        cleanupAttempts(&pairAttempts, now: now, window: pairWindow)
        if now >= pairCodeExpiry { rotatePairCodeLocked() }
        refreshPaired()
    }

    private func allowAttempt(remote: String, table: inout [String: [Date]], window: TimeInterval, limit: Int) -> Bool {
        let now = Date()
        var recent = table[remote, default: []].filter { now.timeIntervalSince($0) < window }
        guard recent.count < limit else {
            table[remote] = recent
            return false
        }
        recent.append(now)
        table[remote] = recent
        return true
    }

    private func cleanupAttempts(_ table: inout [String: [Date]], now: Date, window: TimeInterval) {
        for key in Array(table.keys) {
            let recent = table[key, default: []].filter { now.timeIntervalSince($0) < window }
            if recent.isEmpty { table.removeValue(forKey: key) } else { table[key] = recent }
        }
    }

    private func remoteIPv4(_ endpoint: NWEndpoint) -> String? {
        guard case let .hostPort(host, _) = endpoint else { return nil }
        let value = String(describing: host)
        var addr = in_addr()
        return inet_pton(AF_INET, value, &addr) == 1 ? value : nil
    }

    private func isCurrentWifiPeer(_ remote: String) -> Bool {
        var remoteAddr = in_addr()
        guard inet_pton(AF_INET, remote, &remoteAddr) == 1 else { return false }

        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return false }
        defer { freeifaddrs(head) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let current = cursor {
            let item = current.pointee
            defer { cursor = item.ifa_next }
            guard let address = item.ifa_addr,
                  Int32(address.pointee.sa_family) == AF_INET,
                  let maskAddress = item.ifa_netmask else { continue }
            let name = String(cString: item.ifa_name)
            // On iPhone/iPad the infrastructure Wi-Fi interface is en0. Exclude AWDL/cellular/VPN interfaces.
            guard name == "en0" else { continue }
            let local = UnsafeRawPointer(address).assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr.s_addr
            let mask = UnsafeRawPointer(maskAddress).assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr.s_addr
            if (local & mask) == (remoteAddr.s_addr & mask) { return true }
        }
        return false
    }

    private func httpResponse(code: Int, body: String) -> Data {
        let reason: String
        switch code {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 401: reason = "Unauthorized"
        case 404: reason = "Not Found"
        case 409: reason = "Conflict"
        case 429: reason = "Too Many Requests"
        default: reason = "Error"
        }
        let bytes = Data(body.utf8)
        let head = "HTTP/1.1 \(code) \(reason)\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: \(bytes.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        var result = Data(head.utf8)
        result.append(bytes)
        return result
    }

    private func json(_ obj: [String: Any]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{}".utf8)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    private func randomData(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    private func randomToken(_ bytes: Int) -> String {
        randomData(bytes).base64EncodedString().replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "=", with: "")
    }
}
