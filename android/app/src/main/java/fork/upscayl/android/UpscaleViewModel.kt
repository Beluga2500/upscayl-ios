package fork.upscayl.android

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class UpscaleViewModel(private val app: Application) : AndroidViewModel(app) {
    var source by mutableStateOf<Bitmap?>(null)
    var result by mutableStateOf<Bitmap?>(null)
    var resultPreview by mutableStateOf<Bitmap?>(null)
    var exportFile by mutableStateOf<File?>(null)
    var model by mutableStateOf(UpscaleModel.STANDARD)
    var progress by mutableFloatStateOf(0f)
    var running by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    private var job: Job? = null

    fun load(uri: Uri) {
        if (running) return
        viewModelScope.launch {
            try {
                val (bmp, downscaled) = withContext(Dispatchers.Default) { Engine.decode(app, uri) }
                source = bmp
                result = null
                resultPreview = null
                exportFile = null
                message = if (downscaled) {
                    Loc.t("Image source réduite pour tenir en mémoire.", "Source image downscaled to fit in memory.")
                } else null
            } catch (e: Throwable) {
                message = Loc.t("Image illisible.", "Unreadable image.")
            }
        }
    }

    fun start() {
        val src = source ?: return
        if (running) return
        result = null
        resultPreview = null
        exportFile = null
        message = null
        progress = 0f
        running = true

        val prefs = AppSettings.p
        val opts = UpscaleOptions(
            scale = prefs.scale,
            customWidth = if (prefs.useCustomWidth && prefs.customWidth > 0) prefs.customWidth else null,
            tile = prefs.tileSize,
            tta = prefs.tta,
            double = prefs.doubleUpscayl,
            useGpu = prefs.useGpu
        )

        job = viewModelScope.launch {
            try {
                val res = Engine.upscale(app, src, model, opts) { progress = it }
                val preview = withContext(Dispatchers.Default) { previewOf(res.bitmap) }
                val file = withContext(Dispatchers.IO) { writeFile(res.bitmap, prefs) }
                result = res.bitmap
                resultPreview = preview
                exportFile = file

                val notes = mutableListOf<String>()
                if (res.fellBack) {
                    notes += Loc.t("GPU indisponible : traitement sur le processeur.", "GPU unavailable: processed on the CPU.")
                }
                if (res.clamped) {
                    notes += Loc.t("Taille de sortie réduite (limite mémoire).", "Output size reduced (memory limit).")
                }
                message = if (notes.isEmpty()) null else notes.joinToString(" ")

                if (prefs.notifications && !AppState.foreground) {
                    Notifier.post(app, "Upscayl", Loc.t("Agrandissement terminé.", "Upscale finished."))
                }
                if (prefs.autoSave) saveToGallery()
            } catch (e: CancellationException) {
                message = Loc.t("Annulé.", "Cancelled.")
            } catch (e: Throwable) {
                message = e.message ?: Loc.t("Erreur.", "Error.")
                if (prefs.notifications && !AppState.foreground) Notifier.post(app, "Upscayl", message ?: "")
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun saveToGallery() {
        val file = exportFile ?: return
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) {
                    val mime = OutputFormat.values().firstOrNull { it.ext == file.extension }?.mime ?: "image/png"
                    val resolver = app.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Upscayl")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException(Loc.t("Enregistrement impossible.", "Could not save."))
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Loc.t("Enregistré dans Photos (Pictures/Upscayl).", "Saved to Photos (Pictures/Upscayl).")
            } catch (e: Throwable) {
                e.message ?: Loc.t("Enregistrement impossible.", "Could not save.")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeFile(bmp: Bitmap, prefs: Prefs): File {
        app.cacheDir.listFiles()?.filter { it.name.startsWith("Upscayl_") }?.forEach { it.delete() }
        val f = File(app.cacheDir, "Upscayl_${System.currentTimeMillis() / 1000}.${prefs.format.ext}")
        val quality = (100 - prefs.compression).coerceIn(1, 100)
        f.outputStream().use { out ->
            when (prefs.format) {
                OutputFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                OutputFormat.JPG -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                    if (prefs.compression == 0) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                    else bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                } else {
                    bmp.compress(Bitmap.CompressFormat.WEBP, quality, out)
                }
            }
        }
        return f
    }

    private fun previewOf(b: Bitmap, maxSide: Int = 4096): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val f = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(
            b, max(1, (b.width * f).toInt()), max(1, (b.height * f).toInt()), true
        )
    }
}
