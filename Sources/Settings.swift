import SwiftUI
import CoreML
import UserNotifications

enum OutputFormat: String, Codable, CaseIterable, Identifiable {
    case png, jpg, heic
    var id: String { rawValue }
    var label: String { rawValue.uppercased() }
    var ext: String { rawValue }
}

enum ComputeChoice: String, Codable, CaseIterable, Identifiable {
    case auto, gpu, cpu, neural
    var id: String { rawValue }

    var units: MLComputeUnits {
        switch self {
        case .auto: return .all
        case .gpu: return .cpuAndGPU
        case .cpu: return .cpuOnly
        case .neural: return .cpuAndNeuralEngine
        }
    }

    var title: String {
        switch self {
        case .auto: return Loc.shared.t("Automatique", "Automatic")
        case .gpu: return "CPU + GPU"
        case .cpu: return Loc.shared.t("Processeur seul", "CPU only")
        case .neural: return "CPU + Neural Engine"
        }
    }
}

enum AppTheme: String, Codable, CaseIterable, Identifiable {
    case ocean, violet, forest, sunset, rose, graphite
    var id: String { rawValue }

    var accent: Color {
        switch self {
        case .ocean: return Color(red: 0.04, green: 0.52, blue: 1.00)
        case .violet: return Color(red: 0.56, green: 0.36, blue: 0.96)
        case .forest: return Color(red: 0.16, green: 0.72, blue: 0.38)
        case .sunset: return Color(red: 1.00, green: 0.50, blue: 0.14)
        case .rose: return Color(red: 1.00, green: 0.30, blue: 0.55)
        case .graphite: return Color(red: 0.50, green: 0.54, blue: 0.62)
        }
    }

    /// Second halo du fond.
    var halo: Color {
        switch self {
        case .ocean: return Color(red: 0.35, green: 0.78, blue: 0.98)
        case .violet: return Color(red: 0.86, green: 0.46, blue: 0.95)
        case .forest: return Color(red: 0.30, green: 0.85, blue: 0.75)
        case .sunset: return Color(red: 1.00, green: 0.30, blue: 0.45)
        case .rose: return Color(red: 1.00, green: 0.62, blue: 0.76)
        case .graphite: return Color(red: 0.36, green: 0.40, blue: 0.48)
        }
    }

    var title: String {
        let l = Loc.shared
        switch self {
        case .ocean: return l.t("Océan", "Ocean")
        case .violet: return "Violet"
        case .forest: return l.t("Forêt", "Forest")
        case .sunset: return l.t("Coucher de soleil", "Sunset")
        case .rose: return "Rose"
        case .graphite: return "Graphite"
        }
    }
}

enum AppearanceMode: String, Codable, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }

    var scheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    var title: String {
        let l = Loc.shared
        switch self {
        case .system: return l.t("Système", "System")
        case .light: return l.t("Clair", "Light")
        case .dark: return l.t("Sombre", "Dark")
        }
    }
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue: AppTheme = .ocean
}

extension EnvironmentValues {
    var theme: AppTheme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

struct Prefs: Codable, Equatable {
    var format: OutputFormat = .png
    var scale: Int = 4
    var useCustomWidth = false
    var customWidth: Int = 0
    var compression: Int = 0
    var tileSize: Int = 0
    var tta = false
    var doubleUpscayl = false
    var compute: ComputeChoice = .auto
    var autoSave = false
    var notifications = false
    var theme: AppTheme = .ocean
    var appearance: AppearanceMode = .system

    init() {}

    // Décodage tolérant : une clé absente (ancienne version) prend sa valeur par défaut.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        format = try c.decodeIfPresent(OutputFormat.self, forKey: .format) ?? .png
        scale = try c.decodeIfPresent(Int.self, forKey: .scale) ?? 4
        useCustomWidth = try c.decodeIfPresent(Bool.self, forKey: .useCustomWidth) ?? false
        customWidth = try c.decodeIfPresent(Int.self, forKey: .customWidth) ?? 0
        compression = try c.decodeIfPresent(Int.self, forKey: .compression) ?? 0
        tileSize = try c.decodeIfPresent(Int.self, forKey: .tileSize) ?? 0
        tta = try c.decodeIfPresent(Bool.self, forKey: .tta) ?? false
        doubleUpscayl = try c.decodeIfPresent(Bool.self, forKey: .doubleUpscayl) ?? false
        compute = try c.decodeIfPresent(ComputeChoice.self, forKey: .compute) ?? .auto
        autoSave = try c.decodeIfPresent(Bool.self, forKey: .autoSave) ?? false
        notifications = try c.decodeIfPresent(Bool.self, forKey: .notifications) ?? false
        theme = try c.decodeIfPresent(AppTheme.self, forKey: .theme) ?? .ocean
        appearance = try c.decodeIfPresent(AppearanceMode.self, forKey: .appearance) ?? .system
    }

    func sanitized() -> Prefs {
        var p = self
        p.scale = min(max(p.scale, 1), 16)
        p.customWidth = min(max(p.customWidth, 0), 16384)
        p.compression = min(max(p.compression, 0), 100)
        p.tileSize = min(max(p.tileSize, 0), 1024)
        return p
    }
}

/// Réglages de l'app, mémorisés automatiquement.
final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    @Published var p: Prefs {
        didSet {
            let s = p.sanitized()
            if s != p { p = s; return }
            save()
        }
    }

    private init() {
        if let d = UserDefaults.standard.data(forKey: "prefs_v1"),
           let v = try? JSONDecoder().decode(Prefs.self, from: d) {
            p = v.sanitized()
        } else {
            p = Prefs()
        }
    }

    private func save() {
        if let d = try? JSONEncoder().encode(p) {
            UserDefaults.standard.set(d, forKey: "prefs_v1")
        }
    }

    func reset() { p = Prefs() }
}

enum Notifier {
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func post(_ title: String, _ body: String) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.sound = .default
        let r = UNNotificationRequest(
            identifier: UUID().uuidString, content: c,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        UNUserNotificationCenter.current().add(r)
    }
}

enum AppInfo {
    /// Version de l'app : 0.<mises à jour de fonctionnalités>.<correctifs>  (voir CHANGELOG.md)
    static let version = "0.7.2"
}
