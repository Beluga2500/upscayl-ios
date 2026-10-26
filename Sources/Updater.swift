import Foundation

/// Vérifie si une version plus récente est publiée sur la release « latest ».
final class Updater: ObservableObject {
    static let shared = Updater()

    static let versionURL = URL(string: "https://github.com/akuuvfx/upscayl-ios/releases/download/latest/version-ios.txt")!
    static let pageURL = URL(string: "https://github.com/akuuvfx/upscayl-ios/releases/tag/latest")!

    @Published var remoteVersion: String?
    @Published var available = false
    @Published var dismissed = false
    @Published var checking = false
    @Published var failed = false

    private init() {}

    @MainActor
    func check() async {
        guard !checking else { return }
        checking = true
        failed = false
        defer { checking = false }
        do {
            var req = URLRequest(url: Self.versionURL)
            req.cachePolicy = .reloadIgnoringLocalCacheData
            req.timeoutInterval = 10
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200,
                  let s = String(data: data, encoding: .utf8) else {
                failed = true
                return
            }
            let v = s.trimmingCharacters(in: .whitespacesAndNewlines)
            remoteVersion = v
            available = Self.isNewer(v, than: AppInfo.version)
            if available { dismissed = false }
        } catch {
            failed = true
        }
    }

    static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }
        let y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0
            let q = i < y.count ? y[i] : 0
            if p != q { return p > q }
        }
        return false
    }
}

enum Eta {
    /// Durée restante lisible : « quelques secondes », « ≈ 45 s », « ≈ 3 min 20 s ».
    static func text(remaining s: Double, loc: Loc) -> String {
        let t = Int(s.rounded())
        if t < 5 { return loc.t("quelques secondes", "a few seconds") }
        if t < 60 { return "≈ \(t) s" }
        let m = t / 60, r = t % 60
        if m < 60 { return r == 0 ? "≈ \(m) min" : "≈ \(m) min \(r) s" }
        return "≈ \(m / 60) h \(m % 60) min"
    }
}
