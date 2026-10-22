import SwiftUI

enum Lang: String, CaseIterable, Identifiable {
    case fr, en
    var id: String { rawValue }
    var label: String { self == .fr ? "Français" : "English" }
}

/// Langue de l'app (choisie dans « À propos », mémorisée).
final class Loc: ObservableObject {
    static let shared = Loc()

    @Published var lang: Lang {
        didSet { UserDefaults.standard.set(lang.rawValue, forKey: "app_lang") }
    }

    private init() {
        if let s = UserDefaults.standard.string(forKey: "app_lang"), let l = Lang(rawValue: s) {
            lang = l
        } else {
            lang = (Locale.preferredLanguages.first ?? "fr").hasPrefix("fr") ? .fr : .en
        }
    }

    func t(_ fr: String, _ en: String) -> String { lang == .fr ? fr : en }
}
