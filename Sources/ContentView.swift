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

    func load(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let img = UIImage(data: data) else {
                message = "Image illisible."
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
                await self?.fail("Annulé.")
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
        message = downscaled ? "Image source réduite pour tenir en mémoire." : nil
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
            message = "Accès à Photos refusé."
            return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: url, options: nil)
            }
            message = "Enregistré dans Photos."
        } catch {
            message = error.localizedDescription
        }
    }
}

struct ContentView: View {
    @StateObject private var vm = ViewModel()
    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                preview
                controls
            }
            .padding()
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
                VStack(spacing: 8) {
                    Image(systemName: "photo.on.rectangle.angled").font(.system(size: 44))
                    Text("Choisis une photo à agrandir").font(.subheadline)
                }
                .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var controls: some View {
        VStack(spacing: 12) {
            PhotosPicker(selection: $pickerItem, matching: .images) {
                Label("Choisir une photo", systemImage: "photo")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(vm.isRunning)

            HStack {
                Text("Modèle")
                Spacer()
                Picker("Modèle", selection: $vm.model) {
                    ForEach(UpscaleModel.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.menu)
            }
            .disabled(vm.isRunning)

            Picker("Échelle", selection: $vm.scale) {
                ForEach([2, 3, 4], id: \.self) { Text("×\($0)").tag($0) }
            }
            .pickerStyle(.segmented)
            .disabled(vm.isRunning)

            if vm.isRunning {
                VStack(spacing: 6) {
                    ProgressView(value: vm.progress)
                    HStack {
                        Text("\(Int(vm.progress * 100)) % — garde l'app ouverte")
                            .font(.footnote).foregroundColor(.secondary)
                        Spacer()
                        Button("Annuler", role: .destructive) { vm.cancel() }
                            .font(.footnote)
                    }
                }
            } else {
                Button {
                    vm.start()
                } label: {
                    Label("Agrandir", systemImage: "wand.and.stars")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(vm.source == nil)
            }

            if let r = vm.result, let cg = r.cgImage {
                HStack(spacing: 10) {
                    Button {
                        Task { await vm.saveToPhotos() }
                    } label: {
                        Label("Photos", systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    if let url = vm.exportURL {
                        ShareLink(item: url) {
                            Label("Partager", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                Text("Résultat : \(cg.width) × \(cg.height) px")
                    .font(.footnote).foregroundColor(.secondary)
            }

            if let m = vm.message {
                Text(m).font(.footnote).foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }
}
