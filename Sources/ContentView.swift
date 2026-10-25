import SwiftUI
import PhotosUI
import ImageIO

@MainActor
final class ViewModel: ObservableObject {
    @Published var source: UIImage?
    @Published var result: UIImage?
    @Published var resultPreview: UIImage?
    @Published var exportURL: URL?
    @Published var model: UpscaleModel = UpscaleModel.available.contains(.standard)
        ? .standard : (UpscaleModel.available.first ?? .standard)
    @Published var progress = 0.0
    @Published var isRunning = false
    @Published var message: String?

    private let upscaler = Upscaler()
    private var task: Task<Void, Never>?
    private var loc: Loc { Loc.shared }

    func load(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let img = UIImage(data: data) else {
                message = loc.t("Image illisible.", "Unreadable image.")
                return
            }
            source = img
            result = nil; resultPreview = nil; exportURL = nil; message = nil
        } catch {
            message = error.localizedDescription
        }
    }

    func start() {
        guard let src = source, !isRunning else { return }
        result = nil; resultPreview = nil; exportURL = nil
        message = nil; progress = 0; isRunning = true
        UIApplication.shared.isIdleTimerDisabled = true

        let prefs = AppSettings.shared.p
        if prefs.notifications { Notifier.requestAuthorization() }
        let opts = UpscaleOptions(
            scale: prefs.scale,
            customWidth: (prefs.useCustomWidth && prefs.customWidth > 0) ? prefs.customWidth : nil,
            tile: prefs.tileSize,
            tta: prefs.tta,
            compute: prefs.compute)
        let upscaler = self.upscaler
        let model = self.model

        task = Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let out = try upscaler.upscale(src, model: model, options: opts) { p in
                    Task { @MainActor in self?.progress = p }
                }
                let preview = out.image.preview(maxSide: 4096)
                let url = ViewModel.export(out.image, prefs: prefs)
                await self?.finish(out, preview: preview, url: url, prefs: prefs)
            } catch is CancellationError {
                await self?.fail(Loc.shared.t("Annulé.", "Cancelled."), notify: false)
            } catch {
                await self?.fail(error.localizedDescription, notify: prefs.notifications)
            }
        }
    }

    func cancel() { task?.cancel() }

    private func finish(_ out: UpscaleOutput, preview: UIImage, url: URL?, prefs: Prefs) {
        result = out.image
        resultPreview = preview
        exportURL = url
        isRunning = false
        UIApplication.shared.isIdleTimerDisabled = false

        var notes: [String] = []
        if out.wasDownscaled {
            notes.append(loc.t("Image source réduite pour tenir en mémoire.", "Source image downscaled to fit in memory."))
        }
        if out.wasClamped {
            notes.append(loc.t("Taille de sortie réduite (limite mémoire).", "Output size reduced (memory limit)."))
        }
        message = notes.isEmpty ? nil : notes.joined(separator: " ")

        if prefs.notifications {
            Notifier.post("Upscayl", loc.t("Agrandissement terminé.", "Upscale finished."))
        }
        if prefs.autoSave { Task { await saveToPhotos() } }
    }

    private func fail(_ text: String, notify: Bool) {
        isRunning = false
        UIApplication.shared.isIdleTimerDisabled = false
        message = text
        if notify { Notifier.post("Upscayl", text) }
    }

    func saveToPhotos() async {
        guard let url = exportURL else { return }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            message = loc.t("Accès à Photos refusé.", "Photos access denied.")
            return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: url, options: nil)
            }
            message = loc.t("Enregistré dans Photos.", "Saved to Photos.")
        } catch {
            message = error.localizedDescription
        }
    }

    // MARK: - Export

    nonisolated static func export(_ image: UIImage, prefs: Prefs) -> URL? {
        let dir = FileManager.default.temporaryDirectory
        if let old = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for f in old where f.hasPrefix("Upscayl_") {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(f))
            }
        }
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
        let url = dir.appendingPathComponent("Upscayl_\(Int(Date().timeIntervalSince1970)).\(ext)")
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
                    AboutView().frame(width: w)
                    SettingsView().frame(width: w)
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
                        (icon: "info.circle", title: loc.t("À propos", "About")),
                        (icon: "gearshape", title: loc.t("Paramètres", "Settings"))
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
    @EnvironmentObject private var loc: Loc
    @EnvironmentObject private var settings: AppSettings
    @State private var pickerItem: PhotosPickerItem?

    private let card = RoundedRectangle(cornerRadius: 32, style: .continuous)
    private let panel = RoundedRectangle(cornerRadius: 26, style: .continuous)

    var body: some View {
        VStack(spacing: 16) {
            header
            preview
            controls
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .onChange(of: pickerItem) { item in
            Task { await vm.load(item) }
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

    private var preview: some View {
        ZStack {
            if let before = vm.source, let after = vm.resultPreview {
                CompareView(before: before, after: after)
            } else if let s = vm.source {
                Image(uiImage: s).resizable().scaledToFit()
            } else {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    VStack(spacing: 10) {
                        Image(systemName: "photo.badge.plus")
                            .font(.system(size: 40, weight: .light))
                        Text(loc.t("Ajouter une photo", "Add a photo")).font(.headline)
                        Text(loc.t("Touche pour choisir une image", "Tap to choose an image"))
                            .font(.footnote).foregroundColor(.secondary)
                    }
                    .foregroundColor(.primary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipShape(card)
        .glass(card)
    }

    private var scaleLabel: String {
        let p = settings.p
        if p.useCustomWidth && p.customWidth > 0 { return "\(p.customWidth) px" }
        return "×\(p.scale)"
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
                        .foregroundColor(Theme.accent)
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
                    Text(scaleLabel).foregroundColor(.secondary)
                    if settings.p.tta {
                        Text("TTA")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Theme.accent.opacity(0.18), in: Capsule())
                            .foregroundColor(Theme.accent)
                    }
                }
                .padding(.vertical, 12)

                if vm.isRunning {
                    Divider()
                    VStack(spacing: 6) {
                        ProgressView(value: vm.progress).tint(Theme.accent)
                        HStack {
                            Text("\(Int(vm.progress * 100)) % — \(loc.t("garde l'app ouverte", "keep the app open"))")
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
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Image(systemName: "photo")
                        .font(.system(size: 20, weight: .medium))
                        .frame(width: 56, height: 56)
                }
                .buttonStyle(GlassCircleStyle())
                .disabled(vm.isRunning)

                Button {
                    vm.start()
                } label: {
                    Text(loc.t("Agrandir", "Upscale"))
                }
                .buttonStyle(GlassButtonStyle(prominent: true))
                .disabled(vm.source == nil || vm.isRunning)
                .opacity(vm.source == nil || vm.isRunning ? 0.45 : 1)
            }

            if let r = vm.result, let cg = r.cgImage {
                HStack(spacing: 12) {
                    Button {
                        Task { await vm.saveToPhotos() }
                    } label: {
                        Label(loc.t("Enregistrer", "Save"), systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(GlassButtonStyle())

                    if let url = vm.exportURL {
                        ShareLink(item: url) {
                            Label(loc.t("Partager", "Share"), systemImage: "square.and.arrow.up")
                        }
                        .buttonStyle(GlassButtonStyle())
                    }
                }
                Text("\(loc.t("Résultat", "Result")) : \(cg.width) × \(cg.height) px")
                    .font(.footnote).foregroundColor(.secondary)
            }

            if let m = vm.message {
                Text(m).font(.footnote).foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }
}
