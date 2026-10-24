import SwiftUI
import PhotosUI

@MainActor
final class ViewModel: ObservableObject {
    @Published var source: UIImage?
    @Published var result: UIImage?
    @Published var resultPreview: UIImage?
    @Published var exportURL: URL?
    @Published var model: UpscaleModel = .standard
    @Published var scale = 4
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

        let upscaler = self.upscaler
        let model = self.model
        let scale = self.scale

        task = Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let out = try upscaler.upscale(src, model: model, targetScale: scale) { p in
                    Task { @MainActor in self?.progress = p }
                }
                let preview = out.image.preview(maxSide: 2048)
                let url = FileManager.default.temporaryDirectory
                    .appendingPathComponent("Upscayl_x\(scale)_\(Int(Date().timeIntervalSince1970)).png")
                if let png = out.image.pngData() { try? png.write(to: url) }
                await self?.finish(out.image, preview: preview, url: url, downscaled: out.wasDownscaled)
            } catch is CancellationError {
                await self?.fail(Loc.shared.t("Annulé.", "Cancelled."))
            } catch {
                await self?.fail(error.localizedDescription)
            }
        }
    }

    func cancel() { task?.cancel() }

    private func finish(_ image: UIImage, preview: UIImage, url: URL, downscaled: Bool) {
        result = image
        resultPreview = preview
        exportURL = FileManager.default.fileExists(atPath: url.path) ? url : nil
        isRunning = false
        UIApplication.shared.isIdleTimerDisabled = false
        message = downscaled
            ? loc.t("Image source réduite pour tenir en mémoire.", "Source image downscaled to fit in memory.")
            : nil
    }

    private func fail(_ text: String) {
        isRunning = false
        UIApplication.shared.isIdleTimerDisabled = false
        message = text
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
}

struct ContentView: View {
    @StateObject private var vm = ViewModel()
    @EnvironmentObject private var loc: Loc

    @State private var page = 0
    @State private var dragX: CGFloat = 0
    @State private var scrub: CGFloat?

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let progress = currentProgress(width: w)

            ZStack {
                GlassBackground()

                HStack(spacing: 0) {
                    UpscaleView(vm: vm).frame(width: w)
                    AboutView().frame(width: w)
                }
                .frame(width: w * 2, alignment: .leading)
                .offset(x: -progress * w)
                .frame(width: w, alignment: .leading)
                .clipped()
                .simultaneousGesture(pagerGesture(width: w))
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                GlassTabBar(
                    items: [
                        (icon: "wand.and.stars", title: loc.t("Agrandir", "Upscale")),
                        (icon: "info.circle", title: loc.t("À propos", "About"))
                    ],
                    progress: progress,
                    lifted: scrub != nil,
                    onScrub: { v in
                        withAnimation(.interactiveSpring(response: 0.25, dampingFraction: 0.85)) { scrub = v }
                    },
                    onSettle: { target in settle(to: target) }
                )
                .padding(.horizontal, 36)
                .padding(.bottom, 6)
            }
        }
    }

    private func currentProgress(width w: CGFloat) -> CGFloat {
        if let s = scrub { return s }
        guard w > 0 else { return CGFloat(page) }
        return min(max(CGFloat(page) - dragX / w, 0), 1)
    }

    private func settle(to target: Int) {
        let t = min(max(target, 0), 1)
        if t != page { UISelectionFeedbackGenerator().selectionChanged() }
        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) {
            page = t
            dragX = 0
            scrub = nil
        }
    }

    private func pagerGesture(width w: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 16)
            .onChanged { v in
                guard abs(v.translation.width) > abs(v.translation.height) else { return }
                dragX = v.translation.width
            }
            .onEnded { v in
                guard w > 0 else { return }
                let projected = CGFloat(page) - v.predictedEndTranslation.width / w
                settle(to: projected > 0.5 ? 1 : 0)
            }
    }
}

struct UpscaleView: View {
    @ObservedObject var vm: ViewModel
    @EnvironmentObject private var loc: Loc
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

    private var controls: some View {
        VStack(spacing: 12) {
            VStack(spacing: 0) {
                HStack {
                    Text(loc.t("Modèle", "Model"))
                    Spacer()
                    Menu {
                        Picker(loc.t("Modèle", "Model"), selection: $vm.model) {
                            ForEach(UpscaleModel.allCases) { Text($0.title).tag($0) }
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
                .padding(.vertical, 13)

                Divider()

                HStack {
                    Text(loc.t("Échelle", "Scale"))
                    Spacer()
                    Picker(loc.t("Échelle", "Scale"), selection: $vm.scale) {
                        ForEach([2, 3, 4], id: \.self) { Text("×\($0)").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 168)
                    .disabled(vm.isRunning)
                }
                .padding(.vertical, 9)

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
