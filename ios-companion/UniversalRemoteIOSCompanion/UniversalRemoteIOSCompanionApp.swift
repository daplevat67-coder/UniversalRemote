import SwiftUI

@main
struct UniversalRemoteIOSCompanionApp: App {
    @StateObject private var server = CompanionServer()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(server)
        }
    }
}
