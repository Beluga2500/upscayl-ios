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

struct Prefs: Codable, Equatable {
    var format: OutputFormat = .png
    var scale: Int = 4
    var useCustomWidth = false
    var customWidth: Int = 0
    var compression: Int = 0
    var tileSize: Int = 0
    var tta = false
    var compute: ComputeChoice = .auto
    var autoSave = false
    var notifications = false

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
