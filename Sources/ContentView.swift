import SwiftUI
import PhotosUI
import ImageIO

struct BatchItem: Identifiable {
    enum Status: Equatable {
        case pending, running, done, failed(String)
    }

    let id = UUID()
    let picker: PhotosPickerItem
    var beforeData: Data?       // aperçu « avant » (JPEG)
    var afterData: Data?        // aperçu « après » (JPEG)
    var exportURL: URL?
    var size: CGSize?
    var status: Status = .pending
}

private struct ItemOutput {
    let before: Data?
    let after: Data?
    let url: URL?
    let size: CGSize
    let downscaled: Bool
    let clamped: Bool
}

@MainActor
final class ViewModel: ObservableObject {
    @Published var items: [BatchItem] = []
    @Published var selected = 0
    @Published var displayBefore: UIImage?
    @Published var displayAfter: UIImage?
    @Published var model: UpscaleModel = UpscaleModel.available.contains(.standard)
        ? .standard : (UpscaleModel.available.first ?? .standard)
    @Published var progress = 0.0
    @Published var eta: String?
    @Published var isRunning = false
    @Published var message: String?

    private let upscaler = Upscaler()
    private var task: Task<Void, Never>?
    private var runStart = Date()
    private var loc: Loc { Loc.shared }

    var hasItems: Bool { !items.isEmpty }
    var current: BatchItem? { items.indices.contains(selected) ? items[selected] : nil }
    var doneCount: Int { items.filter { $0.status == .done }.count }
    var runningIndex: Int? { items.firstIndex { $0.status == .running } }

    // MARK: - Sélection

    func setPicked(_ picks: [PhotosPickerItem]) {
        guard !picks.isEmpty, !isRunning else { return }
        items = picks.map { BatchItem(picker: $0) }
        selected = 0
        message = nil
        progress = 0
        eta = nil
        refreshDisplay()
    }

    func select(_ i: Int) {
        guard items.indices.contains(i), i != selected else { return }
        selected = i
        refreshDisplay()
    }

    func next() { select(selected + 1) }
    func previous() { select(selected - 1) }

    private func refreshDisplay() {
        guard let it = current else {
            displayBefore = nil
            displayAfter = nil
            return
        }
        let id = it.id
        Task { [weak self] in
            guard let self else { return }
            if self.items.first(where: { $0.id == id })?.beforeData == nil {
                await self.loadBefore(id)
            }
            guard self.current?.id == id,
                  let cur = self.items.first(where: { $0.id == id }) else { return }
            let b = cur.beforeData, a = cur.afterData
            let imgs = await Task.detached { () -> (UIImage?, UIImage?) in
                (b.flatMap { UIImage(data: $0) }, a.flatMap { UIImage(data: $0) })
            }.value
            if self.current?.id == id {
                self.displayBefore = imgs.0
                self.displayAfter = imgs.1
            }
        }
    }

    private func loadBefore(_ id: UUID) async {
        guard let idx = items.firstIndex(where: { $0.id == id }) else { return }
        let side = items.count == 1 ? 4096 : 2048
        guard let data = try? await items[idx].picker.loadTransferable(type: Data.self) else {
            message = loc.t("Image illisible.", "Unreadable image.")
            return
        }
        let jpeg = await Task.detached { () -> Data? in
            guard let img = UIImage(data: data) else { return nil }
            return ViewModel.previewJPEG(img, side: side)
        }.value
        if let i = items.firstIndex(where: { $0.id == id }) { items[i].beforeData = jpeg }
    }

    // MARK: - Traitement

    func start() {
        guard !items.isEmpty, !isRunning else { return }
        let prefs = AppSettings.shared.p
        if prefs.notifications { Notifier.requestAuthorization() }
        let opts = UpscaleOptions(
            scale: prefs.scale,
            customWidth: (prefs.useCustomWidth && prefs.customWidth > 0) ? prefs.customWidth : nil,
            tile: prefs.tileSize,
            tta: prefs.tta,
            double: prefs.doubleUpscayl,
            compute: prefs.compute)

        ViewModel.cleanupExports()
        for i in items.indices {
            items[i].status = .pending
            items[i].afterData = nil
            items[i].exportURL = nil
            items[i].size = nil
        }
        message = nil
        progress = 0
        eta = nil
        runStart = Date()
        isRunning = true
        UIApplication.shared.isIdleTimerDisabled = true
        refreshDisplay()

        let model = self.model
        let upscaler = self.upscaler
        task = Task { [weak self] in
            await self?.runBatch(model: model, opts: opts, prefs: prefs, upscaler: upscaler)
        }
    }

    func cancel() { task?.cancel() }

    private func setProgress(index: Int, local: Double, total: Int) {
        let g = (Double(index) + local) / Double(total)
        progress = max(progress, min(g, 1))
        let elapsed = Date().timeIntervalSince(runStart)
        if g > 0.02 && elapsed > 3 {
            eta = Eta.text(remaining: elapsed * (1 - g) / g, loc: loc)
        }
    }

    private func runBatch(model: UpscaleModel, opts: UpscaleOptions, prefs: Prefs, upscaler: Upscaler) async {
        let n = items.count
        let side = n == 1 ? 4096 : 2048
        var downscaled = false
        var clamped = false
        var failures = 0
        var lastError = ""

        for i in 0..<n {
            if Task.isCancelled { break }
            items[i].status = .running
            do {
                guard let data = try await items[i].picker.loadTransferable(type: Data.self) else {
                    throw UpscaleError.badImage
                }
                let work = Task.detached(priority: .userInitiated) { [weak self] () throws -> ItemOutput in
                    guard let src = UIImage(data: data) else { throw UpscaleError.badImage }
                    let out = try upscaler.upscale(src, model: model, options: opts) { p in
                        Task { @MainActor in self?.setProgress(index: i, local: p, total: n) }
                    }
                    let before = ViewModel.previewJPEG(src, side: side)
                    let after = ViewModel.previewJPEG(out.image, side: side)
                    let url = ViewModel.export(out.image, prefs: prefs, index: i)
                    let w = out.image.cgImage?.width ?? 0
                    let h = out.image.cgImage?.height ?? 0
                    return ItemOutput(before: before, after: after, url: url,
                                      size: CGSize(width: w, height: h),
                                      downscaled: out.wasDownscaled, clamped: out.wasClamped)
                }
                let o = try await withTaskCancellationHandler {
                    try await work.value
                } onCancel: {
                    work.cancel()
                }

                if o.downscaled { downscaled = true }
                if o.clamped { clamped = true }
                if o.before != nil { items[i].beforeData = o.before }
                items[i].afterData = o.after
                items[i].exportURL = o.url
                items[i].size = o.size
                items[i].status = .done
                if selected == i { refreshDisplay() }
                if prefs.autoSave, let url = o.url { _ = await savePhotos([url]) }
            } catch is CancellationError {
                items[i].status = .pending
                break
            } catch {
                failures += 1
                lastError = error.localizedDescription
                items[i].status = .failed(lastError)
            }
        }

        let cancelled = Task.isCancelled
        isRunning = false
        eta = nil
        if !cancelled { progress = 1 }
        UIApplication.shared.isIdleTimerDisabled = false

        var parts: [String] = []
        if cancelled { parts.append(loc.t("Annulé.", "Cancelled.")) }
        if downscaled {
            parts.append(loc.t("Image source réduite pour tenir en mémoire.", "Source image downscaled to fit in memory."))
        }
        if clamped {
            parts.append(loc.t("Taille de sortie réduite (limite mémoire).", "Output size reduced (memory limit)."))
        }
        if failures > 0 {
            parts.append(loc.t("\(failures) image(s) en échec : \(lastError)", "\(failures) image(s) failed: \(lastError)"))
        }
        message = parts.isEmpty ? nil : parts.joined(separator: " ")

        if prefs.notifications && !cancelled {
            Notifier.post("Upscayl", loc.t("Agrandissement terminé.", "Upscale finished."))
        }
    }

    // MARK: - Photos

    func saveToPhotos() async {
        guard let url = current?.exportURL else { return }
        if await savePhotos([url]) { message = loc.t("Enregistré dans Photos.", "Saved to Photos.") }
    }

    func saveAllToPhotos() async {
        let urls = items.compactMap { $0.exportURL }
        guard !urls.isEmpty else { return }
        if await savePhotos(urls) {
            message = loc.t("\(urls.count) images enregistrées dans Photos.", "\(urls.count) images saved to Photos.")
        }
    }

    private func savePhotos(_ urls: [URL]) async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            message = loc.t("Accès à Photos refusé.", "Photos access denied.")
            return false
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                for u in urls {
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: u, options: nil)
                }
            }
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    // MARK: - Export

    nonisolated static func cleanupExports() {
        let dir = FileManager.default.temporaryDirectory
        if let old = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for f in old where f.hasPrefix("Upscayl_") {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(f))
            }
        }
    }

    nonisolated static func previewJPEG(_ image: UIImage, side: Int) -> Data? {
        let base: UIImage
        if let cg = Upscaler.flattened(image) { base = UIImage(cgImage: cg) } else { base = image }
        return base.preview(maxSide: side).jpegData(compressionQuality: 0.92)
    }

    nonisolated static func export(_ image: UIImage, prefs: Prefs, index: Int) -> URL? {
        let dir = FileManager.default.temporaryDirectory
        let q = max(0.01, 1.0 - Double(prefs.compression) / 100.0)
        var ext = prefs.format.ext
        var data: Data?
        switch prefs.format {
        case .png:
            data = image.pngData()
        case .jpg:
            data = image.jpegData(compressionQuality: q)
        case .heic:
            data = heicData(image, quality: q)
            if data == nil {
                data = image.jpegData(compressionQuality: q)
                ext = "jpg"
            }
        }
        guard let d = data else { return nil }
        let url = dir.appendingPathComponent("Upscayl_\(Int(Date().timeIntervalSince1970))_\(index + 1).\(ext)")
        do { try d.write(to: url) } catch { return nil }
        return url
    }

    nonisolated static func heicData(_ image: UIImage, quality: Double) -> Data? {
        guard let cg = image.cgImage,
              let buf = CFDataCreateMutable(nil, 0),
              let dest = CGImageDestinationCreateWithData(buf, "public.heic" as CFString, 1, nil) else { return nil }
        let opts = [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        CGImageDestinationAddImage(dest, cg, opts)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return buf as Data
    }
}

struct ContentView: View {
    @StateObject private var vm = ViewModel()
    @EnvironmentObject private var loc: Loc
    @EnvironmentObject private var settings: AppSettings

    @State private var page = 0
    @State private var scrub: CGFloat?

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let progress = currentProgress()

            ZStack {
                GlassBackground()

                // Les pages ne se déplacent qu'avec la barre d'onglets (aucun balayage sur le contenu)
                HStack(spacing: 0) {
                    UpscaleView(vm: vm).frame(width: w)
                    SettingsView().frame(width: w)
                    AboutView().frame(width: w)
                }
                .frame(width: w * 3, alignment: .leading)
                .offset(x: -progress * w)
                .frame(width: w, alignment: .leading)
                .clipped()
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                GlassTabBar(
                    items: [
                        (icon: "wand.and.stars", title: loc.t("Agrandir", "Upscale")),
                        (icon: "gearshape", title: loc.t("Paramètres", "Settings")),
                        (icon: "info.circle", title: loc.t("À propos", "About"))
                    ],
                    progress: progress,
                    lifted: scrub != nil,
                    onScrub: { v in
                        withAnimation(.interactiveSpring(response: 0.25, dampingFraction: 0.85)) { scrub = v }
                    },
                    onSettle: { target in settle(to: target) }
                )
                .padding(.horizontal, 24)
                .padding(.bottom, 6)
            }
        }
        .task {
            if settings.p.autoUpdateCheck { await Updater.shared.check() }
        }
    }

    private func currentProgress() -> CGFloat {
        scrub ?? CGFloat(page)
    }

    private func settle(to target: Int) {
        let t = min(max(target, 0), 2)
        if t != page { UISelectionFeedbackGenerator().selectionChanged() }
        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) {
            page = t
            scrub = nil
        }
    }
}

struct UpscaleView: View {
    @ObservedObject var vm: ViewModel
    @ObservedObject private var updater = Updater.shared
    @EnvironmentObject private var loc: Loc
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.theme) private var theme
    @State private var pickerItems: [PhotosPickerItem] = []

    private let card = RoundedRectangle(cornerRadius: 32, style: .continuous)
    private let panel = RoundedRectangle(cornerRadius: 26, style: .continuous)

    var body: some View {
        VStack(spacing: 14) {
            header
            if updater.available && !updater.dismissed { updateBanner }
            preview
            controls
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .onChange(of: pickerItems) { picks in
            if !picks.isEmpty {
                vm.setPicked(picks)
                pickerItems = []
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Upscayl").font(.largeTitle.bold())
            Text(loc.t("Agrandis tes images avec l'IA", "Enhance your images with AI"))
                .font(.subheadline).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var updateBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.circle.fill")
                .font(.title3)
                .foregroundColor(theme.accent)
            VStack(alignment: .leading, spacing: 1) {
                Text(loc.t("Nouvelle version disponible", "New version available"))
                    .font(.subheadline.weight(.semibold))
                Text("\(AppInfo.version) → \(updater.remoteVersion ?? "")")
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Link(loc.t("Télécharger", "Download"), destination: Updater.pageURL)
                .font(.subheadline.weight(.semibold))
            Button {
                updater.dismissed = true
            } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundColor(.secondary)
                    .padding(6)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .glass(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var preview: some View {
        ZStack {
            if let before = vm.displayBefore, let after = vm.displayAfter {
                CompareView(before: before, after: after)
                    .id(vm.current?.id)
            } else if let before = vm.displayBefore {
                Image(uiImage: before).resizable().scaledToFit()
            } else if vm.hasItems {
                ProgressView()
            } else {
                PhotosPicker(selection: $pickerItems, maxSelectionCount: 30, matching: .images) {
                    VStack(spacing: 10) {
                        Image(systemName: "photo.badge.plus")
                            .font(.system(size: 40, weight: .light))
                        Text(loc.t("Ajouter des photos", "Add photos")).font(.headline)
                        Text(loc.t("Touche pour choisir une ou plusieurs images", "Tap to choose one or more images"))
                            .font(.footnote).foregroundColor(.secondary)
                    }
                    .foregroundColor(.primary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay { if vm.items.count > 1 { arrows } }
        .overlay(alignment: .top) { if vm.items.count > 1 { counter } }
        .clipShape(card)
        .glass(card)
    }

    private var counter: some View {
        HStack(spacing: 6) {
            switch vm.current?.status {
            case .done:
                Image(systemName: "checkmark.circle.fill").foregroundColor(theme.accent)
            case .running:
                ProgressView().scaleEffect(0.6)
            case .failed:
                Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange)
            default:
                EmptyView()
            }
            Text("\(vm.selected + 1) / \(vm.items.count)")
                .font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(.ultraThinMaterial, in: Capsule())
        .padding(.top, 12)
    }

    private var arrows: some View {
        HStack {
            arrowButton("chevron.left", enabled: vm.selected > 0) { vm.previous() }
            Spacer()
            arrowButton("chevron.right", enabled: vm.selected < vm.items.count - 1) { vm.next() }
        }
        .padding(.horizontal, 10)
    }

    private func arrowButton(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.primary)
                .frame(width: 40, height: 40)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(Color.white.opacity(0.3), lineWidth: 0.8))
        }
        .opacity(enabled ? 1 : 0.3)
        .disabled(!enabled)
    }

    private var scaleBinding: Binding<Int> {
        Binding(
            get: { settings.p.scale },
            set: { v in
                settings.p.scale = v
                settings.p.useCustomWidth = false
            })
    }

    private var scaleLabel: String {
        let p = settings.p
        if p.useCustomWidth && p.customWidth > 0 { return "\(p.customWidth) px" }
        return "×\(p.scale)"
    }

    private var progressText: String {
        var s = "\(Int(vm.progress * 100)) %"
        if let e = vm.eta { s += " — \(e) \(loc.t("restantes", "left"))" }
        if vm.items.count > 1, let r = vm.runningIndex {
            s = "\(loc.t("Image", "Image")) \(r + 1)/\(vm.items.count) — " + s
        }
        return s
    }

    private var controls: some View {
        VStack(spacing: 12) {
            VStack(spacing: 0) {
                HStack {
                    Text(loc.t("Modèle", "Model"))
                    Spacer()
                    Menu {
                        Picker(loc.t("Modèle", "Model"), selection: $vm.model) {
                            ForEach(UpscaleModel.available) { Text($0.title).tag($0) }
                        }
                    } label: {
                        HStack(spacing: 5) {
                            Text(vm.model.title)
                            Image(systemName: "chevron.up.chevron.down")
                                .font(.system(size: 11, weight: .semibold))
                        }
                        .foregroundColor(theme.accent)
                    }
                    .disabled(vm.isRunning)
                }
                .padding(.top, 13)

                Text(vm.model.detail)
                    .font(.footnote).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4).padding(.bottom, 12)

                Divider()

                HStack {
                    Text(loc.t("Échelle", "Scale"))
                    Spacer()
                    if settings.p.doubleUpscayl { chip("×2 IA") }
                    if settings.p.tta { chip("TTA") }
                    Menu {
                        Picker(loc.t("Échelle", "Scale"), selection: scaleBinding) {
                            ForEach(1...16, id: \.self) { Text("×\($0)").tag($0) }
                        }
                    } label: {
                        HStack(spacing: 5) {
                            Text(scaleLabel)
                            Image(systemName: "chevron.up.chevron.down")
                                .font(.system(size: 11, weight: .semibold))
                        }
                        .foregroundColor(theme.accent)
                    }
                    .disabled(vm.isRunning)
                }
                .padding(.vertical, 12)

                if vm.isRunning {
                    Divider()
                    VStack(spacing: 6) {
                        ProgressView(value: vm.progress).tint(theme.accent)
                        HStack {
                            Text(progressText)
                                .font(.footnote).foregroundColor(.secondary)
                            Spacer()
                            Button(loc.t("Annuler", "Cancel"), role: .destructive) { vm.cancel() }
                                .font(.footnote)
                        }
                    }
                    .padding(.vertical, 12)
                }
            }
            .font(.subheadline)
            .padding(.horizontal, 16)
            .glass(panel)

            HStack(spacing: 12) {
                PhotosPicker(selection: $pickerItems, maxSelectionCount: 30, matching: .images) {
                    Image(systemName: "photo.on.rectangle")
                        .font(.system(size: 20, weight: .medium))
                        .frame(width: 56, height: 56)
                }
                .buttonStyle(GlassCircleStyle())
                .disabled(vm.isRunning)

                Button {
                    vm.start()
                } label: {
                    Text(vm.items.count > 1
                         ? "\(loc.t("Agrandir", "Upscale")) (\(vm.items.count))"
                         : loc.t("Agrandir", "Upscale"))
                }
                .buttonStyle(GlassButtonStyle(prominent: true))
                .disabled(!vm.hasItems || vm.isRunning)
                .opacity(!vm.hasItems || vm.isRunning ? 0.45 : 1)
            }

            if let it = vm.current, it.status == .done, let url = it.exportURL {
                HStack(spacing: 12) {
                    Button {
                        Task { await vm.saveToPhotos() }
                    } label: {
                        Label(loc.t("Enregistrer", "Save"), systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(GlassButtonStyle())

                    ShareLink(item: url) {
                        Label(loc.t("Partager", "Share"), systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(GlassButtonStyle())
                }
                if vm.doneCount > 1 && !vm.isRunning {
                    Button {
                        Task { await vm.saveAllToPhotos() }
                    } label: {
                        Text("\(loc.t("Tout enregistrer", "Save all")) (\(vm.doneCount))")
                    }
                    .buttonStyle(GlassButtonStyle())
                }
                if let s = it.size {
                    Text("\(loc.t("Résultat", "Result")) : \(Int(s.width)) × \(Int(s.height)) px")
                        .font(.footnote).foregroundColor(.secondary)
                }
            }

            if let m = vm.message {
                Text(m).font(.footnote).foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(theme.accent.opacity(0.18), in: Capsule())
            .foregroundColor(theme.accent)
    }
}
