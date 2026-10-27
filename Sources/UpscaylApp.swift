import SwiftUI

@main
struct UpscaylApp: App {
    @StateObject private var loc = Loc.shared
    @StateObject private var settings = AppSettings.shared

    init() {
        // Doit être fait avant la fin du lancement de l'app
        BackgroundWork.shared.registerAtLaunch()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(loc)
                .environmentObject(settings)
                .environment(\.theme, settings.p.theme)
                .preferredColorScheme(settings.p.appearance.scheme)
        }
    }
}
