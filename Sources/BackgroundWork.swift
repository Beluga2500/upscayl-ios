import UIKit
import BackgroundTasks

/// Garde l'agrandissement en vie quand l'app passe en arrière-plan.
/// - Toutes versions : une marge d'environ 30 s (tâche d'arrière-plan classique).
/// - iOS 26 : tâche de « traitement continu » (progression affichée par le système, calcul sur le processeur).
final class BackgroundWork {
    static let shared = BackgroundWork()

    var onExpire: (() -> Void)?

    private var legacyID: UIBackgroundTaskIdentifier = .invalid
    private var registeredIDs: [String] = []
    private var continued: AnyObject?
    private var lastPct = 0
    private var lastTitle = "Upscayl"

    private init() {}

    // MARK: - Enregistrement (au lancement)

    func registerAtLaunch() {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            // Seuls les identifiants déclarés dans Info.plist peuvent être enregistrés
            let permitted = (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]) ?? []
            for id in permitted where id.hasSuffix(".upscale") {
                let ok = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { [weak self] task in
                    guard let t = task as? BGContinuedProcessingTask else {
                        task.setTaskCompleted(success: false)
                        return
                    }
                    self?.adopt(t)
                }
                if ok { registeredIDs.append(id) }
            }
        }
        #endif
    }

    #if compiler(>=6.2)
    @available(iOS 26.0, *)
    private func adopt(_ t: BGContinuedProcessingTask) {
        continued = t
        t.expirationHandler = { [weak self] in
            self?.onExpire?()
        }
        t.progress.totalUnitCount = 100
        t.progress.completedUnitCount = Int64(lastPct)
        t.updateTitle(lastTitle, subtitle: "")
    }
    #endif

    // MARK: - Cycle de vie d'un agrandissement

    func begin(title: String, subtitle: String) {
        lastPct = 0
        lastTitle = title
        if legacyID == .invalid {
            legacyID = UIApplication.shared.beginBackgroundTask(withName: "upscayl") { [weak self] in
                self?.endLegacy()
            }
        }
        #if compiler(>=6.2)
        if #available(iOS 26.0, *), let id = registeredIDs.first {
            let request = BGContinuedProcessingTaskRequest(identifier: id, title: title, subtitle: subtitle)
            do {
                try BGTaskScheduler.shared.submit(request)
            } catch {
                // Pas de tâche continue (identifiant refusé, etc.) : le calcul tourne tant que l'app le peut
            }
        }
        #endif
    }

    func update(progress: Double, subtitle: String) {
        lastPct = Int((progress * 100).rounded())
        #if compiler(>=6.2)
        if #available(iOS 26.0, *), let t = continued as? BGContinuedProcessingTask {
            t.progress.completedUnitCount = Int64(lastPct)
            t.updateTitle(lastTitle, subtitle: subtitle)
        }
        #endif
    }

    func end(success: Bool) {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *), let t = continued as? BGContinuedProcessingTask {
            t.progress.completedUnitCount = 100
            t.setTaskCompleted(success: success)
        }
        #endif
        continued = nil
        endLegacy()
    }

    private func endLegacy() {
        if legacyID != .invalid {
            UIApplication.shared.endBackgroundTask(legacyID)
            legacyID = .invalid
        }
    }
}
