package fork.upscayl.android

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

enum class ItemStatus { PENDING, RUNNING, DONE, FAILED }

class BatchItem(val uri: Uri) {
    val id: Long = System.nanoTime()
    var beforeJpeg by mutableStateOf<ByteArray?>(null)
    var afterJpeg by mutableStateOf<ByteArray?>(null)
    var file by mutableStateOf<File?>(null)
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)
    var status by mutableStateOf(ItemStatus.PENDING)
}

class UpscaleViewModel(private val app: Application) : AndroidViewModel(app) {
    val items = mutableStateListOf<BatchItem>()
    var selected by mutableIntStateOf(0)
    var displayBefore by mutableStateOf<Bitmap?>(null)
    var displayAfter by mutableStateOf<Bitmap?>(null)
    var model by mutableStateOf(UpscaleModel.STANDARD.ref)
    var progress by mutableFloatStateOf(0f)
    var eta by mutableStateOf<String?>(null)
    var running by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    val current: BatchItem? get() = items.getOrNull(selected)
    val doneCount: Int get() = items.count { it.status == ItemStatus.DONE }
    val runningIndex: Int get() = items.indexOfFirst { it.status == ItemStatus.RUNNING }

    private var job: Job? = null
    private var displayJob: Job? = null
    private var runStart = 0L
    private var lastPct = -1

    // ---------- Sélection ----------

    fun setPicked(uris: List<Uri>) {
        if (uris.isEmpty() || running) return
        items.clear()
        items.addAll(uris.map { BatchItem(it) })
        selected = 0
        message = null
        progress = 0f
        eta = null
        refreshDisplay()
    }

    fun select(i: Int) {
        if (i !in items.indices || i == selected) return
        selected = i
        refreshDisplay()
    }

    fun next() = select(selected + 1)
    fun previous() = select(selected - 1)

    private fun side(): Int = if (items.size == 1) 4096 else 2048

    private fun refreshDisplay() {
        displayJob?.cancel()
        val it = current
        if (it == null) {
            displayBefore = null
            displayAfter = null
            return
        }
        displayJob = viewModelScope.launch {
            if (it.beforeJpeg == null) {
                it.beforeJpeg = withContext(Dispatchers.Default) {
                    try {
                        val (bmp, _) = Engine.decode(app, it.uri)
                        val j = previewJpeg(bmp, side())
                        bmp.recycle()
                        j
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (it.beforeJpeg == null) message = Loc.t("Image illisible.", "Unreadable image.")
            }
            val pair = withContext(Dispatchers.Default) {
                val b = it.beforeJpeg?.let { d -> BitmapFactory.decodeByteArray(d, 0, d.size) }
                val a = it.afterJpeg?.let { d -> BitmapFactory.decodeByteArray(d, 0, d.size) }
                b to a
            }
            if (current === it) {
                displayBefore = pair.first
                displayAfter = pair.second
            }
        }
    }

    // ---------- Traitement ----------

    fun start() {
        if (items.isEmpty() || running) return
        val prefs = AppSettings.p
        val opts = UpscaleOptions(
            scale = prefs.scale,
            customWidth = if (prefs.useCustomWidth && prefs.customWidth > 0) prefs.customWidth else null,
            tile = prefs.tileSize,
            tta = prefs.tta,
            double = prefs.doubleUpscayl,
            useGpu = prefs.useGpu
        )

        app.cacheDir.listFiles()?.filter { it.name.startsWith("Upscayl_") }?.forEach { it.delete() }
        items.forEach {
            it.status = ItemStatus.PENDING
            it.afterJpeg = null
            it.file = null
            it.width = 0
            it.height = 0
        }
        message = null
        progress = 0f
        eta = null
        lastPct = -1
        runStart = System.currentTimeMillis()
        running = true
        refreshDisplay()
        startKeepAlive()

        val m = model
        job = viewModelScope.launch {
            val n = items.size
            val side = side()
            var downscaled = false
            var clamped = false
            var fellBack = false
            var failures = 0
            var lastError = ""
            var cancelled = false

            try {
                for (i in 0 until n) {
                    val item = items[i]
                    item.status = ItemStatus.RUNNING
                    try {
                        val (src, wasDown) = withContext(Dispatchers.Default) { Engine.decode(app, item.uri) }
                        if (wasDown) downscaled = true
                        val res = Engine.upscale(app, src, m, opts) { p -> onProgress(i, p, n) }
                        val before = withContext(Dispatchers.Default) { previewJpeg(src, side) }
                        val after = withContext(Dispatchers.Default) { previewJpeg(res.bitmap, side) }
                        val file = withContext(Dispatchers.IO) { writeFile(res.bitmap, prefs, i) }
                        item.width = res.bitmap.width
                        item.height = res.bitmap.height
                        src.recycle()
                        res.bitmap.recycle()
                        if (res.clamped) clamped = true
                        if (res.fellBack) fellBack = true
                        item.beforeJpeg = before
                        item.afterJpeg = after
                        item.file = file
                        item.status = ItemStatus.DONE
                        if (selected == i) refreshDisplay()
                        if (prefs.autoSave) saveFile(file)
                    } catch (e: CancellationException) {
                        item.status = ItemStatus.PENDING
                        throw e
                    } catch (e: Throwable) {
                        item.status = ItemStatus.FAILED
                        failures++
                        lastError = e.message ?: ""
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            } finally {
                running = false
                eta = null
                if (!cancelled) progress = 1f
                stopKeepAlive()
            }

            val notes = mutableListOf<String>()
            if (cancelled) notes += Loc.t("Annulé.", "Cancelled.")
            if (downscaled) notes += Loc.t("Image source réduite pour tenir en mémoire.", "Source image downscaled to fit in memory.")
            if (clamped) notes += Loc.t("Taille de sortie réduite (limite mémoire).", "Output size reduced (memory limit).")
            if (fellBack) notes += Loc.t("GPU indisponible : traitement sur le processeur.", "GPU unavailable: processed on the CPU.")
            if (failures > 0) notes += Loc.t("$failures image(s) en échec : $lastError", "$failures image(s) failed: $lastError")
            message = if (notes.isEmpty()) null else notes.joinToString(" ")

            if (prefs.notifications && !cancelled && !AppState.foreground) {
                Notifier.post(app, "Upscayl", Loc.t("Agrandissement terminé.", "Upscale finished."))
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun onProgress(index: Int, local: Float, total: Int) {
        val g = (index + local) / total
        if (g > progress) progress = g.coerceIn(0f, 1f)
        val elapsed = (System.currentTimeMillis() - runStart) / 1000.0
        if (g > 0.02f && elapsed > 3.0) {
            eta = Eta.text(elapsed * (1 - g) / g)
        }
        val pct = (g * 100).toInt()
        if (pct != lastPct) {
            lastPct = pct
            val label = (if (total > 1) "${index + 1}/$total · " else "") +
                "$pct %" + (eta?.let { " · $it" } ?: "")
            Notifier.progress(app, pct, label)
        }
    }

    // ---------- Service au premier plan ----------

    private fun startKeepAlive() {
        try {
            val i = Intent(app, KeepAliveService::class.java).putExtra("pct", 0).putExtra("text", "Upscayl")
            ContextCompat.startForegroundService(app, i)
        } catch (e: Exception) {
            // le calcul continue tant que l'app reste ouverte
        }
    }

    private fun stopKeepAlive() {
        try {
            app.stopService(Intent(app, KeepAliveService::class.java))
        } catch (e: Exception) {
        }
    }

    // ---------- Galerie ----------

    fun saveToGallery() {
        val file = current?.file ?: return
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { insertInGallery(file) }
                Loc.t("Enregistré dans Photos (Pictures/Upscayl).", "Saved to Photos (Pictures/Upscayl).")
            } catch (e: Throwable) {
                e.message ?: Loc.t("Enregistrement impossible.", "Could not save.")
            }
        }
    }

    fun saveAll() {
        val files = items.mapNotNull { it.file }
        if (files.isEmpty()) return
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { files.forEach { insertInGallery(it) } }
                Loc.t("${files.size} images enregistrées dans Photos.", "${files.size} images saved to Photos.")
            } catch (e: Throwable) {
                e.message ?: Loc.t("Enregistrement impossible.", "Could not save.")
            }
        }
    }

    private suspend fun saveFile(file: File) {
        try {
            withContext(Dispatchers.IO) { insertInGallery(file) }
        } catch (e: Throwable) {
        }
    }

    private fun insertInGallery(file: File) {
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

    // ---------- Fichiers ----------

    @Suppress("DEPRECATION")
    private fun writeFile(bmp: Bitmap, prefs: Prefs, index: Int): File {
        val f = File(app.cacheDir, "Upscayl_${System.currentTimeMillis() / 1000}_${index + 1}.${prefs.format.ext}")
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

    private fun previewJpeg(b: Bitmap, maxSide: Int): ByteArray {
        val m = max(b.width, b.height)
        val scaled = if (m <= maxSide) b else {
            val f = maxSide.toFloat() / m
            Bitmap.createScaledBitmap(b, max(1, (b.width * f).toInt()), max(1, (b.height * f).toInt()), true)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
        if (scaled !== b) scaled.recycle()
        return out.toByteArray()
    }
}
