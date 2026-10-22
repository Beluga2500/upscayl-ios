import SwiftUI

@main
struct UpscaylApp: App {
    @StateObject private var loc = Loc.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(loc)
        }
    }
}
