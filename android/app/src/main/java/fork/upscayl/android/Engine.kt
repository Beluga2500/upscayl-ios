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
import kotlin.math.sqrt

fun interface ProgressCallback {
    fun onProgress(p: Float): Boolean
}

object Native {
    init {
        System.loadLibrary("upscayl")
    }

    external fun load(assets: AssetManager, name: String, useGpu: Boolean): Int
    external fun process(input: Bitmap, output: Bitmap, scale: Int, tile: Int, cb: ProgressCallback): Int
}

enum class UpscaleModel(val id: String, val title: String) {
    STANDARD("upscayl-standard-4x", "Upscayl Standard"),
    LITE("upscayl-lite-4x", "Upscayl Lite (rapide)"),
    HIGH_FIDELITY("high-fidelity-4x", "Haute fidélité"),
    REMACRI("remacri-4x", "Remacri"),
    ULTRAMIX("ultramix-balanced-4x", "Ultramix équilibré"),
    ULTRASHARP("ultrasharp-4x", "Ultrasharp"),
    DIGITAL_ART("digital-art-4x", "Art numérique")
}

class UpscaleResult(val bitmap: Bitmap, val usedGpu: Boolean, val fellBack: Boolean)

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
        targetScale: Int,
        useGpu: Boolean,
        onProgress: (Float) -> Unit
    ): UpscaleResult = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()[Job]
        val cb = ProgressCallback { p ->
            onProgress(p)
            job?.isActive != false
        }

        val mode = Native.load(context.assets, model.id, useGpu)
        if (mode < 0) throw IllegalStateException("Modèle introuvable : ${model.title}")
        var gpu = mode == 1
        var fellBack = false

        val out = try {
            Bitmap.createBitmap(input.width * SCALE, input.height * SCALE, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("Mémoire insuffisante pour cette image.")
        }

        var r = Native.process(input, out, SCALE, if (gpu) 192 else 96, cb)
        if (r == -2 && gpu) {
            // Le GPU a échoué : on retente sur le processeur
            fellBack = true
            gpu = false
            onProgress(0f)
            if (Native.load(context.assets, model.id, false) < 0) {
                throw IllegalStateException("Modèle introuvable : ${model.title}")
            }
            r = Native.process(input, out, SCALE, 96, cb)
        }

        when (r) {
            0 -> Unit
            -4 -> throw CancellationException("Annulé")
            -2 -> throw IllegalStateException("Erreur pendant l'inférence.")
            else -> throw IllegalStateException("Erreur du moteur ($r).")
        }

        val result = if (targetScale == SCALE) {
            out
        } else {
            val scaled = Bitmap.createScaledBitmap(
                out, input.width * targetScale, input.height * targetScale, true
            )
            if (scaled !== out) out.recycle()
            scaled
        }
        UpscaleResult(result, gpu, fellBack)
    }
}
