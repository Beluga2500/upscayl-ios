import CoreML
import CoreVideo
import UIKit

enum UpscaleModel: String, CaseIterable, Identifiable {
    case standard = "RealESRGAN_x4plus"
    case digital = "RealESRGAN_x4plus_anime_6B"
    case fast = "realesr-general-x4v3"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: return "Standard (photos)"
        case .digital: return "Illustrations / anime"
        case .fast: return "Rapide (général)"
        }
    }
}

enum UpscaleError: LocalizedError {
    case modelMissing(String)
    case badImage
    case badOutput
    case outOfMemory

    var errorDescription: String? {
        switch self {
        case .modelMissing(let n): return "Modèle introuvable : \(n)"
        case .badImage: return "Image illisible."
        case .badOutput: return "Sortie du modèle invalide."
        case .outOfMemory: return "Mémoire insuffisante."
        }
    }
}

final class Upscaler {
    static let tile = 128          // doit correspondre à convert_models.py
    static let pad = 12            // recouvrement pour éviter les coutures
    static let modelScale = 4
    static let maxOutputPixels = 64_000_000   // plafond mémoire (≈256 Mo)

    static let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    static let bitmapInfo: UInt32 =
        CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue

    private var models: [String: MLModel] = [:]

    private func loadModel(_ m: UpscaleModel) throws -> MLModel {
        if let cached = models[m.rawValue] { return cached }
        guard let url = Bundle.main.url(forResource: m.rawValue,
                                        withExtension: "mlmodelc",
                                        subdirectory: "Models") else {
            throw UpscaleError.modelMissing(m.rawValue)
        }
        let cfg = MLModelConfiguration()
        cfg.computeUnits = .all
        let model = try MLModel(contentsOf: url, configuration: cfg)
        models[m.rawValue] = model
        return model
    }

    func upscale(_ input: UIImage,
                 model: UpscaleModel,
                 targetScale: Int,
                 progress: (Double) -> Void) throws -> (image: UIImage, wasDownscaled: Bool) {
        let tile = Self.tile, pad = Self.pad, k = Self.modelScale
        let step = tile - 2 * pad
        let net = try loadModel(model)

        guard var cg = Self.flattened(input) else { throw UpscaleError.badImage }

        var downscaled = false
        let maxInputPixels = Self.maxOutputPixels / (k * k)
        if cg.width * cg.height > maxInputPixels {
            let f = (Double(maxInputPixels) / Double(cg.width * cg.height)).squareRoot()
            let nw = max(1, Int(Double(cg.width) * f))
            let nh = max(1, Int(Double(cg.height) * f))
            guard let r = Self.resize(cg, to: nw, nh) else { throw UpscaleError.badImage }
            cg = r
            downscaled = true
        }

        let W = cg.width, H = cg.height

        // Pixels source (BGRA)
        let srcStride = W * 4
        let srcPtr = UnsafeMutablePointer<UInt8>.allocate(capacity: srcStride * H)
        defer { srcPtr.deallocate() }
        guard let srcCtx = CGContext(data: srcPtr, width: W, height: H, bitsPerComponent: 8,
                                     bytesPerRow: srcStride, space: Self.colorSpace,
                                     bitmapInfo: Self.bitmapInfo) else { throw UpscaleError.badImage }
        srcCtx.draw(cg, in: CGRect(x: 0, y: 0, width: W, height: H))

        // Buffer de sortie
        let outW = W * k, outH = H * k
        let outStride = outW * 4
        let outBytes = outStride * outH
        guard let raw = calloc(outBytes, 1) else { throw UpscaleError.outOfMemory }
        var owned = true
        defer { if owned { free(raw) } }
        let outPtr = raw.assumingMemoryBound(to: UInt8.self)

        // Buffer d'entrée réutilisé pour chaque tuile
        var pbOpt: CVPixelBuffer?
        let attrs: CFDictionary = [
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [String: Any]()
        ] as CFDictionary
        CVPixelBufferCreate(kCFAllocatorDefault, tile, tile, kCVPixelFormatType_32BGRA, attrs, &pbOpt)
        guard let inBuf = pbOpt else { throw UpscaleError.outOfMemory }

        let tilesX = (W + step - 1) / step
        let tilesY = (H + step - 1) / step
        let total = tilesX * tilesY
        var done = 0

        for ty in 0..<tilesY {
            for tx in 0..<tilesX {
                if Task.isCancelled { throw CancellationError() }
                let x0 = tx * step, y0 = ty * step

                try autoreleasepool {
                    // 1. Remplir la tuile d'entrée (bords répliqués)
                    CVPixelBufferLockBaseAddress(inBuf, [])
                    let dst = CVPixelBufferGetBaseAddress(inBuf)!.assumingMemoryBound(to: UInt8.self)
                    let dstStride = CVPixelBufferGetBytesPerRow(inBuf)
                    for r in 0..<tile {
                        let sy = min(max(y0 - pad + r, 0), H - 1)
                        let srow = srcPtr + sy * srcStride
                        let drow = dst + r * dstStride
                        for c in 0..<tile {
                            let sx = min(max(x0 - pad + c, 0), W - 1)
                            memcpy(drow + c * 4, srow + sx * 4, 4)
                        }
                    }
                    CVPixelBufferUnlockBaseAddress(inBuf, [])

                    // 2. Inférence
                    let provider = try MLDictionaryFeatureProvider(
                        dictionary: ["input": MLFeatureValue(pixelBuffer: inBuf)])
                    let result = try net.prediction(from: provider)
                    guard let outBuf = result.featureValue(for: "output")?.imageBufferValue,
                          CVPixelBufferGetWidth(outBuf) == tile * k,
                          CVPixelBufferGetPixelFormatType(outBuf) == kCVPixelFormatType_32BGRA
                    else { throw UpscaleError.badOutput }

                    // 3. Copier la zone centrale
                    CVPixelBufferLockBaseAddress(outBuf, .readOnly)
                    let o = CVPixelBufferGetBaseAddress(outBuf)!.assumingMemoryBound(to: UInt8.self)
                    let oStride = CVPixelBufferGetBytesPerRow(outBuf)
                    let w = min(step, W - x0), h = min(step, H - y0)
                    for r in 0..<(h * k) {
                        let from = o + (pad * k + r) * oStride + pad * k * 4
                        let to = outPtr + (y0 * k + r) * outStride + x0 * k * 4
                        memcpy(to, from, w * k * 4)
                    }
                    CVPixelBufferUnlockBaseAddress(outBuf, .readOnly)
                }

                done += 1
                progress(Double(done) / Double(total))
            }
        }

        // CGImage sans copie
        guard let dp = CGDataProvider(dataInfo: nil, data: raw, size: outBytes,
                                      releaseData: { _, data, _ in free(UnsafeMutableRawPointer(mutating: data)) })
        else { throw UpscaleError.outOfMemory }
        owned = false
        guard let full = CGImage(width: outW, height: outH, bitsPerComponent: 8, bitsPerPixel: 32,
                                 bytesPerRow: outStride, space: Self.colorSpace,
                                 bitmapInfo: CGBitmapInfo(rawValue: Self.bitmapInfo),
                                 provider: dp, decode: nil, shouldInterpolate: true,
                                 intent: .defaultIntent) else { throw UpscaleError.badOutput }

        if targetScale == k {
            return (UIImage(cgImage: full), downscaled)
        }
        guard let resized = Self.resize(full, to: W * targetScale, H * targetScale) else {
            throw UpscaleError.outOfMemory
        }
        return (UIImage(cgImage: resized), downscaled)
    }

    // MARK: - Helpers

    /// Applique l'orientation EXIF et renvoie un CGImage opaque en pixels réels.
    static func flattened(_ img: UIImage) -> CGImage? {
        let f = UIGraphicsImageRendererFormat.default()
        f.scale = 1
        f.opaque = true
        let r = UIGraphicsImageRenderer(size: img.size, format: f)
        return r.image { _ in img.draw(at: .zero) }.cgImage
    }

    static func resize(_ cg: CGImage, to w: Int, _ h: Int) -> CGImage? {
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: 0, space: colorSpace,
                                  bitmapInfo: bitmapInfo) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()
    }
}

extension UIImage {
    /// Version réduite pour l'affichage (le plein format reste pour l'export).
    func preview(maxSide: Int) -> UIImage {
        guard let cg = cgImage else { return self }
        let longest = max(cg.width, cg.height)
        if longest <= maxSide { return self }
        let f = Double(maxSide) / Double(longest)
        let w = max(1, Int(Double(cg.width) * f)), h = max(1, Int(Double(cg.height) * f))
        guard let r = Upscaler.resize(cg, to: w, h) else { return self }
        return UIImage(cgImage: r)
    }
}
