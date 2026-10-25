import SwiftUI

@main
struct UpscaylApp: App {
    @StateObject private var loc = Loc.shared
    @StateObject private var settings = AppSettings.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(loc)
                .environmentObject(settings)
        }
    }
}
