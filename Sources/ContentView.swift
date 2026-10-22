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

    var body: some View {
        TabView {
            UpscaleView(vm: vm)
                .tabItem { Label(loc.t("Agrandir", "Upscale"), systemImage: "wand.and.stars") }
            AboutView()
                .tabItem { Label(loc.t("À propos", "About"), systemImage: "info.circle") }
        }
    }
}

struct UpscaleView: View {
    @ObservedObject var vm: ViewModel
    @EnvironmentObject private var loc: Loc
    @State private var pickerItem: PhotosPickerItem?

    private let card = RoundedRectangle(cornerRadius: 28, style: .continuous)
    private let panel = RoundedRectangle(cornerRadius: 24, style: .continuous)

    var body: some View {
        NavigationStack {
            ZStack {
                GlassBackground()
                VStack(spacing: 14) {
                    preview
                    controls
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
            .navigationTitle("Upscayl")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: pickerItem) { item in
                Task { await vm.load(item) }
            }
        }
    }

    private var preview: some View {
        ZStack {
            if let before = vm.source, let after = vm.resultPreview {
                CompareView(before: before, after: after)
            } else if let s = vm.source {
                Image(uiImage: s).resizable().scaledToFit()
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "photo.on.rectangle.angled").font(.system(size: 44))
                    Text(loc.t("Choisis une photo à agrandir", "Pick a photo to upscale")).font(.subheadline)
                }
                .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipShape(card)
        .glass(card)
    }

    private var controls: some View {
        VStack(spacing: 12) {
            VStack(spacing: 12) {
                HStack {
                    Text(loc.t("Modèle", "Model")).font(.subheadline)
                    Spacer()
                    Picker(loc.t("Modèle", "Model"), selection: $vm.model) {
                        ForEach(UpscaleModel.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.menu)
                }
                Picker(loc.t("Échelle", "Scale"), selection: $vm.scale) {
                    ForEach([2, 3, 4], id: \.self) { Text("×\($0)").tag($0) }
                }
                .pickerStyle(.segmented)

                if vm.isRunning {
                    VStack(spacing: 6) {
                        ProgressView(value: vm.progress)
                        HStack {
                            Text("\(Int(vm.progress * 100)) % — \(loc.t("garde l'app ouverte", "keep the app open"))")
                                .font(.footnote).foregroundColor(.secondary)
                            Spacer()
                            Button(loc.t("Annuler", "Cancel"), role: .destructive) { vm.cancel() }
                                .font(.footnote)
                        }
                    }
                }
            }
            .padding(14)
            .glass(panel)

            HStack(spacing: 10) {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label("Photo", systemImage: "photo")
                }
                .buttonStyle(GlassButtonStyle())
                .disabled(vm.isRunning)

                Button {
                    vm.start()
                } label: {
                    Label(loc.t("Agrandir", "Upscale"), systemImage: "wand.and.stars")
                }
                .buttonStyle(GlassButtonStyle(prominent: true))
                .disabled(vm.source == nil || vm.isRunning)
                .opacity(vm.source == nil || vm.isRunning ? 0.5 : 1)
            }

            if let r = vm.result, let cg = r.cgImage {
                HStack(spacing: 10) {
                    Button {
                        Task { await vm.saveToPhotos() }
                    } label: {
                        Label("Photos", systemImage: "square.and.arrow.down")
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
