import CoreML
import CoreVideo
import UIKit

enum UpscaleModel: String, CaseIterable, Identifiable {
    case standard = "upscayl-standard-4x"
    case lite = "upscayl-lite-4x"
    case highFidelity = "high-fidelity-4x"
    case digitalArt = "digital-art-4x"
    case remacri = "remacri-4x"
    case ultramix = "ultramix-balanced-4x"
    case ultrasharp = "ultrasharp-4x"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: return "Upscayl Standard"
        case .lite: return "Upscayl Lite"
        case .highFidelity: return "High Fidelity"
        case .digitalArt: return "Digital Art"
        case .remacri: return "Remacri (Non-Commercial)"
        case .ultramix: return "Ultramix (Non-Commercial)"
        case .ultrasharp: return "Ultrasharp (Non-Commercial)"
        }
    }

    var detail: String {
        let l = Loc.shared
        switch self {
        case .standard:
            return l.t("Adapté à la plupart des images.", "Suitable for most images.")
        case .lite:
            return l.t("Adapté à la plupart des images. Agrandissement à haute vitesse avec une perte de qualité minimale.",
                       "Suitable for most images. High-speed upscaling with minimal quality loss.")
        case .highFidelity:
            return l.t("Pour tous types d'images, axé sur des détails réalistes et des textures lisses.",
                       "For all kinds of images, focused on realistic details and smooth textures.")
        case .digitalArt:
            return l.t("Pour l'art numérique et les illustrations.", "For digital art and illustrations.")
        case .remacri:
            return l.t("Pour les images naturelles. Ajoute de la netteté et des détails (usage non commercial).",
                       "For natural images. Adds sharpness and detail (non-commercial use).")
        case .ultramix:
            return l.t("Pour les images naturelles, avec un équilibre entre netteté et détails (usage non commercial).",
                       "For natural images, balancing sharpness and detail (non-commercial use).")
        case .ultrasharp:
            return l.t("Pour les images naturelles, avec une priorité sur la netteté (usage non commercial).",
                       "For natural images, prioritizing sharpness (non-commercial use).")
        }
    }

    /// Modèles réellement présents dans l'app (une conversion ratée n'empêche pas les autres).
    static let available: [UpscaleModel] = {
        let present = allCases.filter {
            Bundle.main.url(forResource: $0.rawValue, withExtension: "mlmodelc", subdirectory: "Models") != nil
        }
        return present.isEmpty ? allCases : present
    }()
}

enum UpscaleError: LocalizedError {
    case modelMissing(String)
    case badImage
    case badOutput
    case outOfMemory

    var errorDescription: String? {
        switch self {
        case .modelMissing(let n): return Loc.shared.t("Modèle introuvable : \(n)", "Model not found: \(n)")
        case .badImage: return Loc.shared.t("Image illisible.", "Unreadable image.")
        case .badOutput: return Loc.shared.t("Sortie du modèle invalide.", "Invalid model output.")
        case .outOfMemory: return Loc.shared.t("Mémoire insuffisante.", "Not enough memory.")
        }
    }
}

struct UpscaleOptions {
    var scale = 4
    var customWidth: Int?
    var tile = 0            // 0 = auto
    var tta = false
    var double = false      // Double Upscayl : deux passes d'IA
    var compute: ComputeChoice = .auto
}

struct UpscaleOutput {
    let image: UIImage
    let wasDownscaled: Bool
    let wasClamped: Bool
}

final class Upscaler {
    static let modelScale = 4
    static let pad = 12
    static let maxOutputPixels = 64_000_000   // plafond mémoire (≈256 Mo)

    static let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    static let bitmapInfo: UInt32 =
        CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue

    /// Vrai quand l'app est en arrière-plan : le GPU y est interdit, on calcule sur le processeur.
    static var cpuOnlyNow = false

    private var cache: [String: (model: MLModel, tiles: [Int])] = [:]

    private func loadModel(_ m: UpscaleModel, compute: ComputeChoice) throws -> (model: MLModel, tiles: [Int]) {
        let key = m.rawValue + ":" + compute.rawValue
        if let c = cache[key] { return c }
        guard let url = Bundle.main.url(forResource: m.rawValue, withExtension: "mlmodelc",
                                        subdirectory: "Models") else {
            throw UpscaleError.modelMissing(m.title)
        }
        let cfg = MLModelConfiguration()
        cfg.computeUnits = compute.units
        let model = try MLModel(contentsOf: url, configuration: cfg)
        let entry = (model: model, tiles: Self.supportedTiles(model))
        cache = [key: entry]   // un seul modèle en mémoire à la fois
        return entry
    }

    /// Tailles de tuile acceptées par le modèle (carrées).
    private static func supportedTiles(_ model: MLModel) -> [Int] {
        guard let c = model.modelDescription.inputDescriptionsByName["input"]?.imageConstraint else { return [128] }
        let sc = c.sizeConstraint
        switch sc.type {
        case .enumerated:
            let s = sc.enumeratedImageSizes.filter { $0.pixelsWide == $0.pixelsHigh }.map { $0.pixelsWide }.sorted()
            return s.isEmpty ? [c.pixelsWide] : s
        case .range:
            let r = sc.pixelsWideRange
            let s = [64, 96, 128, 192, 256].filter { $0 >= r.location && $0 <= r.location + r.length }
            return s.isEmpty ? [c.pixelsWide] : s
        default:
            return [c.pixelsWide]
        }
    }

    private static func pickTile(requested: Int, tiles: [Int]) -> Int {
        let wanted = requested > 0 ? requested : 128
        return tiles.min(by: { abs($0 - wanted) < abs($1 - wanted) }) ?? 128
    }

    /// Source (x, y) d'un pixel de l'image transformée (rotation k·90° puis miroir), t = 0...7.
    @inline(__always)
    private static func mapCoord(_ x: Int, _ y: Int, _ n: Int, _ t: Int) -> (Int, Int) {
        var x = x, y = y
        var i = 0
        while i < (t & 3) {
            let nx = y
            let ny = n - 1 - x
            x = nx
            y = ny
            i += 1
        }
        if t & 4 != 0 { x = n - 1 - x }
        return (x, y)
    }

    func upscale(_ input: UIImage,
                 model: UpscaleModel,
                 options o: UpscaleOptions,
                 progress: (Double) -> Void) throws -> UpscaleOutput {
        let k = Self.modelScale
        let loaded = try loadModel(model, compute: o.compute)
        let T = Self.pickTile(requested: o.tile, tiles: loaded.tiles)
        let netProvider: () throws -> MLModel = { [unowned self] in
            try self.loadModel(model, compute: Self.cpuOnlyNow ? .cpu : o.compute).model
        }

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

        // Taille finale demandée
        var tw: Int, th: Int
        if let cw = o.customWidth, cw > 0 {
            tw = cw
            th = max(1, Int((Double(H) * Double(cw) / Double(W)).rounded()))
        } else {
            tw = W * o.scale
            th = H * o.scale
        }
        var clamped = false
        if tw * th > Self.maxOutputPixels {
            let f = (Double(Self.maxOutputPixels) / Double(tw * th)).squareRoot()
            tw = max(1, Int(Double(tw) * f))
            th = max(1, Int(Double(th) * f))
            clamped = true
        }

        // 1re passe (x4)
        var result = try runPass(cg, netProvider: netProvider, tile: T, tta: o.tta) { p in
            progress(o.double ? p * 0.5 : p)
        }

        // 2e passe : le résultat est ramené à (cible / 4), puis ré-agrandi x4 par l'IA.
        // À l'échelle x16 c'est exactement un double passage complet ; en dessous, la mémoire reste bornée.
        if o.double {
            let iw = max(1, (tw + k - 1) / k), ih = max(1, (th + k - 1) / k)
            guard let mid = Self.resize(result, to: iw, ih) else { throw UpscaleError.outOfMemory }
            result = mid   // libère la sortie de la 1re passe
            result = try runPass(mid, netProvider: netProvider, tile: T, tta: o.tta) { p in
                progress(0.5 + p * 0.5)
            }
        }

        if result.width == tw && result.height == th {
            return UpscaleOutput(image: UIImage(cgImage: result), wasDownscaled: downscaled, wasClamped: clamped)
        }
        guard let resized = Self.resize(result, to: tw, th) else { throw UpscaleError.outOfMemory }
        return UpscaleOutput(image: UIImage(cgImage: resized), wasDownscaled: downscaled, wasClamped: clamped)
    }

    /// Une passe d'IA x4 sur toute l'image (par tuiles).
    private func runPass(_ cg: CGImage,
                         netProvider: () throws -> MLModel,
                         tile T: Int,
                         tta: Bool,
                         progress: (Double) -> Void) throws -> CGImage {
        let k = Self.modelScale, pad = Self.pad
        let step = T - 2 * pad
        let N = T * k
        let W = cg.width, H = cg.height

        // Pixels source (BGRA)
        let srcStride = W * 4
        let srcPtr = UnsafeMutablePointer<UInt8>.allocate(capacity: srcStride * H)
        defer { srcPtr.deallocate() }
        guard let srcCtx = CGContext(data: srcPtr, width: W, height: H, bitsPerComponent: 8,
                                     bytesPerRow: srcStride, space: Self.colorSpace,
                                     bitmapInfo: Self.bitmapInfo) else { throw UpscaleError.badImage }
        srcCtx.draw(cg, in: CGRect(x: 0, y: 0, width: W, height: H))

        // Buffer de sortie x4
        let outW = W * k, outH = H * k
        let outStride = outW * 4
        let outBytes = outStride * outH
        guard let raw = calloc(outBytes, 1) else { throw UpscaleError.outOfMemory }
        var owned = true
        defer { if owned { free(raw) } }
        let outPtr = raw.assumingMemoryBound(to: UInt8.self)

        // Buffer d'entrée réutilisé
        var pbOpt: CVPixelBuffer?
        let attrs: CFDictionary = [
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [String: Any]()
        ] as CFDictionary
        CVPixelBufferCreate(kCFAllocatorDefault, T, T, kCVPixelFormatType_32BGRA, attrs, &pbOpt)
        guard let inBuf = pbOpt else { throw UpscaleError.outOfMemory }

        var base = [UInt8](repeating: 0, count: T * T * 4)
        let transforms = tta ? Array(0..<8) : [0]
        var acc = [UInt16](repeating: 0, count: tta ? N * N * 3 : 0)

        let tilesX = (W + step - 1) / step
        let tilesY = (H + step - 1) / step
        let total = tilesX * tilesY
        var done = 0

        /// Prédit la tuile `base` transformée par t.
        func run(_ t: Int) throws -> CVPixelBuffer {
            CVPixelBufferLockBaseAddress(inBuf, [])
            let dst = CVPixelBufferGetBaseAddress(inBuf)!.assumingMemoryBound(to: UInt8.self)
            let dstStride = CVPixelBufferGetBytesPerRow(inBuf)
            base.withUnsafeBufferPointer { bp in
                let b = bp.baseAddress!
                for r in 0..<T {
                    let drow = dst + r * dstStride
                    for c in 0..<T {
                        let (sx, sy) = t == 0 ? (c, r) : Self.mapCoord(c, r, T, t)
                        memcpy(drow + c * 4, b + (sy * T + sx) * 4, 4)
                    }
                }
            }
            CVPixelBufferUnlockBaseAddress(inBuf, [])

            let provider = try MLDictionaryFeatureProvider(
                dictionary: ["input": MLFeatureValue(pixelBuffer: inBuf)])
            let result: MLFeatureProvider
            do {
                result = try netProvider().prediction(from: provider)
            } catch {
                // Passage en arrière-plan pendant la tuile : on réessaie sur le processeur
                guard Self.cpuOnlyNow else { throw error }
                result = try netProvider().prediction(from: provider)
            }
            guard let outBuf = result.featureValue(for: "output")?.imageBufferValue,
                  CVPixelBufferGetWidth(outBuf) == N,
                  CVPixelBufferGetPixelFormatType(outBuf) == kCVPixelFormatType_32BGRA
            else { throw UpscaleError.badOutput }
            return outBuf
        }

        for ty in 0..<tilesY {
            for tx in 0..<tilesX {
                if Task.isCancelled { throw CancellationError() }
                let x0 = tx * step, y0 = ty * step
                let w = min(step, W - x0), h = min(step, H - y0)

                try autoreleasepool {
                    // 1. Tuile source avec bords répliqués
                    base.withUnsafeMutableBufferPointer { bp in
                        let b = bp.baseAddress!
                        for r in 0..<T {
                            let sy = min(max(y0 - pad + r, 0), H - 1)
                            let srow = srcPtr + sy * srcStride
                            for c in 0..<T {
                                let sx = min(max(x0 - pad + c, 0), W - 1)
                                memcpy(b + (r * T + c) * 4, srow + sx * 4, 4)
                            }
                        }
                    }

                    if !tta {
                        // 2a. Inférence directe
                        let outBuf = try run(0)
                        CVPixelBufferLockBaseAddress(outBuf, .readOnly)
                        let src = CVPixelBufferGetBaseAddress(outBuf)!.assumingMemoryBound(to: UInt8.self)
                        let oStride = CVPixelBufferGetBytesPerRow(outBuf)
                        for r in 0..<(h * k) {
                            let from = src + (pad * k + r) * oStride + pad * k * 4
                            let to = outPtr + (y0 * k + r) * outStride + x0 * k * 4
                            memcpy(to, from, w * k * 4)
                        }
                        CVPixelBufferUnlockBaseAddress(outBuf, .readOnly)
                    } else {
                        // 2b. TTA : 8 transformations, moyenne des résultats
                        acc.withUnsafeMutableBufferPointer { p in memset(p.baseAddress!, 0, p.count * 2) }
                        for (idx, t) in transforms.enumerated() {
                            if Task.isCancelled { throw CancellationError() }
                            let outBuf = try run(t)
                            CVPixelBufferLockBaseAddress(outBuf, .readOnly)
                            let src = CVPixelBufferGetBaseAddress(outBuf)!.assumingMemoryBound(to: UInt8.self)
                            let oStride = CVPixelBufferGetBytesPerRow(outBuf)
                            acc.withUnsafeMutableBufferPointer { ap in
                                for y in 0..<N {
                                    let row = src + y * oStride
                                    for x in 0..<N {
                                        let (sx, sy) = t == 0 ? (x, y) : Self.mapCoord(x, y, N, t)
                                        let di = (sy * N + sx) * 3
                                        let si = x * 4
                                        ap[di] &+= UInt16(row[si])
                                        ap[di + 1] &+= UInt16(row[si + 1])
                                        ap[di + 2] &+= UInt16(row[si + 2])
                                    }
                                }
                            }
                            CVPixelBufferUnlockBaseAddress(outBuf, .readOnly)
                            progress((Double(done) + Double(idx + 1) / Double(transforms.count)) / Double(total))
                        }
                        let n = UInt16(transforms.count)
                        acc.withUnsafeBufferPointer { ap in
                            for r in 0..<(h * k) {
                                let ay = pad * k + r
                                let to = outPtr + (y0 * k + r) * outStride + x0 * k * 4
                                for c in 0..<(w * k) {
                                    let ax = pad * k + c
                                    let si = (ay * N + ax) * 3
                                    let d = to + c * 4
                                    d[0] = UInt8((ap[si] + n / 2) / n)
                                    d[1] = UInt8((ap[si + 1] + n / 2) / n)
                                    d[2] = UInt8((ap[si + 2] + n / 2) / n)
                                    d[3] = 255
                                }
                            }
                        }
                    }
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
        return full
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
