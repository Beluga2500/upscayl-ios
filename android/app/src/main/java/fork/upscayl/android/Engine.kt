package fork.upscayl.android

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun interface ProgressCallback {
    fun onProgress(p: Float): Boolean
}

object Native {
    init {
        System.loadLibrary("upscayl")
    }

    external fun load(assets: AssetManager, name: String, useGpu: Boolean): Int
    external fun process(
        input: Bitmap, output: Bitmap, scale: Int, tile: Int, tta: Boolean, cb: ProgressCallback
    ): Int
}

enum class UpscaleModel(
    val id: String,
    private val titleText: String,
    private val fr: String,
    private val en: String
) {
    STANDARD(
        "upscayl-standard-4x", "Upscayl Standard",
        "Adapté à la plupart des images.",
        "Suitable for most images."
    ),
    LITE(
        "upscayl-lite-4x", "Upscayl Lite",
        "Adapté à la plupart des images. Agrandissement à haute vitesse avec une perte de qualité minimale.",
        "Suitable for most images. High-speed upscaling with minimal quality loss."
    ),
    HIGH_FIDELITY(
        "high-fidelity-4x", "High Fidelity",
        "Pour tous types d'images, axé sur des détails réalistes et des textures lisses.",
        "For all kinds of images, focused on realistic details and smooth textures."
    ),
    DIGITAL_ART(
        "digital-art-4x", "Digital Art",
        "Pour l'art numérique et les illustrations.",
        "For digital art and illustrations."
    ),
    REMACRI(
        "remacri-4x", "Remacri (Non-Commercial)",
        "Pour les images naturelles. Ajoute de la netteté et des détails (usage non commercial).",
        "For natural images. Adds sharpness and detail (non-commercial use)."
    ),
    ULTRAMIX(
        "ultramix-balanced-4x", "Ultramix (Non-Commercial)",
        "Pour les images naturelles, avec un équilibre entre netteté et détails (usage non commercial).",
        "For natural images, balancing sharpness and detail (non-commercial use)."
    ),
    ULTRASHARP(
        "ultrasharp-4x", "Ultrasharp (Non-Commercial)",
        "Pour les images naturelles, avec une priorité sur la netteté (usage non commercial).",
        "For natural images, prioritizing sharpness (non-commercial use)."
    );

    val title: String get() = titleText
    val detail: String get() = Loc.t(fr, en)
}

data class UpscaleOptions(
    val scale: Int = 4,
    val customWidth: Int? = null,
    val tile: Int = 0,
    val tta: Boolean = false,
    val double: Boolean = false,
    val useGpu: Boolean = true
)

class UpscaleResult(val bitmap: Bitmap, val usedGpu: Boolean, val fellBack: Boolean, val clamped: Boolean)

object Engine {
    const val SCALE = 4
    const val MAX_OUTPUT_PIXELS = 64_000_000L

    /** Décode l'image (EXIF appliqué) et la réduit si la sortie x4 dépasserait la limite mémoire. */
    fun decode(context: Context, uri: Uri): Pair<Bitmap, Boolean> {
        var downscaled = false
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            val maxInput = MAX_OUTPUT_PIXELS / (SCALE * SCALE)
            val px = w.toLong() * h
            if (px > maxInput) {
                val f = sqrt(maxInput.toDouble() / px)
                decoder.setTargetSize(max(1, (w * f).toInt()), max(1, (h * f).toInt()))
                downscaled = true
            }
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
        return bmp to downscaled
    }

    suspend fun upscale(
        context: Context,
        input: Bitmap,
        model: UpscaleModel,
        o: UpscaleOptions,
        onProgress: (Float) -> Unit
    ): UpscaleResult = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()[Job]
        var fellBack = false

        val mode = Native.load(context.assets, model.id, o.useGpu)
        if (mode < 0) {
            throw IllegalStateException(Loc.t("Modèle introuvable : ${model.title}", "Model not found: ${model.title}"))
        }
        var gpu = mode == 1

        // Taille finale demandée
        val w0 = input.width
        val h0 = input.height
        var tw: Int
        var th: Int
        if (o.customWidth != null && o.customWidth > 0) {
            tw = o.customWidth
            th = max(1, (h0.toDouble() * tw / w0).roundToInt())
        } else {
            tw = w0 * o.scale
            th = h0 * o.scale
        }
        var clamped = false
        if (tw.toLong() * th > MAX_OUTPUT_PIXELS) {
            val f = sqrt(MAX_OUTPUT_PIXELS.toDouble() / (tw.toLong() * th))
            tw = max(1, (tw * f).toInt())
            th = max(1, (th * f).toInt())
            clamped = true
        }

        // Une passe d'IA x4 (repli CPU si le GPU échoue)
        fun pass(src: Bitmap, from: Float, to: Float): Bitmap {
            val out = try {
                Bitmap.createBitmap(src.width * SCALE, src.height * SCALE, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException(Loc.t("Mémoire insuffisante pour cette image.", "Not enough memory for this image."))
            }
            val cb = ProgressCallback { p ->
                onProgress(from + p * (to - from))
                job?.isActive != false
            }
            var r = Native.process(src, out, SCALE, if (o.tile > 0) o.tile else if (gpu) 192 else 96, o.tta, cb)
            if (r == -2 && gpu) {
                fellBack = true
                gpu = false
                onProgress(from)
                if (Native.load(context.assets, model.id, false) < 0) {
                    throw IllegalStateException(Loc.t("Modèle introuvable : ${model.title}", "Model not found: ${model.title}"))
                }
                r = Native.process(src, out, SCALE, if (o.tile > 0) o.tile else 96, o.tta, cb)
            }
            when (r) {
                0 -> Unit
                -4 -> throw CancellationException("Annulé")
                -2 -> throw IllegalStateException(Loc.t("Erreur pendant l'inférence.", "Inference error."))
                else -> throw IllegalStateException(Loc.t("Erreur du moteur ($r).", "Engine error ($r)."))
            }
            return out
        }

        var result = pass(input, 0f, if (o.double) 0.5f else 1f)

        // Double Upscayl : le résultat est ramené à (cible / 4) puis ré-agrandi x4 par l'IA.
        // À l'échelle x16 c'est un double passage complet ; en dessous, la mémoire reste bornée.
        if (o.double) {
            val iw = max(1, (tw + SCALE - 1) / SCALE)
            val ih = max(1, (th + SCALE - 1) / SCALE)
            val mid = Bitmap.createScaledBitmap(result, iw, ih, true)
            if (mid !== result) result.recycle()
            result = pass(mid, 0.5f, 1f)
            mid.recycle()
        }

        if (result.width != tw || result.height != th) {
            val scaled = Bitmap.createScaledBitmap(result, tw, th, true)
            if (scaled !== result) result.recycle()
            result = scaled
        }
        UpscaleResult(result, gpu, fellBack, clamped)
    }
}
