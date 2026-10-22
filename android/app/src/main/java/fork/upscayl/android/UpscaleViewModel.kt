package fork.upscayl.android

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    var scale by mutableIntStateOf(4)
    var useGpu by mutableStateOf(true)
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
                message = if (downscaled) Loc.t("Image source réduite pour tenir en mémoire.", "Source image downscaled to fit in memory.") else null
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

        job = viewModelScope.launch {
            try {
                val res = Engine.upscale(app, src, model, scale, useGpu) { progress = it }
                val preview = withContext(Dispatchers.Default) { previewOf(res.bitmap) }
                val file = withContext(Dispatchers.IO) { writePng(res.bitmap) }
                result = res.bitmap
                resultPreview = preview
                exportFile = file
                message = if (res.fellBack) Loc.t("GPU indisponible : traitement sur le processeur.", "GPU unavailable: processed on the CPU.") else null
            } catch (e: CancellationException) {
                message = Loc.t("Annulé.", "Cancelled.")
            } catch (e: Throwable) {
                message = e.message ?: Loc.t("Erreur.", "Error.")
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
                    val resolver = app.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
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

    private fun writePng(bmp: Bitmap): File {
        app.cacheDir.listFiles()?.filter { it.name.startsWith("Upscayl_") }?.forEach { it.delete() }
        val f = File(app.cacheDir, "Upscayl_x${scale}_${System.currentTimeMillis() / 1000}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun previewOf(b: Bitmap, maxSide: Int = 2048): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val f = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(
            b, max(1, (b.width * f).toInt()), max(1, (b.height * f).toInt()), true
        )
    }
}
