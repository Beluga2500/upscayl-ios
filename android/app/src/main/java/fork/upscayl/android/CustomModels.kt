package fork.upscayl.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Modèles ncnn importés par l'utilisateur (paire de fichiers .param + .bin du même nom). */
object CustomModels {
    var list by mutableStateOf<List<ModelRef>>(emptyList())
        private set

    private fun dir(context: Context): File = File(context.filesDir, "custom_models").apply { mkdirs() }

    /** L'échelle est lue dans le nom du fichier (x2, 3x, x4…), 4 par défaut. */
    fun parseScale(name: String): Int {
        val m = Regex("(\\d)x|x(\\d)", RegexOption.IGNORE_CASE).find(name)
        val v = m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        return if (v != null && v in 2..4) v else 4
    }

    fun refresh(context: Context) {
        val d = dir(context)
        val names = d.listFiles()
            ?.filter { it.extension == "param" }
            ?.map { it.nameWithoutExtension }
            ?.filter { File(d, "$it.bin").exists() }
            ?.sorted()
            ?: emptyList()
        list = names.map { n ->
            ModelRef(
                id = "custom:$n",
                title = n,
                detail = Loc.t("Modèle personnalisé", "Custom model"),
                scale = parseScale(n),
                paramPath = File(d, "$n.param").absolutePath,
                binPath = File(d, "$n.bin").absolutePath
            )
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** Renvoie (modèles importés, fichiers ignorés). */
    fun importFiles(context: Context, uris: List<Uri>): Pair<Int, Int> {
        val d = dir(context)
        val byBase = mutableMapOf<String, MutableMap<String, Uri>>()
        var ignored = 0
        for (u in uris) {
            val name = displayName(context, u)
            if (name == null) {
                ignored++
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (ext == "param" || ext == "bin") {
                byBase.getOrPut(base) { mutableMapOf() }[ext] = u
            } else {
                ignored++
            }
        }
        var ok = 0
        for ((base, files) in byBase) {
            val p = files["param"]
            val b = files["bin"]
            if (p == null || b == null) {
                ignored += files.size
                continue
            }
            copy(context, p, File(d, "$base.param"))
            copy(context, b, File(d, "$base.bin"))
            ok++
        }
        refresh(context)
        return ok to ignored
    }

    private fun copy(context: Context, from: Uri, to: File) {
        context.contentResolver.openInputStream(from)?.use { input ->
            to.outputStream().use { out -> input.copyTo(out) }
        }
    }

    fun delete(context: Context, model: ModelRef) {
        model.paramPath?.let { File(it).delete() }
        model.binPath?.let { File(it).delete() }
        refresh(context)
    }
}
