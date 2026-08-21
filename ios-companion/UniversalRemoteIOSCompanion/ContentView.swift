import SwiftUI

struct ContentView: View {
    @EnvironmentObject var server: CompanionServer

    var body: some View {
        NavigationStack {
            List {
                Section("Статус") {
                    Text(server.status)
                    Text(server.running ? "Локальная сеть: активна только пока Companion работает" : "Нажмите «Запустить Companion»")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button(server.running ? "Остановить Companion" : "Запустить Companion") {
                        server.running ? server.stop() : server.start()
                    }
                }

                Section("Одноразовый pairing-код") {
                    Text(server.pairCode).font(.system(.title2, design: .monospaced)).bold()
                    Text("Истекает через ~\(server.remainingText)").font(.caption).foregroundStyle(.secondary)
                    Button("Новый pairing-код") { server.rotatePairCode() }
                }

                Section("Что доступно на iPhone/iPad") {
                    Label("Проверка связи (Ping)", systemImage: "network")
                    Label("Найти устройство: звук/отклик, когда Companion активен", systemImage: "speaker.wave.2")
                    Label("Изменение яркости экрана, когда Companion активен", systemImage: "sun.max")
                    Text("iPadOS/iOS не дают стороннему приложению эмулировать Home/Back/касания по всей системе и не принимают PIN экрана как сетевой пароль.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Последняя команда") { Text(server.lastAction) }

                Section("Сопряжённые пульты") {
                    if server.paired.isEmpty { Text("Нет доверенных пультов").foregroundStyle(.secondary) }
                    ForEach(server.paired) { item in
                        VStack(alignment: .leading) {
                            Text(item.name)
                            Text("до \(item.expiresAt.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(.secondary)
                        }
                        Button("Отозвать", role: .destructive) { server.revoke(item.id) }
                    }
                    if !server.paired.isEmpty { Button("Отозвать все", role: .destructive) { server.revokeAll() } }
                }

                Section("Важно") {
                    Text("iOS может приостановить обычное приложение в фоне, поэтому локальный Companion не обещает постоянную доступность при закрытом/уснувшем приложении. Для управляемых организацией iPad без Companion используйте штатный MDM enrollment.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("UniversalRemote iOS")
        }
    }
}
